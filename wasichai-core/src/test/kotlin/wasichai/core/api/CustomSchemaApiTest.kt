package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest

// an app may pick its own schema names: migrations, ddl and every query follow them, and the
// default schemas are never touched by this context
@TestPropertySource(properties = ["wasichai.database.metadata-schema=acme_meta", "wasichai.database.data-schema=acme_data"])
class CustomSchemaApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private fun count(
        sql: String,
        vararg binds: Pair<String, Any>
    ): Long =
        runBlocking {
            var spec = db.sql(sql)
            binds.forEach { (name, value) -> spec = spec.bind(name, value) }
            (spec.map { row, _ -> row.get(0) as Number }.one().awaitFirstOrNull() ?: 0).toLong()
        }

    @Test
    fun `objects, records and history live in the configured schemas`() {
        val token = bearer()
        val name = uniqueName("predio")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Predio", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        val raw =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)

        val table =
            runBlocking {
                db
                    .sql("SELECT physical_table FROM acme_meta.custom_objects WHERE name = :name")
                    .bind("name", name)
                    .map { row, _ -> row.get("physical_table", String::class.java)!! }
                    .one()
                    .awaitFirstOrNull()!!
            }
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'acme_data' AND table_name = :t", "t" to table)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'app_data' AND table_name = :t", "t" to table)).isEqualTo(0)
        assertThat(count("SELECT count(*) FROM acme_meta.audit_log WHERE object_name = :n", "n" to name)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'acme_meta' AND table_name = 'flyway_history_core'")).isEqualTo(1)
        // extensions go to public whatever the schemas are called, so every schema pair resolves them
        assertThat(
            count("SELECT count(*) FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pgcrypto' AND n.nspname = 'public'")
        ).isEqualTo(1)
    }
}
