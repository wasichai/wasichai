package wasichai.notifications

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// who reads: the token's roles (as every permission check) and OrgUnitDirectory.closureOf (units and their ancestors)
data class InboxReader(
    val organizationId: UUID,
    val userId: UUID,
    val roles: Collection<String>,
    val units: Collection<UUID>
)

// spec B, states. active: not dismissed, not snoozed past now. unread: active and never read. snoozed: snoozed past now.
enum class InboxState { ACTIVE, UNREAD, SNOOZED }

// an item as stored. the caller drops a RECORD link the reader may not READ, or whose object is gone (linkObjectId null)
data class InboxRow(
    val item: InboxItem,
    val linkObjectId: UUID?
)

// what a receipt action needs to know about a notification the reader sees
data class VisibleRow(
    val id: UUID,
    val kind: NotificationKind,
    val source: String
)

// spec B, "What a person sees": one predicate for the list, the summary and every receipt action.
// a notification the reader cannot see, or of another tenant, is simply not there: the caller answers 404.
// receipt writes signal the reader's own streams ({"o","u"}), in the caller's transaction if any.
class InboxRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    init {
        // an over-long channel fails the start, LISTEN or not: not the first receipt
        NotificationChannel.name(schemas)
    }

    // arrays + ANY, never IN (:list): an empty list breaks IN, an empty array matches nothing
    private val visible =
        """
        FROM $m.notifications n
        LEFT JOIN $m.notification_receipts r ON r.notification_id = n.id AND r.user_id = :userId
        WHERE n.organization_id = :org
          AND n.resolved_at IS NULL
          AND n.publish_at <= :now AND (n.expires_at IS NULL OR n.expires_at > :now)
          AND EXISTS (SELECT 1 FROM $m.notification_targets t WHERE t.notification_id = n.id
                      AND (t.type = 'ALL' OR t.user_id = :userId
                           OR t.role_name = ANY(CAST(:roles AS text[])) OR t.unit_id = ANY(CAST(:units AS uuid[]))))
        """.trimIndent()

    suspend fun page(
        reader: InboxReader,
        kind: NotificationKind?,
        state: InboxState,
        now: Instant,
        page: PageRequest
    ): PageResponse<InboxRow> {
        val filter = stateFilter(state) + if (kind != null) " AND n.kind = :kind" else ""
        // ACTION is a to-do list: what is due first
        val order = if (kind == NotificationKind.ACTION) "n.due_at ASC NULLS LAST, n.publish_at DESC, n.id" else "n.publish_at DESC, n.id"

        fun DatabaseClient.GenericExecuteSpec.kind() = if (kind != null) bind("kind", kind.name) else this

        val total =
            db
                .sql("SELECT count(*) AS total $visible $filter")
                .reader(reader, now)
                .kind()
                .map { row, _ -> Rows.long(row, "total") }
                .one()
                .awaitSingle()
        val content =
            db
                .sql(
                    "SELECT n.id, n.kind, n.title, n.body, n.link::text AS link, n.link_object_id, n.publish_at, n.expires_at, n.due_at, " +
                        "n.source, r.read_at, r.snoozed_until $visible $filter ORDER BY $order LIMIT :limit OFFSET :offset"
                ).reader(reader, now)
                .kind()
                .bind("limit", page.size)
                .bind("offset", page.offset)
                .map { row, _ -> inboxRow(row, now) }
                .all()
                .collectList()
                .awaitSingle()
        return PageResponse.of(content, page.page, page.size, total)
    }

    // counts per kind over active items, and the newest active one. two queries whatever the kinds.
    suspend fun summary(
        reader: InboxReader,
        now: Instant
    ): NotificationSummary {
        val active = stateFilter(InboxState.ACTIVE)
        val counts =
            db
                .sql(
                    "SELECT n.kind, count(*) AS active, count(*) FILTER (WHERE r.read_at IS NULL) AS unread, " +
                        "count(*) FILTER (WHERE n.due_at < :now) AS overdue $visible $active GROUP BY n.kind"
                ).reader(reader, now)
                .map { row, _ ->
                    NotificationKind.valueOf(Rows.string(row, "kind")) to
                        KindCount(Rows.long(row, "active"), Rows.long(row, "unread"), Rows.long(row, "overdue"))
                }.all()
                .collectList()
                .awaitSingle()
                .toMap()
        val latest =
            db
                .sql(
                    "SELECT n.id, n.kind, n.title, n.publish_at, n.link::text AS link, n.link_object_id " +
                        "$visible $active ORDER BY n.publish_at DESC, n.id LIMIT 1"
                ).reader(reader, now)
                .map { row, _ ->
                    LatestNotification(
                        id = Rows.uuid(row, "id"),
                        kind = NotificationKind.valueOf(Rows.string(row, "kind")),
                        title = Rows.string(row, "title"),
                        publishAt = Rows.instantOrNull(row, "publish_at")!!,
                        link = link(row),
                        linkObjectId = Rows.uuidOrNull(row, "link_object_id")
                    )
                }.one()
                .awaitFirstOrNull()
        return NotificationSummary.of(counts, latest)
    }

    // whatever its state: a dismissed or snoozed one is still the reader's
    suspend fun visible(
        reader: InboxReader,
        id: UUID,
        now: Instant
    ): VisibleRow? =
        db
            .sql("SELECT n.id, n.kind, n.source $visible AND n.id = :id")
            .reader(reader, now)
            .bind("id", id)
            .map { row, _ -> VisibleRow(Rows.uuid(row, "id"), NotificationKind.valueOf(Rows.string(row, "kind")), Rows.string(row, "source")) }
            .one()
            .awaitFirstOrNull()

    // false: not the reader's to touch (the caller answers 404). a first read keeps its time.
    suspend fun markRead(
        reader: InboxReader,
        id: UUID,
        now: Instant
    ): Boolean = receipt(reader, id, now, "read_at", ":now", "COALESCE($RECEIPTS.read_at, EXCLUDED.read_at)")

    suspend fun dismiss(
        reader: InboxReader,
        id: UUID,
        now: Instant
    ): Boolean = receipt(reader, id, now, "dismissed_at", ":now", "COALESCE($RECEIPTS.dismissed_at, EXCLUDED.dismissed_at)")

    // bounds (now < until <= now + snooze-max) are the caller's
    suspend fun snooze(
        reader: InboxReader,
        id: UUID,
        until: Instant,
        now: Instant
    ): Boolean = receipt(reader, id, now, "snoozed_until", ":until", "EXCLUDED.snoozed_until", until)

    // every visible active unread item, of one kind or all. answers how many it marked.
    suspend fun readAll(
        reader: InboxReader,
        kind: NotificationKind?,
        now: Instant
    ): Int {
        val filter = stateFilter(InboxState.UNREAD) + if (kind != null) " AND n.kind = :kind" else ""
        var spec =
            db
                .sql(
                    "INSERT INTO $m.notification_receipts AS $RECEIPTS (notification_id, user_id, read_at) " +
                        "SELECT n.id, :userId, :now $visible $filter " +
                        "ON CONFLICT (notification_id, user_id) DO UPDATE SET read_at = COALESCE($RECEIPTS.read_at, EXCLUDED.read_at)"
                ).reader(reader, now)
        if (kind != null) spec = spec.bind("kind", kind.name)
        val marked =
            spec
                .fetch()
                .rowsUpdated()
                .awaitSingle()
                .toInt()
        if (marked > 0) db.pgNotify(schemas, reader.organizationId, reader.userId)
        return marked
    }

    // one statement: only a visible row gets a receipt, so a stranger's id writes nothing
    private suspend fun receipt(
        reader: InboxReader,
        id: UUID,
        now: Instant,
        column: String,
        value: String,
        onConflict: String,
        until: Instant? = null
    ): Boolean {
        var spec =
            db
                .sql(
                    "INSERT INTO $m.notification_receipts AS $RECEIPTS (notification_id, user_id, $column) " +
                        "SELECT n.id, :userId, $value $visible AND n.id = :id " +
                        "ON CONFLICT (notification_id, user_id) DO UPDATE SET $column = $onConflict"
                ).reader(reader, now)
                .bind("id", id)
        if (until != null) spec = spec.bind("until", until.odt())
        val written = spec.fetch().rowsUpdated().awaitSingle() > 0
        if (written) db.pgNotify(schemas, reader.organizationId, reader.userId)
        return written
    }

    private fun stateFilter(state: InboxState): String =
        when (state) {
            InboxState.ACTIVE -> " AND $ACTIVE"
            InboxState.UNREAD -> " AND $ACTIVE AND r.read_at IS NULL"
            InboxState.SNOOZED -> " AND r.dismissed_at IS NULL AND r.snoozed_until > :now"
        }

    private fun DatabaseClient.GenericExecuteSpec.reader(
        reader: InboxReader,
        now: Instant
    ): DatabaseClient.GenericExecuteSpec =
        bind("org", reader.organizationId)
            .bind("userId", reader.userId)
            .bind("roles", reader.roles.distinct().toTypedArray())
            .bind("units", reader.units.distinct().toTypedArray())
            .bind("now", now.odt())

    private fun inboxRow(
        row: Row,
        now: Instant
    ): InboxRow {
        val kind = NotificationKind.valueOf(Rows.string(row, "kind"))
        val source = Rows.string(row, "source")
        val dueAt = Rows.instantOrNull(row, "due_at")
        return InboxRow(
            item =
                InboxItem(
                    id = Rows.uuid(row, "id"),
                    kind = kind,
                    title = Rows.string(row, "title"),
                    body = Rows.stringOrNull(row, "body"),
                    link = link(row),
                    publishAt = Rows.instantOrNull(row, "publish_at")!!,
                    expiresAt = Rows.instantOrNull(row, "expires_at"),
                    dueAt = dueAt,
                    overdue = dueAt != null && dueAt < now,
                    source = source,
                    read = Rows.instantOrNull(row, "read_at") != null,
                    snoozedUntil = Rows.instantOrNull(row, "snoozed_until"),
                    dismissible = dismissible(kind, source)
                ),
            linkObjectId = Rows.uuidOrNull(row, "link_object_id")
        )
    }

    private fun link(row: Row): LinkJson? = Rows.stringOrNull(row, "link")?.let { json.readValue(it, LinkJson::class.java) }

    companion object {
        private const val RECEIPTS = "rc"

        // no receipt reads as active: the LEFT JOIN leaves r's columns null
        private const val ACTIVE = "r.dismissed_at IS NULL AND (r.snoozed_until IS NULL OR r.snoozed_until <= :now)"

        // an ACTION of a source leaves when the work is done, not when someone hides it (spec B)
        fun dismissible(
            kind: NotificationKind,
            source: String
        ): Boolean = kind != NotificationKind.ACTION || Sources.isManual(source)
    }
}
