package wasichai.core.data

import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.metadata.ObjectDefinition
import java.time.Instant
import java.util.UUID

data class RecordRow(
    val id: UUID,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val attributes: Map<String, Any?>,
    // one map per installed section, every field of that section listed, null included
    val sections: Map<String, Map<String, Any?>> = emptyMap(),
    // null when the object has no state, or when the record predates it
    val state: String? = null
)

data class RecordQuery(
    val page: PageRequest,
    val sort: String? = null,
    val descending: Boolean = false,
    val search: String? = null,
    val filters: Map<String, String> = emptyMap(),
    // conditions installed modules add (R7)
    val criteria: List<RecordCriterion> = emptyList(),
    val ids: List<UUID>? = null,
    // record-level security. non-null = only rows this user created.
    val createdBy: UUID? = null,
    // selecting the state column on a table that has none would blow up
    val withState: Boolean = false,
    // false: no COUNT(*), the page carries no totals. a big table read page by page needs no total each time.
    val count: Boolean = true,
    // keyset read: the nextCursor of the page before. resumes after its (sort value, id), so a large
    // read costs one pass instead of OFFSET's growing skip. never with page > 0 (ADR-036).
    val after: String? = null
)

// port. physical tables today, could be jsonb tomorrow without touching callers. ADR-004.
interface RecordStore {
    suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState = ObjectWorkflowState.NONE
    ): RecordRow

    suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean = false
    ): RecordRow

    // moves the state only while the record still sits in `from`. null = it moved on without us.
    suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow?

    suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean

    suspend fun findById(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID,
        createdBy: UUID? = null,
        withState: Boolean = false
    ): RecordRow?

    suspend fun query(
        definition: ObjectDefinition,
        organizationId: UUID,
        query: RecordQuery
    ): PageResponse<RecordRow>
}
