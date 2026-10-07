package wasichai.notifications

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// what the upsert and the reconciler compare a draft with
data class StoredRow(
    val id: UUID,
    val key: String?,
    val kind: NotificationKind,
    val fingerprint: String,
    val resolvedAt: Instant?,
    // a draft without publishAt keeps it: the window is checked against it before writing
    val publishAt: Instant,
    // the reconciler leaves alone a row written after its read began
    val updatedAt: Instant
)

enum class UpsertOutcome { CREATED, UPDATED, REOPENED, UNCHANGED }

data class UpsertResult(
    val id: UUID,
    val outcome: UpsertOutcome
) {
    val changed: Boolean get() = outcome != UpsertOutcome.UNCHANGED
}

// the admin list's status filter (spec B, REST)
enum class NotificationStatus { OPEN, SCHEDULED, ENDED }

// one notifications row, for the admin view. targets come from targetsOf.
data class StoredNotification(
    val id: UUID,
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    val link: LinkJson?,
    val linkObjectId: UUID?,
    val publishAt: Instant,
    val expiresAt: Instant?,
    val dueAt: Instant?,
    val source: String,
    val key: String?,
    val fingerprint: String,
    val resolvedAt: Instant?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    // people who read it
    val readCount: Long
)

// fixed tables, every statement scoped to the organization (purge aside: it is maintenance over all of them).
// jsonb travels as text: CAST on the way in, ::text on the way out.
// no transaction here: NotificationWriter opens one (or joins the caller's) around every change.
class NotificationRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    init {
        // an over-long channel fails the start, LISTEN or not: not the first business write
        NotificationChannel.name(schemas)
    }

    // the admin view: a row and how many read it
    private val columns =
        "n.id, n.kind, n.title, n.body, n.link::text AS link, n.link_object_id, n.publish_at, n.expires_at, n.due_at, " +
            "n.source, n.source_key, n.fingerprint, n.resolved_at, n.created_by, n.created_at, n.updated_at, " +
            "(SELECT count(*) FROM $m.notification_receipts r WHERE r.notification_id = n.id AND r.read_at IS NOT NULL) AS read_count"

    // a keyless draft: always a new row
    suspend fun insert(
        organizationId: UUID,
        source: String,
        prepared: PreparedNotification,
        createdBy: UUID?,
        now: Instant
    ): UUID = insertRow(organizationId, source, prepared, createdBy, now, onConflictNothing = false)!!

    // spec B, upsert table. a racing writer meets us on the unique key: DO NOTHING, then the locked update path
    suspend fun upsert(
        organizationId: UUID,
        source: String,
        prepared: PreparedNotification,
        createdBy: UUID?,
        now: Instant
    ): UpsertResult {
        val key = prepared.key ?: return UpsertResult(insert(organizationId, source, prepared, createdBy, now), UpsertOutcome.CREATED)
        val row =
            lockByKey(organizationId, source, key)
                ?: insertRow(organizationId, source, prepared, createdBy, now, onConflictNothing = true)?.let {
                    return UpsertResult(it, UpsertOutcome.CREATED)
                }
                // read committed: this new statement sees the row the other writer committed
                ?: lockByKey(organizationId, source, key)
                ?: error("notification $source/$key vanished between conflict and lock")
        return UpsertResult(row.id, apply(organizationId, row, prepared, now))
    }

    // the rows of one stored key, already locked by the caller: open and equal -> nothing; open -> update; resolved -> reopen
    suspend fun apply(
        organizationId: UUID,
        row: StoredRow,
        prepared: PreparedNotification,
        now: Instant
    ): UpsertOutcome {
        if (row.resolvedAt != null) {
            requireWindow(prepared, prepared.publishAt ?: now)
            update(organizationId, row.id, prepared, prepared.publishAt ?: now, now, reopen = true)
            deleteReceipts(organizationId, row.id)
            return UpsertOutcome.REOPENED
        }
        if (row.fingerprint == prepared.fingerprint) return UpsertOutcome.UNCHANGED
        // a null publishAt keeps the stored one
        requireWindow(prepared, prepared.publishAt ?: row.publishAt)
        update(organizationId, row.id, prepared, prepared.publishAt, now, reopen = false)
        // WARNING -> ACTION is news: everyone sees it again
        if (row.kind != prepared.kind) deleteReceipts(organizationId, row.id)
        return UpsertOutcome.UPDATED
    }

    // admin PUT: a full replace with the upsert's receipt rule. null: no such notification of source here
    suspend fun replace(
        organizationId: UUID,
        source: String,
        id: UUID,
        prepared: PreparedNotification,
        now: Instant
    ): UpsertOutcome? {
        val row =
            db
                .sql("SELECT $ROW_COLUMNS FROM $m.notifications WHERE organization_id = :org AND source = :source AND id = :id FOR UPDATE")
                .bind("org", organizationId)
                .bind("source", source)
                .bind("id", id)
                .map(::row)
                .one()
                .awaitFirstOrNull() ?: return null
        if (row.fingerprint == prepared.fingerprint) return UpsertOutcome.UNCHANGED
        requireWindow(prepared, prepared.publishAt ?: row.publishAt)
        update(organizationId, id, prepared, prepared.publishAt, now, reopen = false)
        if (row.kind != prepared.kind) deleteReceipts(organizationId, id)
        return UpsertOutcome.UPDATED
    }

    suspend fun findById(
        organizationId: UUID,
        id: UUID
    ): StoredNotification? =
        db
            .sql("SELECT $columns FROM $m.notifications n WHERE n.organization_id = :org AND n.id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map(::notification)
            .one()
            .awaitFirstOrNull()

    // targets and receipts go with it (ON DELETE CASCADE)
    suspend fun delete(
        organizationId: UUID,
        source: String,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.notifications WHERE organization_id = :org AND source = :source AND id = :id")
            .bind("org", organizationId)
            .bind("source", source)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun resolve(
        organizationId: UUID,
        source: String,
        key: String,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.notifications SET resolved_at = :now, updated_at = :now " +
                    "WHERE organization_id = :org AND source = :source AND source_key = :key AND resolved_at IS NULL"
            ).bind("org", organizationId)
            .bind("source", source)
            .bind("key", key)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun resolveAll(
        organizationId: UUID,
        source: String,
        now: Instant
    ): Int =
        db
            .sql(
                "UPDATE $m.notifications SET resolved_at = :now, updated_at = :now " +
                    "WHERE organization_id = :org AND source = :source AND resolved_at IS NULL"
            ).bind("org", organizationId)
            .bind("source", source)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()

    suspend fun resolveIds(
        organizationId: UUID,
        ids: Collection<UUID>,
        now: Instant
    ): Int {
        if (ids.isEmpty()) return 0
        return db
            .sql(
                "UPDATE $m.notifications SET resolved_at = :now, updated_at = :now " +
                    "WHERE organization_id = :org AND id = ANY(CAST(:ids AS uuid[])) AND resolved_at IS NULL"
            ).bind("org", organizationId)
            .bind("ids", ids.distinct().toTypedArray())
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()
    }

    // the reconciler's input: every open row of the source, plus resolved rows whose key is in keys
    // (they may come back). locked: inside the writer's transaction nobody moves them under the diff.
    suspend fun openBySource(
        organizationId: UUID,
        source: String,
        keys: Collection<String>?
    ): List<StoredRow> =
        db
            .sql(
                "SELECT $ROW_COLUMNS FROM $m.notifications " +
                    "WHERE organization_id = :org AND source = :source " +
                    "AND (resolved_at IS NULL OR source_key = ANY(CAST(:keys AS text[]))) FOR UPDATE"
            ).bind("org", organizationId)
            .bind("source", source)
            .bind("keys", keys.orEmpty().distinct().toTypedArray())
            .map(::row)
            .all()
            .collectList()
            .awaitSingle()

    // newest first. status per spec B: open = in its window, scheduled = not yet, ended = resolved or expired.
    // unitId: those addressed to that unit, so the UI can warn before the unit is deleted.
    suspend fun adminPage(
        organizationId: UUID,
        source: String?,
        kind: NotificationKind?,
        status: NotificationStatus?,
        unitId: UUID?,
        now: Instant,
        page: PageRequest
    ): PageResponse<StoredNotification> {
        val where = mutableListOf("n.organization_id = :org")
        if (source != null) where += "n.source = :source"
        if (kind != null) where += "n.kind = :kind"
        when (status) {
            null -> {}
            NotificationStatus.OPEN -> where += "n.resolved_at IS NULL AND n.publish_at <= :now AND (n.expires_at IS NULL OR n.expires_at > :now)"
            NotificationStatus.SCHEDULED -> where += "n.resolved_at IS NULL AND n.publish_at > :now"
            NotificationStatus.ENDED -> where += "(n.resolved_at IS NOT NULL OR n.expires_at <= :now)"
        }
        if (unitId != null) where += "EXISTS (SELECT 1 FROM $m.notification_targets t WHERE t.notification_id = n.id AND t.unit_id = :unitId)"
        val clause = where.joinToString(" AND ")

        // r2dbc refuses a bound name the statement never mentions: bind only what the filter used
        fun DatabaseClient.GenericExecuteSpec.filters(): DatabaseClient.GenericExecuteSpec {
            var spec = bind("org", organizationId)
            if (source != null) spec = spec.bind("source", source)
            if (kind != null) spec = spec.bind("kind", kind.name)
            if (status != null) spec = spec.bind("now", now.odt())
            if (unitId != null) spec = spec.bind("unitId", unitId)
            return spec
        }

        val total =
            db
                .sql("SELECT count(*) AS total FROM $m.notifications n WHERE $clause")
                .filters()
                .map { row, _ -> Rows.long(row, "total") }
                .one()
                .awaitSingle()
        val content =
            db
                .sql("SELECT $columns FROM $m.notifications n WHERE $clause ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset")
                .filters()
                .bind("limit", page.size)
                .bind("offset", page.offset)
                .map(::notification)
                .all()
                .collectList()
                .awaitSingle()
        return PageResponse.of(content, page.page, page.size, total)
    }

    // sorted per notification, the order fingerprints use
    suspend fun targetsOf(
        organizationId: UUID,
        ids: Collection<UUID>
    ): Map<UUID, List<StoredTarget>> {
        if (ids.isEmpty()) return emptyMap()
        return db
            .sql(
                """
                SELECT t.notification_id, t.type, t.user_id, t.role_name, t.unit_id
                FROM $m.notification_targets t
                JOIN $m.notifications n ON n.id = t.notification_id
                WHERE n.organization_id = :org AND n.id = ANY(CAST(:ids AS uuid[]))
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("ids", ids.distinct().toTypedArray())
            .map { row, _ ->
                Rows.uuid(row, "notification_id") to
                    StoredTarget(
                        TargetType.valueOf(Rows.string(row, "type")),
                        Rows.uuidOrNull(row, "user_id"),
                        Rows.stringOrNull(row, "role_name"),
                        Rows.uuidOrNull(row, "unit_id")
                    )
            }.all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, targets) -> targets.sorted() }
    }

    // maintenance, so across every organization: resolved or expired before olderThan. answers how many went.
    suspend fun purge(olderThan: Instant): Int =
        db
            .sql("DELETE FROM $m.notifications WHERE resolved_at < :before OR expires_at < :before")
            .bind("before", olderThan.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()

    // spec D: goes out with the caller's commit, or nowhere
    suspend fun notify(
        organizationId: UUID,
        userId: UUID? = null
    ) = db.pgNotify(schemas, organizationId, userId)

    // the row of one key, open or resolved, locked until the transaction ends
    suspend fun lockByKey(
        organizationId: UUID,
        source: String,
        key: String
    ): StoredRow? =
        db
            .sql("SELECT $ROW_COLUMNS FROM $m.notifications WHERE organization_id = :org AND source = :source AND source_key = :key FOR UPDATE")
            .bind("org", organizationId)
            .bind("source", source)
            .bind("key", key)
            .map(::row)
            .one()
            .awaitFirstOrNull()

    // null only with onConflictNothing, when another writer holds the key
    private suspend fun insertRow(
        organizationId: UUID,
        source: String,
        prepared: PreparedNotification,
        createdBy: UUID?,
        now: Instant,
        onConflictNothing: Boolean
    ): UUID? {
        requireWindow(prepared, prepared.publishAt ?: now)
        val conflict = if (onConflictNothing) "ON CONFLICT (organization_id, source, source_key) DO NOTHING" else ""
        val id =
            db
                .sql(
                    """
                    INSERT INTO $m.notifications
                        (organization_id, kind, title, body, link, link_object_id, publish_at, expires_at, due_at,
                         source, source_key, fingerprint, created_by, created_at, updated_at)
                    VALUES (:org, :kind, :title, :body, CAST(:link AS jsonb), :linkObjectId, :publishAt, :expiresAt, :dueAt,
                            :source, :key, :fingerprint, :createdBy, :now, :now)
                    $conflict
                    RETURNING id
                    """.trimIndent()
                ).bind("org", organizationId)
                .content(prepared)
                .bind("publishAt", (prepared.publishAt ?: now).odt())
                .bind("source", source)
                .nullable("key", prepared.key, String::class.java)
                .nullable("createdBy", createdBy, UUID::class.java)
                .bind("now", now.odt())
                .map { row, _ -> Rows.uuid(row, "id") }
                .one()
                .awaitFirstOrNull() ?: return null
        insertTargets(organizationId, id, prepared.targets)
        return id
    }

    // publishAt null keeps the stored one. reopen also clears resolved_at.
    private suspend fun update(
        organizationId: UUID,
        id: UUID,
        prepared: PreparedNotification,
        publishAt: Instant?,
        now: Instant,
        reopen: Boolean
    ) {
        val resolved = if (reopen) "resolved_at = NULL," else ""
        db
            .sql(
                """
                UPDATE $m.notifications
                SET kind = :kind, title = :title, body = :body, link = CAST(:link AS jsonb), link_object_id = :linkObjectId,
                    publish_at = COALESCE(:publishAt, publish_at), expires_at = :expiresAt, due_at = :dueAt,
                    fingerprint = :fingerprint, $resolved updated_at = :now
                WHERE organization_id = :org AND id = :id
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("id", id)
            .content(prepared)
            .nullable("publishAt", publishAt?.odt(), OffsetDateTime::class.java)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                "DELETE FROM $m.notification_targets t USING $m.notifications n " +
                    "WHERE t.notification_id = n.id AND n.id = :id AND n.organization_id = :org"
            ).bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        insertTargets(organizationId, id, prepared.targets)
    }

    // one statement for every target; the join keeps it inside the organization
    private suspend fun insertTargets(
        organizationId: UUID,
        id: UUID,
        targets: List<StoredTarget>
    ) {
        if (targets.isEmpty()) return
        val values =
            targets.indices.joinToString(", ") { i ->
                "(CAST(:type$i AS text), CAST(:user$i AS uuid), CAST(:role$i AS text), CAST(:unit$i AS uuid))"
            }
        var spec =
            db
                .sql(
                    """
                    INSERT INTO $m.notification_targets (notification_id, type, user_id, role_name, unit_id)
                    SELECT n.id, v.type, v.user_id, v.role_name, v.unit_id
                    FROM (VALUES $values) AS v (type, user_id, role_name, unit_id)
                    JOIN $m.notifications n ON n.id = :id AND n.organization_id = :org
                    """.trimIndent()
                ).bind("org", organizationId)
                .bind("id", id)
        targets.forEachIndexed { i, target ->
            spec =
                spec
                    .bind("type$i", target.type.name)
                    .nullable("user$i", target.userId, UUID::class.java)
                    .nullable("role$i", target.roleName, String::class.java)
                    .nullable("unit$i", target.unitId, UUID::class.java)
        }
        spec.fetch().rowsUpdated().awaitSingle()
    }

    // the table's window check, said first. a draft without publishAt keeps the stored one or takes now, which
    // validation never saw: an IllegalArgumentException (a refused batch, a publish that throws), not an integrity error
    private fun requireWindow(
        prepared: PreparedNotification,
        publishAt: Instant
    ) {
        require(prepared.expiresAt == null || prepared.expiresAt > publishAt) { "expiresAt must be after publishAt ($publishAt)" }
    }

    // a reset: everyone reads it as new again
    private suspend fun deleteReceipts(
        organizationId: UUID,
        id: UUID
    ) {
        db
            .sql(
                "DELETE FROM $m.notification_receipts r USING $m.notifications n " +
                    "WHERE r.notification_id = n.id AND n.id = :id AND n.organization_id = :org"
            ).bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // the columns insert and update share
    private fun DatabaseClient.GenericExecuteSpec.content(prepared: PreparedNotification): DatabaseClient.GenericExecuteSpec =
        bind("kind", prepared.kind.name)
            .bind("title", prepared.title)
            .nullable("body", prepared.body, String::class.java)
            .nullable("link", prepared.link?.let { json.writeValueAsString(it.toJson()) }, String::class.java)
            .nullable("linkObjectId", prepared.linkObjectId, UUID::class.java)
            .nullable("expiresAt", prepared.expiresAt?.odt(), OffsetDateTime::class.java)
            .nullable("dueAt", prepared.dueAt?.odt(), OffsetDateTime::class.java)
            .bind("fingerprint", prepared.fingerprint)

    private fun row(
        row: Row,
        metadata: RowMetadata
    ): StoredRow =
        StoredRow(
            id = Rows.uuid(row, "id"),
            key = Rows.stringOrNull(row, "source_key"),
            kind = NotificationKind.valueOf(Rows.string(row, "kind")),
            fingerprint = Rows.string(row, "fingerprint"),
            resolvedAt = Rows.instantOrNull(row, "resolved_at"),
            publishAt = Rows.instantOrNull(row, "publish_at")!!,
            updatedAt = Rows.instantOrNull(row, "updated_at")!!
        )

    private fun notification(
        row: Row,
        metadata: RowMetadata
    ): StoredNotification =
        StoredNotification(
            id = Rows.uuid(row, "id"),
            kind = NotificationKind.valueOf(Rows.string(row, "kind")),
            title = Rows.string(row, "title"),
            body = Rows.stringOrNull(row, "body"),
            link = Rows.stringOrNull(row, "link")?.let { json.readValue(it, LinkJson::class.java) },
            linkObjectId = Rows.uuidOrNull(row, "link_object_id"),
            publishAt = Rows.instantOrNull(row, "publish_at")!!,
            expiresAt = Rows.instantOrNull(row, "expires_at"),
            dueAt = Rows.instantOrNull(row, "due_at"),
            source = Rows.string(row, "source"),
            key = Rows.stringOrNull(row, "source_key"),
            fingerprint = Rows.string(row, "fingerprint"),
            resolvedAt = Rows.instantOrNull(row, "resolved_at"),
            createdBy = Rows.uuidOrNull(row, "created_by"),
            createdAt = Rows.instantOrNull(row, "created_at")!!,
            updatedAt = Rows.instantOrNull(row, "updated_at")!!,
            readCount = Rows.long(row, "read_count")
        )

    private companion object {
        const val ROW_COLUMNS = "id, source_key, kind, fingerprint, resolved_at, publish_at, updated_at"
    }
}

// timestamptz binds as OffsetDateTime; UTC, the driver's own reading
internal fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun <T : Any> DatabaseClient.GenericExecuteSpec.nullable(
    name: String,
    value: T?,
    type: Class<T>
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
