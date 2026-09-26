package wasichai.it.full

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

class ViewApiTest : FullAppIntegrationTest() {
    private lateinit var token: String
    private lateinit var predio: String

    @BeforeEach
    fun createObjects() {
        token = bearer()
        predio = uniqueName("predio")
        createObject(predio)
    }

    @Test
    fun `an object with no stored view gets a generated default`() {
        client
            .get()
            .uri("/api/objects/$predio/views")
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
            .jsonPath("$[0].isDefault")
            .isEqualTo(true)
            .jsonPath("$[0].label")
            .isEqualTo("Predios")
            .jsonPath("$[0].objectName")
            .isEqualTo(predio)
            // oculto is invisible, so it stays out
            .jsonPath("$[0].definition.columns.length()")
            .isEqualTo(3)
            .jsonPath("$[0].definition.columns[0]")
            .isEqualTo("codigo")
            .jsonPath("$[0].definition.columns[2]")
            .isEqualTo("uso")
            .jsonPath("$[0].definition.pageSize")
            .isEqualTo(25)
            .jsonPath("$[0].definition.sort")
            .doesNotExist()
    }

    @Test
    fun `a stored view is listed and resolvable by name`() {
        val name = uniqueName("vista")
        createView(name, listOf("codigo", "direccion"), mapOf("uso" to "COMERCIAL"), mapOf("field" to "codigo", "direction" to "ASC"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.name")
            .isEqualTo(name)

        client
            .get()
            .uri("/api/objects/$predio/views")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo(name)
            .jsonPath("$[0].generated")
            .isEqualTo(false)

        view(name)
            .jsonPath("$.label")
            .isEqualTo("Predios comerciales")
            .jsonPath("$.definition.columns[1]")
            .isEqualTo("direccion")
            .jsonPath("$.definition.filters.uso")
            .isEqualTo("COMERCIAL")
            .jsonPath("$.definition.sort.field")
            .isEqualTo("codigo")
            .jsonPath("$.definition.sort.direction")
            .isEqualTo("ASC")

        // and the metadata endpoint reports the stored one
        client
            .get()
            .uri("/api/metadata/objects/$predio/views")
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
    fun `a column naming an unknown field is refused`() {
        createView(uniqueName("vista"), listOf("codigo", "nope"), emptyMap(), null)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a filter on an unknown field is refused`() {
        createView(uniqueName("vista"), listOf("codigo"), mapOf("nope" to "1"), null)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an empty page size is refused`() {
        createView(uniqueName("vista"), listOf("codigo"), emptyMap(), null, pageSize = 0)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `sorting by an unknown field is refused`() {
        createView(uniqueName("vista"), listOf("codigo"), emptyMap(), mapOf("field" to "nope", "direction" to "DESC"))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `sorting by a system column is accepted`() {
        createView(uniqueName("vista"), listOf("codigo"), emptyMap(), mapOf("field" to "created_at", "direction" to "DESC"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.definition.sort.field")
            .isEqualTo("created_at")
            .jsonPath("$.definition.sort.direction")
            .isEqualTo("DESC")
    }

    @Test
    fun `a second view with the same name is refused`() {
        val name = uniqueName("vista")
        createView(name, listOf("codigo"), emptyMap(), null).expectStatus().isCreated
        createView(name, listOf("direccion"), emptyMap(), null).expectStatus().isEqualTo(409)
    }

    @Test
    fun `marking a second view default clears the first one`() {
        val first = uniqueName("vista")
        val second = uniqueName("vista")
        createView(first, listOf("codigo"), emptyMap(), null, isDefault = true).expectStatus().isCreated
        createView(second, listOf("direccion"), emptyMap(), null, isDefault = true).expectStatus().isCreated

        view(first).jsonPath("$.isDefault").isEqualTo(false)
        view(second).jsonPath("$.isDefault").isEqualTo(true)

        // "default" resolves to the flagged one, not to a generated view
        view("default")
            .jsonPath("$.name")
            .isEqualTo(second)
            .jsonPath("$.generated")
            .isEqualTo(false)

        // and the flag moves back on update
        client
            .put()
            .uri("/api/objects/$predio/views/$first")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("isDefault" to true))
            .exchange()
            .expectStatus()
            .isOk

        view(first).jsonPath("$.isDefault").isEqualTo(true)
        view(second).jsonPath("$.isDefault").isEqualTo(false)
    }

    @Test
    fun `deleting the stored view falls back to the generated default`() {
        val name = uniqueName("vista")
        createView(name, listOf("codigo"), emptyMap(), null).expectStatus().isCreated

        client
            .delete()
            .uri("/api/objects/$predio/views/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        client
            .get()
            .uri("/api/objects/$predio/views")
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
    }

    @Test
    fun `an unknown view name is a 404`() {
        client
            .get()
            .uri("/api/objects/$predio/views/nope")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    private fun view(name: String) =
        client
            .get()
            .uri("/api/objects/$predio/views/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun createView(
        name: String,
        columns: List<String>,
        filters: Map<String, String>,
        sort: Map<String, String>?,
        pageSize: Int = 25,
        isDefault: Boolean = false
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$predio/views")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predios comerciales",
                    "isDefault" to isDefault,
                    "definition" to
                        buildMap {
                            put("columns", columns)
                            put("filters", filters)
                            put("pageSize", pageSize)
                            sort?.let { put("sort", it) }
                        }
                )
            ).exchange()

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
                            mapOf("name" to "oculto", "type" to "TEXT", "visible" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }
}
