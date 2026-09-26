package wasichai.it.slice.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.it.support.SliceSmokeTest

class PagesOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("pages", "forms")

    @Test
    fun `the generated record page is a one-region page`() {
        resolve()
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
    }

    @Test
    fun `a stored page replaces the generated one`() {
        createPage(mapOf("type" to "TEXT", "column" to 1, "content" to "hola"))
            .expectStatus()
            .isCreated
        resolve()
            .jsonPath("$.generated")
            .isEqualTo(false)
    }

    // the MAP and WORKFLOW components come from gis and workflow, which this app has not got
    @Test
    fun `a MAP or WORKFLOW component is refused without the module that draws it`() {
        listOf(
            mapOf("type" to "MAP", "column" to 1, "title" to "Ubicacion"),
            mapOf("type" to "WORKFLOW", "column" to 1)
        ).forEach { component ->
            createPage(component)
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.detail")
                .value<String> { assertThat(it).contains(component.getValue("type").toString()) }
        }
    }

    private fun resolve(): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$objectName/pages/record-detail")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun createPage(component: Map<String, Any>): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "objectName" to objectName,
                    "name" to uniqueName("page").take(30),
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    "template" to "one-region",
                    "definition" to
                        mapOf(
                            "page" to
                                mapOf(
                                    "type" to "PAGE",
                                    "children" to
                                        listOf(mapOf("type" to "REGION", "region" to "MAIN", "layout" to "single-column", "children" to listOf(component)))
                                )
                        )
                )
            ).exchange()
}
