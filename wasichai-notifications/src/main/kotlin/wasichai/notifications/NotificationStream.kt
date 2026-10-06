package wasichai.notifications

import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.Instant

// spec D: one person's live summary. pure: the caller resolved who reads and how a summary is made.
// every timer runs on the given scheduler, so tests drive it on virtual time.
object NotificationStream {
    const val EVENT = "summary"
    const val PING = "ping"

    private val log = LoggerFactory.getLogger(NotificationStream::class.java)

    fun stream(
        reader: InboxReader,
        signals: Flux<Signal>,
        summary: () -> Mono<NotificationSummary>,
        refresh: Duration,
        heartbeat: Duration,
        debounce: Duration,
        expiresAt: Instant?,
        clock: Clock,
        scheduler: Scheduler = Schedulers.parallel()
    ): Flux<ServerSentEvent<Any>> {
        val left = expiresAt?.let { Duration.between(clock.instant(), it) }
        if (left != null && !left.isPositive) return Flux.empty()

        val heard =
            signals
                .filter { it.reaches(reader.organizationId, reader.userId) }
                .sample(Flux.interval(debounce, scheduler).onBackpressureDrop())
        val triggers =
            Flux.merge(
                Flux.just(Unit),
                heard.map { },
                // a window that opens or an expiry writes nothing and notifies nobody
                Flux.interval(refresh, scheduler).onBackpressureDrop().map { }
            )
        val summaries =
            triggers
                // one recompute in flight, at most one waiting: the waiting one reads the newest state anyway
                .onBackpressureLatest()
                .concatMap({ summaryOrSkip(reader, summary) }, 1)
                .distinctUntilChanged()
                .map { ServerSentEvent.builder<Any>(it).event(EVENT).build() }
        // proxies close an idle connection
        val pings =
            Flux
                .interval(heartbeat, scheduler)
                .onBackpressureDrop()
                .map { ServerSentEvent.builder<Any>().comment(PING).build() }
        val events = Flux.merge(summaries, pings)
        // the token's exp: the UI reconnects with a fresh token, or signs out on 401
        return if (left == null) events else events.takeUntilOther(Mono.delay(left, scheduler))
    }

    // a failed read (database blip) skips one beat; the next trigger tries again
    private fun summaryOrSkip(
        reader: InboxReader,
        summary: () -> Mono<NotificationSummary>
    ): Mono<NotificationSummary> =
        Mono.defer(summary).onErrorResume { e ->
            log.warn("notification summary for user {} failed: {}", reader.userId, e.message)
            Mono.empty()
        }
}
