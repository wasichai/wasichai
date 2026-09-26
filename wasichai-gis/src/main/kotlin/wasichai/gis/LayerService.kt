package wasichai.gis

import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition

// a layer is one geometry of one object. an object with two geometries owns two layers.
data class LayerStatusResponse(
    val objectName: String,
    val geometryName: String,
    val label: String,
    val layerName: String,
    val geometryType: String,
    val srid: Int,
    val published: Boolean,
    val wms: String,
    val wfs: String
)

data class GeoServerServicesResponse(
    val enabled: Boolean,
    val url: String,
    val workspace: String,
    val wms: String,
    val wfs: String,
    val wmts: String
)

private const val DISABLED = "GeoServer integration is disabled (wasichai.gis.geoserver.enabled=false)"

// publishing is configuration, not data movement: the table is already there with its geometry column.
@Service
class LayerService(
    private val metadata: MetadataService,
    private val client: GeoServerClient,
    private val properties: GeoServerProperties,
    private val currentUser: CurrentUser
) {
    suspend fun status(): List<LayerStatusResponse> {
        // listDefinitions already demands READ and filters by tenant
        val layers =
            metadata.listDefinitions().flatMap { definition ->
                definition.geometryFields.mapIndexed { index, field -> Triple(definition.obj, field, index == 0) }
            }
        if (!properties.enabled || layers.isEmpty()) return layers.map { (obj, field, _) -> obj.toStatus(field, false) }
        val published = client.publishedLayerNames()
        return layers.map { (obj, field, first) ->
            obj.toStatus(field, GeoServerLayers.published(obj.physicalTable, field.columnName, first, published))
        }
    }

    suspend fun publish(
        objectName: String,
        geometryName: String?
    ): LayerStatusResponse {
        val (definition, field) = requireGeometry(objectName, geometryName, Actions.MANAGE_METADATA)
        val obj = definition.obj
        if (!properties.enabled) throw GeoServerException(DISABLED)
        client.ensureWorkspace()
        client.ensureDataStore()
        val name = layerName(obj, field)
        // republishing normalises: the pre-ADR-019 layer of this table is superseded by this one,
        // and leaving it would mean two layers for one geometry with the old one picking a column
        // on its own.
        if (client.featureTypeExists(obj.physicalTable)) client.deleteFeatureType(obj.physicalTable)
        // idempotent: geoserver answers 500 on a duplicate feature type, so ask before posting
        if (!client.featureTypeExists(name)) {
            client.publishFeatureType(
                table = obj.physicalTable,
                layerName = name,
                geometryColumn = field.columnName,
                geometryType = field.geometryType?.postgisType.orEmpty(),
                srid = field.srid ?: 0,
                title = "${obj.label} · ${field.label}",
                attributeColumns = definition.fields.filter { it.type != GEOMETRY }.map { it.columnName }
            )
        }
        return obj.toStatus(field, true)
    }

    suspend fun unpublish(
        objectName: String,
        geometryName: String?
    ) {
        val (definition, field) = requireGeometry(objectName, geometryName, Actions.MANAGE_METADATA)
        if (!properties.enabled) throw GeoServerException(DISABLED)
        client.deleteFeatureType(layerName(definition.obj, field))
        // layers published before a layer was a pair were named after the table alone
        if (client.featureTypeExists(definition.obj.physicalTable)) client.deleteFeatureType(definition.obj.physicalTable)
    }

    suspend fun services(): GeoServerServicesResponse {
        currentUser.requireWithPermission(Actions.READ)
        val base = properties.baseUrl
        return GeoServerServicesResponse(
            enabled = properties.enabled,
            url = base,
            workspace = properties.workspace,
            wms = GeoServerUrls.wms(base, properties.workspace),
            wfs = GeoServerUrls.wfs(base, properties.workspace),
            wmts = GeoServerUrls.wmts(base)
        )
    }

    private suspend fun requireGeometry(
        objectName: String,
        geometryName: String?,
        action: String
    ): Pair<ObjectDefinition, CustomField> {
        val user = currentUser.requireWithPermission(action)
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        val geometries = definition.geometryFields
        if (geometries.isEmpty()) {
            throw ValidationException(
                "Object '$objectName' has no geometry",
                "object",
                "only objects with a geometry field can be published as a layer"
            )
        }
        // no name means the first one, so a link saved before geometries were plural still works
        val field =
            if (geometryName == null) {
                geometries.first()
            } else {
                geometries.firstOrNull { it.name == geometryName }
                    ?: throw ValidationException(
                        "Unknown geometry '$geometryName'",
                        "geometry",
                        "is not a geometry of '$objectName'"
                    )
            }
        return definition to field
    }

    private fun layerName(
        obj: CustomObject,
        field: CustomField
    ): String = GeoServerLayers.name(obj.physicalTable, field.columnName)

    private fun CustomObject.toStatus(
        field: CustomField,
        published: Boolean
    ): LayerStatusResponse {
        val name = layerName(this, field)
        return LayerStatusResponse(
            objectName = this.name,
            geometryName = field.name,
            label = label,
            layerName = name,
            geometryType = field.geometryType?.name.orEmpty(),
            srid = field.srid ?: 0,
            published = published,
            wms = GeoServerUrls.wmsLayer(properties.baseUrl, properties.workspace, name),
            wfs = GeoServerUrls.wfsLayer(properties.baseUrl, properties.workspace, name)
        )
    }
}
