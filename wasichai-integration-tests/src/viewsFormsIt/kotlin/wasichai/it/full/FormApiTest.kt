package wasichai.it.full

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

class FormApiTest : FullAppIntegrationTest() {
    private lateinit var token: String
    private lateinit var predio: String

    @BeforeEach
    fun createObjects() {
        token = bearer()
        predio = uniqueName("predio")
        createObject(predio)
    }

    @Test
    fun `an object with no stored form gets one nameless section with every editable field`() {
        client
            .get()
            .uri("/api/objects/$predio/forms")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].generated")
            .isEqualTo(true)
            .jsonPath("$[0].name")
            .isEqualTo("default")
            .jsonPath("$[0].label")
            .isEqualTo("Predio")
            .jsonPath("$[0].objectName")
            .isEqualTo(predio)
            .jsonPath("$[0].definition.sections.length()")
            .isEqualTo(1)
            .jsonPath("$[0].definition.sections[0].title")
            .doesNotExist()
            // calculado is read-only, so it stays out
            .jsonPath("$[0].definition.sections[0].fields.length()")
            .isEqualTo(3)
            .jsonPath("$[0].definition.sections[0].fields[0]")
            .isEqualTo("codigo")
            .jsonPath("$[0].definition.sections[0].fields[2]")
            .isEqualTo("uso")
    }

    @Test
    fun `a stored form is listed and resolvable by name`() {
        val name = uniqueName("alta")
        createForm(
            name,
            listOf(
                mapOf("title" to "Identificacion", "fields" to listOf("codigo", "direccion")),
                mapOf("title" to "Uso", "fields" to listOf("uso"))
            )
        ).expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.name")
            .isEqualTo(name)

        client
            .get()
            .uri("/api/objects/$predio/forms")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo(name)

        form(name)
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.definition.sections.length()")
            .isEqualTo(2)
            .jsonPath("$.definition.sections[0].title")
            .isEqualTo("Identificacion")
            .jsonPath("$.definition.sections[1].fields[0]")
            .isEqualTo("uso")

        // and the metadata endpoint reports the stored one
        client
            .get()
            .uri("/api/metadata/objects/$predio/forms")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo(name)
    }

    @Test
    fun `a section naming an unknown field is refused`() {
        createForm(uniqueName("alta"), listOf(mapOf("title" to "Datos", "fields" to listOf("codigo", "nope"))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a field repeated across sections is refused`() {
        createForm(
            uniqueName("alta"),
            listOf(
                mapOf("title" to "Uno", "fields" to listOf("codigo")),
                mapOf("title" to "Dos", "fields" to listOf("codigo"))
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `a read-only field on a form is refused`() {
        createForm(uniqueName("alta"), listOf(mapOf("title" to "Datos", "fields" to listOf("calculado"))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a form with no sections is refused`() {
        createForm(uniqueName("alta"), emptyList()).expectStatus().isBadRequest
    }

    @Test
    fun `a blank section title is refused`() {
        createForm(uniqueName("alta"), listOf(mapOf("title" to "  ", "fields" to listOf("codigo"))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a second form with the same name is refused`() {
        val name = uniqueName("alta")
        createForm(name, listOf(mapOf("fields" to listOf("codigo")))).expectStatus().isCreated
        createForm(name, listOf(mapOf("fields" to listOf("direccion")))).expectStatus().isEqualTo(409)
    }

    @Test
    fun `updating a form swaps its label and sections`() {
        val name = uniqueName("alta")
        createForm(name, listOf(mapOf("title" to "Uno", "fields" to listOf("codigo")))).expectStatus().isCreated

        client
            .put()
            .uri("/api/objects/$predio/forms/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "label" to "Alta rapida",
                    "definition" to mapOf("sections" to listOf(mapOf("title" to "Dos", "fields" to listOf("direccion", "uso"))))
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Alta rapida")
            .jsonPath("$.definition.sections[0].title")
            .isEqualTo("Dos")
            .jsonPath("$.definition.sections[0].fields.length()")
            .isEqualTo(2)
    }

    @Test
    fun `deleting the stored form falls back to the generated default`() {
        val name = uniqueName("alta")
        createForm(name, listOf(mapOf("fields" to listOf("codigo")))).expectStatus().isCreated

        client
            .delete()
            .uri("/api/objects/$predio/forms/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        client
            .get()
            .uri("/api/objects/$predio/forms")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].generated")
            .isEqualTo(true)
    }

    @Test
    fun `a page form component naming an unknown form is refused`() {
        createPage(uniqueName("page"), mapOf("type" to "FORM", "column" to 1, "form" to "nope"))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page form component naming both a form and fields is refused`() {
        val name = uniqueName("alta")
        createForm(name, listOf(mapOf("fields" to listOf("codigo")))).expectStatus().isCreated

        createPage(
            uniqueName("page"),
            mapOf("type" to "FORM", "column" to 1, "form" to name, "fields" to listOf("codigo"))
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page form component may point at a stored form`() {
        val formName = uniqueName("alta")
        createForm(formName, listOf(mapOf("fields" to listOf("codigo")))).expectStatus().isCreated

        createPage(uniqueName("page"), mapOf("type" to "FORM", "column" to 1, "form" to formName))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.definition.page.children[0].children[0].form")
            .isEqualTo(formName)
    }

    private fun form(name: String) =
        client
            .get()
            .uri("/api/objects/$predio/forms/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun createForm(
        name: String,
        sections: List<Map<String, Any>>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$predio/forms")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Alta de predio",
                    "definition" to mapOf("sections" to sections)
                )
            ).exchange()

    private fun createPage(
        name: String,
        component: Map<String, Any>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "objectName" to predio,
                    "name" to name,
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    "template" to "one-region",
                    "definition" to mapOf("page" to page(region("MAIN", listOf(component))))
                )
            ).exchange()

    private fun page(vararg regions: Map<String, Any>): Map<String, Any> = mapOf("type" to "PAGE", "children" to regions.toList())

    private fun region(
        name: String,
        children: List<Map<String, Any>> = emptyList(),
        layout: String = "single-column"
    ): Map<String, Any> = mapOf("type" to "REGION", "region" to name, "layout" to layout, "children" to children)

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
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "direccion", "type" to "TEXT"),
                            mapOf("name" to "uso", "type" to "TEXT"),
                            mapOf("name" to "calculado", "type" to "TEXT", "editable" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }
}
