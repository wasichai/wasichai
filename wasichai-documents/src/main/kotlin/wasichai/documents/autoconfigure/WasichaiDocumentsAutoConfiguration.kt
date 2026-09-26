package wasichai.documents.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditService
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordStore
import wasichai.core.data.RelatedRecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.documents.DocumentController
import wasichai.documents.DocumentCounterRepository
import wasichai.documents.DocumentRepository
import wasichai.documents.DocumentService
import wasichai.documents.DocumentTypeController
import wasichai.documents.DocumentTypeRepository
import wasichai.documents.DocumentTypeService

// document types and issued documents. the automation port adapter is a separate auto-config
// (WasichaiDocumentsAutomationAutoConfiguration) that only exists when wasichai-automation does.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.documents", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiDocumentsProperties::class)
class WasichaiDocumentsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiDocumentsMigration(): ModuleMigration = ModuleMigration("documents", "classpath:db/wasichai/documents", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun documentRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): DocumentRepository = DocumentRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentCounterRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): DocumentCounterRepository = DocumentCounterRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): DocumentTypeRepository = DocumentTypeRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun documentService(
        documents: DocumentRepository,
        counters: DocumentCounterRepository,
        types: DocumentTypeRepository,
        metadata: MetadataService,
        related: RelatedRecordService,
        store: RecordStore,
        currentUser: CurrentUser,
        audit: AuditService
    ): DocumentService = DocumentService(documents, counters, types, metadata, related, store, currentUser, audit)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeService(
        types: DocumentTypeRepository,
        documents: DocumentRepository,
        metadata: MetadataService,
        relationships: RelationshipService,
        currentUser: CurrentUser
    ): DocumentTypeService = DocumentTypeService(types, documents, metadata, relationships, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun documentController(documents: DocumentService): DocumentController = DocumentController(documents)

    @Bean
    @ConditionalOnMissingBean
    fun documentTypeController(types: DocumentTypeService): DocumentTypeController = DocumentTypeController(types)
}
