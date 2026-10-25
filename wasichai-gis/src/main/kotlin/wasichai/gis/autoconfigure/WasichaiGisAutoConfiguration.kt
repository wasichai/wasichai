package wasichai.gis.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordService
import wasichai.core.identity.AccessPolicy
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
import wasichai.gis.MapViewController
import wasichai.gis.MapViewRepository
import wasichai.gis.MapViewService
import wasichai.gis.StyleClassService

// GIS as a plug-in: a field type, a query parameter, an object-removal listener and its own routes (layers, map views).
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
    fun styleClassService(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        metadata: MetadataService,
        access: AccessPolicy,
        currentUser: CurrentUser
    ): StyleClassService = StyleClassService(db, schemas, metadata, access, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun layerService(
        metadata: MetadataService,
        client: GeoServerClient,
        properties: GeoServerProperties,
        currentUser: CurrentUser,
        styles: StyleClassService
    ): LayerService = LayerService(metadata, client, properties, currentUser, styles)

    @Bean
    @ConditionalOnMissingBean
    fun layerController(
        layers: LayerService,
        styles: StyleClassService
    ): LayerController = LayerController(layers, styles)

    @Bean
    @ConditionalOnMissingBean
    fun mapViewRepository(
        db: DatabaseClient,
        objectMapper: JsonMapper,
        schemas: WasichaiSchemas
    ): MapViewRepository = MapViewRepository(db, objectMapper, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun mapViewService(
        views: MapViewRepository,
        metadata: MetadataService,
        styles: StyleClassService,
        currentUser: CurrentUser
    ): MapViewService = MapViewService(views, metadata, styles, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun mapViewController(views: MapViewService): MapViewController = MapViewController(views)

    @Bean
    @ConditionalOnMissingBean
    fun featureController(
        records: RecordService,
        queries: RecordQueryParser,
        types: FieldTypeRegistry
    ): FeatureController = FeatureController(records, queries, types)
}
