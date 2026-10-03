package wasichai.core.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditController
import wasichai.core.audit.AuditQueryService
import wasichai.core.audit.AuditService
import wasichai.core.data.AppendOnlyReferences
import wasichai.core.data.NoWorkflowStates
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordController
import wasichai.core.data.RecordQueryContributor
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordService
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWriteGuard
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.RelatedRecordController
import wasichai.core.data.RelatedRecordService
import wasichai.core.data.RelationTargets
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipMapper
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.WasichaiSchemas

// records, audit and related records. a module that gives records a state, or stores them another
// way, declares its bean in an auto-config that runs before this one.
@AutoConfiguration(after = [WasichaiMetadataAutoConfiguration::class])
class WasichaiDataAutoConfiguration {
    // JsonMapper, not the wider ObjectMapper: see WasichaiMetadataAutoConfiguration.customFieldRepository.
    @Bean
    @ConditionalOnMissingBean
    fun auditService(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): AuditService = AuditService(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun auditQueryService(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        currentUser: CurrentUser,
        metadata: MetadataService,
        access: AccessPolicy,
        schemas: WasichaiSchemas
    ): AuditQueryService = AuditQueryService(db, objectMapper, currentUser, metadata, access, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun auditController(audit: AuditQueryService): AuditController = AuditController(audit)

    // null object: no module installed, no object has a state (R9)
    @Bean
    @ConditionalOnMissingBean
    fun workflowStates(): WorkflowStates = NoWorkflowStates()

    @Bean
    @ConditionalOnMissingBean
    fun recordStore(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry
    ): RecordStore = PhysicalTableRecordStore(db, schemas, types)

    // the transaction manager is looked up on first withXactLock: tryLock needs none (ADR-039)
    @Bean
    @ConditionalOnMissingBean
    fun clusterLock(
        db: DatabaseClient,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): ClusterLock = ClusterLock(db) { TransactionalOperator.create(transactionManager.getObject()) }

    @Bean
    @ConditionalOnMissingBean
    fun recordQueryParser(contributors: ObjectProvider<RecordQueryContributor>): RecordQueryParser = RecordQueryParser(contributors.orderedStream().toList())

    // no @ConditionalOnMissingBean: appendOnly is for everyone, an app adds a RecordWriteGuard, never swaps this (ADR-040)
    @Bean
    fun recordWriteGuards(guards: ObjectProvider<RecordWriteGuard>): RecordWriteGuards = RecordWriteGuards(guards.orderedStream().toList())

    // no @ConditionalOnMissingBean: appendOnly is for everyone (ADR-040)
    @Bean
    fun appendOnlyReferences(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        relationships: RelationshipRepository,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): AppendOnlyReferences = AppendOnlyReferences(db, schemas, objects, fields, relationships) { TransactionalOperator.create(transactionManager.getObject()) }

    // no @ConditionalOnMissingBean: a relation value naming no record is a 400 for everyone (D29)
    @Bean
    fun relationTargets(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        objects: CustomObjectRepository
    ): RelationTargets = RelationTargets(db, schemas, objects)

    @Bean
    @ConditionalOnMissingBean
    fun recordService(
        metadata: MetadataService,
        store: RecordStore,
        audit: AuditService,
        currentUser: CurrentUser,
        access: AccessPolicy,
        workflows: WorkflowStates,
        types: FieldTypeRegistry,
        changes: ObjectProvider<RecordChangeListener>,
        guards: RecordWriteGuards,
        references: AppendOnlyReferences,
        relationTargets: RelationTargets
    ): RecordService =
        RecordService(metadata, store, audit, currentUser, access, workflows, types, changes.orderedStream().toList(), guards, references, relationTargets)

    @Bean
    @ConditionalOnMissingBean
    fun relatedRecordService(
        relationships: RelationshipRepository,
        relationshipService: RelationshipService,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        metadata: MetadataService,
        store: RecordStore,
        currentUser: CurrentUser,
        access: AccessPolicy,
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        audit: AuditService,
        guards: RecordWriteGuards
    ): RelatedRecordService =
        RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas, audit, guards)

    @Bean
    @ConditionalOnMissingBean
    fun recordController(
        records: RecordService,
        queries: RecordQueryParser
    ): RecordController = RecordController(records, queries)

    @Bean
    @ConditionalOnMissingBean
    fun relatedRecordController(
        relationships: RelationshipService,
        related: RelatedRecordService,
        mapper: RelationshipMapper,
        queries: RecordQueryParser
    ): RelatedRecordController = RelatedRecordController(relationships, related, mapper, queries)
}
