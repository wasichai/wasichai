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
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
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

// raw row. states stay as maps until we know what the caller may read. internal for tests.
internal data class AuditRow(
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
    private val schemas: WasichaiSchemas,
    private val scope: AuditRecordScope
) {
    // tenant-wide read: needs an organization-wide READ grant, not one on some object.
    // the admin trail (admin:*) is MANAGE_ORGANIZATION's instead (ADR-049): asked for by name, anyone else gets
    // nothing, not a 403 that says it is there; unasked, it is left out for them, before the limit.
    suspend fun list(
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?
    ): List<AuditEntry> {
        val user = currentUser.require()
        if (AdminEntity.isAdmin(objectName)) {
            if (!currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)) return emptyList()
            return toEntries(user, fetch(user.organizationId, objectName, recordId, operation, limit, withAdmin = true))
        }
        currentUser.requirePermission(user, Actions.READ)
        val withAdmin = currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)
        val rows = fetch(user.organizationId, objectName, recordId, operation, limit, withAdmin)
        return toEntries(user, inScope(user, rows))
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
        // a record outside the app's read scope has no history for this caller: 404, as GET on it (ADR-048)
        val readable = scope.readable(user, definition, listOf(recordId))
        if (readable != null && recordId !in readable) throw NotFoundException("Record $recordId does not exist")
        val rows = fetch(user.organizationId, definition.obj.name, recordId, null, limit, withAdmin = false)
        return toEntries(user, rows)
    }

    // only the entries of records the caller reads (ADR-048). with a scope on the object, a record that is
    // gone cannot be shown to be in it, so its entries go too, as do those of an object that is gone.
    // after the limit: a scoped caller may get fewer entries than asked for. admin entries name no record of an
    // object, so no scope reaches them (ADR-049). internal for tests.
    internal suspend fun inScope(
        user: AuthenticatedUser,
        rows: List<AuditRow>
    ): List<AuditRow> {
        if (!scope.appliesTo(user)) return rows
        val readable =
            rows
                .filter { it.recordId != null && !AdminEntity.isAdmin(it.objectName) }
                .groupBy({ it.objectName }, { it.recordId!! })
                .mapValues { (objectName, ids) ->
                    val definition = definitionOrNull(user, objectName) ?: return@mapValues emptySet<UUID>()
                    scope.readable(user, definition, ids)
                }
        return rows.filter { row ->
            val allowed = readable[row.objectName]
            row.recordId == null || allowed == null || row.recordId in allowed
        }
    }

    private suspend fun definitionOrNull(
        user: AuthenticatedUser,
        objectName: String
    ): ObjectDefinition? =
        try {
            metadata.loadDefinition(user.organizationId, objectName)
        } catch (_: NotFoundException) {
            null
        }

    private suspend fun fetch(
        organizationId: UUID,
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?,
        withAdmin: Boolean
    ): List<AuditRow> {
        val filters = StringBuilder()
        if (recordId != null) filters.append(" AND a.record_id = :recordId")
        if (!withAdmin) filters.append(" AND a.object_name NOT LIKE :adminNames")
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
        if (!withAdmin) spec = spec.bind("adminNames", AdminEntity.PREFIX + "%")
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

    // the log would otherwise hand out field values the field permissions hide. internal for tests.
    internal suspend fun toEntries(
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
                changes = changes(row, allowed),
                documentId = row.documentId?.toString(),
                reason = row.reason,
                serviceAccount = row.serviceAccount
            )
        }
    }

    // an admin entry's CREATE and DELETE list every key: who made or dropped a role says what it was (ADR-049).
    // a record's carry none, as they always did.
    private fun changes(
        row: AuditRow,
        allowed: Set<String>?
    ): List<FieldChange> {
        if (AdminEntity.isAdmin(row.objectName)) return AuditDiff.changes(row.before ?: emptyMap(), row.after ?: emptyMap())
        return AuditDiff.changes(filter(row.before, allowed), filter(row.after, allowed))
    }

    // null means no restriction. empty set means nothing may be shown.
    // an admin entry reaches here only for a MANAGE_ORGANIZATION holder, and is theirs whole (ADR-049).
    private suspend fun readableFields(
        user: AuthenticatedUser,
        objectName: String
    ): Set<String>? {
        if (user.isAdmin || AdminEntity.isAdmin(objectName)) return null
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
