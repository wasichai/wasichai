package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.UpdateFieldRequest
import wasichai.gis.GisFixtures.definition
import wasichai.gis.GisFixtures.geometry
import wasichai.gis.GisFixtures.obj
import wasichai.gis.GisFixtures.refused
import wasichai.gis.GisFixtures.text

class GeometryFieldTypeTest {
    private val handler = GeometryFieldType(JsonMapper.builder().build())

    private fun request(
        vararg extensions: Pair<String, Any?>,
        unique: Boolean = false,
        defaultValue: String? = null
    ) = FieldRequest(name = "lote", type = "GEOMETRY", unique = unique, defaultValue = defaultValue, extensions = mapOf(*extensions))

    // ---- metadata ----

    @Test
    fun `a new geometry defaults to 2d in 4326, whatever case its type is written in`() {
        assertThat(handler.attributesOf("lote", request("geometryType" to "point")))
            .isEqualTo(mapOf("geometry_type" to "POINT", "srid" to 4326, "dimension" to 2))
    }

    @Test
    fun `srid and dimension are taken as given, a numeric string included`() {
        assertThat(handler.attributesOf("lote", request("geometryType" to "POLYGON", "srid" to "32718", "dimension" to 3)))
            .isEqualTo(mapOf("geometry_type" to "POLYGON", "srid" to 32718, "dimension" to 3))
    }

    @Test
    fun `unique and a default mean nothing on a geometry`() {
        refused("Geometry field 'lote' cannot be unique", "unique") { handler.attributesOf("lote", request("geometryType" to "POINT", unique = true)) }
        refused("Geometry field 'lote' cannot have a default", "defaultValue") {
            handler.attributesOf("lote", request("geometryType" to "POINT", defaultValue = "x"))
        }
    }

    @Test
    fun `bad dimension, srid or type are a 400, never a 500`() {
        refused("Invalid dimension", "dimension") { handler.attributesOf("lote", request("geometryType" to "POINT", "dimension" to 4)) }
        refused("Invalid SRID -1", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to -1)) }
        refused("Invalid SRID 1000000", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to 1_000_000)) }
        refused("Invalid SRID abc", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to "abc")) }
        refused("Invalid SRID 1.5", "srid") { handler.attributesOf("lote", request("geometryType" to "POINT", "srid" to 1.5)) }
        refused("Unknown geometry type ''", "geometryType") { handler.attributesOf("lote", request()) }
        refused("Unknown geometry type 'circle'", "geometryType") { handler.attributesOf("lote", request("geometryType" to "circle")) }
    }

    @Test
    fun `an existing geometry cannot be made unique`() {
        refused("Geometry field 'lote' cannot be unique", "unique") { handler.checkUpdate(geometry("lote"), UpdateFieldRequest(unique = true)) }
        handler.checkUpdate(geometry("lote"), UpdateFieldRequest(label = "Lote"))
    }

    // ---- json ----

    @Test
    fun `every field says geometry, null unless it is one`() {
        assertThat(handler.fieldProperties(text("codigo"))).containsExactly(
            org.assertj.core.api.Assertions
                .entry("geometry", null)
        )
        assertThat(handler.fieldProperties(geometry("acceso", "POINT", 4326, 3)))
            .containsExactly(
                org.assertj.core.api.Assertions
                    .entry("geometry", GeometryResponse("POINT", 4326, 3))
            )
    }

    @Test
    fun `an object's geometry is its first geometry field, null when flat`() {
        val spatial = definition(text("codigo"), geometry("lote", "POLYGON"), geometry("acceso", "POINT"))
        assertThat(handler.objectProperties(spatial)["geometry"]).isEqualTo(GeometryResponse("POLYGON", 32718, 2))
        assertThat(handler.objectProperties(definition(text("codigo")))).containsEntry("geometry", null)
    }

    // ---- ddl ----

    @Test
    fun `the column is typed by shape, dimension and crs`() {
        assertThat(handler.columnType(geometry("lote", "POLYGON", 32718, 2))).isEqualTo("geometry(Polygon, 32718)")
        assertThat(handler.columnType(geometry("acceso", "POINT", 4326, 3))).isEqualTo("geometry(PointZ, 4326)")
    }

    // attributes come from the database: checked again before they reach DDL (P1 ledger)
    @Test
    fun `the column refuses a stored field with no type or a bad srid`() {
        refused("Geometry field 'lote' has no type", "lote") { handler.columnType(geometry("lote", type = null)) }
        refused("Invalid SRID 0", "srid") { handler.columnType(geometry("lote", srid = 0)) }
    }

    @Test
    fun `a gist index per geometry column`() {
        assertThat(handler.indexes(obj, "\"app_data\".\"predio__00000000\"", geometry("lote")))
            .containsExactly("CREATE INDEX \"predio__00000000_lote_gix\" ON \"app_data\".\"predio__00000000\" USING GIST (\"lote\")")
    }

    // ---- records ----

    @Test
    fun `geojson in 4326 on the wire, the column's own crs on disk`() {
        val lote = geometry("lote", srid = 32718)
        assertThat(handler.bindExpression(lote, "s0")).isEqualTo("ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:s0 AS text)), 4326), 32718)")
        assertThat(handler.select(lote, "\"lote\"")).isEqualTo("ST_AsGeoJSON(ST_Transform(\"lote\", 4326)) AS \"lote__geojson\"")
        assertThat(handler.readName(lote)).isEqualTo("lote__geojson")
        assertThat(handler.javaType(lote)).isEqualTo(String::class.java)
        assertThat(handler.section).isEqualTo("geometries")
    }

    @Test
    fun `a geometry value must be geojson of the field's own shape`() {
        val lote = geometry("lote", "POLYGON")
        val polygon = mapOf("type" to "polygon", "coordinates" to listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(0.0, 1.0), listOf(0.0, 0.0))))
        assertThat(handler.toDatabase(lote, polygon) as String).contains("\"type\":\"polygon\"").contains("\"coordinates\"")
        assertThat(handler.toDatabase(lote, null)).isNull()
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0))) }
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, mapOf("coordinates" to listOf(1.0, 2.0))) }
        refused("Invalid geometry", "lote") { handler.toDatabase(lote, "POINT(1 2)") }
    }

    @Test
    fun `geojson text reads back as a map`() {
        assertThat(handler.fromDatabase(geometry("lote"), """{"type":"Point","coordinates":[1.0,2.0]}"""))
            .isEqualTo(mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0)))
        assertThat(handler.fromDatabase(geometry("lote"), null)).isNull()
    }

    @Test
    fun `a geometry is filtered with bbox, never by equality or sort, and unknown keys say geometry`() {
        refused("Cannot filter or sort by geometry 'lote'", "lote") { throw handler.rejectFilterOrSort(geometry("lote")) }
        refused("Unknown geometry 'x'", "x") { throw handler.unknownSectionKey("x", definition(geometry("lote"))) }
    }

    // ---- registry ----

    @Test
    fun `installed next to core, GEOMETRY parses and is listed last, as before`() {
        val registry = FieldTypeRegistry(listOf(handler))
        assertThat(registry.parse("geometry")).isEqualTo(GEOMETRY)
        assertThat(registry.types.last()).isEqualTo(FieldType("GEOMETRY"))
        assertThat(registry.sections).containsExactly("geometries")
        assertThat(registry.attributeColumns.keys).containsExactly("geometry_type", "srid", "dimension")
        refused(
            "Unknown field type 'circle'",
            "type"
        ) { registry.parse("circle") }
    }
}
