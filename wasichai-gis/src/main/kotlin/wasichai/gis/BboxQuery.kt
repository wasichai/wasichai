package wasichai.gis

import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQueryContributor
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
)

// ?bbox=minX,minY,maxX,maxY (EPSG:4326) and ?geometry=<field>: records whose geometry meets the box.
// numbers are bound; only the srid, validated, is written into the sql.
class BboxQuery : RecordQueryContributor {
    override val parameters: Set<String> = setOf("bbox", "geometry")

    override fun parse(params: Map<String, String>): RecordCriterion? {
        // geometry alone filters nothing: it only says which column a bbox means
        val box = params["bbox"]?.let(::parseBbox) ?: return null
        val named = params["geometry"]?.trim()?.ifBlank { null }
        return RecordCriterion { definition, bind ->
            val field = geometryOrFail(definition, named)
            val column = SqlIdentifier.quote(field.columnName)
            "ST_Intersects($column, ST_Transform(ST_MakeEnvelope(${bind(box.minX)}, ${bind(box.minY)}, ${bind(box.maxX)}, ${bind(box.maxY)}, $WGS84), " +
                "${validSrid(field.srid ?: 0)}))"
        }
    }

    companion object {
        fun parseBbox(raw: String): BoundingBox {
            val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 4) {
                throw ValidationException("Invalid bbox", "bbox", "must be minX,minY,maxX,maxY in EPSG:4326")
            }
            return BoundingBox(parts[0], parts[1], parts[2], parts[3])
        }

        // the geometry a spatial query means: the one it named, or the first one the object declares
        fun geometryOrFail(
            definition: ObjectDefinition,
            name: String?
        ): CustomField {
            if (name == null) {
                return definition.geometryFields.firstOrNull()
                    ?: throw ValidationException("Object '${definition.obj.name}' has no geometry", "bbox", "the object is not spatial")
            }
            return definition.geometryFields.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown geometry '$name'", "geometry", "is not a geometry of '${definition.obj.name}'")
        }
    }
}
