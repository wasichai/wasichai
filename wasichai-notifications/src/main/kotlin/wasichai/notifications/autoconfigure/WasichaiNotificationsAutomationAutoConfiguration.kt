package wasichai.notifications.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import wasichai.automation.AutomationNotifier
import wasichai.notifications.AutomationNotifierAdapter
import wasichai.notifications.NotificationPreparer
import wasichai.notifications.NotificationWriter
import java.time.Clock

// notifications behind automation's NOTIFY port (ADR-060), only when wasichai-automation is on the classpath.
// the class is named as a string, so this config is skipped before anything tries to load it (M2).
// automation looks the port up lazily, so no order against its auto-config is needed.
@AutoConfiguration(after = [WasichaiNotificationsAutoConfiguration::class])
@ConditionalOnClass(name = ["wasichai.automation.AutomationNotifier"])
@ConditionalOnBean(NotificationWriter::class)
class WasichaiNotificationsAutomationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AutomationNotifier::class)
    fun automationNotifierAdapter(
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        clock: ObjectProvider<Clock>
    ): AutomationNotifierAdapter = AutomationNotifierAdapter(preparer, writer, clock.getIfUnique { Clock.systemUTC() })
}
