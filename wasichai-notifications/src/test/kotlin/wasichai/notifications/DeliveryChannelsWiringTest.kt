package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.mail.MailSender
import org.springframework.mail.SimpleMailMessage
import wasichai.automation.AutomationNotifier
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutomationAutoConfiguration
import wasichai.notifications.autoconfigure.WasichaiNotificationsEmailAutoConfiguration
import wasichai.test.WasichaiContextRunner

// ADR-060 wiring without a database: channels, the email switch, preferences and automation's port
class DeliveryChannelsWiringTest {
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(
                AutoConfigurations.of(
                    WasichaiNotificationsAutoConfiguration::class.java,
                    WasichaiNotificationsEmailAutoConfiguration::class.java,
                    WasichaiNotificationsAutomationAutoConfiguration::class.java
                )
            )

    private class NoMail : MailSender {
        override fun send(vararg simpleMessages: SimpleMailMessage) = Unit
    }

    private fun channel(name: String) =
        object : DeliveryChannel {
            override val name = name

            override suspend fun deliver(
                message: DeliveryMessage,
                recipient: DeliveryRecipient
            ) = Unit
        }

    @Test
    fun `without a channel nothing is delivered, and preferences are a bean with none`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(Deliveries::class.java).channels).isEmpty()
            assertThat(context).hasSingleBean(NotificationPreferencesController::class.java)
            assertThat(context).doesNotHaveBean("emailChannel")
        }
    }

    @Test
    fun `email is off unless enabled, and needs a mail sender`() {
        // a sender alone: off
        runner.withBean(MailSender::class.java, { NoMail() }).run { context ->
            assertThat(context.getBean(Deliveries::class.java).channels).isEmpty()
        }
        // enabled without a sender: nothing to send with
        runner.withPropertyValues("wasichai.notifications.email.enabled=true", "wasichai.notifications.email.from=a@x.test").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(Deliveries::class.java).channels).isEmpty()
        }
        runner
            .withBean(MailSender::class.java, { NoMail() })
            .withPropertyValues("wasichai.notifications.email.enabled=true", "wasichai.notifications.email.from=a@x.test")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean("emailChannel")).isInstanceOf(EmailChannel::class.java)
                assertThat(context.getBean(Deliveries::class.java).channels).containsExactly("email")
            }
    }

    @Test
    fun `email enabled without a sender address fails the start`() {
        runner
            .withBean(MailSender::class.java, { NoMail() })
            .withPropertyValues("wasichai.notifications.email.enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining("wasichai.notifications.email.from is required")
            }
    }

    @Test
    fun `an app's channel is picked up, and a bad name or a repeated one fails the start`() {
        runner.withBean("push", DeliveryChannel::class.java, { channel("push") }).run { context ->
            assertThat(context.getBean(Deliveries::class.java).channels).containsExactly("push")
        }
        listOf("in-app", "Push", "x").forEach { name ->
            runner.withBean("bad", DeliveryChannel::class.java, { channel(name) }).run { context ->
                assertThat(context).hasFailed()
            }
        }
        runner
            .withBean("one", DeliveryChannel::class.java, { channel("push") })
            .withBean("two", DeliveryChannel::class.java, { channel("push") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining("unique names")
            }
    }

    @Test
    fun `delivery settings out of range fail the start`() {
        listOf(
            "wasichai.notifications.delivery-max-attempts=0" to "delivery-max-attempts",
            "wasichai.notifications.delivery-max-attempts=21" to "delivery-max-attempts",
            "wasichai.notifications.delivery-batch=0" to "delivery-batch",
            "wasichai.notifications.delivery-interval=0s" to "delivery-interval",
            "wasichai.notifications.delivery-backoff=0s" to "delivery-backoff"
        ).forEach { (property, message) ->
            runner.withPropertyValues(property).run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining(message)
            }
        }
    }

    @Test
    fun `automation's notify port is served when automation is on the classpath`() {
        runner.run { context ->
            assertThat(context).hasSingleBean(AutomationNotifier::class.java)
            assertThat(context.getBean(AutomationNotifier::class.java)).isInstanceOf(AutomationNotifierAdapter::class.java)
        }
        // the app's own wins
        val own = mock(AutomationNotifier::class.java)
        runner.withBean(AutomationNotifier::class.java, { own }).run { context ->
            assertThat(context.getBean(AutomationNotifier::class.java)).isSameAs(own)
        }
    }

    @Test
    fun `switched off, no channel, no preferences and no notify port`() {
        runner
            .withBean(MailSender::class.java, { NoMail() })
            .withPropertyValues(
                "wasichai.notifications.enabled=false",
                "wasichai.notifications.email.enabled=true",
                "wasichai.notifications.email.from=a@x.test"
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Deliveries::class.java)
                assertThat(context).doesNotHaveBean(NotificationPreferencesController::class.java)
                assertThat(context).doesNotHaveBean("emailChannel")
                assertThat(context).doesNotHaveBean(AutomationNotifier::class.java)
            }
    }

    @Test
    fun `channel names are checked`() {
        assertThatThrownBy { DeliveryChannel.requireValid(listOf(channel("in-app"))) }.hasMessageContaining("is the inbox")
        assertThatThrownBy { DeliveryChannel.requireValid(listOf(channel("SMS"))) }.hasMessageContaining("must match")
        DeliveryChannel.requireValid(listOf(channel("email"), channel("push-fcm")))
    }
}
