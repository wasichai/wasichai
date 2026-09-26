package wasichai.gis

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordService
import wasichai.core.metadata.FieldTypeRegistry
import java.util.UUID

private const val MAX_FEATURES = 5000

// an object's records as geojson, for the map. same permissions and bbox as the record list.
@RestController
@RequestMapping("/api/gis/objects/{object}/features")
class FeatureController(
    private val records: RecordService,
    private val queries: RecordQueryParser,
    private val types: FieldTypeRegistry
) {
    @GetMapping
    suspend fun collection(
        @PathVariable("object") objectName: String,
        @RequestParam params: Map<String, String>
    ): FeatureCollection {
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_FEATURES) ?: 1000
        val query = queries.parse(params).copy(page = PageRequest(0, limit))
        val (definition, rows) = records.rows(objectName, query)
        // a field whose module is gone has no handler: it cannot be the label
        val labelField = definition.fields.firstOrNull { types.isInstalled(it.type) && types.handler(it.type).textLike }?.name
        // a geojson Feature holds one geometry, so a request carries one. named, or the first.
        val geometry =
            params["geometry"]?.trim()?.ifBlank { null }
                ?: definition.geometryFields.firstOrNull()?.name
                ?: throw NotFoundException("Object '$objectName' has no geometry")
        return FeatureCollection(
            rows.map { row ->
                Feature(
                    id = "${row.id}:$geometry",
                    geometry = shape(row.sections[GEOMETRIES]?.get(geometry)),
                    properties =
                        row.attributes +
                            mapOf("__label" to labelField?.let { row.attributes[it] }, "__id" to row.id.toString())
                )
            }
        )
    }

    @GetMapping("/{id}")
    suspend fun feature(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestParam(required = false) geometry: String?
    ): Feature {
        val record = records.get(objectName, id)
        val geometries = record.sections[GEOMETRIES].orEmpty()
        val name = geometry ?: geometries.keys.firstOrNull() ?: throw NotFoundException("Record $id has no geometry")
        val shape = shape(geometries[name]) ?: throw NotFoundException("Record $id has no geometry '$name'")
        return Feature(id = "${record.id}:$name", geometry = shape, properties = record.attributes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun shape(value: Any?): Map<String, Any?>? = value as Map<String, Any?>?
}
