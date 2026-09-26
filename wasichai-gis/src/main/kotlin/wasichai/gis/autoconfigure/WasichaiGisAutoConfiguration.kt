package wasichai.gis.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.gis.BboxQuery
import wasichai.gis.FeatureController
import wasichai.gis.GeoServerClient
import wasichai.gis.GeoServerProperties
import wasichai.gis.GeometryFieldType
import wasichai.gis.LayerCleanup
import wasichai.gis.LayerController
import wasichai.gis.LayerService

// GIS as a plug-in: a field type, a query parameter, an object-removal listener and its own routes.
// core finds the first three through ObjectProviders, so no ordering against core is needed.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.gis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiGisProperties::class, GeoServerProperties::class)
class WasichaiGisAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off
    @Bean
    fun wasichaiGisMigration(): ModuleMigration = ModuleMigration("gis", "classpath:db/wasichai/gis", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun geometryFieldType(objectMapper: JsonMapper): GeometryFieldType = GeometryFieldType(objectMapper)

    @Bean
    @ConditionalOnMissingBean
    fun bboxQuery(): BboxQuery = BboxQuery()

    @Bean
    @ConditionalOnMissingBean
    fun geoServerClient(
        properties: GeoServerProperties,
        schemas: WasichaiSchemas
    ): GeoServerClient = GeoServerClient(properties, properties.datastore.schema ?: schemas.data)

    @Bean
    @ConditionalOnMissingBean
    fun layerCleanup(
        client: GeoServerClient,
        properties: GeoServerProperties
    ): LayerCleanup = LayerCleanup(client, properties)

    @Bean
    @ConditionalOnMissingBean
    fun layerService(
        metadata: MetadataService,
        client: GeoServerClient,
        properties: GeoServerProperties,
        currentUser: CurrentUser
    ): LayerService = LayerService(metadata, client, properties, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun layerController(layers: LayerService): LayerController = LayerController(layers)

    @Bean
    @ConditionalOnMissingBean
    fun featureController(
        records: RecordService,
        queries: RecordQueryParser,
        types: FieldTypeRegistry
    ): FeatureController = FeatureController(records, queries, types)
}
