package wasichai.core.data

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import wasichai.core.platform.ClusterLock
import java.time.Duration

/**
 * Deletes expired idempotency keys every [interval] (ADR-058), on one replica at a time (ClusterLock,
 * ADR-039). Housekeeping only: an expired key never replays, purged or not. One statement for every
 * organization, by created_at; no user, no request. A failed pass is logged and the next one still comes.
 *
 * [interval] zero keeps it off; tests call [runOnce].
 */
class IdempotencyKeyPurge(
    private val keys: IdempotencyKeys,
    private val lock: ClusterLock,
    private val interval: Duration
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    override fun start() {
        if (interval.isZero || subscription != null) return
        // interval has no backpressure: a slow pass drops ticks instead of dying with an overflow
        subscription =
            Flux
                .interval(interval)
                .onBackpressureDrop()
                .concatMap { _ ->
                    mono { runOnce() }.onErrorResume { e ->
                        log.warn("Idempotency key purge failed: {}", e.message, e)
                        Mono.empty()
                    }
                }.subscribe({}, { log.error("Idempotency key purge stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    /** One pass: how many keys it deleted, or null when another replica holds the purge. */
    suspend fun runOnce(): Long? =
        lock.tryLock(LOCK)?.use {
            keys.purgeExpired().also { if (it > 0) log.info("Purged {} expired idempotency keys", it) }
        }

    private companion object {
        const val LOCK = "wasichai.idempotency.purge"
    }
}
