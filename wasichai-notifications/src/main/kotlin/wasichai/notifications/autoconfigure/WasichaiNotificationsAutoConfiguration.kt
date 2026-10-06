package wasichai.notifications.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.platform.ModuleMigration

// notifications (ADR-046). tables reference core's org_units: core migrates at 0, modules after.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.notifications", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NotificationsProperties::class)
class WasichaiNotificationsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiNotificationsMigration(): ModuleMigration =
        ModuleMigration("notifications", "classpath:db/wasichai/notifications", ModuleMigration.MODULE_ORDER)
}
