package wasichai.files

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import wasichai.core.platform.ClusterLock
import java.time.Clock
import java.time.Duration

/**
 * Deletes stored files no record names any more (ADR-0061): replaced, cleared, their record or field
 * or object deleted, their tenant deleted, or uploaded and never attached. Every [interval], on one
 * replica at a time (ClusterLock, ADR-039), for every organization with a file older than [delay]:
 * the files of that organization no FILE or IMAGE column of its objects names. Bytes first, then the
 * row, so a failed delete is tried again next pass. No user, no request.
 *
 * [interval] zero keeps it off; tests call [runOnce].
 */
class StoredFileCleanup(
    private val files: StoredFileRepository,
    private val store: FileStore,
    private val lock: ClusterLock,
    private val interval: Duration,
    private val delay: Duration,
    private val clock: Clock = Clock.systemUTC()
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
                        log.warn("Stored file cleanup failed: {}", e.message, e)
                        Mono.empty()
                    }
                }.subscribe({}, { log.error("Stored file cleanup stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    /** One pass: how many files it deleted, or null when another replica holds the cleanup. */
    suspend fun runOnce(): Int? =
        lock.tryLock(LOCK)?.use {
            val cutoff = clock.instant().minus(delay)
            var deleted = 0
            files.organizationsWithFilesBefore(cutoff).forEach { organizationId ->
                // one tenant's failure (a table dropped mid-pass, a store error) does not stop the others
                try {
                    deleted += cleanOrganization(organizationId, cutoff)
                } catch (e: Exception) {
                    log.warn("Stored file cleanup failed for organization {}: {}", organizationId, e.message, e)
                }
            }
            if (deleted > 0) log.info("Deleted {} unreferenced stored files", deleted)
            deleted
        }

    private suspend fun cleanOrganization(
        organizationId: java.util.UUID,
        cutoff: java.time.Instant
    ): Int {
        var deleted = 0
        while (true) {
            val batch = files.orphans(organizationId, cutoff, BATCH)
            batch.forEach { file ->
                store.delete(file.objectKey)
                if (files.delete(organizationId, file.id)) deleted++
            }
            if (batch.size < BATCH) return deleted
        }
    }

    private companion object {
        const val LOCK = "wasichai.files.cleanup"
        const val BATCH = 200
    }
}
