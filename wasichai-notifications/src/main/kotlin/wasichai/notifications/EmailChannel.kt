package wasichai.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.mail.MailSender
import org.springframework.mail.SimpleMailMessage

// the module's email channel (ADR-060): plain text, the title as subject and the body as text. compiled
// against spring mail, loaded only when the app has it (WasichaiNotificationsEmailAutoConfiguration).
// a richer mail (html, a link to the app) is the app's own DeliveryChannel bean, or its own emailChannel.
class EmailChannel(
    private val sender: MailSender,
    private val from: String,
    private val subjectPrefix: String = ""
) : DeliveryChannel {
    override val name: String = NAME

    init {
        require(from.isNotBlank()) { "wasichai.notifications.email.from is required when the email channel is enabled" }
    }

    override suspend fun deliver(
        message: DeliveryMessage,
        recipient: DeliveryRecipient
    ) {
        val mail =
            SimpleMailMessage().apply {
                setFrom(this@EmailChannel.from)
                setTo(recipient.email)
                subject = subjectPrefix + message.title
                text = message.body?.takeIf { it.isNotBlank() } ?: message.title
            }
        // JavaMailSender blocks on the smtp socket: never on an event loop thread
        withContext(Dispatchers.IO) { sender.send(mail) }
    }

    companion object {
        const val NAME = "email"
    }
}
