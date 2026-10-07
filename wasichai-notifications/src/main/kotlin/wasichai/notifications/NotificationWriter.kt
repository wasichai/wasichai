package wasichai.notifications

import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.platform.ClusterLock
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// the one write engine: admin REST, Notifications.publish, the rule listener and the reconciler all come through here.
// each call is one transaction that joins the caller's (ADR-038), and signals the organization once when
// anything changed: the pg_notify goes out with the commit, or not at all.
// writers of one (organization, source) take turns (ClusterLock.withXactLock, held to the commit): a rule run
// and the record listener, or a source run and a publish, never interleave their read and their write.
// drafts arrive prepared (NotificationPreparer): this class decides nothing about format or recipients.
class NotificationWriter(
    private val repository: NotificationRepository,
    private val lock: ClusterLock,
    private val clock: Clock,
    transactions: () -> TransactionalOperator
) {
    // resolved on first write, as ClusterLock does
    private val operator by lazy(transactions)

    // keyed: the upsert table. keyless: a new row
    suspend fun publish(
        organizationId: UUID,
        source: String,
        prepared: PreparedNotification,
        createdBy: UUID?
    ): UpsertResult =
        write(organizationId, source) { now ->
            repository.upsert(organizationId, source, prepared, createdBy, now).let { it to it.changed }
        }

    // the rule listener's: only a key the source has open is updated. none, or resolved: UNCHANGED, the next run decides
    suspend fun updateOpen(
        organizationId: UUID,
        source: String,
        prepared: PreparedNotification
    ): UpsertOutcome {
        val key = requireNotNull(prepared.key) { "updateOpen needs a keyed notification" }
        return write(organizationId, source) { now ->
            val row = repository.lockByKey(organizationId, source, key)?.takeIf { it.resolvedAt == null }
            val outcome = if (row == null) UpsertOutcome.UNCHANGED else repository.apply(organizationId, row, prepared, now)
            outcome to (outcome != UpsertOutcome.UNCHANGED)
        }
    }

    // null: no such notification of source in this organization
    suspend fun replace(
        organizationId: UUID,
        source: String,
        id: UUID,
        prepared: PreparedNotification
    ): UpsertOutcome? =
        write(organizationId, source) { now ->
            repository.replace(organizationId, source, id, prepared, now).let { it to (it == UpsertOutcome.UPDATED) }
        }

    suspend fun delete(
        organizationId: UUID,
        source: String,
        id: UUID
    ): Boolean = write(organizationId, source) { _ -> repository.delete(organizationId, source, id).let { it to it } }

    suspend fun resolve(
        organizationId: UUID,
        source: String,
        key: String
    ): Boolean = write(organizationId, source) { now -> repository.resolve(organizationId, source, key, now).let { it to it } }

    suspend fun resolveAll(
        organizationId: UUID,
        source: String
    ): Int = write(organizationId, source) { now -> repository.resolveAll(organizationId, source, now).let { it to (it > 0) } }

    // spec C: what the source reports now is the whole truth for it. missing keys are resolved.
    // a keyless draft or a repeated key refuses the batch before anything is read.
    // readStart: when the source began reading. a row written after it came from someone who saw newer data
    // (the listener, a publish), so it is neither updated nor resolved here; the next run catches up.
    suspend fun reconcile(
        organizationId: UUID,
        source: String,
        prepared: List<PreparedNotification>,
        readStart: Instant
    ): ReconcileResult {
        NotificationReconciler.requireKeys(prepared)
        return write(organizationId, source) { now ->
            val stored = repository.openBySource(organizationId, source, prepared.mapNotNull { it.key })
            val plan = NotificationReconciler.diff(stored, prepared, readStart)
            val outcomes =
                plan.steps.map { step ->
                    when (step.action) {
                        // upsert, not insert: a writer outside the loop may have taken the key meanwhile
                        ReconcileAction.CREATE -> repository.upsert(organizationId, source, step.draft, null, now).outcome
                        ReconcileAction.UPDATE, ReconcileAction.REOPEN -> repository.apply(organizationId, step.stored!!, step.draft, now)
                        ReconcileAction.SKIP -> UpsertOutcome.UNCHANGED
                    }
                }
            val resolved = repository.resolveIds(organizationId, plan.resolve.map { it.id }, now)
            val result =
                ReconcileResult(
                    created = outcomes.count { it == UpsertOutcome.CREATED },
                    updated = outcomes.count { it == UpsertOutcome.UPDATED },
                    reopened = outcomes.count { it == UpsertOutcome.REOPENED },
                    resolved = resolved
                )
            result to result.changed
        }
    }

    // block answers (result, changed). now is read under the lock, so a later writer of the source stamps later.
    // postgres keeps microseconds: so does now
    private suspend fun <T> write(
        organizationId: UUID,
        source: String,
        block: suspend (Instant) -> Pair<T, Boolean>
    ): T =
        operator.executeAndAwait {
            lock.withXactLock(lockKey(organizationId, source)) {
                val (result, changed) = block(clock.instant().truncatedTo(ChronoUnit.MICROS))
                if (changed) repository.notify(organizationId)
                result
            }
        }

    internal companion object {
        // per organization: one tenant's run never waits on another's
        fun lockKey(
            organizationId: UUID,
            source: String
        ): String = "wasichai.notifications.$organizationId.$source"
    }
}
