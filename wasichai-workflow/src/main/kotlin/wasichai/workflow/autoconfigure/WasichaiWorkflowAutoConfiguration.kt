package wasichai.workflow.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditService
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordReadMasks
import wasichai.core.data.RecordReadScopes
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWriteGuards
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleDirectory
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.workflow.WorkflowController
import wasichai.workflow.WorkflowRepository
import wasichai.workflow.WorkflowService
import wasichai.workflow.WorkflowStatesAdapter
import wasichai.workflow.WorkflowSystemColumns

// record states. before core's data config: our WorkflowStates must exist when core decides
// whether it still needs its NoWorkflowStates null object (P1 R9).
@AutoConfiguration(before = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.workflow", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiWorkflowProperties::class)
class WasichaiWorkflowAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiWorkflowMigration(): ModuleMigration = ModuleMigration("workflow", "classpath:db/wasichai/workflow", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun workflowSystemColumns(): WorkflowSystemColumns = WorkflowSystemColumns()

    @Bean
    @ConditionalOnMissingBean
    fun workflowRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): WorkflowRepository = WorkflowRepository(db, objectMapper, schemas)

    // named apart from core's "workflowStates" bean: same name would be a bean override, not a replacement
    @Bean
    @ConditionalOnMissingBean
    fun workflowStatesAdapter(workflows: WorkflowRepository): WorkflowStatesAdapter = WorkflowStatesAdapter(workflows)

    @Bean
    @ConditionalOnMissingBean
    fun workflowService(
        roles: RoleDirectory,
        workflows: WorkflowRepository,
        metadata: MetadataService,
        schema: ObjectSchemaManager,
        store: RecordStore,
        audit: AuditService,
        currentUser: CurrentUser,
        access: AccessPolicy,
        changes: ObjectProvider<RecordChangeListener>,
        guards: RecordWriteGuards,
        readScopes: RecordReadScopes,
        masks: RecordReadMasks
    ): WorkflowService =
        WorkflowService(roles, workflows, metadata, schema, store, audit, currentUser, access, changes.orderedStream().toList(), guards, readScopes, masks)

    @Bean
    @ConditionalOnMissingBean
    fun workflowController(workflows: WorkflowService): WorkflowController = WorkflowController(workflows)
}
