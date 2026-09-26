package wasichai.it.slice.gis

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper
import wasichai.it.support.SliceSmokeTest

// geoserver stays out of the suite
@TestPropertySource(properties = ["wasichai.gis.geoserver.enabled=false", "wasichai.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"])
class GisOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("gis")

    private val json = JsonMapper.builder().build()

    // about 1.1 km by 1.1 km in central lima
    private val ring =
        listOf(listOf(-77.03, -12.05), listOf(-77.02, -12.05), listOf(-77.02, -12.04), listOf(-77.03, -12.04), listOf(-77.03, -12.05))

    @Test
    fun `a polygon goes into postgis in the field's srid and comes back as wgs84`() {
        val name = createLoteObject()
        val raw = createRecord(name)
        val record = json.readTree(raw)
        val back = record.get("geometries").get("lote")
        assertThat(back.get("type").asString()).isEqualTo("Polygon")
        val corner = back.get("coordinates").get(0).get(0)
        assertThat(corner.get(0).asDouble()).isCloseTo(-77.03, within(1e-6))
        assertThat(corner.get(1).asDouble()).isCloseTo(-12.05, within(1e-6))

        val table = physicalTable(name)
        assertThat(scalar("SELECT ST_SRID(lote) FROM app_data.\"$table\" LIMIT 1").toInt()).isEqualTo(32718)
        // square metres, since 32718 is metric: ~1088 m x ~1106 m
        assertThat(scalar("SELECT ST_Area(lote) FROM app_data.\"$table\" LIMIT 1").toDouble()).isBetween(1.15e6, 1.25e6)
        val gistSql =
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'app_data' AND tablename = '$table' AND indexdef LIKE '%USING gist%'"
        assertThat(scalar(gistSql).toInt()).isEqualTo(1)
    }

    @Test
    fun `the record is a feature, and bbox filters it`() {
        val name = createLoteObject()
        val id = json.readTree(createRecord(name)).get("id").asString()

        client
            .get()
            .uri("/api/gis/objects/$name/features")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("FeatureCollection")
            .jsonPath("$.features.length()")
            .isEqualTo(1)
            .jsonPath("$.features[0].id")
            .isEqualTo("$id:lote")
            .jsonPath("$.features[0].properties.__label")
            .isEqualTo("L-1")

        listOf("-78,-13,-76,-11" to 1, "-70,-10,-69,-9" to 0).forEach { (bbox, count) ->
            client
                .get()
                .uri("/api/gis/objects/$name/features?bbox=$bbox")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.features.length()")
                .isEqualTo(count)
        }
    }

    @Test
    fun `layers list without geoserver`() {
        client
            .get()
            .uri("/api/gis/layers")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun createLoteObject(): String {
        val name = uniqueName("lote")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Lote",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(name: String): String {
        val body =
            mapOf(
                "attributes" to mapOf("codigo" to "L-1"),
                "geometries" to mapOf("lote" to mapOf("type" to "Polygon", "coordinates" to listOf(ring)))
            )
        return client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    }

    private fun physicalTable(name: String): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM wasichai.custom_objects WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun scalar(sql: String): Number =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0) as Number }
                .one()
                .awaitFirstOrNull()!!
        }
}
