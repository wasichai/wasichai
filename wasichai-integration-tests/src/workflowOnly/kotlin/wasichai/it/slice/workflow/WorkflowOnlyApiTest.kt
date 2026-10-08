package wasichai.it.slice.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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

    // ADR-051: a transition writes the record, so If-Match holds it as on PUT, and it answers the new ETag
    @Test
    fun `a transition holds to If-Match and answers the record's etag`() {
        putWorkflow()
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
        val id = created.responseBody!!.substringAfter("\"id\":\"").substringBefore("\"")
        val stale = created.responseHeaders.eTag!!
        val current =
            client
                .put()
                .uri("/api/objects/$objectName/records/$id")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .header(HttpHeaders.IF_MATCH, stale)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "W-2")))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseHeaders
                .eTag!!

        transition(id, stale)
            .expectStatus()
            .isEqualTo(HttpStatus.PRECONDITION_FAILED)
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("If-Match")
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("draft")

        val moved =
            transition(id, current)
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
        assertThat(moved.responseBody).contains("\"state\":\"approved\"")
        assertThat(moved.responseHeaders.eTag).isNotNull().isNotEqualTo(current)
        // the state moved on: a request that fails without its precondition keeps that answer (RFC 9110 13.2.1)
        transition(id, current).expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    private fun transition(
        id: String,
        ifMatch: String
    ) = client
        .post()
        .uri("/api/objects/$objectName/records/$id/transitions/approve")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .header(HttpHeaders.IF_MATCH, ifMatch)
        .exchange()

    private fun putWorkflow() {
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
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
    }
}
