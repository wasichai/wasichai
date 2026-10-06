package wasichai.notifications

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// POST and PUT body (spec B, REST). kind and title nullable: a missing one is a 400 on that field, not a jackson error
data class NotificationRequest(
    val kind: String? = null,
    val title: String? = null,
    val body: String? = null,
    val link: LinkJson? = null,
    val audience: List<AudienceJson> = emptyList(),
    val publishAt: Instant? = null,
    val expiresAt: Instant? = null,
    val dueAt: Instant? = null
) {
    // wire -> draft. a bad kind, link or audience type is one 400 naming every field; the rest is the preparer's
    fun toDraft(): NotificationDraft {
        val violations = mutableListOf<FieldViolation>()
        val kind = NotificationKind.entries.firstOrNull { it.name == kind?.trim()?.uppercase() }
        if (kind == null) violations += FieldViolation("kind", "must be one of ${NotificationKind.entries.joinToString()}")
        val link = link?.toLink("link", violations)
        val audience = audience.mapIndexedNotNull { i, json -> json.toAudience("audience[$i]", violations) }
        if (violations.isNotEmpty()) throw ValidationException(DraftCheck.MESSAGE, violations)
        return NotificationDraft(
            kind = kind!!,
            title = title.orEmpty(),
            audience = audience,
            body = body,
            link = link,
            publishAt = publishAt,
            expiresAt = expiresAt,
            dueAt = dueAt
        )
    }
}

// the admin view (spec B, REST)
data class NotificationAdminView(
    val id: UUID,
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    val link: LinkJson?,
    val audience: List<AudienceView>,
    val publishAt: Instant,
    val expiresAt: Instant?,
    val dueAt: Instant?,
    val source: String,
    val key: String?,
    val resolvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val readCount: Long
)

// manual notifications by hand. MANAGE_ORGANIZATION, so never a service account (ADR-043).
// strict: every problem is a 400 naming its field. a notification of a source is read here, never changed.
class NotificationAdminService(
    private val currentUser: CurrentUser,
    private val preparer: NotificationPreparer,
    private val writer: NotificationWriter,
    private val repository: NotificationRepository,
    private val audience: AudienceResolver,
    private val units: OrgUnitDirectory,
    private val clock: Clock
) {
    suspend fun list(
        source: String?,
        kind: String?,
        status: String?,
        unit: String?,
        page: Int?,
        size: Int?
    ): PageResponse<NotificationAdminView> {
        val organizationId = requireAdmin()
        val violations = mutableListOf<FieldViolation>()
        val kindFilter = kind?.let { parse<NotificationKind>(it, "kind", violations) }
        val statusFilter = status?.let { parse<NotificationStatus>(it, "status", violations) }
        // a unit is addressed by code, stored by id
        val unitId =
            unit?.let { code ->
                units.idsByCode(organizationId, listOf(code)).values.firstOrNull().also {
                    if (it == null) violations += FieldViolation("unit", "unknown unit '${OrgUnitDirectory.normaliseCode(code)}'")
                }
            }
        if (violations.isNotEmpty()) throw ValidationException("Invalid filter", violations)
        val found =
            repository.adminPage(
                organizationId,
                source?.takeIf { it.isNotBlank() },
                kindFilter,
                statusFilter,
                unitId,
                clock.instant(),
                PageRequest.of(page, size)
            )
        val views = views(organizationId, found.content)
        return found.map { views.getValue(it.id) }
    }

    suspend fun get(id: UUID): NotificationAdminView {
        val organizationId = requireAdmin()
        return view(organizationId, id)
    }

    suspend fun create(request: NotificationRequest): NotificationAdminView {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val prepared = prepare(user.organizationId, request.toDraft())
        val id = writer.publish(user.organizationId, Sources.MANUAL, prepared, createdBy = user.userId).id
        return view(user.organizationId, id)
    }

    // full replace, the upsert's receipt rule: a kind change resets who read it
    suspend fun replace(
        id: UUID,
        request: NotificationRequest
    ): NotificationAdminView {
        val organizationId = requireAdmin()
        val stored = manualOrFail(organizationId, id)
        val draft = request.toDraft()
        checkStoredWindow(stored, draft)
        writer.replace(organizationId, id, prepare(organizationId, draft)) ?: throw notFound()
        return view(organizationId, id)
    }

    suspend fun delete(id: UUID) {
        val organizationId = requireAdmin()
        manualOrFail(organizationId, id)
        if (!writer.delete(organizationId, id)) throw notFound()
    }

    // strict: unknown recipients are 400s, so a null (nobody left) cannot happen; checked all the same
    private suspend fun prepare(
        organizationId: UUID,
        draft: NotificationDraft
    ): PreparedNotification =
        preparer.prepare(organizationId, Sources.MANUAL, draft, clock.instant(), strict = true, allowReservedSource = true)
            ?: throw ValidationException(DraftCheck.MESSAGE, "audience", "reaches nobody")

    // a PUT without publishAt keeps the stored one (upsert rule). when that is still ahead, the expiry is checked
    // against it here: validation only knew now, and the table's window check would answer a 500
    private fun checkStoredWindow(
        stored: StoredNotification,
        draft: NotificationDraft
    ) {
        if (draft.publishAt != null || draft.expiresAt == null) return
        val expiresAt = draft.expiresAt.truncatedTo(ChronoUnit.MICROS)
        if (stored.publishAt > clock.instant() && expiresAt <= stored.publishAt) {
            throw ValidationException(DraftCheck.MESSAGE, "expiresAt", "must be after publishAt (${stored.publishAt})")
        }
    }

    // 404 for another tenant's id; 409 for one of a source: the app resolves it, a person does not edit it
    private suspend fun manualOrFail(
        organizationId: UUID,
        id: UUID
    ): StoredNotification {
        val stored = repository.findById(organizationId, id) ?: throw notFound()
        if (!Sources.isManual(stored.source)) throw ConflictException("Notification is owned by its source")
        return stored
    }

    private suspend fun view(
        organizationId: UUID,
        id: UUID
    ): NotificationAdminView {
        val stored = repository.findById(organizationId, id) ?: throw notFound()
        return views(organizationId, listOf(stored)).getValue(id)
    }

    // a page at once: one targets read, one email and one code lookup
    private suspend fun views(
        organizationId: UUID,
        stored: List<StoredNotification>
    ): Map<UUID, NotificationAdminView> {
        val targets = repository.targetsOf(organizationId, stored.map { it.id })
        val audiences = audience.describeAll(organizationId, stored.associate { it.id to targets[it.id].orEmpty() })
        return stored.associate { it.id to it.toView(audiences.getValue(it.id)) }
    }

    private suspend fun requireAdmin(): UUID = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION).organizationId

    private fun notFound() = NotFoundException("Notification not found")

    private inline fun <reified E : Enum<E>> parse(
        value: String,
        field: String,
        violations: MutableList<FieldViolation>
    ): E? {
        val found = enumValues<E>().firstOrNull { it.name == value.trim().uppercase() }
        if (found == null) violations += FieldViolation(field, "must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}")
        return found
    }

    private fun StoredNotification.toView(audience: List<AudienceView>) =
        NotificationAdminView(
            id = id,
            kind = kind,
            title = title,
            body = body,
            link = link,
            audience = audience,
            publishAt = publishAt,
            expiresAt = expiresAt,
            dueAt = dueAt,
            source = source,
            key = key,
            resolvedAt = resolvedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            readCount = readCount
        )
}

@RestController
@RequestMapping("/api/notifications")
class NotificationAdminController(
    private val admin: NotificationAdminService
) {
    // newest first. status: open | scheduled | ended. unit: a code, those addressed to it
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) unit: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ): PageResponse<NotificationAdminView> = admin.list(source, kind, status, unit, page, size)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: NotificationRequest
    ): NotificationAdminView = admin.create(request)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): NotificationAdminView = admin.get(id)

    @PutMapping("/{id}")
    suspend fun replace(
        @PathVariable id: UUID,
        @RequestBody request: NotificationRequest
    ): NotificationAdminView = admin.replace(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = admin.delete(id)
}
