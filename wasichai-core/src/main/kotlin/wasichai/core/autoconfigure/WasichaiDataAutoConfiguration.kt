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
import wasichai.core.audit.AuditLogAdminAudit
import wasichai.core.audit.AuditLogOwnershipCheck
import wasichai.core.audit.AuditQueryService
import wasichai.core.audit.AuditService
import wasichai.core.data.AppendOnlyReferences
import wasichai.core.data.IdempotencyKeyPurge
import wasichai.core.data.IdempotencyKeys
import wasichai.core.data.NoWorkflowStates
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordController
import wasichai.core.data.RecordQueryContributor
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordReadScopes
import wasichai.core.data.RecordService
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWriteGuard
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.RelatedRecordController
import wasichai.core.data.RelatedRecordService
import wasichai.core.data.RelationTargets
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipMapper
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import wasichai.core.platform.WasichaiIdempotencyProperties
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

    // a WARN at start when the runtime role could drop audit_log's append-only triggers (ADR-054). never fails startup.
    @Bean
    @ConditionalOnMissingBean
    fun auditLogOwnershipCheck(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): AuditLogOwnershipCheck = AuditLogOwnershipCheck(db, schemas)

    // the admin trail (ADR-049): metadata, admin and organization write through this port, in their transaction
    @Bean
    @ConditionalOnMissingBean
    fun adminAudit(audit: AuditService): AdminAudit = AuditLogAdminAudit(audit)

    @Bean
    @ConditionalOnMissingBean
    fun auditQueryService(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        currentUser: CurrentUser,
        metadata: MetadataService,
        access: AccessPolicy,
        schemas: WasichaiSchemas,
        readScopes: RecordReadScopes
    ): AuditQueryService = AuditQueryService(db, objectMapper, currentUser, metadata, access, schemas, readScopes)

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

    // no @ConditionalOnMissingBean: an app adds a RecordReadScope, it never swaps out another's (ADR-048)
    @Bean
    fun recordReadScopes(
        scopes: ObjectProvider<RecordReadScope>,
        store: RecordStore
    ): RecordReadScopes = RecordReadScopes(scopes.orderedStream().toList(), store)

    // no @ConditionalOnMissingBean: appendOnly is for everyone, an app adds a RecordWriteGuard, never swaps this (ADR-040)
    @Bean
    fun recordWriteGuards(
        guards: ObjectProvider<RecordWriteGuard>,
        relationTargets: RelationTargets
    ): RecordWriteGuards = RecordWriteGuards(guards.orderedStream().toList(), relationTargets)

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

    // no @ConditionalOnMissingBean: a relation value naming no record is a 400 for everyone, through RecordWriteGuards (D29)
    @Bean
    fun relationTargets(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        readScopes: RecordReadScopes
    ): RelationTargets = RelationTargets(db, schemas, objects, fields, readScopes)

    // the transaction manager is looked up on first createAll, as for IdempotencyKeys
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
        readScopes: RecordReadScopes,
        tenants: TenantDirectory,
        idempotency: IdempotencyKeys,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): RecordService =
        RecordService(
            metadata,
            store,
            audit,
            currentUser,
            access,
            workflows,
            types,
            changes.orderedStream().toList(),
            guards,
            references,
            readScopes,
            tenants,
            idempotency
        ) { TransactionalOperator.create(transactionManager.getObject()) }

    // Idempotency-Key on record creation (ADR-058). the transaction manager is looked up on first use, as for ClusterLock
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyKeys(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        objectMapper: JsonMapper,
        properties: WasichaiIdempotencyProperties,
        transactionManager: ObjectProvider<ReactiveTransactionManager>
    ): IdempotencyKeys = IdempotencyKeys(db, schemas, objectMapper, properties) { TransactionalOperator.create(transactionManager.getObject()) }

    // deletes expired keys on one replica (ADR-039). starts after every singleton, migrations included
    @Bean
    @ConditionalOnMissingBean
    fun idempotencyKeyPurge(
        keys: IdempotencyKeys,
        clusterLock: ClusterLock,
        properties: WasichaiIdempotencyProperties
    ): IdempotencyKeyPurge = IdempotencyKeyPurge(keys, clusterLock, properties.purgeInterval)

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
        guards: RecordWriteGuards,
        readScopes: RecordReadScopes
    ): RelatedRecordService =
        RelatedRecordService(relationships, relationshipService, objects, fields, metadata, store, currentUser, access, db, schemas, audit, guards, readScopes)

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
