package wasichai.it.slice.forms

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest

class FormsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("forms")

    @Test
    fun `an object gets a generated default form, and a stored one round-trips`() {
        client
            .get()
            .uri("/api/objects/$objectName/forms")
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

        val name = uniqueName("alta")
        client
            .post()
            .uri("/api/objects/$objectName/forms")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Alta",
                    "definition" to mapOf("sections" to listOf(mapOf("title" to "Datos", "fields" to listOf("codigo"))))
                )
            ).exchange()
            .expectStatus()
            .isCreated

        client
            .get()
            .uri("/api/objects/$objectName/forms/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
    }

    @Test
    fun `forms owns its metadata route`() {
        client
            .get()
            .uri("/api/metadata/objects/$objectName/forms")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }
}
