package wasichai.notifications

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * An app's computed states as notifications (spec C, ADR-046). Declare one as a bean; the module's
 * loop asks it every [interval], once per cluster, for every organization.
 *
 * [currentNotifications] answers **everything that should be open now** for the organization, each
 * draft with a stable [NotificationDraft.key]. What it answered last time and leaves out now is
 * resolved; what comes back is reopened; a draft whose content did not change writes nothing. A draft
 * without a key, or two with the same key, refuse the whole answer for that organization (logged).
 *
 * - It is called **outside any transaction**, so it may call remote systems, and **as the platform**
 *   (`RecordService.asPlatform(organizationId)`): `RecordService` reads and writes are scoped to that
 *   organization with no permission check. Other services that ask `CurrentUser` still refuse.
 * - [key] is the notifications' `source`: `^[a-z][a-z0-9_.:-]{1,80}$`, not `manual`, not `rule:…`, not
 *   `purge` (the loop's own), and unique among the app's sources; otherwise the app does not start.
 * - Do not also `Notifications.publish` under the same source key: the source would resolve what
 *   `publish` wrote.
 * - Aggregate: "12 permits expire this week" with a link to a list, rather than one notification per
 *   record. An inbox that floods is ignored.
 */
interface NotificationSource {
    val key: String

    val interval: Duration get() = Duration.ofMinutes(15)

    suspend fun currentNotifications(
        organizationId: UUID,
        now: Instant
    ): List<NotificationDraft>
}
