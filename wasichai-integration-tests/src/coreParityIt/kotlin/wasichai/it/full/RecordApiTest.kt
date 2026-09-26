package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient

private val POLYGON =
    mapOf(
        "type" to "Polygon",
        "coordinates" to
            listOf(
                listOf(
                    listOf(-77.05, -12.05),
                    listOf(-77.04, -12.05),
                    listOf(-77.04, -12.04),
                    listOf(-77.05, -12.04),
                    listOf(-77.05, -12.05)
                )
            )
    )

class RecordApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var objectName: String
    private lateinit var token: String

    @BeforeEach
    fun createObject() {
        objectName = uniqueName("predio")
        token = bearer()
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Predio",
                    "pluralLabel" to "Predios",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT", "required" to true),
                            mapOf("name" to "area", "type" to "DECIMAL"),
                            mapOf(
                                "name" to "uso",
                                "type" to "ENUM",
                                "enumOptions" to listOf("RESIDENCIAL", "COMERCIAL")
                            ),
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718),
                            mapOf("name" to "acceso", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `stores a polygon in the declared CRS and returns it in WGS84`() {
        val id = createRecord("P-001")

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("P-001")
            .jsonPath("$.attributes.uso")
            .isEqualTo("COMERCIAL")
            .jsonPath("$.geometries.lote.type")
            .isEqualTo("Polygon")
            .jsonPath("$.geometries.lote.coordinates[0][0][0]")
            .isEqualTo(-77.05)
            // every declared geometry is listed, so a null means "empty", not "unknown"
            .jsonPath("$.geometries.acceso")
            .isEmpty

        // reprojected on the way in: metres, not degrees
        assertThat(storedSrid()).isEqualTo(32718)
        assertThat(storedArea()).isGreaterThan(1_000_000.0)
    }

    @Test
    fun `rejects a value outside the enum options`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "P-002", "uso" to "INDUSTRIAL")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("uso")
    }

    @Test
    fun `rejects a geometry of the wrong type`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to "P-003"),
                    "geometries" to mapOf("lote" to mapOf("type" to "Point", "coordinates" to listOf(-77.0, -12.0)))
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("lote")
    }

    @Test
    fun `rejects a missing required field`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("area" to 10)))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `lists records with paging, search and sorting`() {
        createRecord("P-100")
        createRecord("P-200")

        client
            .get()
            .uri("/api/objects/$objectName/records?page=0&size=1&sort=codigo&dir=desc")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(2)
            .jsonPath("$.totalPages")
            .isEqualTo(2)
            .jsonPath("$.content.length()")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("P-200")

        client
            .get()
            .uri("/api/objects/$objectName/records?q=P-100")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
    }

    @Test
    fun `updates and deletes a record and audits every operation`() {
        val id = createRecord("P-300")

        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to "P-300", "area" to 999, "uso" to "RESIDENCIAL"),
                    "geometries" to mapOf("lote" to POLYGON)
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.uso")
            .isEqualTo("RESIDENCIAL")

        client
            .delete()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound

        assertThat(auditOperations()).containsExactly("CREATE", "UPDATE", "DELETE")
    }

    @Test
    fun `serves records as a GeoJSON FeatureCollection`() {
        createRecord("P-400")

        client
            .get()
            .uri("/api/gis/objects/$objectName/features")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("FeatureCollection")
            .jsonPath("$.features.length()")
            .isEqualTo(1)
            .jsonPath("$.features[0].type")
            .isEqualTo("Feature")
            .jsonPath("$.features[0].geometry.type")
            .isEqualTo("Polygon")
            .jsonPath("$.features[0].properties.codigo")
            .isEqualTo("P-400")
    }

    @Test
    fun `filters features by bounding box`() {
        createRecord("P-500")

        client
            .get()
            .uri("/api/gis/objects/$objectName/features?bbox=-70,-10,-69,-9")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.features.length()")
            .isEqualTo(0)

        client
            .get()
            .uri("/api/gis/objects/$objectName/features?bbox=-78,-13,-76,-11")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.features.length()")
            .isEqualTo(1)
    }

    // the two geometries are independent columns: writing one must not disturb the other
    @Test
    fun `a record carries every geometry the object declares, each on its own`() {
        val id = createRecord("P-700")
        val point = mapOf("type" to "Point", "coordinates" to listOf(-77.045, -12.045))

        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to "P-700", "area" to 850.5, "uso" to "COMERCIAL"),
                    "geometries" to mapOf("acceso" to point)
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.geometries.acceso.type")
            .isEqualTo("Point")
            // left out of the request, so left alone
            .jsonPath("$.geometries.lote.type")
            .isEqualTo("Polygon")
    }

    @Test
    fun `a geometry sent as null is cleared`() {
        val id = createRecord("P-800")

        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to "P-800", "area" to 850.5, "uso" to "COMERCIAL"),
                    "geometries" to mapOf<String, Any?>("lote" to null)
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.geometries.lote")
            .isEmpty
    }

    @Test
    fun `refuses a geometry the object does not declare`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to "P-900"),
                    "geometries" to mapOf("fachada" to POLYGON)
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("fachada")
    }

    // a bbox has to say which column it means once there is more than one
    @Test
    fun `a bbox filters the geometry it names`() {
        createRecord("P-600")

        client
            .get()
            .uri("/api/gis/objects/$objectName/features?geometry=acceso&bbox=-78,-13,-76,-11")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            // acceso was never drawn, so nothing intersects
            .jsonPath("$.features.length()")
            .isEqualTo(0)

        client
            .get()
            .uri("/api/gis/objects/$objectName/features?geometry=fachada&bbox=-78,-13,-76,-11")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    private fun createRecord(code: String): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to code, "area" to 850.5, "uso" to "COMERCIAL"),
                    "geometries" to mapOf("lote" to POLYGON)
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.id")
            .value<String> { }
            .returnResult()
            .let { result ->
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .find(result.responseBody!!.decodeToString())!!
                    .groupValues[1]
            }

    private fun table(): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM wasichai.custom_objects WHERE name = :name")
                .bind("name", objectName)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun storedSrid(): Int =
        runBlocking {
            db
                .sql("SELECT ST_SRID(lote) AS srid FROM app_data.\"${table()}\" LIMIT 1")
                .map { row, _ -> (row.get("srid") as Number).toInt() }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun storedArea(): Double =
        runBlocking {
            db
                .sql("SELECT ST_Area(lote) AS area FROM app_data.\"${table()}\" LIMIT 1")
                .map { row, _ -> (row.get("area") as Number).toDouble() }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun auditOperations(): List<String> =
        runBlocking {
            val operations = mutableListOf<String>()
            db
                .sql(
                    """
                    SELECT operation FROM wasichai.audit_log
                    WHERE object_name = :objectName ORDER BY occurred_at
                    """.trimIndent()
                ).bind("objectName", objectName)
                .map { row, _ -> row.get("operation", String::class.java)!! }
                .all()
                .collectList()
                .awaitFirstOrNull()
                ?.let { operations.addAll(it) }
            operations
        }
}
