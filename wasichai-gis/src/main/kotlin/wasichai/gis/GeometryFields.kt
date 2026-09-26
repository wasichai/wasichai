package wasichai.gis

import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition

val GEOMETRY = FieldType("GEOMETRY")

// the record payload section geometries travel in, keyed by field name (P1 R6)
const val GEOMETRIES = "geometries"

// custom_fields columns this module owns (P1 R3); its migration creates them
const val GEOMETRY_TYPE_COLUMN = "geometry_type"
const val SRID_COLUMN = "srid"
const val DIMENSION_COLUMN = "dimension"

// what "geometry" says in the json of a field, and of an object (its first geometry field)
data class GeometryResponse(
    val type: String,
    val srid: Int,
    val dimension: Int
)

// null on every field that is not a GEOMETRY. a stored value the enum does not know reads as null
// rather than breaking every response that lists the field.
val CustomField.geometryType: GeometryType?
    get() = (attributes[GEOMETRY_TYPE_COLUMN] as String?)?.let { raw -> GeometryType.entries.firstOrNull { it.name == raw } }

val CustomField.srid: Int? get() = (attributes[SRID_COLUMN] as Number?)?.toInt()

val CustomField.dimension: Int? get() = (attributes[DIMENSION_COLUMN] as Number?)?.toInt()

// the geometry columns of this object, in field order. empty means a flat object.
val ObjectDefinition.geometryFields: List<CustomField>
    get() = fields.filter { it.type == GEOMETRY }

fun CustomField.toGeometryResponse(): GeometryResponse? = geometryType?.let { GeometryResponse(it.name, srid ?: 0, dimension ?: 2) }
