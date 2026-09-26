package wasichai.it.slice.views

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest

class ViewsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("views")

    @Test
    fun `an object gets a generated default view, and a stored one round-trips`() {
        client
            .get()
            .uri("/api/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
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

        val name = uniqueName("vista")
        client
            .post()
            .uri("/api/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Todos",
                    "isDefault" to false,
                    "definition" to mapOf("columns" to listOf("codigo"), "filters" to emptyMap<String, String>(), "pageSize" to 25)
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(false)

        client
            .get()
            .uri("/api/objects/$objectName/views/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
    }

    @Test
    fun `views owns its metadata route`() {
        client
            .get()
            .uri("/api/metadata/objects/$objectName/views")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }
}
