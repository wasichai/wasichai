package wasichai.it.slice.workflow

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest

class WorkflowOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("workflow")

    @Test
    fun `a record walks its workflow without pages installed`() {
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("wf").take(30),
                    "label" to "Aprobacion",
                    "enabled" to true,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to
                                listOf(
                                    mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"),
                                    mapOf("name" to "reject", "label" to "Rechazar", "from" to "draft", "to" to "draft")
                                )
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "W-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/transitions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].name")
            .isEqualTo("approve")

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(id)
            .jsonPath("$.state")
            .isEqualTo("approved")
    }

    @Test
    fun `workflow publishes the column it keeps`() {
        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')].scope")
            .isEqualTo("WORKFLOW")
    }
}
