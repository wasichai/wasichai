package wasichai.it.full

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

class DocumentTypeApiTest : FullAppIntegrationTest() {
    private lateinit var token: String
    private lateinit var predio: String
    private lateinit var sigla: String

    @BeforeEach
    fun createObjects() {
        token = bearer()
        predio = uniqueName("predio")
        // a sigla is unique across the whole organization, not per object: every test mints its own,
        // or the second one to run collides with the first's -- which is the rule working, not a flake
        sigla = uniqueName("s").uppercase().take(10)
        createObject(predio)
    }

    @Test
    fun `creates a type, reads it back, renames it and deletes it`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("Berlín, "), platform("today"))))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo("oficio")
            .jsonPath("$.prefix")
            .isEqualTo(sigla)
            .jsonPath("$.objectName")
            .isEqualTo(predio)

        client
            .get()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Oficio")
            // the template comes back as it went in, node for node
            .jsonPath("$.template.content[0].content[0].text")
            .isEqualTo("Berlín, ")
            .jsonPath("$.template.content[0].content[1].attrs.value")
            .isEqualTo("today")

        client
            .put()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Oficio múltiple"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Oficio múltiple")
            // a PUT that carries no template leaves the stored one alone
            .jsonPath("$.template.content[0].content[1].attrs.value")
            .isEqualTo("today")

        client
            .delete()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `an object may hold several types`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("uno")))).expectStatus().isCreated
        create("constancia", sigla + "B", "Constancia", doc(paragraph(text("dos")))).expectStatus().isCreated

        client
            .get()
            .uri("/api/objects/$predio/document-types")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
    }

    // the sigla is what makes a number name one document: the counter runs per type, so two types
    // sharing a sigla would each issue their own SGTM-2026-001
    @Test
    fun `two types cannot share a sigla`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("uno")))).expectStatus().isCreated
        create("constancia", sigla, "Constancia", doc(paragraph(text("dos"))))
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `a second type with the same name is refused`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("uno")))).expectStatus().isCreated
        create("oficio", sigla + "B", "Oficio", doc(paragraph(text("dos"))))
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `a sigla that is not a sigla is refused`() {
        create("oficio", "of-1", "Oficio", doc(paragraph(text("uno"))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Invalid prefix 'OF-1'")
    }

    @Test
    fun `a type keeping its own sigla can still be renamed`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("uno")))).expectStatus().isCreated
        client
            .put()
            .uri("/api/objects/$predio/document-types/oficio")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Otro", "prefix" to sigla))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a template naming a field the object has not got is refused`() {
        create("oficio", sigla, "Oficio", doc(paragraph(field("fantasma"))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown field 'fantasma'")
    }

    @Test
    fun `a template naming a field the object does have is accepted, however deep it sits`() {
        create("oficio", sigla, "Oficio", doc(paragraph(text("El predio ")), paragraph(field("codigo"))))
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a template naming a relationship the object has not got is refused`() {
        create("oficio", sigla, "Oficio", doc(related("fantasma")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown relationship 'fantasma'")
    }

    @Test
    fun `a platform value nobody defines is refused`() {
        create("oficio", sigla, "Oficio", doc(paragraph(platform("manana"))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown value 'manana'")
    }

    @Test
    fun `a field node naming nothing at all is refused`() {
        create("oficio", sigla, "Oficio", doc(paragraph(mapOf("type" to "objectField"))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A objectField names no field")
    }

    @Test
    fun `a template whose root is not a doc is refused`() {
        create("oficio", sigla, "Oficio", paragraph(text("suelto")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The template root is 'paragraph'")
    }

    // ---- fixtures ----

    private fun doc(vararg content: Map<String, Any?>): Map<String, Any?> = mapOf("type" to "doc", "content" to content.toList())

    private fun paragraph(vararg content: Map<String, Any?>): Map<String, Any?> = mapOf("type" to "paragraph", "content" to content.toList())

    private fun text(value: String): Map<String, Any?> = mapOf("type" to "text", "text" to value)

    private fun field(name: String): Map<String, Any?> = mapOf("type" to "objectField", "attrs" to mapOf("field" to name))

    private fun related(name: String): Map<String, Any?> = mapOf("type" to "relatedTable", "attrs" to mapOf("relationship" to name))

    private fun platform(value: String): Map<String, Any?> = mapOf("type" to "platformValue", "attrs" to mapOf("value" to value))

    private fun create(
        name: String,
        prefix: String,
        label: String,
        template: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$predio/document-types")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "prefix" to prefix, "label" to label, "template" to template))
            .exchange()

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "pluralLabel" to "Predios",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "direccion", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }
}
