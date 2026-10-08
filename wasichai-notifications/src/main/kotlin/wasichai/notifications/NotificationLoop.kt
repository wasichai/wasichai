package wasichai.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import wasichai.core.data.RecordService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import wasichai.notifications.autoconfigure.NotificationsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

// one thing the loop runs per organization every interval: an app's NotificationSource, or the module's own rules
internal interface LoopWork {
    // notification_source_runs row and lock name
    val key: String
    val interval: Duration

    // one organization. throwing is logged and the loop moves on to the next one
    suspend fun run(
        organizationId: UUID,
        now: Instant
    )
}

// notification_source_runs: when each item last ran, shared by every replica
internal interface SourceRuns {
    suspend fun lastRun(key: String): Instant?

    suspend fun markRun(
        key: String,
        at: Instant
    )
}

// once per cluster: false when another replica holds key
internal fun interface LoopLock {
    suspend fun runExclusive(
        key: String,
        block: suspend () -> Unit
    ): Boolean
}

// spec C: an app's source as loop work. drafts outside any transaction, as the platform; then one reconcile,
// the only transactional step. a malformed draft or a repeated key is an IllegalArgumentException: the
// organization's batch is refused, the loop logs it.
internal class SourceWork(
    private val source: NotificationSource,
    private val records: RecordService,
    private val preparer: NotificationPreparer,
    private val writer: NotificationWriter
) : LoopWork {
    override val key: String = source.key
    override val interval: Duration = source.interval

    init {
        val problems = NotificationValidation.checkSource(key)
        require(problems.isEmpty()) {
            "NotificationSource ${source.javaClass.name}: key '$key' is not a source of its own: ${problems.joinToString { it.message }}"
        }
        require(!interval.isNegative && !interval.isZero) { "NotificationSource '$key': interval must be positive, was $interval" }
    }

    override suspend fun run(
        organizationId: UUID,
        now: Instant
    ) {
        records.asPlatform(organizationId) {
            val drafts = source.currentNotifications(organizationId, now)
            // lenient: unknown recipients dropped; a draft nobody is left for stays out (resolves a stored one)
            val prepared = drafts.mapNotNull { preparer.prepare(organizationId, key, it, now, strict = false) }
            // now is the tick's, taken before the read: what was written after it is left for the next run
            val result = writer.reconcile(organizationId, key, prepared, readStart = now)
            if (result.changed) log.debug("source {} organization {}: {}", key, organizationId, result)
        }
    }

    override fun toString(): String = source.javaClass.name

    private companion object {
        private val log = LoggerFactory.getLogger(SourceWork::class.java)
    }
}

/**
 * Runs every [NotificationSource] (and the module's own work) once per interval across replicas, and purges
 * old notifications daily (spec C, ADR-046). Built like `AutomationDrain`: one timer, one pass at a time,
 * every failure logged and swallowed, so one organization or source never stops the others.
 *
 * `wasichai.notifications.tick=0s` keeps it off; tests drive [runOnce] and [runSource] by hand.
 */
@Component
class NotificationLoop internal constructor(
    work: List<LoopWork>,
    private val runs: SourceRuns,
    private val lock: LoopLock,
    private val organizations: suspend () -> List<UUID>,
    // deletes what ended before the instant; answers how many
    private val purge: suspend (Instant) -> Int,
    private val properties: NotificationsProperties,
    private val clock: Clock
) : SmartLifecycle {
    internal constructor(
        sources: List<NotificationSource>,
        extras: List<LoopWork>,
        clusterLock: ClusterLock,
        records: RecordService,
        tenants: TenantDirectory,
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        repository: NotificationRepository,
        runs: SourceRuns,
        properties: NotificationsProperties,
        clock: Clock
    ) : this(
        sources.map { SourceWork(it, records, preparer, writer) } + extras,
        runs,
        LoopLock { key, block -> clusterLock.tryLock(key)?.use { block() } != null },
        { tenants.organizations().map { it.id } },
        repository::purge,
        properties,
        clock
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val work: Map<String, LoopWork>
    private var subscription: Disposable? = null

    init {
        val repeated = work.groupBy { it.key }.filterValues { it.size > 1 }
        require(repeated.isEmpty()) {
            "notification sources need unique keys: " +
                repeated.entries.joinToString("; ") { (key, items) -> "'$key' is taken by ${items.joinToString()}" }
        }
        require(work.none { it.key == PURGE }) { "'$PURGE' is the notifications loop's own key: a source cannot take it" }
        this.work = work.associateBy { it.key }
    }

    override fun start() {
        if (properties.tick.isZero || subscription != null) return
        subscription =
            tickLoop(properties.tick, { log.error("Notifications loop tick failed", it) }) {
                logged({ runOnce() }) { log.warn("Notifications loop failed: {}", it.message) }
            }
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    /** One pass: every due item, then the purge when a day has gone by. What fails is logged, never thrown. */
    suspend fun runOnce(now: Instant = clock.instant()) {
        work.values.forEach { item ->
            logged({ runIfDue(item, now) }) { log.warn("Notification source {} failed: {}", item.key, it.message, it) }
        }
        logged({ purgeIfDue(now) }) { log.warn("Notifications purge failed: {}", it.message, it) }
    }

    /**
     * Runs the item [key] now, due or not, and records the run. False when another replica is running it.
     * For tests and manual runs; an unknown key is an [IllegalArgumentException].
     */
    suspend fun runSource(key: String): Boolean {
        val item = requireNotNull(work[key]) { "no notification source '$key'; known: ${work.keys.sorted()}" }
        val now = clock.instant()
        return lock.runExclusive(LOCK_PREFIX + key) { runEverywhere(item, now) }
    }

    private suspend fun runIfDue(
        item: LoopWork,
        now: Instant
    ) {
        // pooled read first: most ticks find nothing due and never open a lock connection
        if (!due(item.key, item.interval, now)) return
        lock.runExclusive(LOCK_PREFIX + item.key) {
            // another replica may have run it between our read and our lock
            if (due(item.key, item.interval, now)) runEverywhere(item, now)
        }
    }

    // every organization, each on its own: one that fails is logged, the rest still run, the run still counts
    private suspend fun runEverywhere(
        item: LoopWork,
        now: Instant
    ) {
        organizations().forEach { organizationId ->
            logged({ item.run(organizationId, now) }) { e ->
                // an IllegalArgumentException is a malformed batch: a bug in the app, refused for this organization
                if (e is IllegalArgumentException) {
                    log.error("Notification source {} refused for organization {}: {}", item.key, organizationId, e.message)
                } else {
                    log.warn("Notification source {} failed for organization {}: {}", item.key, organizationId, e.message, e)
                }
            }
        }
        runs.markRun(item.key, now)
    }

    private suspend fun purgeIfDue(now: Instant) {
        if (!due(PURGE, PURGE_INTERVAL, now)) return
        lock.runExclusive(LOCK_PREFIX + PURGE) {
            if (due(PURGE, PURGE_INTERVAL, now)) {
                val purged = purge(now.minus(properties.retention))
                if (purged > 0) log.info("Purged {} notifications that ended before {}", purged, now.minus(properties.retention))
                runs.markRun(PURGE, now)
            }
        }
    }

    // never ran, or ran at least an interval ago
    private suspend fun due(
        key: String,
        interval: Duration,
        now: Instant
    ): Boolean {
        val last = runs.lastRun(key) ?: return true
        return !last.plus(interval).isAfter(now)
    }

    private companion object {
        const val LOCK_PREFIX = "wasichai.notifications."
        const val PURGE = Sources.PURGE
        val PURGE_INTERVAL: Duration = Duration.ofDays(1)
    }
}

// failures are logged and swallowed. a CancellationException that is not ours (a source's withTimeout, a
// java.util.concurrent one from a future) is a failure like any other: only our own cancellation (stop) goes up
internal suspend fun logged(
    block: suspend () -> Unit,
    onFailure: (Throwable) -> Unit
) {
    try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        onFailure(e)
    } catch (e: Exception) {
        onFailure(e)
    }
}

// AutomationDrain's loop, which is internal to automation. interval has no backpressure: a slow pass drops
// ticks instead of dying with an overflow. a tick that fails anyway is handed to onError and the next one
// still comes: an error reaching interval would end the loop for good.
internal fun tickLoop(
    interval: Duration,
    onError: (Throwable) -> Unit,
    body: suspend () -> Unit
): Disposable =
    Flux
        .interval(interval)
        .onBackpressureDrop()
        .concatMap { _ ->
            mono { body() }.onErrorResume { e ->
                onError(e)
                Mono.empty()
            }
        }.subscribe({}, onError)
