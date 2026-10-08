package wasichai.notifications.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.mail.MailSender
import wasichai.notifications.DeliveryChannel
import wasichai.notifications.EmailChannel

// the email channel (ADR-060): off unless wasichai.notifications.email.enabled=true, and only when the app has
// spring mail and a MailSender (spring-boot-starter-mail, spring.mail.host). classes named as strings, so the
// config is skipped before anything tries to load them (M2). after boot's mail config, which makes the sender.
@AutoConfiguration(
    after = [WasichaiNotificationsAutoConfiguration::class],
    afterName = ["org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"]
)
@ConditionalOnClass(name = ["org.springframework.mail.MailSender"])
@ConditionalOnProperty(prefix = "wasichai.notifications", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "wasichai.notifications.email", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(type = ["org.springframework.mail.MailSender"])
class WasichaiNotificationsEmailAutoConfiguration {
    // by name: an app replaces the module's email with its own bean called emailChannel
    @Bean
    @ConditionalOnMissingBean(name = ["emailChannel"])
    fun emailChannel(
        sender: MailSender,
        properties: NotificationsProperties
    ): DeliveryChannel = EmailChannel(sender, properties.email.from.orEmpty(), properties.email.subjectPrefix)
}
