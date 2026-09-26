package wasichai.gis

import wasichai.core.common.ValidationException

// what a GEOMETRY field holds. "none" is an object with no geometry field, not a value in here.
enum class GeometryType(
    val postgisType: String
) {
    POINT("Point"),
    LINESTRING("LineString"),
    POLYGON("Polygon"),
    MULTIPOINT("MultiPoint"),
    MULTILINESTRING("MultiLineString"),
    MULTIPOLYGON("MultiPolygon");

    // 3d columns are PointZ, PolygonZ and so on
    fun columnType(dimension: Int): String = if (dimension == 3) postgisType + "Z" else postgisType

    companion object {
        fun parse(
            raw: String?,
            field: String = "geometryType"
        ): GeometryType =
            entries.firstOrNull { it.name == raw?.uppercase() }
                ?: throw ValidationException(
                    "Unknown geometry type '${raw ?: ""}'",
                    field,
                    "must be one of ${entries.joinToString(", ") { it.name }}"
                )
    }
}

// geojson crosses the api in EPSG:4326 whatever crs the column stores. ADR-007.
const val WGS84 = 4326
const val DEFAULT_SRID = 4326
private const val MAX_SRID = 999_999

// the srid is interpolated into DDL and SQL, so it must be a plain positive int
fun validSrid(srid: Int): Int {
    if (srid <= 0 || srid > MAX_SRID) {
        throw ValidationException("Invalid SRID $srid", "srid", "must be a positive EPSG code")
    }
    return srid
}
