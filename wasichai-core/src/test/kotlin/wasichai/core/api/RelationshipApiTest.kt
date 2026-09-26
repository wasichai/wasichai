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

class RelationshipApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    private lateinit var token: String
    private lateinit var predio: String
    private lateinit var contribuyente: String

    @BeforeEach
    fun createObjects() {
        token = bearer()
        predio = uniqueName("predio")
        contribuyente = uniqueName("contrib")
        createObject(contribuyente, "Contribuyente", "nombre")
        createObject(predio, "Predio", "codigo")
    }

    @Test
    fun `many-to-one puts the foreign key on the source and reads from both sides`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular")
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.fieldName")
            .isEqualTo("titular")

        val owner = createRecord(contribuyente, "nombre", "Ana")
        val plot =
            client
                .post()
                .uri("/api/objects/$predio/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "P-1", "titular" to owner)))
                .exchange()
                .expectStatus()
                .isCreated
                .let { idOf(it) }

        // from the plot: its one owner
        client
            .get()
            .uri("/api/objects/$predio/records/$plot/related/rel")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound

        related(predio, plot, relationshipName)
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.nombre")
            .isEqualTo("Ana")

        // from the owner: every plot pointing back
        related(contribuyente, owner, relationshipName)
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("P-1")
    }

    @Test
    fun `one-to-many puts the foreign key on the target`() {
        createRelationship("ONE_TO_MANY", contribuyente, predio, "propietario")
            .expectStatus()
            .isCreated

        val owner = createRecord(contribuyente, "nombre", "Luis")
        client
            .post()
            .uri("/api/objects/$predio/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "P-2", "propietario" to owner)))
            .exchange()
            .expectStatus()
            .isCreated

        related(contribuyente, owner, relationshipName)
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("P-2")
    }

    @Test
    fun `many-to-many links and unlinks through a join table`() {
        createRelationship("MANY_TO_MANY", predio, contribuyente, null)
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.joinTable")
            .value<String> { assertThat(it).startsWith("rel_") }

        val plot = createRecord(predio, "codigo", "P-3")
        val owner = createRecord(contribuyente, "nombre", "Marta")

        client
            .post()
            .uri("/api/objects/$predio/records/$plot/related/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("otherId" to owner))
            .exchange()
            .expectStatus()
            .isNoContent

        related(predio, plot, relationshipName).jsonPath("$.totalElements").isEqualTo(1)
        related(contribuyente, owner, relationshipName).jsonPath("$.totalElements").isEqualTo(1)

        client
            .delete()
            .uri("/api/objects/$predio/records/$plot/related/$relationshipName/$owner")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        related(predio, plot, relationshipName).jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `link is refused on a relationship that is not many-to-many`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated
        val plot = createRecord(predio, "codigo", "P-4")
        val owner = createRecord(contribuyente, "nombre", "Ines")

        client
            .post()
            .uri("/api/objects/$predio/records/$plot/related/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("otherId" to owner))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `lists the relationships an object takes part in, from either side`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated

        client
            .get()
            .uri("/api/objects/$contribuyente/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].objectName")
            .isEqualTo(predio)
            .jsonPath("$[0].many")
            .isEqualTo(true)
    }

    @Test
    fun `deleting a relationship removes the column it created`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated
        assertThat(columnExists(predio, "titular")).isTrue()

        client
            .delete()
            .uri("/api/relationships/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        assertThat(columnExists(predio, "titular")).isFalse()
    }

    @Test
    fun `renaming the labels changes what each side reads`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated

        client
            .put()
            .uri("/api/relationships/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Propietario", "inverseLabel" to "Sus predios"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Propietario")
            .jsonPath("$.inverseLabel")
            .isEqualTo("Sus predios")

        // the forward label is what the source side reads
        client
            .get()
            .uri("/api/objects/$predio/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].label")
            .isEqualTo("Propietario")

        // and the inverse is what the other one reads
        client
            .get()
            .uri("/api/objects/$contribuyente/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].label")
            .isEqualTo("Sus predios")
    }

    // a blank inverse label is no inverse label: the other side falls back to the plural
    @Test
    fun `clearing the inverse label brings back the other object's plural`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated

        client
            .put()
            .uri("/api/relationships/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("inverseLabel" to "   "))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.inverseLabel")
            .isEmpty

        client
            .get()
            .uri("/api/objects/$contribuyente/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            // the fixture declares no plural, so the object's own label is its plural
            .jsonPath("$[0].label")
            .isEqualTo("Predio")
    }

    @Test
    fun `a relationship cannot be left without a label`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated

        client
            .put()
            .uri("/api/relationships/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "  "))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("label")
    }

    // reshaping would move a column between tables and the stored links cannot follow
    @Test
    fun `a relationship cannot be reshaped, and the column stays where it was`() {
        createRelationship("MANY_TO_ONE", predio, contribuyente, "titular").expectStatus().isCreated

        client
            .put()
            .uri("/api/relationships/$relationshipName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("type" to "ONE_TO_MANY"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("type")

        assertThat(columnExists(predio, "titular")).isTrue()
        assertThat(columnExists(contribuyente, "titular")).isFalse()
    }

    @Test
    fun `editing a relationship that does not exist is a 404`() {
        client
            .put()
            .uri("/api/relationships/nosuchrelationship")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Otra"))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    private lateinit var relationshipName: String

    private fun createRelationship(
        type: String,
        source: String,
        target: String,
        fieldName: String?
    ) = run {
        relationshipName = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                buildMap {
                    put("name", relationshipName)
                    put("label", "Titular")
                    put("inverseLabel", "Predios")
                    put("type", type)
                    put("source", source)
                    put("target", target)
                    fieldName?.let { put("fieldName", it) }
                }
            ).exchange()
    }

    private fun createObject(
        name: String,
        label: String,
        field: String
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to label,
                    "fields" to listOf(mapOf("name" to field, "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        objectName: String,
        field: String,
        value: String
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf(field to value)))
            .exchange()
            .expectStatus()
            .isCreated
            .let { idOf(it) }

    private fun idOf(spec: org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec): String {
        val body =
            spec
                .expectBody()
                .returnResult()
                .responseBody!!
                .decodeToString()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private fun related(
        objectName: String,
        recordId: String,
        relationship: String
    ) = client
        .get()
        .uri("/api/objects/$objectName/records/$recordId/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun columnExists(
        objectName: String,
        column: String
    ): Boolean =
        runBlocking {
            db
                .sql(
                    """
                    SELECT 1 FROM information_schema.columns c
                    JOIN wasichai.custom_objects o ON o.physical_table = c.table_name
                    WHERE c.table_schema = 'app_data' AND o.name = :objectName AND c.column_name = :column
                    """.trimIndent()
                ).bind("objectName", objectName)
                .bind("column", column)
                .map { _, _ -> true }
                .one()
                .awaitFirstOrNull() ?: false
        }
}
