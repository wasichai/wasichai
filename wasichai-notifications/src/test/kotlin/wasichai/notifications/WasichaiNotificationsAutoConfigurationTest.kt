package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordChangeListener
import wasichai.core.metadata.FieldUsage
import wasichai.core.metadata.ObjectRemovalListener
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.autoconfigure.NotificationsProperties
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

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
    fun `storage, audience and the writer are beans an app may replace`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            listOf(
                AudienceResolver::class.java,
                NotificationPreparer::class.java,
                NotificationRepository::class.java,
                InboxRepository::class.java,
                NotificationWriter::class.java
            ).forEach { assertThat(context).hasSingleBean(it) }
        }
        val own = NotificationRepository(mock(DatabaseClient::class.java), JsonMapper.builder().build(), WasichaiSchemas("wasichai", "app_data"))
        runner.withBean(NotificationRepository::class.java, { own }).run { context ->
            assertThat(context.getBean(NotificationRepository::class.java)).isSameAs(own)
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

    // 50 + "_notifications" is 64: postgres would cut the channel. the start fails, even with no listener to build
    @Test
    fun `a metadata schema too long for the channel fails the start without a listener`() {
        runner
            .withPropertyValues("wasichai.database.metadata-schema=" + "m".repeat(50), "wasichai.notifications.listen=false")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining("notification channel")
            }
        runner.withPropertyValues("wasichai.database.metadata-schema=" + "m".repeat(49), "wasichai.notifications.listen=false").run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    @Test
    fun `switched off, no migration and no settings`() {
        runner.withPropertyValues("wasichai.notifications.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("wasichaiNotificationsMigration")
            assertThat(context).doesNotHaveBean(NotificationsProperties::class.java)
            assertThat(context).doesNotHaveBean(NotificationWriter::class.java)
            assertThat(context).doesNotHaveBean(InboxRepository::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `the loop is a bean, and two sources with one key fail the start`() {
        fun source(key: String) =
            object : NotificationSource {
                override val key = key

                override suspend fun currentNotifications(
                    organizationId: UUID,
                    now: Instant
                ): List<NotificationDraft> = emptyList()
            }
        runner.withBean("first", NotificationSource::class.java, { source("app.deadlines") }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(NotificationLoop::class.java)
        }
        runner
            .withBean("first", NotificationSource::class.java, { source("app.deadlines") })
            .withBean("second", NotificationSource::class.java, { source("app.deadlines") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context).getFailure().rootCause().hasMessageContaining("'app.deadlines' is taken by")
            }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration")
    }

    // publish and admin (task 8)
    @Test
    fun `publishing from code and the admin api are beans`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            listOf(Notifications::class.java, NotificationAdminService::class.java, NotificationAdminController::class.java)
                .forEach { assertThat(context).hasSingleBean(it) }
        }
        runner.withPropertyValues("wasichai.notifications.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(Notifications::class.java)
            assertThat(context).doesNotHaveBean(NotificationAdminController::class.java)
        }
    }

    // date rules (task 12b)
    @Test
    fun `date rules are beans, and core sees their listener and field usage`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            listOf(
                NotificationRuleRepository::class.java,
                RuleNotifications::class.java,
                NotificationRuleService::class.java,
                NotificationRuleController::class.java,
                NotificationRuleListener::class.java,
                NotificationRuleCleanup::class.java,
                NotificationRuleFieldUsage::class.java
            ).forEach { assertThat(context).hasSingleBean(it) }
            assertThat(context.getBeansOfType(RecordChangeListener::class.java).values).hasAtLeastOneElementOfType(NotificationRuleListener::class.java)
            assertThat(context.getBeansOfType(FieldUsage::class.java).values).hasAtLeastOneElementOfType(NotificationRuleFieldUsage::class.java)
            assertThat(context.getBeansOfType(ObjectRemovalListener::class.java).values).hasAtLeastOneElementOfType(NotificationRuleCleanup::class.java)
        }
        runner.withPropertyValues("wasichai.notifications.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(NotificationRuleController::class.java)
            assertThat(context).doesNotHaveBean(NotificationRuleListener::class.java)
        }
    }

    @Test
    fun `rules count days in the zone property, else the app clock's, else the system's`() {
        val tokyo = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneId.of("Asia/Tokyo"))
        runner.withBean(Clock::class.java, { tokyo }).withPropertyValues("wasichai.notifications.zone=America/Lima").run { context ->
            assertThat(context.getBean(RuleNotifications::class.java).zone).isEqualTo(ZoneId.of("America/Lima"))
        }
        runner.withBean(Clock::class.java, { tokyo }).run { context ->
            assertThat(context.getBean(RuleNotifications::class.java).zone).isEqualTo(ZoneId.of("Asia/Tokyo"))
        }
        runner.run { context ->
            assertThat(context.getBean(RuleNotifications::class.java).zone).isEqualTo(ZoneId.systemDefault())
        }
    }
}
