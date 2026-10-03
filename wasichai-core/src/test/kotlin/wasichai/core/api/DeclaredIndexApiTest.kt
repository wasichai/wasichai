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
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.metadata.DeclaredIndexReconciler
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 21: declared indexes on every organization's table, relations indexed, and a filtered list
// that actually uses them
class DeclaredIndexApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var transactions: ReactiveTransactionManager

    @Autowired
    private lateinit var reconciler: DeclaredIndexReconciler

    // the same model an app applies to every tenant: a target, and an object with an indexed field,
    // a composite index and a MANY_TO_ONE relationship
    private fun applyModel(
        token: String,
        name: String,
        target: String
    ) {
        post(token, "/api/objects", mapOf("name" to target, "label" to "Contribuyente", "fields" to listOf(mapOf("name" to "nombre", "type" to "TEXT"))))
        post(
            token,
            "/api/objects",
            mapOf(
                "name" to name,
                "label" to "Cuota",
                "fields" to
                    listOf(
                        mapOf("name" to "anio", "type" to "INTEGER"),
                        mapOf("name" to "mes", "type" to "INTEGER"),
                        mapOf("name" to "codigo", "type" to "TEXT", "indexed" to true)
                    ),
                "indexes" to listOf(listOf("anio", "mes"))
            )
        )
        post(
            token,
            "/api/relationships",
            mapOf("name" to "${name}_titular", "label" to "Titular", "type" to "MANY_TO_ONE", "source" to name, "target" to target, "fieldName" to "titular")
        )
    }

    @Test
    fun `a declared field, a field set and a relation are indexed in a second organization too, and applying again changes nothing`() {
        val name = uniqueName("cuota")
        val target = uniqueName("contrib")
        val demo = bearer()
        applyModel(demo, name, target)

        // a tenant provisioned after the model was first applied
        val slug = "idx-" + uniqueName("").take(8)
        post(
            demo,
            "/api/organizations",
            mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret")
        )
        val other = bearer("$slug@wasichai.local", "supersecret")
        applyModel(other, name, target)

        val tables = physicalTables(name)
        assertThat(tables).hasSize(2)
        tables.forEach { table ->
            assertThat(indexColumns(table)).describedAs(table).contains("(codigo)", "(anio, mes)", "(titular)")
        }

        // the definition says what was declared
        client
            .get()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, other)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.indexes[0][0]")
            .isEqualTo("anio")
            .jsonPath("$.indexes[0][1]")
            .isEqualTo("mes")
            .jsonPath("$.fields[?(@.name == 'codigo')].indexed")
            .isEqualTo(true)

        // applying the same metadata again is a no-op
        val before = indexDefinitions(tables.last())
        put(other, "/api/objects/$name", mapOf("label" to "Cuota", "indexes" to listOf(listOf("anio", "mes"))))
        put(other, "/api/metadata/objects/$name/fields/codigo", mapOf("indexed" to true))
        assertThat(reconciler.reconcileBlocking()).isZero()
        assertThat(indexDefinitions(tables.last())).isEqualTo(before)
    }

    @Test
    fun `an EXPLAIN of a list filtered on the field set uses its index`() {
        val name = uniqueName("cuota")
        val token = bearer()
        applyModel(token, name, uniqueName("contrib"))
        val table = physicalTables(name).single()
        val org = organizationOf(name)

        // 6000 rows over 10 years x 12 months, so the set is selective and the stats say so
        runBlocking {
            db
                .sql(
                    "INSERT INTO ${schemas.dataTable(table)} (organization_id, anio, mes, codigo) " +
                        "SELECT :org, 2016 + g % 10, 1 + g % 12, 'C-' || g FROM generate_series(1, 6000) g"
                ).bind("org", org)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
            db
                .sql("ANALYZE ${schemas.dataTable(table)}")
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }

        // the list query as the store writes it for ?anio=2020&mes=5. seq scans off inside this one
        // transaction only, so the test does not hinge on how the planner weighs a small table
        val plan =
            runBlocking {
                TransactionalOperator.create(transactions).executeAndAwait {
                    db
                        .sql("SET LOCAL enable_seqscan = off")
                        .fetch()
                        .rowsUpdated()
                        .awaitFirstOrNull()
                    db
                        .sql(
                            "EXPLAIN SELECT id FROM ${schemas.dataTable(table)} " +
                                "WHERE organization_id = :organizationId AND \"anio\" = :f0 AND \"mes\" = :f1 " +
                                "ORDER BY created_at ASC, id ASC LIMIT 26"
                        ).bind("organizationId", org)
                        .bind("f0", 2020L)
                        .bind("f1", 5L)
                        .map { row, _ -> row.get(0, String::class.java)!! }
                        .all()
                        .asFlow()
                        .toList()
                }
            }
        val setIndex = indexNames(table).single { it.second.endsWith("(anio, mes)") }.first

        assertThat(plan.joinToString("\n")).contains(setIndex)

        // and the list itself answers through the api
        client
            .get()
            .uri("/api/objects/$name/records?anio=2020&mes=5&count=false")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content.length()")
            .isEqualTo(25)
            .jsonPath("$.totalElements")
            .isEmpty
    }

    @Test
    fun `startup reconciliation builds a relation index a table lacks`() {
        val name = uniqueName("cuota")
        applyModel(bearer(), name, uniqueName("contrib"))
        val table = physicalTables(name).single()
        // a table built before declared indexes: its relation column has no index
        val relationIndex = indexNames(table).single { it.second.endsWith("(titular)") }.first
        runBlocking {
            db
                .sql("DROP INDEX ${schemas.data}.\"$relationIndex\"")
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }
        assertThat(indexColumns(table)).doesNotContain("(titular)")

        assertThat(reconciler.reconcileBlocking()).isEqualTo(1)

        assertThat(indexColumns(table)).contains("(titular)")
    }

    @Test
    fun `indexes change with the metadata and refuse what cannot be indexed`() {
        val name = uniqueName("cuota")
        val token = bearer()
        applyModel(token, name, uniqueName("contrib"))
        val table = physicalTables(name).single()

        // a field in a set cannot be dropped from under it
        client
            .delete()
            .uri("/api/metadata/objects/$name/fields/mes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        // unknown fields and long text are refused, naming the property
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Cuota", "indexes" to listOf(listOf("anio", "nope"))))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("indexes")
        client
            .post()
            .uri("/api/metadata/objects/$name/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "notas", "type" to "LONG_TEXT", "indexed" to true))
            .exchange()
            .expectStatus()
            .isBadRequest

        // replacing the sets drops the old index and builds the new one; un-indexing a field drops its own
        put(token, "/api/objects/$name", mapOf("label" to "Cuota", "indexes" to listOf(listOf("mes", "anio"))))
        put(token, "/api/metadata/objects/$name/fields/codigo", mapOf("indexed" to false))
        assertThat(indexColumns(table)).contains("(mes, anio)", "(titular)").doesNotContain("(anio, mes)", "(codigo)")

        // a field added as indexed gets its index at once; a label-only update leaves the sets alone
        post(token, "/api/metadata/objects/$name/fields", mapOf("name" to "zona", "type" to "TEXT", "indexed" to true))
        put(token, "/api/objects/$name", mapOf("label" to "Cuota mensual"))
        assertThat(indexColumns(table)).contains("(zona)", "(mes, anio)")

        put(token, "/api/objects/$name", mapOf("label" to "Cuota", "indexes" to emptyList<List<String>>()))
        assertThat(indexColumns(table)).doesNotContain("(mes, anio)")
    }

    // review round 1: a relation column a composite index names cannot go with its relationship either
    @Test
    fun `a relationship whose column a composite index names cannot be deleted`() {
        val name = uniqueName("cuota")
        val token = bearer()
        applyModel(token, name, uniqueName("contrib"))
        val table = physicalTables(name).single()
        put(token, "/api/objects/$name", mapOf("label" to "Cuota", "indexes" to listOf(listOf("anio", "titular"))))

        client
            .delete()
            .uri("/api/relationships/${name}_titular")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
        assertThat(indexColumns(table)).contains("(anio, titular)", "(titular)")

        put(token, "/api/objects/$name", mapOf("label" to "Cuota", "indexes" to emptyList<List<String>>()))
        client
            .delete()
            .uri("/api/relationships/${name}_titular")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    private fun DeclaredIndexReconciler.reconcileBlocking(): Int = runBlocking { reconcile() }

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

    private fun organizationOf(name: String): UUID =
        runBlocking {
            db
                .sql("SELECT organization_id FROM ${schemas.metadata}.custom_objects WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("organization_id", UUID::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    // (index name, definition)
    private fun indexNames(table: String): List<Pair<String, String>> =
        runBlocking {
            db
                .sql("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = :schema AND tablename = :table")
                .bind("schema", schemas.data)
                .bind("table", table)
                .map { row, _ -> row.get("indexname", String::class.java)!! to row.get("indexdef", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        }

    private fun indexDefinitions(table: String): Set<String> = indexNames(table).map { it.second }.toSet()

    // "(a, b)" per btree index: what each index covers, quotes gone
    private fun indexColumns(table: String): List<String> = indexDefinitions(table).map { it.substringAfter("USING btree ").replace("\"", "") }
}
