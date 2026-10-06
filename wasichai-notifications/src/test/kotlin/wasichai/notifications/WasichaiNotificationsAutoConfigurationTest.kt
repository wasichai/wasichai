package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.platform.ModuleMigration
import wasichai.notifications.autoconfigure.NotificationsProperties
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.time.Duration
import java.time.ZoneId

class WasichaiNotificationsAutoConfigurationTest {
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiNotificationsAutoConfiguration::class.java))

    @Test
    fun `notifications brings its migration after core's`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val migration = context.getBean("wasichaiNotificationsMigration", ModuleMigration::class.java)
            assertThat(migration.location).isEqualTo("classpath:db/wasichai/notifications")
            assertThat(migration.order).isEqualTo(ModuleMigration.MODULE_ORDER)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "notifications")
        }
    }

    @Test
    fun `settings default to the spec's table`() {
        runner.run { context ->
            assertThat(context.getBean(NotificationsProperties::class.java)).isEqualTo(
                NotificationsProperties(
                    enabled = true,
                    tick = Duration.ofSeconds(30),
                    ruleInterval = Duration.ofMinutes(15),
                    ruleMaxNotifications = 100,
                    retention = Duration.ofDays(90),
                    snoozeMax = Duration.ofDays(30),
                    listen = true,
                    streamRefresh = Duration.ofSeconds(60),
                    streamHeartbeat = Duration.ofSeconds(25),
                    streamDebounce = Duration.ofMillis(500),
                    zone = null,
                    datePattern = "dd/MM/yyyy"
                )
            )
        }
    }

    @Test
    fun `settings bind under wasichai notifications`() {
        runner
            .withPropertyValues(
                "wasichai.notifications.tick=0s",
                "wasichai.notifications.rule-max-notifications=200",
                "wasichai.notifications.snooze-max=7d",
                "wasichai.notifications.listen=false",
                "wasichai.notifications.zone=America/Lima",
                "wasichai.notifications.date-pattern=yyyy-MM-dd"
            ).run { context ->
                val properties = context.getBean(NotificationsProperties::class.java)
                assertThat(properties.tick).isZero()
                assertThat(properties.ruleMaxNotifications).isEqualTo(200)
                assertThat(properties.snoozeMax).isEqualTo(Duration.ofDays(7))
                assertThat(properties.listen).isFalse()
                assertThat(properties.zone).isEqualTo(ZoneId.of("America/Lima"))
                assertThat(properties.datePattern).isEqualTo("yyyy-MM-dd")
            }
    }

    @Test
    fun `a rule cap over 200 or under 1 fails the start`() {
        listOf("201", "0").forEach { cap ->
            runner.withPropertyValues("wasichai.notifications.rule-max-notifications=$cap").run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining("rule-max-notifications must be between 1 and 200")
            }
        }
    }

    @Test
    fun `switched off, no migration and no settings`() {
        runner.withPropertyValues("wasichai.notifications.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("wasichaiNotificationsMigration")
            assertThat(context).doesNotHaveBean(NotificationsProperties::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration")
    }
}
