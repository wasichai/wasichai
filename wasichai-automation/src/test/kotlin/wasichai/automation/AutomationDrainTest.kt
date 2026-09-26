package wasichai.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
            runBlocking { delay(600) }
        } finally {
            subscription.dispose()
        }

        assertThat(died.get()).isNull()
        // unguarded it gets through three or four batches before the overflow kills it
        assertThat(drained.get()).isGreaterThan(15)
    }
}
