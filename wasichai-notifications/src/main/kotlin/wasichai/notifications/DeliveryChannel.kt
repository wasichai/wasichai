package wasichai.notifications

import java.time.Instant
import java.util.UUID

/**
 * A way out of the app for notifications: email, a push service, a chat (ADR-060). The in-app inbox is always
 * on and is not a channel. Declare one as a bean; the module's email channel is one too.
 *
 * - **When.** News for a person (a notification created, reopened, or whose kind changed) is written, in the
 *   writer's transaction, as one `PENDING` delivery per person and channel: the people its audience reaches at
 *   that moment, minus those whose preference leaves the kind out. A worker sends it later, once per cluster,
 *   so [deliver] never runs inside a business write and a failing channel never fails one.
 * - **Failure.** [deliver] throwing is a failed attempt: retried with backoff, then `FAILED` with the error.
 * - **At least once.** A replica that dies between [deliver] and marking the row sends it again.
 * - **No record links.** [DeliveryMessage.link] never carries a `RECORD` link: the inbox drops one per reader
 *   without `READ`, and a channel cannot ask.
 */
interface DeliveryChannel {
    /** `^[a-z][a-z0-9-]{1,30}$`, unique among the app's channels, never `in-app`. The preferences route names it. */
    val name: String

    suspend fun deliver(
        message: DeliveryMessage,
        recipient: DeliveryRecipient
    )

    companion object {
        const val IN_APP = "in-app"
        val NAME = Regex("^[a-z][a-z0-9-]{1,30}$")

        // the start fails on a bad set: the preferences route and the rows are keyed by name
        fun requireValid(channels: List<DeliveryChannel>) {
            channels.forEach { channel ->
                require(NAME.matches(channel.name)) { "DeliveryChannel ${channel.javaClass.name}: name '${channel.name}' must match ${NAME.pattern}" }
                require(channel.name != IN_APP) { "DeliveryChannel ${channel.javaClass.name}: '$IN_APP' is the inbox, not a channel" }
            }
            val repeated = channels.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "delivery channels need unique names: ${repeated.joinToString()} taken twice" }
        }
    }
}

/** What a channel sends: the notification as published. */
data class DeliveryMessage(
    val notificationId: UUID,
    val organizationId: UUID,
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    // ROUTE or URL; a RECORD link is left out
    val link: LinkJson?,
    val publishAt: Instant,
    val dueAt: Instant?
)

/** Who it goes to: an enabled person of the organization. */
data class DeliveryRecipient(
    val userId: UUID,
    val email: String
)
