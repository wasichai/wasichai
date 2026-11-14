package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest

// issue 164, ADR-079: an object says what its record writes leave in audit_log, a field whether its value does.
// the consumer is hneyra/curricula: a catalog rebuilt on every import, with its own history elsewhere
class AuditModeApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `NONE leaves no entry for a create, an update or a delete`() {
        val name = createObject(auditMode = "NONE")
        val id = create(name, mapOf("codigo" to "C-1"))
        update(name, id, mapOf("codigo" to "C-2"))
        delete(name, id)

        assertThat(entries(name)).isEmpty()
        history(name, id).jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `OPERATION keeps the entry, who and why, without the states`() {
        val name = createObject(auditMode = "OPERATION")
        val id = create(name, mapOf("codigo" to "C-1"))
        update(name, id, mapOf("codigo" to "C-2"))

        assertThat(entries(name)).containsExactly(Entry("CREATE", null, null), Entry("UPDATE", null, null))
        history(name, id)
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].userEmail")
            .isEqualTo(ADMIN_EMAIL)
            .jsonPath("$[0].changes.length()")
            .isEqualTo(0)
    }

    @Test
    fun `a field with audited false stays out of both states, and the entry names it`() {
        val name = createObject(fields = listOf(text("codigo"), text("contenido") + ("audited" to false)))
        val id = create(name, mapOf("codigo" to "C-1", "contenido" to "un texto largo"))
        update(name, id, mapOf("codigo" to "C-2", "contenido" to "otro texto largo"))

        val stored = entries(name)
        assertThat(stored.map { it.after }).allSatisfy { assertThat(it).doesNotContain("texto largo").contains("\"_omitted\": [\"contenido\"]") }
        assertThat(stored.last().before).doesNotContain("texto largo").contains("\"_omitted\": [\"contenido\"]")
        // the history shows what changed of the rest, and says what it left out
        history(name, id)
            .jsonPath("$[0].changes.length()")
            .isEqualTo(1)
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("codigo")
            .jsonPath("$[0].omitted[0]")
            .isEqualTo("contenido")
    }

    @Test
    fun `without the flags an object and its entries are what they were`() {
        val name = createObject()
        val id = create(name, mapOf("codigo" to "C-1"))

        client
            .get()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.auditMode")
            .doesNotExist()
            .jsonPath("$.fields[0].audited")
            .doesNotExist()
        assertThat(entries(name).single().after).contains("\"codigo\": \"C-1\"").doesNotContain("_omitted")
        history(name, id).jsonPath("$[0].omitted").doesNotExist()
    }

    @Test
    fun `the flags are answered, changed with MANAGE_METADATA and told in the admin trail`() {
        val name = createObject()
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("label" to name, "auditMode" to "operation"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.auditMode")
            .isEqualTo("OPERATION")
        client
            .put()
            .uri("/api/metadata/objects/$name/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("audited" to false))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.audited")
            .isEqualTo(false)
        // a label saved by an editor that knows nothing of the flag keeps it
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("label" to "Otra"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.auditMode")
            .isEqualTo("OPERATION")

        val trail = adminTrail()
        assertThat(trail).anySatisfy { assertThat(it).contains("\"auditMode\":\"OPERATION\"") }
        assertThat(trail).anySatisfy { assertThat(it).contains("\"audited\":false") }

        // a caller without MANAGE_METADATA changes neither
        val reader = readerToken()
        client
            .put()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, reader)
            .bodyValue(mapOf("label" to name, "auditMode" to "NONE"))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `an unknown mode is a 400 on auditMode`() {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to uniqueName("modo"), "label" to "Modo", "auditMode" to "SOMETIMES"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("auditMode")
    }

    private data class Entry(
        val operation: String,
        val before: String?,
        val after: String?
    )

    private fun text(name: String): Map<String, Any> = mapOf("name" to name, "type" to "TEXT")

    private fun createObject(
        auditMode: String? = null,
        fields: List<Map<String, Any>> = listOf(text("codigo"))
    ): String {
        val name = uniqueName("audmode")
        val body = mutableMapOf<String, Any>("name" to name, "label" to name, "fields" to fields)
        auditMode?.let { body["auditMode"] = it }
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun create(
        name: String,
        attributes: Map<String, Any?>
    ): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun update(
        name: String,
        id: String,
        attributes: Map<String, Any?>
    ) {
        client
            .put()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun delete(
        name: String,
        id: String
    ) {
        client
            .delete()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    private fun history(
        name: String,
        id: String
    ): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    // what audit_log holds for the object, oldest first
    private fun entries(name: String): List<Entry> =
        runBlocking {
            db
                .sql(
                    "SELECT operation, before_state::text AS b, after_state::text AS a FROM ${schemas.metadata}.audit_log " +
                        "WHERE object_name = :name ORDER BY occurred_at, id"
                ).bind("name", name)
                .map { row, _ -> Entry(row.get("operation", String::class.java)!!, row.get("b", String::class.java), row.get("a", String::class.java)) }
                .all()
                .collectList()
                .awaitSingle()
        }

    // the after states of the model's admin entries, as the api lists them
    private fun adminTrail(): List<String> =
        listOf("admin:object", "admin:field").flatMap { entity ->
            runBlocking {
                db
                    .sql("SELECT after_state::text AS a FROM ${schemas.metadata}.audit_log WHERE object_name = :entity AND operation = 'UPDATE'")
                    .bind("entity", entity)
                    .map { row, _ -> row.get("a", String::class.java)!!.replace(" ", "") }
                    .all()
                    .collectList()
                    .awaitSingle()
            }
        }

    private fun readerToken(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        val email = "${uniqueName("reader")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Reader", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
