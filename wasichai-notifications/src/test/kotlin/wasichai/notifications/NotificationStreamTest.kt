package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// spec D's pipeline on virtual time: what a stream sends, and when
class NotificationStreamTest {
    private val org = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val reader = InboxReader(org, me, listOf("USER"), emptyList())
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val refresh = Duration.ofSeconds(60)
    private val heartbeat = Duration.ofSeconds(25)
    private val debounce = Duration.ofMillis(500)

    private fun summary(unread: Long): NotificationSummary = NotificationSummary.of(mapOf(NotificationKind.ACTION to KindCount(unread, unread, 0)), null)

    // each call answers the next count; the last one repeats
    private class Summaries(
        vararg counts: Long
    ) {
        val calls = AtomicInteger()
        private val values = counts.toList()

        fun next(): Mono<NotificationSummary> =
            Mono.fromSupplier {
                val i = calls.getAndIncrement().coerceAtMost(values.size - 1)
                NotificationSummary.of(mapOf(NotificationKind.ACTION to KindCount(values[i], values[i], 0)), null)
            }
    }

    private fun stream(
        signals: Flux<Signal>,
        summaries: Summaries,
        expiresAt: Instant? = null
    ): Flux<ServerSentEvent<Any>> =
        NotificationStream.stream(
            reader = reader,
            signals = signals,
            summary = summaries::next,
            refresh = refresh,
            heartbeat = heartbeat,
            debounce = debounce,
            expiresAt = expiresAt,
            clock = clock,
            scheduler = Schedulers.parallel()
        )

    private fun isSummary(
        event: ServerSentEvent<Any>,
        unread: Long
    ): Boolean = event.event() == "summary" && event.data() == summary(unread)

    private fun isPing(event: ServerSentEvent<Any>): Boolean = event.comment() == "ping" && event.data() == null && event.event() == null

    @Test
    fun `the first event is the summary, at once`() {
        val summaries = Summaries(3)
        StepVerifier
            .withVirtualTime { stream(Flux.never(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 3)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `a signal for this person brings a new summary after the debounce`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val summaries = Summaries(0, 1)
        StepVerifier
            .withVirtualTime { stream(signals.asFlux(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .then { signals.tryEmitNext(Signal(org, me)) }
            .expectNoEvent(Duration.ofMillis(499))
            .thenAwait(Duration.ofMillis(1))
            .assertNext { assertThat(isSummary(it, 1)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `an organization signal and a wildcard reach everyone of it`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val summaries = Summaries(0, 1, 2)
        StepVerifier
            .withVirtualTime { stream(signals.asFlux(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .then { signals.tryEmitNext(Signal(org, null)) }
            .thenAwait(debounce)
            .assertNext { assertThat(isSummary(it, 1)).isTrue() }
            .then { signals.tryEmitNext(Signal.WILDCARD) }
            .thenAwait(debounce)
            .assertNext { assertThat(isSummary(it, 2)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `signals of another organization or another person are ignored`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val summaries = Summaries(0, 1)
        StepVerifier
            .withVirtualTime { stream(signals.asFlux(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .then {
                signals.tryEmitNext(Signal(UUID.randomUUID(), null))
                signals.tryEmitNext(Signal(UUID.randomUUID(), me))
                signals.tryEmitNext(Signal(org, UUID.randomUUID()))
            }.expectNoEvent(Duration.ofSeconds(20))
            .thenCancel()
            .verify(Duration.ofSeconds(5))
        assertThat(summaries.calls.get()).isEqualTo(1)
    }

    @Test
    fun `a burst of signals costs one recompute`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val summaries = Summaries(0, 1)
        StepVerifier
            .withVirtualTime { stream(signals.asFlux(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .then { repeat(10) { signals.tryEmitNext(Signal(org, me)) } }
            .thenAwait(debounce)
            .assertNext { assertThat(isSummary(it, 1)).isTrue() }
            .expectNoEvent(Duration.ofSeconds(20))
            .thenCancel()
            .verify(Duration.ofSeconds(5))
        assertThat(summaries.calls.get()).isEqualTo(2)
    }

    @Test
    fun `an unchanged summary is not sent again`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val summaries = Summaries(2, 2, 5)
        StepVerifier
            .withVirtualTime { stream(signals.asFlux(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 2)).isTrue() }
            .then { signals.tryEmitNext(Signal(org, me)) }
            .expectNoEvent(Duration.ofSeconds(1))
            .then { signals.tryEmitNext(Signal(org, me)) }
            .thenAwait(debounce)
            .assertNext { assertThat(isSummary(it, 5)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
        assertThat(summaries.calls.get()).isEqualTo(3)
    }

    @Test
    fun `a comment keeps the stream alive every heartbeat`() {
        val summaries = Summaries(0)
        StepVerifier
            .withVirtualTime { stream(Flux.never(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .expectNoEvent(Duration.ofSeconds(24))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext { assertThat(isPing(it)).isTrue() }
            .expectNoEvent(Duration.ofSeconds(24))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext { assertThat(isPing(it)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `the refresh recomputes whatever was heard`() {
        val summaries = Summaries(0, 4)
        StepVerifier
            .withVirtualTime { stream(Flux.never(), summaries) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .thenAwait(Duration.ofSeconds(50))
            .assertNext { assertThat(isPing(it)).isTrue() }
            .assertNext { assertThat(isPing(it)).isTrue() }
            .thenAwait(Duration.ofSeconds(10))
            .assertNext { assertThat(isSummary(it, 4)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
        assertThat(summaries.calls.get()).isEqualTo(2)
    }

    @Test
    fun `the stream completes when the token expires`() {
        val summaries = Summaries(0)
        StepVerifier
            .withVirtualTime { stream(Flux.never(), summaries, expiresAt = now.plusSeconds(30)) }
            .expectSubscription()
            .assertNext { assertThat(isSummary(it, 0)).isTrue() }
            .thenAwait(Duration.ofSeconds(25))
            .assertNext { assertThat(isPing(it)).isTrue() }
            .thenAwait(Duration.ofSeconds(5))
            .verifyComplete()
    }

    @Test
    fun `a token already expired ends the stream at once`() {
        StepVerifier
            .withVirtualTime { stream(Flux.never(), Summaries(0), expiresAt = now.minusSeconds(1)) }
            .expectSubscription()
            .verifyComplete()
    }

    @Test
    fun `a failing summary skips one beat and the stream lives on`() {
        val signals = Sinks.many().multicast().directBestEffort<Signal>()
        val calls = AtomicInteger()
        val flaky = {
            if (calls.getAndIncrement() == 1) Mono.error(IllegalStateException("db down")) else Mono.just(summary(calls.get().toLong()))
        }
        StepVerifier
            .withVirtualTime {
                NotificationStream.stream(reader, signals.asFlux(), flaky, refresh, heartbeat, debounce, null, clock, Schedulers.parallel())
            }.expectSubscription()
            .assertNext { assertThat(isSummary(it, 1)).isTrue() }
            .then { signals.tryEmitNext(Signal(org, me)) }
            .thenAwait(debounce)
            .expectNoEvent(Duration.ofSeconds(1))
            .then { signals.tryEmitNext(Signal(org, me)) }
            .thenAwait(debounce)
            .assertNext { assertThat(isSummary(it, 3)).isTrue() }
            .thenCancel()
            .verify(Duration.ofSeconds(5))
    }
}
