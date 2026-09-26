package wasichai.core.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.test.WasichaiIntegrationTest

class MetadataApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Test
    fun `creates an object with fields and a physical table`() {
        val name = uniqueName("predio")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "pluralLabel" to "Predios",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT", "required" to true, "unique" to true),
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
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
            .jsonPath("$.fields.length()")
            .isEqualTo(3)

        val table = physicalTable(name)

        assertThat(columnType(table, "codigo")).isEqualTo("text")
        assertThat(columnType(table, "area")).isEqualTo("numeric")
        assertThat(columnType(table, "organization_id")).isEqualTo("uuid")

        assertThat(indexNames(table)).contains("${table}_org_idx")
    }

    @Test
    fun `rejects a reserved SQL keyword as an object name`() {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(mapOf("name" to "select", "label" to "Bad"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentTypeCompatibleWith("application/problem+json")
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("name")
    }

    @Test
    fun `rejects a duplicate object name`() {
        val name = uniqueName("via")
        val token = bearer()
        val body = mapOf("name" to name, "label" to "Via")

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `adds a field to an existing object and to its table`() {
        val name = uniqueName("lote")
        val token = bearer()

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Lote"))
            .exchange()
            .expectStatus()
            .isCreated

        client
            .post()
            .uri("/api/metadata/objects/$name/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "superficie", "type" to "DECIMAL"))
            .exchange()
            .expectStatus()
            .isCreated

        assertThat(columnType(physicalTable(name), "superficie")).isEqualTo("numeric")
    }

    @Test
    fun `requires authentication`() {
        client
            .get()
            .uri("/api/objects")
            .exchange()
            .expectStatus()
            .isUnauthorized
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

    private fun columnType(
        table: String,
        column: String
    ): String? =
        runBlocking {
            db
                .sql(
                    """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'app_data' AND table_name = :table AND column_name = :column
                    """.trimIndent()
                ).bind("table", table)
                .bind("column", column)
                .map { row, _ -> row.get("data_type", String::class.java)!! }
                .one()
                .awaitFirstOrNull()
        }

    private fun indexNames(table: String): List<String> =
        runBlocking {
            db
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'app_data' AND tablename = :table")
                .bind("table", table)
                .map { row, _ -> row.get("indexname", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        }
}
