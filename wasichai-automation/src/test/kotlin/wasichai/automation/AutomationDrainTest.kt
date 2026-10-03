package wasichai.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AutomationDrainTest {
    // the drain ticks once a second and a batch takes as long as it takes, so the downstream
    // always replenishes slower than the source. an unguarded Flux.interval dies of overflow after
    // about thirty ticks: the loop stops, and the run it had already claimed stays RUNNING for
    // good. in production that killed the tenant's whole queue until the next restart.
    //
    // ticks here are 1ms and a body takes 10ms -- the same mismatch, sixty seconds' worth of it
    // per second of test.
    //
    // waits on a count, not a clock: a loaded machine runs fewer batches per second, never fewer
    // batches in all. an unguarded loop dies after three or four, so it stops at the death instead.
    @Test
    fun `the loop outlives ticks the batch is too slow to keep up with`() {
        val drained = AtomicInteger()
        val died = AtomicReference<Throwable>()

        val subscription =
            drainLoop(Duration.ofMillis(1), died::set) {
                delay(10)
                drained.incrementAndGet()
            }

        try {
            runBlocking {
                withTimeout(60_000) {
                    while (drained.get() < BATCHES && died.get() == null) delay(10)
                }
            }
        } finally {
            subscription.dispose()
        }

        assertThat(died.get()).isNull()
        assertThat(drained.get()).isGreaterThanOrEqualTo(BATCHES)
    }

    private companion object {
        // well past the ~32 ticks an unguarded interval survives
        const val BATCHES = 40
    }
}
