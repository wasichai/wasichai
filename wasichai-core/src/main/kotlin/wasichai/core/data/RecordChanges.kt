package wasichai.core.data

import java.util.UUID

enum class RecordChangeKind { CREATED, UPDATED, DELETED, TRANSITIONED }

// what happened to one record, with the values as they were. listeners judge this snapshot,
// never a fresh read: by the time they act the row may have moved on.
data class RecordChange(
    val organizationId: UUID,
    val userId: UUID?,
    val objectId: UUID,
    val objectName: String,
    val recordId: UUID,
    val kind: RecordChangeKind,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
    val state: String? = null,
    val transition: String? = null,
    // how deep a chain of listener-caused changes already is, and what caused it. both stop loops.
    val depth: Int = 0,
    val causedBy: UUID? = null
)

// told of every record change, right after the write, in @Order. RecordService opens no
// transaction of its own (ADR-0025) - a listener that needs atomicity opens its own.
// data never reaches into a module's tables itself.
interface RecordChangeListener {
    suspend fun recordChanged(change: RecordChange)
}
