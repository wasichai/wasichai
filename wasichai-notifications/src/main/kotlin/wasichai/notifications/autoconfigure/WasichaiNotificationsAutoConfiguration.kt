package wasichai.notifications.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
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
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import wasichai.core.metadata.MetadataService
import wasichai.core.organization.OrganizationRepository
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.AudienceResolver
import wasichai.notifications.InboxController
import wasichai.notifications.InboxRepository
import wasichai.notifications.InboxService
import wasichai.notifications.NotificationAdminController
import wasichai.notifications.NotificationAdminService
import wasichai.notifications.NotificationLoop
import wasichai.notifications.NotificationPreparer
import wasichai.notifications.NotificationRepository
import wasichai.notifications.NotificationSource
import wasichai.notifications.NotificationWriter
import wasichai.notifications.Notifications
import wasichai.notifications.SourceRunRepository
import java.time.Clock

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

    // JsonMapper, not the wider ObjectMapper: see core's WasichaiMetadataAutoConfiguration.customFieldRepository
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
        clock: ObjectProvider<Clock>,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): NotificationWriter =
        NotificationWriter(repository, clock.getIfUnique { Clock.systemUTC() }) { TransactionalOperator.create(transactionManager.getObject()) }

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
        clock: ObjectProvider<Clock>
    ): NotificationLoop =
        NotificationLoop(
            sources.orderedStream().toList(),
            emptyList(),
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
}
