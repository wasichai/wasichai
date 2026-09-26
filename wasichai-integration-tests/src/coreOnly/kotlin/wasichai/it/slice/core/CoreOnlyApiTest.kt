package wasichai.it.slice.core

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import wasichai.it.support.SliceSmokeTest

// core alone, from its starter, on plain postgres: every module is optional
// two checks repeat wasichai-core's CoreOnlyApiTest on purpose: here core comes from its starter, with nothing else on the classpath
class CoreOnlyApiTest : SliceSmokeTest() {
    override val installed = emptySet<String>()

    private val json = JsonMapper.builder().build()

    @Test
    fun `a record carries attributes and state, and nothing a module would add`() {
        val raw =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(
            json
                .readTree(raw)
                .propertyNames()
                .asSequence()
                .toList()
        ).containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `core ran without postgis`() {
        // on 5443 that proof leans on the server, so pin it: a stray extension would hide a postgis dependency
        val postgis =
            runBlocking {
                db
                    .sql("SELECT count(*) AS n FROM pg_extension WHERE extname = 'postgis'")
                    .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                    .one()
                    .awaitFirstOrNull()
            }
        assertThat(postgis).isEqualTo(0L)
    }

    @Test
    fun `a GEOMETRY field is refused without the gis module`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POINT"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("type")
    }

    @Test
    fun `no module column exists, no geometry attributes, no workflow state`() {
        val columns =
            runBlocking {
                db
                    .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'wasichai' AND table_name = 'custom_fields'")
                    .map { row, _ -> row.get("column_name", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        assertThat(columns).contains("name", "type").doesNotContain("geometry_type", "srid", "dimension")

        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')]")
            .doesNotExist()
    }
}
