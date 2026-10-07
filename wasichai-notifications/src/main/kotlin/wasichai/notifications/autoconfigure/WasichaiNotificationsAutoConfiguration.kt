package wasichai.notifications.autoconfigure

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordService
import wasichai.core.data.RecordStore
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import wasichai.core.metadata.MetadataService
import wasichai.core.organization.OrganizationRepository
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.Connections
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.AudienceResolver
import wasichai.notifications.InboxController
import wasichai.notifications.InboxRepository
import wasichai.notifications.InboxService
import wasichai.notifications.NotificationAdminController
import wasichai.notifications.NotificationAdminService
import wasichai.notifications.NotificationChannel
import wasichai.notifications.NotificationListener
import wasichai.notifications.NotificationLoop
import wasichai.notifications.NotificationPreparer
import wasichai.notifications.NotificationRepository
import wasichai.notifications.NotificationRuleCleanup
import wasichai.notifications.NotificationRuleController
import wasichai.notifications.NotificationRuleFieldUsage
import wasichai.notifications.NotificationRuleListener
import wasichai.notifications.NotificationRuleRepository
import wasichai.notifications.NotificationRuleService
import wasichai.notifications.NotificationSignals
import wasichai.notifications.NotificationSource
import wasichai.notifications.NotificationStreamController
import wasichai.notifications.NotificationWriter
import wasichai.notifications.Notifications
import wasichai.notifications.RuleNotifications
import wasichai.notifications.RulesWork
import wasichai.notifications.SourceRunRepository
import java.time.Clock
import java.time.ZoneId

// notifications (ADR-046). tables reference core's org_units: core migrates at 0, modules after.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.notifications", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NotificationsProperties::class)
class WasichaiNotificationsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiNotificationsMigration(): ModuleMigration =
        ModuleMigration("notifications", "classpath:db/wasichai/notifications", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun audienceResolver(
        users: UserDirectory,
        roles: RoleDirectory,
        units: OrgUnitDirectory
    ): AudienceResolver = AudienceResolver(users, roles, units)

    @Bean
    @ConditionalOnMissingBean
    fun notificationPreparer(
        metadata: MetadataService,
        audience: AudienceResolver
    ): NotificationPreparer = NotificationPreparer(metadata, audience)

    // JsonMapper, not the wider ObjectMapper: see core's WasichaiMetadataAutoConfiguration.customFieldRepository.
    // both repositories check the channel's length when built: an over-long metadata schema fails the start
    @Bean
    @ConditionalOnMissingBean
    fun notificationRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): NotificationRepository = NotificationRepository(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun inboxRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): InboxRepository = InboxRepository(db, json, schemas)

    // the app's Clock when it has exactly one (tests fix time), else the system's.
    // the transaction manager is looked up on first write, as core's ClusterLock does.
    @Bean
    @ConditionalOnMissingBean
    fun notificationWriter(
        repository: NotificationRepository,
        clusterLock: ClusterLock,
        clock: ObjectProvider<Clock>,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): NotificationWriter =
        NotificationWriter(repository, clusterLock, clock.getIfUnique { Clock.systemUTC() }) {
            TransactionalOperator.create(transactionManager.getObject())
        }

    // my notifications (task 9)
    @Bean
    @ConditionalOnMissingBean
    fun inboxService(
        inbox: InboxRepository,
        currentUser: CurrentUser,
        units: OrgUnitDirectory,
        clock: ObjectProvider<Clock>,
        properties: NotificationsProperties
    ): InboxService = InboxService(inbox, currentUser, units, clock.getIfUnique { Clock.systemUTC() }, properties.snoozeMax)

    @Bean
    @ConditionalOnMissingBean
    fun inboxController(
        service: InboxService,
        currentUser: CurrentUser
    ): InboxController = InboxController(service, currentUser)

    // publish and admin (task 8)
    @Bean
    @ConditionalOnMissingBean
    fun notifications(
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        clock: ObjectProvider<Clock>
    ): Notifications = Notifications(preparer, writer, clock.getIfUnique { Clock.systemUTC() })

    @Bean
    @ConditionalOnMissingBean
    fun notificationAdminService(
        currentUser: CurrentUser,
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        repository: NotificationRepository,
        audience: AudienceResolver,
        units: OrgUnitDirectory,
        clock: ObjectProvider<Clock>
    ): NotificationAdminService = NotificationAdminService(currentUser, preparer, writer, repository, audience, units, clock.getIfUnique { Clock.systemUTC() })

    @Bean
    @ConditionalOnMissingBean
    fun notificationAdminController(admin: NotificationAdminService): NotificationAdminController = NotificationAdminController(admin)

    // scheduled sources (task 10). the loop starts after every singleton (SmartLifecycle), migrations included.
    // the run table is the loop's alone: no bean of its own.
    @Bean
    @ConditionalOnMissingBean
    fun notificationLoop(
        sources: ObjectProvider<NotificationSource>,
        clusterLock: ClusterLock,
        records: RecordService,
        organizations: OrganizationRepository,
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        repository: NotificationRepository,
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        properties: NotificationsProperties,
        clock: ObjectProvider<Clock>,
        rules: NotificationRuleRepository,
        metadata: MetadataService,
        ruleNotifications: RuleNotifications
    ): NotificationLoop =
        NotificationLoop(
            sources.orderedStream().toList(),
            listOf(RulesWork(rules, metadata, ruleNotifications, properties.ruleInterval)),
            clusterLock,
            records,
            organizations,
            preparer,
            writer,
            repository,
            SourceRunRepository(db, schemas),
            properties,
            clock.getIfUnique { Clock.systemUTC() }
        )

    // live stream (task 11)
    @Bean
    @ConditionalOnMissingBean
    fun notificationSignals(): NotificationSignals = NotificationSignals()

    // the driver is the app's (compileOnly here): without it there is nothing to LISTEN on
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = ["io.r2dbc.postgresql.api.PostgresqlConnection"])
    @ConditionalOnProperty(prefix = "wasichai.notifications", name = ["listen"], havingValue = "true", matchIfMissing = true)
    fun notificationListener(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        signals: NotificationSignals
    ): NotificationListener = NotificationListener({ Connections.unpooled(db.connectionFactory) }, NotificationChannel.name(schemas), signals)

    @Bean
    @ConditionalOnMissingBean
    fun notificationStreamController(
        currentUser: CurrentUser,
        units: OrgUnitDirectory,
        inbox: InboxService,
        signals: NotificationSignals,
        properties: NotificationsProperties,
        clock: ObjectProvider<Clock>
    ): NotificationStreamController = NotificationStreamController(currentUser, units, inbox, signals, properties, clock.getIfUnique { Clock.systemUTC() })

    // date rules (task 12b)
    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): NotificationRuleRepository = NotificationRuleRepository(db, json, schemas)

    // the zone is settled here, once: the property, else the app's unique Clock's, else the system's (WARN)
    @Bean
    @ConditionalOnMissingBean
    fun ruleNotifications(
        store: RecordStore,
        preparer: NotificationPreparer,
        writer: NotificationWriter,
        properties: NotificationsProperties,
        clock: ObjectProvider<Clock>
    ): RuleNotifications =
        RuleNotifications(store, preparer, writer, ruleZone(properties, clock.getIfUnique()), properties.datePattern, properties.ruleMaxNotifications)

    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleService(
        currentUser: CurrentUser,
        metadata: MetadataService,
        rules: NotificationRuleRepository,
        audience: AudienceResolver,
        ruleNotifications: RuleNotifications,
        writer: NotificationWriter,
        clock: ObjectProvider<Clock>,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): NotificationRuleService =
        NotificationRuleService(currentUser, metadata, rules, audience, ruleNotifications, writer, clock.getIfUnique { Clock.systemUTC() }) {
            TransactionalOperator.create(transactionManager.getObject())
        }

    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleController(service: NotificationRuleService): NotificationRuleController = NotificationRuleController(service)

    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleListener(
        rules: NotificationRuleRepository,
        metadata: MetadataService,
        ruleNotifications: RuleNotifications,
        writer: NotificationWriter,
        clock: ObjectProvider<Clock>
    ): NotificationRuleListener = NotificationRuleListener(rules, metadata, ruleNotifications, writer, clock.getIfUnique { Clock.systemUTC() })

    // no MetadataService here: it collects its removal listeners when it is built
    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleCleanup(
        rules: NotificationRuleRepository,
        writer: NotificationWriter
    ): NotificationRuleCleanup = NotificationRuleCleanup(rules, writer)

    @Bean
    @ConditionalOnMissingBean
    fun notificationRuleFieldUsage(rules: NotificationRuleRepository): NotificationRuleFieldUsage = NotificationRuleFieldUsage(rules)

    private companion object {
        private val log = LoggerFactory.getLogger(WasichaiNotificationsAutoConfiguration::class.java)

        // a day starts at midnight somewhere: say where, or the server's zone decides when a licence "vence"
        fun ruleZone(
            properties: NotificationsProperties,
            clock: Clock?
        ): ZoneId {
            properties.zone?.let { return it }
            clock?.let { return it.zone }
            val zone = ZoneId.systemDefault()
            log.warn("Notification date rules count days in the system zone {}: set wasichai.notifications.zone", zone)
            return zone
        }
    }
}
