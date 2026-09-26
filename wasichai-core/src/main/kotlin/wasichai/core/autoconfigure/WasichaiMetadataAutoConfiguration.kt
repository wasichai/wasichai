package wasichai.core.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CallerPermissionsController
import wasichai.core.metadata.CallerPermissionsService
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.FieldUsage
import wasichai.core.metadata.MetadataMapper
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectController
import wasichai.core.metadata.ObjectMetadataController
import wasichai.core.metadata.ObjectRemovalListener
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.metadata.RelationshipController
import wasichai.core.metadata.RelationshipMapper
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.metadata.RelationshipService
import wasichai.core.metadata.SystemFieldController
import wasichai.core.platform.SystemColumns
import wasichai.core.platform.WasichaiSchemas

// custom objects, fields, relationships and the field-type registry modules extend
@AutoConfiguration(after = [WasichaiSecurityAutoConfiguration::class])
class WasichaiMetadataAutoConfiguration {
    // core's twelve types first, then every FieldTypeHandler bean in @Order
    @Bean
    @ConditionalOnMissingBean
    fun fieldTypeRegistry(handlers: ObjectProvider<FieldTypeHandler>): FieldTypeRegistry = FieldTypeRegistry(handlers.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun customObjectRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): CustomObjectRepository = CustomObjectRepository(db, schemas)

    // JsonMapper, not the wider ObjectMapper: Boot 4.1's JacksonAutoConfiguration exposes a
    // JsonMapper bean (jackson 3). asking for the narrower type binds to it unambiguously even if
    // an app also has some other ObjectMapper-typed bean lying around.
    @Bean
    @ConditionalOnMissingBean
    fun customFieldRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry
    ): CustomFieldRepository = CustomFieldRepository(db, objectMapper, schemas, types)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RelationshipRepository = RelationshipRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun objectSchemaManager(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry
    ): ObjectSchemaManager = ObjectSchemaManager(db, schemas, types)

    @Bean
    @ConditionalOnMissingBean
    fun metadataService(
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        relationships: RelationshipRepository,
        schema: ObjectSchemaManager,
        currentUser: CurrentUser,
        access: AccessPolicy,
        types: FieldTypeRegistry,
        systemColumns: SystemColumns,
        usages: ObjectProvider<FieldUsage>,
        removals: ObjectProvider<ObjectRemovalListener>
    ): MetadataService =
        MetadataService(
            objects,
            fields,
            relationships,
            schema,
            currentUser,
            access,
            types,
            systemColumns,
            usages.orderedStream().toList(),
            removals.orderedStream().toList()
        )

    @Bean
    @ConditionalOnMissingBean
    fun relationshipService(
        relationships: RelationshipRepository,
        objects: CustomObjectRepository,
        fields: CustomFieldRepository,
        metadata: MetadataService,
        schema: ObjectSchemaManager,
        currentUser: CurrentUser
    ): RelationshipService = RelationshipService(relationships, objects, fields, metadata, schema, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun metadataMapper(
        objects: CustomObjectRepository,
        types: FieldTypeRegistry
    ): MetadataMapper = MetadataMapper(objects, types)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipMapper(
        objects: CustomObjectRepository,
        fields: CustomFieldRepository
    ): RelationshipMapper = RelationshipMapper(objects, fields)

    @Bean
    @ConditionalOnMissingBean
    fun callerPermissionsService(
        objects: CustomObjectRepository,
        currentUser: CurrentUser
    ): CallerPermissionsService = CallerPermissionsService(objects, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun objectController(
        metadata: MetadataService,
        mapper: MetadataMapper,
        currentUser: CurrentUser
    ): ObjectController = ObjectController(metadata, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun objectMetadataController(
        metadata: MetadataService,
        mapper: MetadataMapper,
        currentUser: CurrentUser
    ): ObjectMetadataController = ObjectMetadataController(metadata, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun systemFieldController(systemColumns: SystemColumns): SystemFieldController = SystemFieldController(systemColumns)

    @Bean
    @ConditionalOnMissingBean
    fun relationshipController(
        relationships: RelationshipService,
        mapper: RelationshipMapper,
        currentUser: CurrentUser
    ): RelationshipController = RelationshipController(relationships, mapper, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun callerPermissionsController(permissions: CallerPermissionsService): CallerPermissionsController = CallerPermissionsController(permissions)
}
