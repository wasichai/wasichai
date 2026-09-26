package wasichai.gis

import tools.jackson.databind.ObjectMapper
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.UpdateFieldRequest
import wasichai.core.platform.SqlIdentifier

// GEOMETRY: a typed postgis column. geojson crosses r2dbc as text: ST_AsGeoJSON out,
// ST_GeomFromGeoJSON in, always in 4326 on the wire and the field's own crs on disk. ADR-007, ADR-019.
class GeometryFieldType(
    private val objectMapper: ObjectMapper
) : FieldTypeHandler {
    override val type = GEOMETRY

    override val attributeColumns: Map<String, Class<*>> =
        linkedMapOf(
            GEOMETRY_TYPE_COLUMN to String::class.java,
            SRID_COLUMN to Int::class.javaObjectType,
            DIMENSION_COLUMN to Int::class.javaObjectType
        )

    override val section = GEOMETRIES

    // ---- metadata ----

    // a geometry is a column with a shape and a crs. unique and a default mean nothing on one.
    // checked in the original's order, so the first complaint is the same one.
    override fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> {
        if (request.unique) {
            throw ValidationException("Geometry field '$fieldName' cannot be unique", "unique", "has no meaning on a geometry")
        }
        if (request.defaultValue != null) {
            throw ValidationException("Geometry field '$fieldName' cannot have a default", "defaultValue", "has no meaning on a geometry")
        }
        val dimension = wholeNumber(request.extensions["dimension"]) { invalidDimension() } ?: 2
        if (dimension !in 2..3) throw invalidDimension()
        val rawSrid = request.extensions["srid"]
        val srid =
            validSrid(
                wholeNumber(rawSrid) { ValidationException("Invalid SRID $rawSrid", "srid", "must be a positive EPSG code") } ?: DEFAULT_SRID
            )
        val geometryType = GeometryType.parse(request.extensions["geometryType"]?.toString())
        return mapOf(GEOMETRY_TYPE_COLUMN to geometryType.name, SRID_COLUMN to srid, DIMENSION_COLUMN to dimension)
    }

    override fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) {
        if (request.unique == true) {
            throw ValidationException("Geometry field '${field.name}' cannot be unique", "unique", "has no meaning on a geometry")
        }
    }

    // every field carries the key, null unless it is a geometry: the json the original always answered
    override fun fieldProperties(field: CustomField): Map<String, Any?> = mapOf("geometry" to if (field.type == GEOMETRY) field.toGeometryResponse() else null)

    // the first geometry field, kept so callers that only ask "is this object spatial" still work
    override fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        mapOf("geometry" to definition.geometryFields.firstOrNull()?.toGeometryResponse())

    // ---- ddl ----

    // a typed geometry column, not a bare "geometry": the type and the srid are the constraint.
    // the attributes come back from the database, so they are checked again before DDL sees them.
    override fun columnType(field: CustomField): String {
        val geometryType =
            field.geometryType
                ?: throw ValidationException("Geometry field '${field.name}' has no type", field.name, "requires geometryType")
        return "geometry(${geometryType.columnType(field.dimension ?: 2)}, ${validSrid(field.srid ?: 0)})"
    }

    // a geometry column without a GIST index is a table scan per bbox
    override fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> {
        val name = SqlIdentifier.indexName(obj.physicalTable, field.columnName, "gix")
        return listOf("CREATE INDEX ${SqlIdentifier.quote(name)} ON $table USING GIST (${SqlIdentifier.quote(field.columnName)})")
    }

    // ---- records ----

    override fun unknownSectionKey(
        key: String,
        definition: ObjectDefinition
    ): ValidationException = ValidationException("Unknown geometry '$key'", key, "is not a geometry of '${definition.obj.name}'")

    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        if (value == null) return null
        val geometry = value as? Map<*, *> ?: throw ValidationException("Invalid geometry", field.name, "must be a GeoJSON object")
        val expected = field.geometryType?.postgisType
        val type = geometry["type"] as? String ?: throw ValidationException("Invalid geometry", field.name, "missing 'type'")
        if (!type.equals(expected, ignoreCase = true)) {
            throw ValidationException("Invalid geometry", field.name, "must be a $expected")
        }
        return objectMapper.writeValueAsString(geometry)
    }

    override fun javaType(field: CustomField): Class<*> = String::class.java

    // stored in the field's srid, exchanged in 4326
    override fun bindExpression(
        field: CustomField,
        parameter: String
    ): String = "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:$parameter AS text)), $WGS84), ${validSrid(field.srid ?: 0)})"

    // core never adds an alias (P1 ledger): a rewritten expression must name itself
    override fun select(
        field: CustomField,
        column: String
    ): String = "ST_AsGeoJSON(ST_Transform($column, $WGS84)) AS ${SqlIdentifier.quote(readName(field))}"

    // one alias per geometry column, so two of them never share a slot
    override fun readName(field: CustomField): String = "${field.columnName}__geojson"

    @Suppress("UNCHECKED_CAST")
    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? = (value as String?)?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }

    // sorting or matching a geometry for equality is not a thing. bbox is how you filter one.
    override fun rejectFilterOrSort(field: CustomField): ValidationException =
        ValidationException("Cannot filter or sort by geometry '${field.name}'", field.name, "use bbox instead")

    private fun invalidDimension() = ValidationException("Invalid dimension", "dimension", "must be 2 or 3")

    // json numbers arrive as Int, Long or Double, and an admin may send "32718". a fraction is refused.
    private fun wholeNumber(
        raw: Any?,
        invalid: () -> ValidationException
    ): Int? =
        when (raw) {
            null -> null
            is Int -> raw
            is Number -> {
                val value = raw.toDouble()
                val whole = value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
                if (whole) value.toInt() else throw invalid()
            }
            is String -> raw.trim().toIntOrNull() ?: throw invalid()
            else -> throw invalid()
        }
}
