package wasichai.notifications

import org.slf4j.LoggerFactory
import wasichai.automation.AutomationNotifier
import wasichai.automation.NotifyRequest
import java.time.Clock
import java.util.UUID

// the notifications side of automation's NOTIFY port (ADR-060). compiled against wasichai-automation, loaded
// only when an app has it (WasichaiNotificationsAutomationAutoConfiguration).
// source automation:<name>, keyed <record id>.<action index>: entering the state again is news again (the
// same notification reopened or updated, never a second one), and an unchanged one writes nothing.
class AutomationNotifierAdapter(
    private val preparer: NotificationPreparer,
    private val writer: NotificationWriter,
    private val clock: Clock
) : AutomationNotifier {
    override val available: Boolean = true

    override suspend fun notify(request: NotifyRequest): String {
        val audience = audience(request.organizationId, request.to)
        if (audience.isEmpty()) return "notified nobody: no recipient in '${request.to}'"
        val kind = requireNotNull(NotificationKind.entries.firstOrNull { it.name == request.kind }) { "unknown notification kind '${request.kind}'" }
        val draft =
            NotificationDraft(
                kind = kind,
                title = request.title,
                audience = audience,
                body = request.body?.ifBlank { null },
                link = request.recordId?.let { NotificationLink.Record(request.objectName, it) },
                key = request.recordId?.let { "$it.${request.actionIndex}" }
            )
        val source = Sources.automation(request.automation)
        // lenient, as Notifications.publish: an unknown person is dropped, a long title cut
        val prepared =
            preparer.prepare(request.organizationId, source, draft, clock.instant(), strict = false, allowReservedSource = true)
                ?: return "notified nobody: no recipient left of '${request.to}'"
        val result = writer.publish(request.organizationId, source, prepared, createdBy = null)
        return "notification ${result.id} ${result.outcome.name.lowercase()} for ${request.to}"
    }

    // "{{owner}}, role:SUPERVISOR" rendered: a user id, an email, role:<NAME>, unit:<CODE>. an entry that is
    // none of these (an empty field, a typo) is dropped with a WARN, like an unknown person
    private fun audience(
        organizationId: UUID,
        to: String
    ): List<Audience> =
        to.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { entry ->
            val audience =
                when {
                    entry.startsWith(ROLE, ignoreCase = true) ->
                        entry
                            .drop(ROLE.length)
                            .trim()
                            .ifEmpty { null }
                            ?.let { Audience.Role(it) }
                    entry.startsWith(UNIT, ignoreCase = true) ->
                        entry
                            .drop(UNIT.length)
                            .trim()
                            .ifEmpty { null }
                            ?.let { Audience.Unit(it) }
                    '@' in entry -> Audience.Email(entry)
                    else -> runCatching { UUID.fromString(entry.removePrefix(USER)) }.getOrNull()?.let { Audience.User(it) }
                }
            if (audience == null) log.warn("organization {}: NOTIFY recipient '{}' is no user id, email, role: or unit:, dropped", organizationId, entry)
            audience
        }

    private companion object {
        private val log = LoggerFactory.getLogger(AutomationNotifierAdapter::class.java)
        const val ROLE = "role:"
        const val UNIT = "unit:"
        const val USER = "user:"
    }
}
