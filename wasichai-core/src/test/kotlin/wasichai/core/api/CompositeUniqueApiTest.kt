package wasichai.core.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest

// issue 14: composite unique constraints declared in metadata, and a unique violation as a 409 naming the fields
class CompositeUniqueApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    // what an app applies to every tenant: an order keyed by its source, and a relation it may also be keyed by
    private fun applyModel(
        token: String,
        name: String,
        target: String
    ) {
        post(token, "/api/objects", mapOf("name" to target, "label" to "Caja", "fields" to listOf(mapOf("name" to "nombre", "type" to "TEXT"))))
        post(
            token,
            "/api/objects",
            mapOf(
                "name" to name,
                "label" to "Orden",
                "fields" to
                    listOf(
                        mapOf("name" to "sistema_origen", "type" to "TEXT"),
                        mapOf("name" to "referencia_externa", "type" to "TEXT"),
                        mapOf("name" to "codigo", "type" to "TEXT", "unique" to true)
                    ),
                "uniqueConstraints" to listOf(listOf(" Sistema_Origen", "referencia_externa"))
            )
        )
        post(
            token,
            "/api/relationships",
            mapOf("name" to "${name}_caja", "label" to "Caja", "type" to "MANY_TO_ONE", "source" to name, "target" to target, "fieldName" to "caja")
        )
    }

    @Test
    fun `a declared set is a real unique per organization, and a repeat is a 409 naming its fields`() {
        val name = uniqueName("orden")
        val target = uniqueName("caja")
        val demo = bearer()
        applyModel(demo, name, target)

        // a tenant provisioned later gets the same constraint when the model is applied to it
        val slug = "uq-" + uniqueName("").take(8)
        post(
            demo,
            "/api/organizations",
            mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret")
        )
        val other = bearer("$slug@wasichai.local", "supersecret")
        applyModel(other, name, target)
        physicalTables(name).also { assertThat(it).hasSize(2) }.forEach { table ->
            assertThat(uniqueColumns(table)).describedAs(table).contains("sistema_origen,referencia_externa")
        }

        client
            .get()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, demo)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.uniqueConstraints[0][0]")
            .isEqualTo("sistema_origen")
            .jsonPath("$.uniqueConstraints[0][1]")
            .isEqualTo("referencia_externa")

        createRecord(demo, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1", "codigo" to "A")).expectStatus().isCreated
        // the same pair in another organization is not a repeat
        createRecord(other, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1", "codigo" to "A")).expectStatus().isCreated
        // the same pair in the same organization is
        createRecord(demo, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1", "codigo" to "B"))
            .expectStatus()
            .isEqualTo(409)
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(409)
            .jsonPath("$.errors.length()")
            .isEqualTo(2)
            .jsonPath("$.errors[0].field")
            .isEqualTo("sistema_origen")
            .jsonPath("$.errors[1].field")
            .isEqualTo("referencia_externa")
            .jsonPath("$.detail")
            .value<String> { assertThat(it).doesNotContain("SIAF") }

        // an update into a taken pair is the same 409 (PUT replaces every attribute)
        val second =
            createRecord(demo, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "2", "codigo" to "C"))
                .expectStatus()
                .isCreated
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!["id"]
        client
            .put()
            .uri("/api/objects/$name/records/$second")
            .header(HttpHeaders.AUTHORIZATION, demo)
            .bodyValue(mapOf("attributes" to mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1", "codigo" to "C")))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("sistema_origen")

        // a single-field unique answers the same way
        createRecord(demo, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "3", "codigo" to "A"))
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors.length()")
            .isEqualTo(1)
            .jsonPath("$.errors[0].field")
            .isEqualTo("codigo")
    }

    @Test
    fun `sets change with the metadata, and one the data already breaks is a 409 that changes nothing`() {
        val name = uniqueName("orden")
        val target = uniqueName("caja")
        val token = bearer()
        applyModel(token, name, target)
        val table = physicalTables(name).single()

        // unknown fields are refused naming the property
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Orden", "uniqueConstraints" to listOf(listOf("sistema_origen", "nope"))))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("uniqueConstraints")

        // two records share a caja: a unique (caja) cannot be added, and nothing moves
        val caja = createRecord(token, target, mapOf("nombre" to "Caja 1")).expectBody(Map::class.java).returnResult().responseBody!!["id"]
        createRecord(token, name, mapOf("sistema_origen" to "A", "referencia_externa" to "1", "caja" to caja)).expectStatus().isCreated
        createRecord(token, name, mapOf("sistema_origen" to "A", "referencia_externa" to "2", "caja" to caja)).expectStatus().isCreated
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Otra", "uniqueConstraints" to listOf(listOf("caja"))))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("uniqueConstraints")
        client
            .get()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Orden")
            .jsonPath("$.uniqueConstraints.length()")
            .isEqualTo(1)
        assertThat(uniqueColumns(table)).contains("sistema_origen,referencia_externa").noneMatch { it.endsWith("caja") }

        // the fields of a set cannot be dropped from under it, nor the relationship that owns one
        put(
            token,
            "/api/objects/$name",
            mapOf(
                "label" to "Orden",
                "uniqueConstraints" to listOf(listOf("sistema_origen", "referencia_externa"), listOf("referencia_externa", "caja"))
            )
        )
        assertThat(uniqueColumns(table)).contains("referencia_externa,caja")
        client
            .delete()
            .uri("/api/metadata/objects/$name/fields/sistema_origen")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
        client
            .delete()
            .uri("/api/relationships/${name}_caja")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        // a label-only update leaves the sets alone; [] drops them all and repeats are fine again
        put(token, "/api/objects/$name", mapOf("label" to "Orden"))
        assertThat(uniqueColumns(table)).hasSize(3)
        put(token, "/api/objects/$name", mapOf("label" to "Orden", "uniqueConstraints" to emptyList<List<String>>()))
        assertThat(uniqueColumns(table)).containsExactly("codigo")
        createRecord(token, name, mapOf("sistema_origen" to "A", "referencia_externa" to "1")).expectStatus().isCreated
        client
            .delete()
            .uri("/api/relationships/${name}_caja")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    // review round 1: the single-field unique toggle must touch only its own one-column constraint
    @Test
    fun `toggling unique on a field leaves the composite set that names it in place`() {
        val name = uniqueName("orden")
        val token = bearer()
        applyModel(token, name, uniqueName("caja"))
        val table = physicalTables(name).single()

        put(token, "/api/metadata/objects/$name/fields/sistema_origen", mapOf("unique" to true))
        put(token, "/api/metadata/objects/$name/fields/sistema_origen", mapOf("unique" to false))

        assertThat(uniqueColumns(table)).contains("sistema_origen,referencia_externa").doesNotContain("sistema_origen")
        createRecord(token, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1")).expectStatus().isCreated
        createRecord(token, name, mapOf("sistema_origen" to "SIAF", "referencia_externa" to "1"))
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("sistema_origen")
    }

    private fun createRecord(
        token: String,
        name: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun post(
        token: String,
        uri: String,
        body: Any
    ) {
        client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun put(
        token: String,
        uri: String,
        body: Any
    ) {
        client
            .put()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun physicalTables(name: String): List<String> =
        runBlocking {
            db
                .sql("SELECT physical_table FROM ${schemas.metadata}.custom_objects WHERE name = :name ORDER BY created_at")
                .bind("name", name)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        }

    // "a,b,c" per unique constraint on the table, columns in constraint order
    private fun uniqueColumns(table: String): List<String> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT string_agg(a.attname, ',' ORDER BY k.ord) AS columns
                    FROM pg_constraint c
                    JOIN pg_class t ON t.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                    WHERE n.nspname = :schema AND t.relname = :table AND c.contype = 'u'
                    GROUP BY c.oid
                    """.trimIndent()
                ).bind("schema", schemas.data)
                .bind("table", table)
                .map { row, _ -> row.get("columns", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        }
}
