package wasichai.notifications

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import wasichai.core.data.RecordService
import wasichai.notifications.autoconfigure.NotificationsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// the loop's scheduling without a database: runs, lock and organizations are fakes
class NotificationLoopTest {
    private val t0 = Instant.parse("2026-10-06T12:00:00Z")
    private val clock = Clock.fixed(t0, ZoneOffset.UTC)
    private val orgA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val orgB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private class FakeRuns : SourceRuns {
        val last = mutableMapOf<String, Instant>()
        val failing = mutableSetOf<String>()

        override suspend fun lastRun(key: String): Instant? {
            if (key in failing) error("runs table unreachable for $key")
            return last[key]
        }

        override suspend fun markRun(
            key: String,
            at: Instant
        ) {
            last[key] = at
        }
    }

    private class FakeWork(
        override val key: String,
        override val interval: Duration = Duration.ofMinutes(15),
        val failFor: Set<UUID> = emptySet()
    ) : LoopWork {
        val calls = mutableListOf<Pair<UUID, Instant>>()

        override suspend fun run(
            organizationId: UUID,
            now: Instant
        ) {
            calls += organizationId to now
            if (organizationId in failFor) error("$key broke for $organizationId")
        }
    }

    private val runs = FakeRuns()
    private val locks = mutableListOf<String>()
    private val purges = mutableListOf<Instant>()

    private fun loop(
        vararg work: LoopWork,
        lock: LoopLock =
            LoopLock { key, block ->
                locks += key
                block()
                true
            },
        properties: NotificationsProperties = NotificationsProperties(),
        organizations: suspend () -> List<UUID> = { listOf(orgA, orgB) }
    ) = NotificationLoop(work.toList(), runs, lock, organizations, { before ->
        purges += before
        0
    }, properties, clock)

    @Test
    fun `a work item is due when it never ran or its interval has passed`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            val loop = loop(work)

            loop.runOnce(t0)
            assertThat(work.calls).containsExactly(orgA to t0, orgB to t0)
            assertThat(runs.last["app.deadlines"]).isEqualTo(t0)
            assertThat(locks).contains("wasichai.notifications.app.deadlines")

            loop.runOnce(t0.plus(Duration.ofMinutes(14)))
            assertThat(work.calls).hasSize(2)

            loop.runOnce(t0.plus(Duration.ofMinutes(15)))
            assertThat(work.calls).hasSize(4)
            assertThat(runs.last["app.deadlines"]).isEqualTo(t0.plus(Duration.ofMinutes(15)))
        }

    @Test
    fun `runOnce without an instant takes the clock's`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            loop(work).runOnce()
            assertThat(work.calls.map { it.second }).containsOnly(t0)
        }

    @Test
    fun `another replica holding the lock means this one skips the item`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            loop(work, lock = { _, _ -> false }).runOnce(t0)
            assertThat(work.calls).isEmpty()
            assertThat(runs.last).doesNotContainKey("app.deadlines")
        }

    @Test
    fun `under the lock the item is checked again, so a replica that just ran it wins`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            // the other replica ran and released between our read and our lock
            val lock =
                LoopLock { _, block ->
                    runs.last["app.deadlines"] = t0
                    block()
                    true
                }
            loop(work, lock = lock).runOnce(t0)
            assertThat(work.calls).isEmpty()
        }

    @Test
    fun `a failing organization does not stop the next, and the run still counts`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines", failFor = setOf(orgA))
            loop(work).runOnce(t0)
            assertThat(work.calls.map { it.first }).containsExactly(orgA, orgB)
            assertThat(runs.last["app.deadlines"]).isEqualTo(t0)
        }

    @Test
    fun `a failing item does not stop the next`(): Unit =
        runBlocking {
            val broken = FakeWork("app.broken", failFor = setOf(orgA, orgB))
            val unreachable = FakeWork("app.unreachable")
            val fine = FakeWork("app.fine")
            runs.failing += "app.unreachable"
            loop(broken, unreachable, fine).runOnce(t0)
            assertThat(broken.calls).hasSize(2)
            assertThat(unreachable.calls).isEmpty()
            assertThat(fine.calls.map { it.first }).containsExactly(orgA, orgB)
        }

    // a source's own timeout is a CancellationException, but the loop was not cancelled: logged like any failure
    @Test
    fun `a source that times out or meets a cancelled future does not stop the loop`(): Unit =
        runBlocking {
            val timingOut =
                object : LoopWork {
                    override val key = "app.slow"
                    override val interval: Duration = Duration.ofMinutes(15)
                    val calls = mutableListOf<UUID>()

                    override suspend fun run(
                        organizationId: UUID,
                        now: Instant
                    ) {
                        calls += organizationId
                        if (organizationId == orgA) withTimeout(1) { delay(10_000) }
                        throw java.util.concurrent.CancellationException("future cancelled for $organizationId")
                    }
                }
            val fine = FakeWork("app.fine")
            val loop = loop(timingOut, fine)

            loop.runOnce(t0)
            assertThat(timingOut.calls).containsExactly(orgA, orgB)
            assertThat(runs.last["app.slow"]).isEqualTo(t0)
            assertThat(fine.calls.map { it.first }).containsExactly(orgA, orgB)
            assertThat(purges).hasSize(1)

            // the next pass runs too
            loop.runOnce(t0.plus(Duration.ofMinutes(15)))
            assertThat(timingOut.calls).hasSize(4)
            assertThat(fine.calls).hasSize(4)
        }

    @Test
    fun `a tick that fails never ends the timer`() {
        val ticks = AtomicInteger()
        val failures = AtomicInteger()
        val third = CountDownLatch(3)
        val timer =
            tickLoop(Duration.ofMillis(10), { failures.incrementAndGet() }) {
                ticks.incrementAndGet()
                third.countDown()
                // what logged() lets through: not ours to swallow, yet the next tick must still come
                if (ticks.get() <= 2) throw java.util.concurrent.CancellationException("tick ${ticks.get()}")
            }
        try {
            assertThat(third.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(failures.get()).isEqualTo(2)
        } finally {
            timer.dispose()
        }
    }

    @Test
    fun `organizations that cannot be listed leave the item due`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            loop(work, organizations = { error("database down") }).runOnce(t0)
            assertThat(runs.last).doesNotContainKey("app.deadlines")
        }

    @Test
    fun `purge runs once a day under its own lock, keeping the retention`(): Unit =
        runBlocking {
            val loop = loop(properties = NotificationsProperties(retention = Duration.ofDays(30)))
            loop.runOnce(t0)
            assertThat(purges).containsExactly(t0.minus(Duration.ofDays(30)))
            assertThat(locks).containsExactly("wasichai.notifications.purge")
            assertThat(runs.last["purge"]).isEqualTo(t0)

            loop.runOnce(t0.plus(Duration.ofHours(23)))
            assertThat(purges).hasSize(1)

            loop.runOnce(t0.plus(Duration.ofDays(1)))
            assertThat(purges).hasSize(2)
        }

    @Test
    fun `runSource runs one item now, due or not`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            val other = FakeWork("app.other")
            runs.last["app.deadlines"] = t0
            val loop = loop(work, other)

            assertThat(loop.runSource("app.deadlines")).isTrue()
            assertThat(work.calls).hasSize(2)
            assertThat(other.calls).isEmpty()
            assertThat(purges).isEmpty()

            assertThatThrownBy { runBlocking { loop.runSource("app.nope") } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("app.nope")
        }

    @Test
    fun `runSource answers false when another replica holds the item`(): Unit =
        runBlocking {
            val work = FakeWork("app.deadlines")
            assertThat(loop(work, lock = { _, _ -> false }).runSource("app.deadlines")).isFalse()
            assertThat(work.calls).isEmpty()
        }

    @Test
    fun `two items with one key fail the start`() {
        assertThatThrownBy { loop(FakeWork("app.deadlines"), FakeWork("app.other"), FakeWork("app.deadlines")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'app.deadlines'")
    }

    @Test
    fun `purge is the loop's own key`() {
        assertThatThrownBy { loop(FakeWork("purge")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'purge'")
    }

    @Test
    fun `a source keyed purge or rules is told they are the loop's own`() {
        listOf("purge", "rules").forEach { key ->
            val source =
                object : NotificationSource {
                    override val key = key

                    override suspend fun currentNotifications(
                        organizationId: UUID,
                        now: Instant
                    ): List<NotificationDraft> = emptyList()
                }
            assertThatThrownBy {
                SourceWork(source, mock(RecordService::class.java), mock(NotificationPreparer::class.java), mock(NotificationWriter::class.java))
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("'purge' and 'rules' are the notifications loop's own keys")
        }
    }

    @Test
    fun `a source key that is malformed or the module's own fails the start`() {
        listOf("manual", "rule:vence", "Bad Key", "x").forEach { key ->
            val source =
                object : NotificationSource {
                    override val key = key

                    override suspend fun currentNotifications(
                        organizationId: UUID,
                        now: Instant
                    ): List<NotificationDraft> = emptyList()
                }
            assertThatThrownBy {
                SourceWork(
                    source,
                    mock(RecordService::class.java),
                    mock(NotificationPreparer::class.java),
                    mock(NotificationWriter::class.java)
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("'$key'")
        }
    }

    @Test
    fun `a source keeps its key and interval as work`() {
        val source =
            object : NotificationSource {
                override val key = "app.deadlines"
                override val interval: Duration = Duration.ofHours(1)

                override suspend fun currentNotifications(
                    organizationId: UUID,
                    now: Instant
                ): List<NotificationDraft> = emptyList()
            }
        val work = SourceWork(source, mock(RecordService::class.java), mock(NotificationPreparer::class.java), mock(NotificationWriter::class.java))
        assertThat(work.key).isEqualTo("app.deadlines")
        assertThat(work.interval).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `a zero tick leaves the loop off`() {
        val off = loop(properties = NotificationsProperties(tick = Duration.ZERO))
        off.start()
        assertThat(off.isRunning()).isFalse()

        val on = loop(properties = NotificationsProperties(tick = Duration.ofHours(1)))
        on.start()
        try {
            assertThat(on.isRunning()).isTrue()
        } finally {
            on.stop()
        }
        assertThat(on.isRunning()).isFalse()
    }
}
