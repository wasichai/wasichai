package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest

// what an app with wasichai-core and no module sees. proves every module is optional.
class CoreOnlyApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private val json = JsonMapper.builder().build()
    private lateinit var token: String
    private lateinit var objectName: String

    @BeforeEach
    fun createObject() {
        token = bearer()
        objectName = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to objectName, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun body(
        method: String,
        uri: String,
        payload: Any? = null
    ): JsonNode {
        val spec =
            when (method) {
                "POST" ->
                    client
                        .post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(payload!!)
                else -> client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, token)
            }
        val raw =
            spec
                .exchange()
                .expectStatus()
                .is2xxSuccessful
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return json.readTree(raw)
    }

    private fun keys(node: JsonNode): List<String> = node.propertyNames().asSequence().toList()

    @Test
    fun `a record carries attributes and state, and nothing a module would add`() {
        val record = body("POST", "/api/objects/$objectName/records", mapOf("attributes" to mapOf("codigo" to "A-1")))

        assertThat(keys(record)).containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `object and field json have no module keys`() {
        val definition = body("GET", "/api/objects/$objectName")

        assertThat(keys(definition)).containsExactlyInAnyOrder("id", "name", "label", "pluralLabel", "description", "enabled", "fields")
        assertThat(keys(definition.get("fields").get(0))).containsExactlyInAnyOrder(
            "id",
            "name",
            "label",
            "type",
            "required",
            "unique",
            "defaultValue",
            "description",
            "position",
            "enumOptions",
            "relationTarget",
            "visible",
            "editable"
        )
    }

    @Test
    fun `module properties sent without the module are ignored, like any unknown property`() {
        val record = body("POST", "/api/objects/$objectName/records", mapOf("attributes" to mapOf("codigo" to "A-2"), "geometries" to mapOf("lote" to null)))
        val field = body("POST", "/api/metadata/objects/$objectName/fields", mapOf("name" to "nota", "type" to "TEXT", "geometryType" to "POINT"))

        assertThat(keys(record)).doesNotContain("geometries")
        assertThat(field.get("type").asString()).isEqualTo("TEXT")
        assertThat(keys(field)).doesNotContain("geometryType", "geometry")
    }

    @Test
    fun `a type no installed module provides is refused`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POINT"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("type")
    }

    @Test
    fun `bbox is an unknown field without the module that owns it`() {
        client
            .get()
            .uri("/api/objects/$objectName/records?bbox=1,2,3,4")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("bbox")
    }

    @Test
    fun `health names the app and errors carry the wasichai problem type`() {
        client
            .get()
            .uri("/api/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.application")
            .isEqualTo("wasichai")

        client
            .get()
            .uri("/api/objects/${uniqueName("missing")}")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("https://wasichai.dev/problems/404")
    }

    @Test
    fun `the views, forms and pages lists belong to their modules`() {
        listOf("views", "forms", "pages").forEach { part ->
            client
                .get()
                .uri("/api/metadata/objects/$objectName/$part")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isNotFound
        }
    }

    @Test
    fun `the page-resolution route is absent without the pages module, auth checked first`() {
        client
            .get()
            .uri("/api/objects/$objectName/pages/record")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound

        // no token at all: security runs before routing finds nothing to dispatch to
        client
            .get()
            .uri("/api/objects/$objectName/pages/record")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `the gis and workflow routes are absent and postgis is not needed`() {
        listOf("/api/gis/objects/$objectName/features", "/api/objects/$objectName/workflow").forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isNotFound
        }
        val postgis =
            runBlocking {
                db
                    .sql("SELECT count(*) FROM pg_extension WHERE extname = 'postgis'")
                    .map { row, _ -> (row.get(0) as Number).toLong() }
                    .one()
                    .awaitFirstOrNull()
            }
        assertThat(postgis).isEqualTo(0L)
    }
}
