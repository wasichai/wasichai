package wasichai.core.audit

import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import wasichai.core.common.Actions
import wasichai.core.common.NotFoundException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.readableNames
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: String,
    val userEmail: String?,
    val objectName: String,
    val recordId: String?,
    val operation: String,
    val occurredAt: Instant?,
    val changes: List<FieldChange>,
    val documentId: String?,
    // why, as the writer said it (ADR-041). null when none was given.
    val reason: String? = null,
    // the service account that made the change (ADR-043). left out when a person did: their entries stay as they were
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val serviceAccount: String? = null
)

// raw row. states stay as maps until we know what the caller may read.
private data class AuditRow(
    val id: UUID,
    val userEmail: String?,
    val objectName: String,
    val recordId: UUID?,
    val operation: String,
    val occurredAt: Instant?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
    val documentId: UUID?,
    val reason: String?,
    val serviceAccount: String?
)

@Service
class AuditQueryService(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val access: AccessPolicy,
    private val schemas: WasichaiSchemas
) {
    // tenant-wide read: needs an organization-wide READ grant, not one on some object
    suspend fun list(
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?
    ): List<AuditEntry> {
        val user = currentUser.requireWithPermission(Actions.READ)
        val rows = fetch(user.organizationId, objectName, recordId, operation, limit)
        return toEntries(user, rows)
    }

    // history of one record is a read of that object, so it is checked against that object
    suspend fun history(
        objectName: String,
        recordId: UUID,
        limit: Int?
    ): List<AuditEntry> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val rows = fetch(user.organizationId, definition.obj.name, recordId, null, limit)
        return toEntries(user, rows)
    }

    private suspend fun fetch(
        organizationId: UUID,
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?
    ): List<AuditRow> {
        val filters = StringBuilder()
        if (recordId != null) filters.append(" AND a.record_id = :recordId")
        var spec =
            db
                .sql(
                    """
                    SELECT a.id, u.email, a.object_name, a.record_id, a.operation, a.occurred_at, a.document_id, a.reason,
                           a.before_state::text AS before_state, a.after_state::text AS after_state,
                           sa.name AS service_account
                    FROM ${schemas.metadata}.audit_log a
                    LEFT JOIN ${schemas.metadata}.users u ON u.id = a.user_id
                    LEFT JOIN ${schemas.metadata}.service_accounts sa ON sa.id = a.user_id
                    WHERE a.organization_id = :organizationId
                      AND (:objectName = '' OR a.object_name = :objectName)
                      AND (:operation = '' OR a.operation = :operation)$filters
                    ORDER BY a.occurred_at DESC, a.id DESC
                    LIMIT :limit
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("objectName", objectName ?: "")
                .bind("operation", operation?.trim()?.uppercase() ?: "")
                .bind("limit", (limit ?: 100).coerceIn(1, 500))
        if (recordId != null) spec = spec.bind("recordId", recordId)
        return spec
            .map { row, _ ->
                AuditRow(
                    id = Rows.uuid(row, "id"),
                    userEmail = Rows.stringOrNull(row, "email"),
                    objectName = Rows.string(row, "object_name"),
                    recordId = Rows.uuidOrNull(row, "record_id"),
                    operation = Rows.string(row, "operation"),
                    occurredAt = Rows.instantOrNull(row, "occurred_at"),
                    before = parse(Rows.stringOrNull(row, "before_state")),
                    after = parse(Rows.stringOrNull(row, "after_state")),
                    documentId = Rows.uuidOrNull(row, "document_id"),
                    reason = Rows.stringOrNull(row, "reason"),
                    serviceAccount = Rows.stringOrNull(row, "service_account")
                )
            }.all()
            .asFlow()
            .toList()
    }

    // the log would otherwise hand out field values the field permissions hide
    private suspend fun toEntries(
        user: AuthenticatedUser,
        rows: List<AuditRow>
    ): List<AuditEntry> {
        val readable = mutableMapOf<String, Set<String>?>()
        return rows.map { row ->
            val allowed = readable.getOrPut(row.objectName) { readableFields(user, row.objectName) }
            AuditEntry(
                id = row.id.toString(),
                userEmail = row.userEmail,
                objectName = row.objectName,
                recordId = row.recordId?.toString(),
                operation = row.operation,
                occurredAt = row.occurredAt,
                changes = AuditDiff.changes(filter(row.before, allowed), filter(row.after, allowed)),
                documentId = row.documentId?.toString(),
                reason = row.reason,
                serviceAccount = row.serviceAccount
            )
        }
    }

    // null means no restriction. empty set means nothing may be shown.
    private suspend fun readableFields(
        user: AuthenticatedUser,
        objectName: String
    ): Set<String>? {
        if (user.isAdmin) return null
        val definition =
            try {
                metadata.loadDefinition(user.organizationId, objectName)
            } catch (_: NotFoundException) {
                // object is gone: nothing left to prove the caller may read its fields
                return emptySet()
            }
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        if (fieldAccess.unrestricted) return null
        return definition.readableNames(fieldAccess)
    }

    private fun filter(
        state: Map<String, Any?>?,
        allowed: Set<String>?
    ): Map<String, Any?>? {
        if (state == null || allowed == null) return state
        return state.filterKeys { it in allowed }
    }

    private fun parse(json: String?): Map<String, Any?>? = json?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }
}
