package wasichai.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.autoconfigure.NotificationsProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What [NotificationRepository] says when a notification is news for its audience: created, reopened, or its
 * kind changed (the moments its receipts start over). Called inside the writer's transaction.
 */
fun interface NotificationNews {
    suspend fun announce(
        organizationId: UUID,
        notificationId: UUID,
        kind: NotificationKind,
        targets: List<StoredTarget>,
        publishAt: Instant,
        now: Instant
    )

    companion object {
        val NONE = NotificationNews { _, _, _, _, _, _ -> }
    }
}

enum class DeliveryStatus { PENDING, SENT, FAILED, SKIPPED }

// one due row with what its channel sends
internal data class DueDelivery(
    val id: UUID,
    val userId: UUID,
    val channel: String,
    val attempts: Int,
    val message: DeliveryMessage
)

// notification_deliveries and notification_preferences. every statement scoped to the organization, or to
// the person, whose id comes from the token or from a tenant-scoped directory.
class DeliveryRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    // one row per person and channel, minus those whose preference leaves the kind out. news again resets
    // the row, so one news is one copy, however often it is written.
    suspend fun enqueue(
        organizationId: UUID,
        notificationId: UUID,
        channel: String,
        kind: NotificationKind,
        userIds: Collection<UUID>,
        at: Instant,
        now: Instant
    ): Int {
        if (userIds.isEmpty()) return 0
        return db
            .sql(
                """
                INSERT INTO $m.notification_deliveries (notification_id, organization_id, user_id, channel, next_attempt_at, updated_at)
                SELECT n.id, n.organization_id, p.user_id, :channel, :at, :now
                FROM unnest(CAST(:users AS uuid[])) AS p (user_id)
                JOIN $m.notifications n ON n.id = :id AND n.organization_id = :org
                WHERE NOT EXISTS (
                    SELECT 1 FROM $m.notification_preferences f
                    WHERE f.user_id = p.user_id AND f.channel = :channel AND NOT (CAST(:kind AS text) = ANY(f.kinds))
                )
                ON CONFLICT (notification_id, user_id, channel) DO UPDATE
                SET status = 'PENDING', attempts = 0, last_error = NULL, sent_at = NULL,
                    next_attempt_at = EXCLUDED.next_attempt_at, updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("id", notificationId)
            .bind("channel", channel)
            .bind("kind", kind.name)
            .bind("users", userIds.distinct().toTypedArray())
            .bind("at", at.odt())
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()
    }

    // a notification resolved or expired before its turn is not sent
    suspend fun skipEnded(
        organizationId: UUID,
        now: Instant
    ): Int =
        db
            .sql(
                """
                UPDATE $m.notification_deliveries d
                SET status = 'SKIPPED', last_error = 'ended before delivery', updated_at = :now
                FROM $m.notifications n
                WHERE d.notification_id = n.id AND d.organization_id = :org AND n.organization_id = :org
                  AND d.status = 'PENDING' AND (n.resolved_at IS NOT NULL OR n.expires_at <= :now)
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()

    // oldest first, of the channels this app has: a channel taken out leaves its rows waiting, not failing
    internal suspend fun due(
        organizationId: UUID,
        channels: Collection<String>,
        now: Instant,
        limit: Int
    ): List<DueDelivery> {
        if (channels.isEmpty()) return emptyList()
        return db
            .sql(
                """
                SELECT d.id, d.user_id, d.channel, d.attempts, n.id AS notification_id, n.kind, n.title, n.body,
                       n.link::text AS link, n.publish_at, n.due_at
                FROM $m.notification_deliveries d
                JOIN $m.notifications n ON n.id = d.notification_id AND n.organization_id = :org
                WHERE d.organization_id = :org AND d.status = 'PENDING' AND d.next_attempt_at <= :now
                  AND d.channel = ANY(CAST(:channels AS text[]))
                  AND n.resolved_at IS NULL AND n.publish_at <= :now AND (n.expires_at IS NULL OR n.expires_at > :now)
                ORDER BY d.next_attempt_at, d.id
                LIMIT :limit
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("channels", channels.distinct().toTypedArray())
            .bind("now", now.odt())
            .bind("limit", limit)
            .map { row, _ ->
                DueDelivery(
                    id = Rows.uuid(row, "id"),
                    userId = Rows.uuid(row, "user_id"),
                    channel = Rows.string(row, "channel"),
                    attempts = Rows.int(row, "attempts"),
                    message =
                        DeliveryMessage(
                            notificationId = Rows.uuid(row, "notification_id"),
                            organizationId = organizationId,
                            kind = NotificationKind.valueOf(Rows.string(row, "kind")),
                            title = Rows.string(row, "title"),
                            body = Rows.stringOrNull(row, "body"),
                            // the inbox checks READ per reader before showing a RECORD link; a channel cannot
                            link = Rows.stringOrNull(row, "link")?.let { json.readValue(it, LinkJson::class.java) }?.takeIf { it.type != LINK_RECORD },
                            publishAt = Rows.instantOrNull(row, "publish_at")!!,
                            dueAt = Rows.instantOrNull(row, "due_at")
                        )
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    suspend fun markSent(
        organizationId: UUID,
        id: UUID,
        now: Instant
    ) = mark(organizationId, id, "status = 'SENT', attempts = attempts + 1, last_error = NULL, sent_at = :now", now)

    suspend fun markSkipped(
        organizationId: UUID,
        id: UUID,
        reason: String,
        now: Instant
    ) = mark(organizationId, id, "status = 'SKIPPED', last_error = :error", now, reason)

    // nextAttempt null: no try left, FAILED
    suspend fun markAttemptFailed(
        organizationId: UUID,
        id: UUID,
        error: String,
        nextAttempt: Instant?,
        now: Instant
    ) {
        if (nextAttempt == null) {
            mark(organizationId, id, "status = 'FAILED', attempts = attempts + 1, last_error = :error", now, error)
        } else {
            mark(organizationId, id, "attempts = attempts + 1, last_error = :error, next_attempt_at = :next", now, error, nextAttempt)
        }
    }

    // only a PENDING row moves: a row reset meanwhile by news again is the next run's
    private suspend fun mark(
        organizationId: UUID,
        id: UUID,
        set: String,
        now: Instant,
        error: String? = null,
        next: Instant? = null
    ) {
        var spec =
            db
                .sql("UPDATE $m.notification_deliveries SET $set, updated_at = :now WHERE organization_id = :org AND id = :id AND status = 'PENDING'")
                .bind("org", organizationId)
                .bind("id", id)
                .bind("now", now.odt())
        if (error != null) spec = spec.bind("error", error)
        if (next != null) spec = spec.bind("next", next.odt())
        spec.fetch().rowsUpdated().awaitSingle()
    }

    // channel -> kinds the person chose; a channel without a row is missing (every kind)
    suspend fun preferences(userId: UUID): Map<String, Set<NotificationKind>> =
        db
            .sql("SELECT channel, kinds FROM $m.notification_preferences WHERE user_id = :userId")
            .bind("userId", userId)
            .map { row, _ ->
                Rows.string(row, "channel") to
                    row
                        .get("kinds", Array<String>::class.java)!!
                        .mapNotNull { kind -> NotificationKind.entries.firstOrNull { it.name == kind } }
                        .toSet()
            }.all()
            .collectList()
            .awaitSingle()
            .toMap()

    suspend fun savePreference(
        userId: UUID,
        channel: String,
        kinds: Set<NotificationKind>
    ) {
        db
            .sql(
                "INSERT INTO $m.notification_preferences (user_id, channel, kinds) VALUES (:userId, :channel, CAST(:kinds AS text[])) " +
                    "ON CONFLICT (user_id, channel) DO UPDATE SET kinds = EXCLUDED.kinds, updated_at = now()"
            ).bind("userId", userId)
            .bind("channel", channel)
            .bind(
                "kinds",
                NotificationKind.entries
                    .filter { it in kinds }
                    .map { it.name }
                    .toTypedArray()
            ).fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}

/** What one delivery run did in one organization. */
data class DeliveryRun(
    val sent: Int,
    val retried: Int,
    val failed: Int,
    val skipped: Int
)

/**
 * Delivery channels (ADR-060): fans news out to people when it is written, and sends what is due later.
 * With no [DeliveryChannel] in the app it does nothing, and writes nothing.
 */
class Deliveries(
    channels: List<DeliveryChannel>,
    private val repository: DeliveryRepository,
    private val users: UserDirectory,
    private val roles: RoleDirectory,
    private val units: OrgUnitDirectory,
    private val maxAttempts: Int,
    private val backoff: Duration,
    private val batch: Int
) : NotificationNews {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byName: Map<String, DeliveryChannel>

    init {
        DeliveryChannel.requireValid(channels)
        byName = channels.associateBy { it.name }
    }

    constructor(
        channels: List<DeliveryChannel>,
        repository: DeliveryRepository,
        users: UserDirectory,
        roles: RoleDirectory,
        units: OrgUnitDirectory,
        properties: NotificationsProperties
    ) : this(channels, repository, users, roles, units, properties.deliveryMaxAttempts, properties.deliveryBackoff, properties.deliveryBatch)

    /** The app's channel names, in no particular order. */
    val channels: Set<String> get() = byName.keys

    // in the writer's transaction. a scheduled notification waits for its publication
    override suspend fun announce(
        organizationId: UUID,
        notificationId: UUID,
        kind: NotificationKind,
        targets: List<StoredTarget>,
        publishAt: Instant,
        now: Instant
    ) {
        if (byName.isEmpty()) return
        val people = people(organizationId, targets)
        if (people.isEmpty()) return
        val at = maxOf(publishAt, now)
        byName.keys.forEach { channel -> repository.enqueue(organizationId, notificationId, channel, kind, people, at, now) }
    }

    /**
     * Sends what is due in one organization, at most `delivery-batch` rows: the loop's work, once per cluster.
     * One channel failing is that row's failed attempt; nothing here throws for it.
     */
    suspend fun deliverDue(
        organizationId: UUID,
        now: Instant
    ): DeliveryRun {
        if (byName.isEmpty()) return DeliveryRun(0, 0, 0, 0)
        var skipped = repository.skipEnded(organizationId, now)
        val due = repository.due(organizationId, byName.keys, now, batch)
        if (due.isEmpty()) return DeliveryRun(0, 0, 0, skipped)
        // a person disabled since the news is nobody's recipient any more
        val enabled = users.existing(organizationId, due.map { it.userId })
        val emails = users.emailsById(organizationId, enabled)
        var sent = 0
        var retried = 0
        var failed = 0
        due.forEach { delivery ->
            val email = emails[delivery.userId]
            if (email == null) {
                repository.markSkipped(organizationId, delivery.id, "recipient is not an enabled person", now)
                skipped++
                return@forEach
            }
            val error = attempt(byName.getValue(delivery.channel), delivery.message, DeliveryRecipient(delivery.userId, email))
            if (error == null) {
                repository.markSent(organizationId, delivery.id, now)
                sent++
                return@forEach
            }
            val tries = delivery.attempts + 1
            val next = if (tries >= maxAttempts) null else now.plus(backoff.multipliedBy(1L shl (tries - 1).coerceAtMost(MAX_SHIFT)))
            repository.markAttemptFailed(organizationId, delivery.id, error, next, now)
            if (next == null) {
                failed++
                log.warn(
                    "Notification {} to {} by {} failed after {} tries: {}",
                    delivery.message.notificationId,
                    delivery.userId,
                    delivery.channel,
                    tries,
                    error
                )
            } else {
                retried++
                log.debug(
                    "Notification {} to {} by {} failed, retry at {}: {}",
                    delivery.message.notificationId,
                    delivery.userId,
                    delivery.channel,
                    next,
                    error
                )
            }
        }
        return DeliveryRun(sent, retried, failed, skipped)
    }

    // null: delivered. else why not, short enough to store
    private suspend fun attempt(
        channel: DeliveryChannel,
        message: DeliveryMessage,
        recipient: DeliveryRecipient
    ): String? =
        try {
            channel.deliver(message, recipient)
            null
        } catch (e: CancellationException) {
            // only stopping the loop cancels it; a channel's own timeout is a failed attempt
            currentCoroutineContext().ensureActive()
            describe(e)
        } catch (e: Exception) {
            describe(e)
        }

    private fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message}".take(MAX_ERROR)

    // the audience matched on read, said with names: everyone, these people, a role's holders, a unit's subtree
    private suspend fun people(
        organizationId: UUID,
        targets: List<StoredTarget>
    ): Set<UUID> {
        if (targets.any { it.type == TargetType.ALL }) return users.enabledIds(organizationId)
        return users.existing(organizationId, targets.mapNotNull { it.userId }) +
            roles.holderIds(organizationId, targets.mapNotNull { it.roleName }) +
            units.memberIdsWithin(organizationId, targets.mapNotNull { it.unitId })
    }

    private companion object {
        const val MAX_ERROR = 1000

        // 2^20 backoffs is beyond any sane max-attempts; the shift never overflows
        const val MAX_SHIFT = 20
    }
}

// the loop's work: each organization's due deliveries, once per cluster every delivery-interval
internal class DeliveryWork(
    private val deliveries: Deliveries,
    override val interval: Duration
) : LoopWork {
    override val key: String = Sources.DELIVERIES

    override suspend fun run(
        organizationId: UUID,
        now: Instant
    ) {
        val run = deliveries.deliverDue(organizationId, now)
        if (run != DeliveryRun(0, 0, 0, 0)) log.debug("deliveries organization {}: {}", organizationId, run)
    }

    override fun toString(): String = "notification deliveries"

    private companion object {
        private val log = LoggerFactory.getLogger(DeliveryWork::class.java)
    }
}
