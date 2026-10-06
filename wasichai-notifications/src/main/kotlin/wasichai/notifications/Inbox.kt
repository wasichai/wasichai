package wasichai.notifications

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleQueries
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class SnoozeRequest(
    val until: Instant? = null
)

data class ReadAllRequest(
    val kind: String? = null
)

// spec B, "My notifications": what a person sees and does with it.
// a notification not visible to the caller (other tenant, not addressed, out of its window, resolved) is a 404.
class InboxService(
    private val inbox: InboxRepository,
    private val currentUser: CurrentUser,
    private val units: OrgUnitDirectory,
    private val clock: Clock,
    private val snoozeMax: Duration
) {
    suspend fun page(
        user: AuthenticatedUser,
        kind: String?,
        state: String?,
        page: Int?,
        size: Int?
    ): PageResponse<InboxItem> {
        val reader = reader(user)
        val rows = inbox.page(reader, parseKind(kind), parseState(state), now(), PageRequest.of(page, size))
        val permitted = permittedFor(user, rows.content.map { it.item.link to it.linkObjectId })
        return rows.map { it.item.copy(link = keep(it.item.link, it.linkObjectId, permitted)) }
    }

    suspend fun summary(user: AuthenticatedUser): NotificationSummary {
        val summary = inbox.summary(reader(user), now())
        val latest = summary.latest ?: return summary
        val permitted = permittedFor(user, listOf(latest.link to latest.linkObjectId))
        return summary.copy(latest = latest.copy(link = keep(latest.link, latest.linkObjectId, permitted)))
    }

    suspend fun read(
        user: AuthenticatedUser,
        id: UUID
    ) {
        if (!inbox.markRead(reader(user), id, now())) throw notFound(id)
    }

    suspend fun dismiss(
        user: AuthenticatedUser,
        id: UUID
    ) {
        val reader = reader(user)
        val now = now()
        val row = inbox.visible(reader, id, now) ?: throw notFound(id)
        if (!InboxRepository.dismissible(row.kind, row.source)) throw ConflictException("This notification leaves when its work is done")
        if (!inbox.dismiss(reader, id, now)) throw notFound(id)
    }

    suspend fun snooze(
        user: AuthenticatedUser,
        id: UUID,
        until: Instant?
    ) {
        val reader = reader(user)
        val now = now()
        // postgres keeps microseconds
        val at = until?.truncatedTo(ChronoUnit.MICROS) ?: throw ValidationException("Invalid snooze", "until", "is required")
        if (at <= now) throw ValidationException("Invalid snooze", "until", "must be in the future")
        if (at > now.plus(snoozeMax)) throw ValidationException("Invalid snooze", "until", "must be at most $snoozeMax from now")
        if (!inbox.snooze(reader, id, at, now)) throw notFound(id)
    }

    suspend fun readAll(
        user: AuthenticatedUser,
        kind: String?
    ) {
        inbox.readAll(reader(user), parseKind(kind), now())
    }

    // a service account is not a person: nobody addresses it, so it has no inbox
    private suspend fun reader(user: AuthenticatedUser): InboxReader {
        if (user.serviceAccount != null) throw ForbiddenException("A service account has no notifications")
        return InboxReader(user.organizationId, user.userId, user.roles, units.closureOf(user.organizationId, user.userId))
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun notFound(id: UUID) = NotFoundException("Notification $id not found")

    // once per request, and only when a RECORD link needs it
    private suspend fun permittedFor(
        user: AuthenticatedUser,
        links: List<Pair<LinkJson?, UUID?>>
    ): RoleQueries.PermittedObjects? =
        if (links.any { (link, objectId) -> link?.type == LINK_RECORD && objectId != null }) currentUser.permittedObjects(user, Actions.READ) else null

    // RECORD: kept while its object exists and the reader may READ it. ROUTE and URL: the UI guards them.
    private fun keep(
        link: LinkJson?,
        objectId: UUID?,
        permitted: RoleQueries.PermittedObjects?
    ): LinkJson? =
        when {
            link == null || link.type != LINK_RECORD -> link
            objectId == null || permitted == null -> null
            else -> link.takeIf { permitted.allows(objectId) }
        }

    private companion object {
        fun parseKind(kind: String?): NotificationKind? {
            val value = kind?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return NotificationKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw ValidationException("Invalid filter", "kind", "must be one of ${NotificationKind.entries.joinToString(", ")}")
        }

        fun parseState(state: String?): InboxState {
            val value = state?.trim()?.takeIf { it.isNotEmpty() } ?: return InboxState.ACTIVE
            return InboxState.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw ValidationException("Invalid filter", "state", "must be one of ${InboxState.entries.joinToString(", ") { it.name.lowercase() }}")
        }
    }
}

@RestController
@RequestMapping("/api/auth/me/notifications")
class InboxController(
    private val service: InboxService,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): PageResponse<InboxItem> = service.page(currentUser.require(), kind, state, page, size)

    @GetMapping("/summary")
    suspend fun summary(): NotificationSummary = service.summary(currentUser.require())

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun read(
        @PathVariable id: UUID
    ) = service.read(currentUser.require(), id)

    @PostMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun dismiss(
        @PathVariable id: UUID
    ) = service.dismiss(currentUser.require(), id)

    @PostMapping("/{id}/snooze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun snooze(
        @PathVariable id: UUID,
        @RequestBody body: SnoozeRequest
    ) = service.snooze(currentUser.require(), id, body.until)

    // the body is optional: no body marks every kind
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun readAll(
        @RequestBody(required = false) body: ReadAllRequest?
    ) = service.readAll(currentUser.require(), body?.kind)
}
