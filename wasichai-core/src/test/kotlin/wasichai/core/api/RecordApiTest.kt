package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.test.WasichaiIntegrationTest

class RecordApiTest : WasichaiIntegrationTest() {
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
                            )
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
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
                    "attributes" to mapOf("codigo" to "P-300", "area" to 999, "uso" to "RESIDENCIAL")
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

    private fun createRecord(code: String): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to code, "area" to 850.5, "uso" to "COMERCIAL")
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
