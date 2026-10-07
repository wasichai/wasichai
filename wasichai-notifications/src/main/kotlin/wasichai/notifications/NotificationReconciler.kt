package wasichai.notifications

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

enum class ReconcileAction { CREATE, UPDATE, REOPEN, SKIP }

data class ReconcileStep(
    val action: ReconcileAction,
    val draft: PreparedNotification,
    // null for CREATE
    val stored: StoredRow?
)

data class ReconcilePlan(
    // one per draft, in draft order
    val steps: List<ReconcileStep>,
    // open rows the source no longer reports, keyless ones included
    val resolve: List<StoredRow>
) {
    val changes: Boolean get() = resolve.isNotEmpty() || steps.any { it.action != ReconcileAction.SKIP }
}

data class ReconcileResult(
    val created: Int,
    val updated: Int,
    val reopened: Int,
    val resolved: Int
) {
    // the run endpoint answers the four counts only
    @get:JsonIgnore
    val changed: Boolean get() = created + updated + reopened + resolved > 0
}

// spec C: a source's output is a set keyed by key. the upsert table, decided without the database.
internal object NotificationReconciler {
    // a keyless draft or a repeated key refuses the whole batch: it cannot be matched next time
    fun requireKeys(drafts: List<PreparedNotification>) {
        require(drafts.all { it.key != null }) { "every notification of a source needs a key" }
        val repeated =
            drafts
                .groupingBy { it.key }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(repeated.isEmpty()) { "keys must be unique within a source: ${repeated.joinToString { "'$it'" }}" }
    }

    // stored: the source's open rows plus resolved rows with the drafts' keys (NotificationRepository.openBySource).
    // readStart: a row written after it (updated_at later) was written by someone who saw newer data than the
    // drafts: skipped, never resolved. a frozen test clock stamps equal instants: equal is not later.
    fun diff(
        stored: List<StoredRow>,
        drafts: List<PreparedNotification>,
        readStart: Instant? = null
    ): ReconcilePlan {
        requireKeys(drafts)

        fun newer(row: StoredRow) = readStart != null && row.updatedAt > readStart
        val byKey = stored.filter { it.key != null }.associateBy { it.key }
        val steps =
            drafts.map { draft ->
                val row = byKey[draft.key]
                val action =
                    when {
                        row == null -> ReconcileAction.CREATE
                        newer(row) -> ReconcileAction.SKIP
                        row.resolvedAt != null -> ReconcileAction.REOPEN
                        row.fingerprint == draft.fingerprint -> ReconcileAction.SKIP
                        else -> ReconcileAction.UPDATE
                    }
                ReconcileStep(action, draft, row)
            }
        val keys = drafts.mapTo(HashSet()) { it.key }
        val resolve = stored.filter { it.resolvedAt == null && (it.key == null || it.key !in keys) && !newer(it) }
        return ReconcilePlan(steps, resolve)
    }
}
