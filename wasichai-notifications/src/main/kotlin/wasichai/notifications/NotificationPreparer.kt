package wasichai.notifications

import org.slf4j.LoggerFactory
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID

// draft -> PreparedNotification: format, the link's object, the audience. every producer goes through here.
// strict (REST): every problem is one 400 naming its field.
// lenient (Kotlin): a long title or body is cut; a bad format or an unknown object is an IllegalArgumentException
// (a bug in the app); unknown recipients are dropped, and null means nobody is left: nothing to write.
class NotificationPreparer(
    private val metadata: MetadataService,
    private val audience: AudienceResolver
) {
    suspend fun prepare(
        organizationId: UUID,
        source: String,
        draft: NotificationDraft,
        now: Instant,
        strict: Boolean,
        allowReservedSource: Boolean = false
    ): PreparedNotification? {
        val check = NotificationValidation.check(source, draft, now, allowReservedSource, cutLongText = !strict)
        val normalized = if (strict) check.orThrow() else check.orThrowIllegalArgument()

        val violations = mutableListOf<FieldViolation>()
        val linkObjectId =
            (normalized.link as? NotificationLink.Record)?.let { link ->
                val id = objectId(organizationId, link.objectName)
                if (id == null) {
                    val reason = "unknown object '${link.objectName}'"
                    require(strict) { "${DraftCheck.MESSAGE}: link.object $reason" }
                    violations += FieldViolation("link.object", reason)
                }
                id
            }

        val resolved = audience.resolve(organizationId, normalized.audience, strict)
        violations += resolved.violations
        if (violations.isNotEmpty()) throw ValidationException(DraftCheck.MESSAGE, violations)
        if (resolved.targets.isEmpty()) {
            log.warn("organization {}: notification '{}' of {} reaches nobody, not written", organizationId, normalized.title, source)
            return null
        }
        return normalized.prepare(resolved.targets, linkObjectId)
    }

    private suspend fun objectId(
        organizationId: UUID,
        name: String
    ): UUID? =
        try {
            metadata.loadDefinition(organizationId, name).obj.id
        } catch (_: NotFoundException) {
            null
        }

    private companion object {
        private val log = LoggerFactory.getLogger(NotificationPreparer::class.java)
    }
}
