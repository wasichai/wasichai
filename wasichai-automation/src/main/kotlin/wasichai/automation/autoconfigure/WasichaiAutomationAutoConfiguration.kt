package wasichai.automation.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.automation.AutomationController
import wasichai.automation.AutomationDispatcher
import wasichai.automation.AutomationDrain
import wasichai.automation.AutomationFieldUsage
import wasichai.automation.AutomationNotifier
import wasichai.automation.AutomationProperties
import wasichai.automation.AutomationRepository
import wasichai.automation.AutomationRunRepository
import wasichai.automation.AutomationRunner
import wasichai.automation.AutomationService
import wasichai.automation.DocumentIssuer
import wasichai.automation.NoAutomationNotifier
import wasichai.automation.NoDocumentIssuer
import wasichai.automation.WebhookSender
import wasichai.core.audit.AuditService
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas

// automations. the document and notify ports are looked up, not required: with no documents or notifications
// module the null one answers, and no auto-config order has to be right for that (M2).
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.automation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AutomationProperties::class)
class WasichaiAutomationAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiAutomationMigration(): ModuleMigration = ModuleMigration("automation", "classpath:db/wasichai/automation", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun automationRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): AutomationRepository = AutomationRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun automationRunRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): AutomationRunRepository = AutomationRunRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun automationDispatcher(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        properties: AutomationProperties
    ): AutomationDispatcher = AutomationDispatcher(automations, runs, properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationFieldUsage(automations: AutomationRepository): AutomationFieldUsage = AutomationFieldUsage(automations)

    @Bean
    @ConditionalOnMissingBean
    fun webhookSender(properties: AutomationProperties): WebhookSender = WebhookSender(properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationRunner(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        metadata: MetadataService,
        store: RecordStore,
        workflows: WorkflowStates,
        audit: AuditService,
        dispatcher: AutomationDispatcher,
        webhooks: WebhookSender,
        documents: ObjectProvider<DocumentIssuer>,
        guards: RecordWriteGuards,
        types: FieldTypeRegistry,
        notifier: ObjectProvider<AutomationNotifier>
    ): AutomationRunner =
        AutomationRunner(
            automations,
            runs,
            metadata,
            store,
            workflows,
            audit,
            dispatcher,
            webhooks,
            documents.getIfAvailable { NoDocumentIssuer() },
            guards,
            types,
            notifier.getIfAvailable { NoAutomationNotifier() }
        )

    @Bean
    @ConditionalOnMissingBean
    fun automationService(
        automations: AutomationRepository,
        runs: AutomationRunRepository,
        metadata: MetadataService,
        webhooks: WebhookSender,
        documents: ObjectProvider<DocumentIssuer>,
        currentUser: CurrentUser,
        notifier: ObjectProvider<AutomationNotifier>
    ): AutomationService =
        AutomationService(
            automations,
            runs,
            metadata,
            webhooks,
            documents.getIfAvailable { NoDocumentIssuer() },
            currentUser,
            notifier.getIfAvailable { NoAutomationNotifier() }
        )

    // polls from SmartLifecycle.start(), after every singleton (migrations included): no @DependsOn (M9)
    @Bean
    @ConditionalOnMissingBean
    fun automationDrain(
        runner: AutomationRunner,
        properties: AutomationProperties
    ): AutomationDrain = AutomationDrain(runner, properties)

    @Bean
    @ConditionalOnMissingBean
    fun automationController(automations: AutomationService): AutomationController = AutomationController(automations)
}
