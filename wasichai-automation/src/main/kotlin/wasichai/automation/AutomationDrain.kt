package wasichai.automation

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.time.Duration

// the queue needs someone to look at it. one timer, one batch at a time, errors logged and
// swallowed: a poisoned run must not stop the loop for every other tenant.
@Component
class AutomationDrain(
    private val runner: AutomationRunner,
    private val properties: AutomationProperties
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    override fun start() {
        if (!properties.polling || subscription != null) return
        subscription =
            drainLoop(properties.pollInterval, { log.error("Automation drain stopped", it) }) {
                runCatching { runner.drainOnce(properties.batch) }
                    .onFailure { log.warn("Automation drain failed: {}", it.message) }
            }
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null
}

// interval has no backpressure. once the downstream replenishes slower than the ticks it dies with
// an overflow, and dying cancels the batch in flight -- the run it had already claimed stays
// RUNNING forever and the tenant's queue is dead until the next restart. dropping a tick costs
// nothing: the next one looks at the same queue.
//
// onError is a last resort and should never fire. it exists so that a loop that dies takes a line
// in the log with it, instead of going to reactor's onErrorDropped where nobody reads it.
internal fun drainLoop(
    interval: Duration,
    onError: (Throwable) -> Unit,
    body: suspend () -> Unit
): Disposable =
    Flux
        .interval(interval)
        .onBackpressureDrop()
        .concatMap { mono { body() } }
        .subscribe({}, onError)
