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

// creating an object was covered from day one. this is everything that comes after it.
class ObjectCrudApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var objectName: String
    private lateinit var token: String

    @BeforeEach
    fun createObject() {
        objectName = uniqueName("parcela")
        token = bearer()
        create(objectName, "Parcela", listOf(mapOf("name" to "codigo", "type" to "TEXT")))
    }

    @Test
    fun `updating an object rewrites its labels and description`() {
        client
            .put()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Parcela catastral", "pluralLabel" to "Parcelas", "description" to "Unidad de suelo"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Parcela catastral")
            .jsonPath("$.pluralLabel")
            .isEqualTo("Parcelas")
            .jsonPath("$.description")
            .isEqualTo("Unidad de suelo")
    }

    @Test
    fun `renaming an object is refused instead of being ignored`() {
        client
            .put()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "otro", "label" to "Parcela"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("name")
    }

    @Test
    fun `renaming or retyping a field is refused instead of being ignored`() {
        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "clave"))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .put()
            .uri("/api/metadata/objects/$objectName/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("type" to "INTEGER"))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a disabled object keeps its data readable but accepts no writes`() {
        val recordId = createRecord("P-1")

        client
            .put()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Parcela", "enabled" to false))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)

        // reading is the whole point of disabling instead of deleting
        client
            .get()
            .uri("/api/objects/$objectName/records/$recordId")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "P-2")))
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        client
            .delete()
            .uri("/api/objects/$objectName/records/$recordId")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `deleting an object drops its table and forgets its metadata`() {
        val table = physicalTable(objectName)
        assertThat(tableExists(table)).isTrue()

        client
            .delete()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        assertThat(tableExists(table)).isFalse()

        client
            .get()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `an object another object points at cannot be deleted, and says who points at it`() {
        val owner = uniqueName("edificio")
        create(
            owner,
            "Edificio",
            listOf(mapOf("name" to "parcela", "type" to "RELATION", "relationTarget" to objectName))
        )

        client
            .delete()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains("$owner.parcela") }

        // the table survives a refused delete
        assertThat(tableExists(physicalTable(objectName))).isTrue()
    }

    @Test
    fun `deleting an object drops the join table of its relationships`() {
        val other = uniqueName("uso")
        create(other, "Uso", listOf(mapOf("name" to "nombre", "type" to "TEXT")))

        val relationship = uniqueName("parcela_uso")
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to relationship,
                    "label" to "Usos",
                    "type" to "MANY_TO_MANY",
                    "source" to objectName,
                    "target" to other
                )
            ).exchange()
            .expectStatus()
            .isCreated

        val joinTable = joinTableOf(relationship)
        assertThat(joinTable).isNotBlank()
        assertThat(tableExists(joinTable)).isTrue()

        client
            .delete()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        // the relationship row cascades away; without this the table would outlive it
        assertThat(tableExists(joinTable)).isFalse()
    }

    @Test
    fun `a relationship owns its field, so the field cannot be deleted on its own`() {
        val other = uniqueName("zona")
        create(other, "Zona", listOf(mapOf("name" to "nombre", "type" to "TEXT")))

        val relationship = uniqueName("parcela_zona")
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to relationship,
                    "label" to "Zona",
                    "type" to "MANY_TO_ONE",
                    "source" to objectName,
                    "target" to other
                )
            ).exchange()
            .expectStatus()
            .isCreated

        val field = relationFieldName(relationship)
        client
            .delete()
            .uri("/api/metadata/objects/$objectName/fields/$field")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains(relationship) }
    }

    @Test
    fun `editing metadata needs MANAGE_METADATA`() {
        val reader = readOnlyUser()

        client
            .put()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, reader)
            .bodyValue(mapOf("label" to "Otro"))
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .delete()
            .uri("/api/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, reader)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    // a user who may read the object and nothing more
    private fun readOnlyUser(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to role, "label" to "Reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to objectName, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("reader")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("email" to email, "displayName" to "Reader", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    private fun create(
        name: String,
        label: String,
        fields: List<Map<String, Any?>>
    ) = client
        .post()
        .uri("/api/objects")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("name" to name, "label" to label, "fields" to fields))
        .exchange()
        .expectStatus()
        .isCreated

    private fun createRecord(code: String): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to code)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .returnResult()
            .let { String(it.responseBody!!) }
            .substringAfter("\"id\":\"")
            .substringBefore('"')

    private fun physicalTable(name: String): String =
        runBlocking {
            db
                .sql("SELECT physical_table FROM wasichai.custom_objects WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("physical_table", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun joinTableOf(relationship: String): String =
        runBlocking {
            db
                .sql("SELECT join_table FROM wasichai.relationships WHERE name = :name")
                .bind("name", relationship)
                .map { row, _ -> row.get("join_table", String::class.java) ?: "" }
                .one()
                .awaitFirstOrNull()
                .orEmpty()
        }

    private fun relationFieldName(relationship: String): String =
        runBlocking {
            db
                .sql(
                    """
                    SELECT f.name FROM wasichai.custom_fields f
                    JOIN wasichai.relationships r ON r.relation_field_id = f.id
                    WHERE r.name = :name
                    """.trimIndent()
                ).bind("name", relationship)
                .map { row, _ -> row.get("name", String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    private fun tableExists(table: String): Boolean =
        runBlocking {
            db
                .sql("SELECT to_regclass('app_data.' || :table) AS found")
                .bind("table", table)
                .map { row, _ -> row.get("found") != null }
                .one()
                .awaitFirstOrNull() ?: false
        }

    private fun columnExists(column: String): Boolean =
        runBlocking {
            db
                .sql(
                    """
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'app_data' AND table_name = :table AND column_name = :column
                    """.trimIndent()
                ).bind("table", physicalTable(objectName))
                .bind("column", column)
                .map { _, _ -> true }
                .one()
                .awaitFirstOrNull() ?: false
        }
}
