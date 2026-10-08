package wasichai.automation

import wasichai.core.common.ValidationException
import java.util.UUID

// what a NOTIFY action needs from a notifications module, and nothing more (ADR-060). wasichai-notifications
// implements it when both are installed; automation never reaches into its tables or its beans -- the same
// shape as DocumentIssuer.
interface AutomationNotifier {
    // false: NOTIFY is refused when an automation is saved
    val available: Boolean

    // to, title and body arrive rendered. to is a comma-separated list: a user id, an email, role:<NAME>
    // or unit:<CODE>. answers what the run step prints.
    suspend fun notify(request: NotifyRequest): String
}

data class NotifyRequest(
    val organizationId: UUID,
    val automation: String,
    val objectName: String,
    val recordId: UUID?,
    // the action's place in the automation: one notification per record and action, news again on re-entry
    val actionIndex: Int,
    val to: String,
    val kind: String,
    val title: String,
    val body: String?
)

// no notifications module: NOTIFY is refused when saved, and a stored one fails its run instead of the boot
class NoAutomationNotifier : AutomationNotifier {
    override val available: Boolean = false

    override suspend fun notify(request: NotifyRequest): String =
        throw ValidationException(NOT_INSTALLED, "actions", "the notifications module is not installed")

    companion object {
        const val NOT_INSTALLED = "NOTIFY needs the notifications module"
    }
}
