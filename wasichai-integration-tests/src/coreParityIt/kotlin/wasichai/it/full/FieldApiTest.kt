package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.metadata.SystemFieldResponse

class FieldApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var objectName: String
    private lateinit var token: String

    @BeforeEach
    fun createObject() {
        objectName = uniqueName("lote")
        token = bearer()
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Lote",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "estado", "type" to "ENUM", "enumOptions" to listOf("A", "B"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `making a field required adds NOT NULL to the column`() {
        assertThat(isNullable("codigo")).isTrue()

        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("required" to true, "label" to "Código"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.required")
            .isEqualTo(true)
            .jsonPath("$.label")
            .isEqualTo("Código")

        assertThat(isNullable("codigo")).isFalse()
    }

    @Test
    fun `making a field unique adds a constraint that rejects duplicates`() {
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("unique" to true))
            .exchange()
            .expectStatus()
            .isOk

        createRecord("L-1").expectStatus().isCreated
        // the original answered 5xx; a repeat is now the caller's 409 naming the field (ADR-031 D23)
        createRecord("L-1")
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("codigo")
    }

    @Test
    fun `changing enum options replaces the check constraint`() {
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/estado")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("enumOptions" to listOf("ACTIVO", "ARCHIVADO")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enumOptions[0]")
            .isEqualTo("ACTIVO")

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-2", "estado" to "A")))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-3", "estado" to "ACTIVO")))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `deleting a field drops its column`() {
        client
            .delete()
            .uri("/api/metadata/objects/$objectName/fields/estado")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        assertThat(columnExists("estado")).isFalse()

        client
            .get()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
    }

    @Test
    fun `rejects enum options on a field that is not an enum`() {
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("enumOptions" to listOf("X")))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `publishes the names it keeps for itself`() {
        val published = systemFields()
        assertThat(published.map { it.name }).contains("id", "created_at", "workflow_state", "version")
        // a geometry is a user field now, so its name is the user's to pick
        assertThat(published.map { it.name }).doesNotContain("geom")
        assertThat(published.first { it.name == "created_at" }).isEqualTo(SystemFieldResponse("created_at", "DATETIME", "ALWAYS"))
        // the state column only exists once a workflow is attached, and the screen has to say so
        assertThat(published.first { it.name == "workflow_state" }.scope).isEqualTo("WORKFLOW")
        // reserved but never created: no type, because there is no column
        assertThat(published.first { it.name == "version" }).isEqualTo(SystemFieldResponse("version", null, "RESERVED"))
    }

    @Test
    fun `every published name is a name it refuses`() {
        systemFields().map { it.name }.forEach { name ->
            client
                .post()
                .uri("/api/metadata/objects/$objectName/fields")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("name" to name, "type" to "TEXT"))
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    // what the field editor lists. the pair of tests above is the point: it matches what the server
    // refuses, so the screen cannot promise a name the api will reject.
    private fun systemFields(): List<SystemFieldResponse> =
        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBodyList(SystemFieldResponse::class.java)
            .returnResult()
            .responseBody!!

    private fun createRecord(code: String) =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to code)))
            .exchange()

    private fun table(): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM wasichai.custom_objects WHERE name = :name")
                .bind("name", objectName)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun isNullable(column: String): Boolean =
        runBlocking {
            db
                .sql(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'app_data' AND table_name = :table AND column_name = :column
                    """.trimIndent()
                ).bind("table", table())
                .bind("column", column)
                .map { row, _ -> row.get("is_nullable", String::class.java)!! }
                .one()
                .awaitFirstOrNull() == "YES"
        }

    private fun columnExists(column: String): Boolean =
        runBlocking {
            db
                .sql(
                    """
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'app_data' AND table_name = :table AND column_name = :column
                    """.trimIndent()
                ).bind("table", table())
                .bind("column", column)
                .map { _, _ -> true }
                .one()
                .awaitFirstOrNull() ?: false
        }
}
