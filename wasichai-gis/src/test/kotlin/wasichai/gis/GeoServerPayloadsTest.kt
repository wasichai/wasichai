package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoServerPayloadsTest {
    private val datastore =
        GeoServerDataStoreProperties(
            name = "wasichai-postgis",
            host = "postgres",
            port = 5432,
            database = "wasichai",
            schema = "app_data",
            username = "wasichai",
            password = "secret"
        )

    @Suppress("UNCHECKED_CAST")
    private fun connectionParameters(payload: Map<String, Any>): Map<String, String> {
        val store = payload["dataStore"] as Map<String, Any>
        val parameters = store["connectionParameters"] as Map<String, Any>
        val entries = parameters["entry"] as List<Map<String, String>>
        return entries.associate { it["@key"]!! to it["$"]!! }
    }

    @Test
    fun `workspace payload carries the workspace name`() {
        assertThat(GeoServerPayloads.workspace("wasichai")).isEqualTo(mapOf("workspace" to mapOf("name" to "wasichai")))
    }

    @Test
    fun `datastore payload is a postgis store on the resolved schema`() {
        val payload = GeoServerPayloads.dataStore(datastore, "app_data")

        @Suppress("UNCHECKED_CAST")
        val store = payload["dataStore"] as Map<String, Any>
        assertThat(store["name"]).isEqualTo("wasichai-postgis")
        assertThat(store["type"]).isEqualTo("PostGIS")
        assertThat(store["enabled"]).isEqualTo(true)

        assertThat(connectionParameters(payload))
            .containsEntry("dbtype", "postgis")
            .containsEntry("host", "postgres")
            .containsEntry("port", "5432")
            .containsEntry("database", "wasichai")
            .containsEntry("schema", "app_data")
            .containsEntry("user", "wasichai")
            .containsEntry("passwd", "secret")
            .containsEntry("Expose primary keys", "true")
    }

    @Test
    fun `featuretype payload carries the layer name and the srid as an EPSG code`() {
        val payload = featureType(table = "predio__00000000", layerName = "predio__00000000__lote", srid = 32718)

        val featureType = payload.featureType()
        assertThat(featureType["name"]).isEqualTo("predio__00000000__lote")
        assertThat(featureType["srs"]).isEqualTo("EPSG:32718")
        assertThat(featureType["title"]).isEqualTo("Predio")
        assertThat(featureType["enabled"]).isEqualTo(true)
    }

    // a table with two geometry columns lets geoserver pick one in silence. this is what stops it.
    @Test
    fun `featuretype payload names the geometry column it publishes`() {
        val payload = featureType(geometryColumn = "acceso", geometryType = "Point")

        val geometry = payload.virtualTable()["geometry"] as Map<*, *>
        assertThat(geometry["name"]).isEqualTo("acceso")
        assertThat(geometry["type"]).isEqualTo("Point")
        assertThat(geometry["srid"]).isEqualTo(32718)
    }

    // the object's other geometries must not travel along as extra attributes
    @Test
    fun `the virtual table selects one geometry and the plain columns`() {
        val sql = GeoServerPayloads.virtualTableSql("app_data", "predio__00000000", "lote", listOf("codigo", "area"))

        assertThat(sql).isEqualTo(
            """SELECT "id", "created_at", "updated_at", "codigo", "area", "lote" FROM "app_data"."predio__00000000""""
        )
        assertThat(sql).doesNotContain("acceso")
    }

    // wasichai.database.data-schema is configurable; the layer must read the tables where they are
    @Test
    fun `the virtual table reads from the configured schema`() {
        assertThat(GeoServerPayloads.virtualTableSql("acme_data", "t", "g", emptyList()))
            .isEqualTo("""SELECT "id", "created_at", "updated_at", "g" FROM "acme_data"."t"""")
    }

    // schema lands raw in the sql string; refuse anything that is not a plain identifier
    @Test
    fun `an invalid schema is refused, never interpolated`() {
        assertThrows<IllegalArgumentException> { GeoServerPayloads.virtualTableSql("x; drop", "t", "g", emptyList()) }
    }

    @Test
    fun `the virtual table is keyed on id, so wfs clients get a feature id`() {
        assertThat(featureType().virtualTable()["keyColumn"]).isEqualTo("id")
    }

    @Test
    fun `a layer is named after the table and the column it publishes`() {
        assertThat(GeoServerLayers.name("predio__00000000", "lote")).isEqualTo("predio__00000000__lote")
    }

    // a layer published before ADR-019 is named after the table alone, and it is still serving
    @Test
    fun `the layer named after the table alone counts as the first geometry's`() {
        val published = setOf("predio__00000000")

        assertThat(GeoServerLayers.published("predio__00000000", "geom", firstGeometry = true, published)).isTrue()
        // the second geometry never had a layer under the old scheme
        assertThat(GeoServerLayers.published("predio__00000000", "acceso", firstGeometry = false, published)).isFalse()
    }

    @Test
    fun `a layer of its own is what counts once there is one`() {
        val published = setOf("predio__00000000__acceso")

        assertThat(GeoServerLayers.published("predio__00000000", "acceso", firstGeometry = false, published)).isTrue()
        assertThat(GeoServerLayers.published("predio__00000000", "geom", firstGeometry = true, published)).isFalse()
    }

    @Test
    fun `another table's layer is never mistaken for this one`() {
        val published = setOf("via__00000000", "via__00000000__traza")

        assertThat(GeoServerLayers.published("predio__00000000", "geom", firstGeometry = true, published)).isFalse()
    }

    private fun featureType(
        table: String = "predio__00000000",
        layerName: String = "predio__00000000__lote",
        geometryColumn: String = "lote",
        geometryType: String = "Polygon",
        srid: Int = 32718,
        title: String = "Predio",
        attributeColumns: List<String> = listOf("codigo")
    ): Map<String, Any> = GeoServerPayloads.featureType("app_data", table, layerName, geometryColumn, geometryType, srid, title, attributeColumns)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.featureType(): Map<String, Any> = this["featureType"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.virtualTable(): Map<String, Any> {
        val metadata = featureType()["metadata"] as Map<String, Any>
        val entries = metadata["entry"] as List<Map<String, Any>>
        return entries.first { it["@key"] == "JDBC_VIRTUAL_TABLE" }["virtualTable"] as Map<String, Any>
    }
}
