package wasichai.notifications

import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// the one write engine: admin REST, Notifications.publish and the reconciler all come through here.
// each call is one transaction that joins the caller's (ADR-038), and signals the organization once when
// anything changed: the pg_notify goes out with the commit, or not at all.
// drafts arrive prepared (NotificationPreparer): this class decides nothing about format or recipients.
class NotificationWriter(
    private val repository: NotificationRepository,
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
        write(organizationId) { now ->
            repository.upsert(organizationId, source, prepared, createdBy, now).let { it to it.changed }
        }

    // null: no such notification in this organization
    suspend fun replace(
        organizationId: UUID,
        id: UUID,
        prepared: PreparedNotification
    ): UpsertOutcome? =
        write(organizationId) { now ->
            repository.replace(organizationId, id, prepared, now).let { it to (it == UpsertOutcome.UPDATED) }
        }

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean = write(organizationId) { _ -> repository.delete(organizationId, id).let { it to it } }

    suspend fun resolve(
        organizationId: UUID,
        source: String,
        key: String
    ): Boolean = write(organizationId) { now -> repository.resolve(organizationId, source, key, now).let { it to it } }

    suspend fun resolveAll(
        organizationId: UUID,
        source: String
    ): Int = write(organizationId) { now -> repository.resolveAll(organizationId, source, now).let { it to (it > 0) } }

    // spec C: what the source reports now is the whole truth for it. missing keys are resolved.
    // a keyless draft or a repeated key refuses the batch before anything is read.
    suspend fun reconcile(
        organizationId: UUID,
        source: String,
        prepared: List<PreparedNotification>
    ): ReconcileResult {
        NotificationReconciler.requireKeys(prepared)
        return write(organizationId) { now ->
            val stored = repository.openBySource(organizationId, source, prepared.mapNotNull { it.key })
            val plan = NotificationReconciler.diff(stored, prepared)
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

    // block answers (result, changed). postgres keeps microseconds: so does now
    private suspend fun <T> write(
        organizationId: UUID,
        block: suspend (Instant) -> Pair<T, Boolean>
    ): T =
        operator.executeAndAwait {
            val (result, changed) = block(clock.instant().truncatedTo(ChronoUnit.MICROS))
            if (changed) repository.notify(organizationId)
            result
        }
}
