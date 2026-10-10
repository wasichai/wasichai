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
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val serviceAccount: String? = null,
    // the request it came from and what wrote it (ADR-050). left out on rows older than both
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val correlationId: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val source: String? = null
)

// what GET /api/audit narrows by besides the object, the record and the operation. blank is no filter.
// from/to: occurred_at >= from AND occurred_at < to. userId, serviceAccount: who made the change (ADR-052).
data class AuditFilter(
    val correlationId: String? = null,
    val source: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val userId: UUID? = null,
    val serviceAccount: String? = null
) {
    internal val correlation: String? get() = correlationId?.trim()?.takeIf { it.isNotEmpty() }
    internal val origin: String? get() = source?.trim()?.takeIf { it.isNotEmpty() }
    internal val account: String? get() = serviceAccount?.trim()?.takeIf { it.isNotEmpty() }
}

// one page of the log. the list itself is what the api answers; the cursor travels in a header,
// so a client that knows nothing of paging reads what it always read (ADR-052).
data class AuditPage(
    val entries: List<AuditEntry>,
    // null when no row follows
    val nextCursor: String?
) {
    companion object {
        const val NEXT_CURSOR_HEADER = "X-Next-Cursor"
    }
}

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
    val serviceAccount: String?,
    val correlationId: String? = null,
    val source: String? = null
)

@Service
class AuditQueryService(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val access: AccessPolicy,
    private val schemas: WasichaiSchemas,
    private val scope: AuditRecordScope,
    // the app's read masks (ADR-064). defaulted: code that builds this service itself keeps compiling
    private val masks: AuditStateMask? = null
) {
    // tenant-wide read: needs an organization-wide READ grant, not one on some object.
    // the admin trail (admin:*) is MANAGE_ORGANIZATION's instead (ADR-049): asked for by name, anyone else gets
    // nothing, not a 403 that says it is there; unasked, it is left out for them, before the limit.
    // filter: the rows of one request or one source (ADR-050), a period, a user (ADR-052). it only narrows: who
    // reads what stays as above.
    suspend fun list(
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?,
        filter: AuditFilter = AuditFilter()
    ): List<AuditEntry> = page(objectName, recordId, operation, limit, filter).entries

    // list, one page at a time: [after] is the nextCursor of the page before, read under the same filters (ADR-052)
    suspend fun page(
        objectName: String?,
        recordId: UUID?,
        operation: String?,
        limit: Int?,
        filter: AuditFilter = AuditFilter(),
        after: String? = null
    ): AuditPage {
        val user = currentUser.require()
        val query = AuditQuery(LIST, objectName, recordId, operation, limit, filter, after)
        if (AdminEntity.isAdmin(objectName)) {
            if (!currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)) return AuditPage(emptyList(), null)
            val (rows, next) = read(user.organizationId, query, withAdmin = true)
            return AuditPage(toEntries(user, rows), next)
        }
        currentUser.requirePermission(user, Actions.READ)
        val withAdmin = currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)
        val (rows, next) = read(user.organizationId, query, withAdmin)
        return AuditPage(toEntries(user, inScope(user, rows)), next)
    }

    // history of one record is a read of that object, so it is checked against that object
    suspend fun history(
        objectName: String,
        recordId: UUID,
        limit: Int?
    ): List<AuditEntry> = historyPage(objectName, recordId, limit).entries

    // history, one page at a time, narrowed like list (ADR-052)
    suspend fun historyPage(
        objectName: String,
        recordId: UUID,
        limit: Int?,
        filter: AuditFilter = AuditFilter(),
        after: String? = null
    ): AuditPage {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        // a record outside the app's read scope has no history for this caller: 404, as GET on it (ADR-048)
        val readable = scope.readable(user, definition, listOf(recordId))
        if (readable != null && recordId !in readable) throw NotFoundException("Record $recordId does not exist")
        val query = AuditQuery(HISTORY, definition.obj.name, recordId, null, limit, filter, after)
        val (rows, next) = read(user.organizationId, query, withAdmin = false)
        return AuditPage(toEntries(user, rows), next)
    }

    // only the entries of records the caller reads (ADR-048). with a scope on the object, a record that is
    // gone cannot be shown to be in it, so its entries go too, as do those of an object that is gone.
    // after the limit, and after the cursor is taken: a scoped caller may get fewer entries than asked for, even
    // none with a cursor still there, but no row is skipped or repeated across pages (ADR-052). admin entries name
    // no record of an object, so no scope reaches them (ADR-049). internal for tests.
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

    // one row past the page says whether another follows. the cursor is the page's last row as read, before the
    // read scope drops any, so the next page starts where this read stopped (ADR-052)
    private suspend fun read(
        organizationId: UUID,
        query: AuditQuery,
        withAdmin: Boolean
    ): Pair<List<AuditRow>, String?> {
        val filters = query.filters()
        val cursor =
            query.after
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AuditCursor.decode(it, filters) }
        val (sql, bindings) = select(organizationId, query, withAdmin, cursor)
        var spec = db.sql(sql)
        bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
        val rows =
            spec
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
                        serviceAccount = Rows.stringOrNull(row, "service_account"),
                        correlationId = Rows.stringOrNull(row, "correlation_id"),
                        source = Rows.stringOrNull(row, "source")
                    )
                }.all()
                .asFlow()
                .toList()
        val page = rows.take(query.size)
        val next =
            if (rows.size > query.size) {
                // occurred_at is NOT NULL in the table
                page.last().let { AuditCursor(filters, it.occurredAt!!, it.id).encode() }
            } else {
                null
            }
        return page to next
    }

    // the sql and its values, apart so a test can EXPLAIN exactly what runs. internal for tests.
    internal fun select(
        organizationId: UUID,
        query: AuditQuery,
        withAdmin: Boolean,
        cursor: AuditCursor? = null
    ): Pair<String, Map<String, Any>> {
        val filter = query.filter
        val bindings =
            mutableMapOf<String, Any>(
                "organizationId" to organizationId,
                "objectName" to (query.objectName ?: ""),
                "operation" to query.normalizedOperation,
                // one past the page: is there another?
                "limit" to query.size + 1
            )
        val filters = StringBuilder()
        query.recordId?.let {
            filters.append(" AND a.record_id = :recordId")
            bindings["recordId"] = it
        }
        if (!withAdmin) {
            filters.append(" AND a.object_name NOT LIKE :adminNames")
            bindings["adminNames"] = AdminEntity.PREFIX + "%"
        }
        // next to organization_id: (organization_id, correlation_id) is indexed
        filter.correlation?.let {
            filters.append(" AND a.correlation_id = :correlationId")
            bindings["correlationId"] = it
        }
        filter.origin?.let {
            filters.append(" AND a.source = :source")
            bindings["source"] = it
        }
        // (organization_id, user_id, occurred_at DESC) serves a user and a period together (V12)
        filter.userId?.let {
            filters.append(" AND a.user_id = :userId")
            bindings["userId"] = it
        }
        filter.account?.let {
            filters.append(" AND sa.name = :serviceAccount")
            bindings["serviceAccount"] = it
        }
        filter.from?.let {
            filters.append(" AND a.occurred_at >= :from")
            bindings["from"] = it
        }
        filter.to?.let {
            filters.append(" AND a.occurred_at < :to")
            bindings["to"] = it
        }
        // strictly after the cursor's row in (occurred_at DESC, id DESC) order. the <= alone bounds the index scan
        cursor?.let {
            filters.append(" AND a.occurred_at <= :afterAt AND (a.occurred_at < :afterAt OR a.id < :afterId)")
            bindings["afterAt"] = it.occurredAt
            bindings["afterId"] = it.id
        }
        val sql =
            """
            SELECT a.id, u.email, a.object_name, a.record_id, a.operation, a.occurred_at, a.document_id, a.reason,
                   a.correlation_id, a.source,
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
        return sql to bindings
    }

    // the log would otherwise hand out field values the field permissions hide. internal for tests.
    internal suspend fun toEntries(
        user: AuthenticatedUser,
        rows: List<AuditRow>
    ): List<AuditEntry> {
        val readable = mutableMapOf<String, Set<String>?>()
        val masked = mutableMapOf<String, ObjectDefinition?>()
        return rows.map { row ->
            val allowed = readable.getOrPut(row.objectName) { readableFields(user, row.objectName) }
            val mask = masked.getOrPut(row.objectName) { maskedDefinition(user, row.objectName) }
            AuditEntry(
                id = row.id.toString(),
                userEmail = row.userEmail,
                objectName = row.objectName,
                recordId = row.recordId?.toString(),
                operation = row.operation,
                occurredAt = row.occurredAt,
                changes = changes(mask?.let { masked(user, it, row) } ?: row, allowed),
                documentId = row.documentId?.toString(),
                reason = row.reason,
                serviceAccount = row.serviceAccount,
                correlationId = row.correlationId,
                source = row.source
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

    // the definition the masks rewrite this object's states with, null when none does. never an admin entry
    private suspend fun maskedDefinition(
        user: AuthenticatedUser,
        objectName: String
    ): ObjectDefinition? {
        if (masks == null || AdminEntity.isAdmin(objectName)) return null
        val definition = definitionOrNull(user, objectName) ?: return null
        return definition.takeIf { masks.appliesTo(user, it) }
    }

    // each state is the whole record at that point: what the masks decide on
    private suspend fun masked(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        row: AuditRow
    ): AuditRow {
        val masks = masks ?: return row
        return row.copy(
            before = row.before?.let { masks.state(user, definition, it) },
            after = row.after?.let { masks.state(user, definition, it) }
        )
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

// one read of the log: what it asks for and where it resumes. internal for tests.
internal data class AuditQuery(
    // LIST or HISTORY: a history cursor never continues the tenant list, nor the other way round
    val kind: String,
    val objectName: String?,
    val recordId: UUID?,
    val operation: String?,
    val limit: Int?,
    val filter: AuditFilter,
    val after: String? = null
) {
    val size: Int get() = (limit ?: 100).coerceIn(1, 500)

    val normalizedOperation: String get() = operation?.trim()?.uppercase() ?: ""

    // every filter that shapes the list, as it is applied; not the limit, a page may change size
    fun filters(): String =
        AuditCursor.filtersOf(
            listOf(
                kind,
                objectName ?: "",
                recordId,
                normalizedOperation,
                filter.correlation,
                filter.origin,
                filter.from,
                filter.to,
                filter.userId,
                filter.account
            )
        )
}

private const val LIST = "list"
private const val HISTORY = "history"
