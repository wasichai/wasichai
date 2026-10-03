package wasichai.it.full

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import wasichai.core.common.ConflictException
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// ADR-040: a transition moves a record's state, so it is an UPDATE. appendOnly refuses it for ADMIN,
// and a RecordWriteGuard sees it, with its name, before the state moves. ADR-041: requiresReason asks
// a transition for its reason too, and the transition's audit row carries it.
@Import(WorkflowWriteRulesTest.GuardConfig::class)
class WorkflowWriteRulesTest : FullAppIntegrationTest() {
    @TestConfiguration
    class GuardConfig {
        @Bean
        fun noRejects(): RecordWriteGuard =
            object : RecordWriteGuard {
                override suspend fun beforeWrite(change: RecordWrite) {
                    if (change.kind == RecordChangeKind.TRANSITIONED && change.transition == "reject") throw ConflictException("No rejects here")
                }
            }
    }

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `append-only refuses ADMIN a transition`() {
        val name = uniqueName("wfreceipt")
        createObject(name, appendOnly = true)
        putWorkflow(name)
        val id = createRecord(name)

        transition(name, id, "approve").expectStatus().isEqualTo(HttpStatus.CONFLICT)

        stateOf(name, id).isEqualTo("draft")
    }

    @Test
    fun `a guard vetoes a transition before the state moves`() {
        val name = uniqueName("wfguard")
        createObject(name, appendOnly = false)
        putWorkflow(name)
        val id = createRecord(name)

        transition(name, id, "reject").expectStatus().isEqualTo(HttpStatus.CONFLICT)
        stateOf(name, id).isEqualTo("draft")
        transition(name, id, "approve").expectStatus().isOk
        stateOf(name, id).isEqualTo("approved")
    }

    @Test
    fun `requires-reason refuses a transition without one, and its audit row carries it`() {
        val name = uniqueName("wfreason")
        createObject(name, appendOnly = false, requiresReason = true)
        putWorkflow(name)
        val id = createRecord(name, reason = "alta")

        transition(name, id, "approve")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("reason")
        stateOf(name, id).isEqualTo("draft")

        transition(name, id, "approve", "UTF-8''aprobado por gerencia %E2%9C%93").expectStatus().isOk
        stateOf(name, id).isEqualTo("approved")
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].reason")
            .isEqualTo("aprobado por gerencia \u2713")
            .jsonPath("$[1].reason")
            .isEqualTo("alta")
    }

    private fun createObject(
        name: String,
        appendOnly: Boolean,
        requiresReason: Boolean = false
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Recibo",
                    "appendOnly" to appendOnly,
                    "requiresReason" to requiresReason,
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun putWorkflow(name: String) {
        val definition =
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
        client
            .put()
            .uri("/api/objects/$name/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to uniqueName("wf"), "label" to "Aprobacion", "enabled" to true, "definition" to definition))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun createRecord(
        name: String,
        reason: String? = null
    ): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .headers { headers -> reason?.let { headers.set(ChangeReason.HEADER, it) } }
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-1")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun transition(
        name: String,
        id: String,
        transition: String,
        reason: String? = null
    ) = client
        .post()
        .uri("/api/objects/$name/records/$id/transitions/$transition")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .headers { headers -> reason?.let { headers.set(ChangeReason.HEADER, it) } }
        .exchange()

    private fun stateOf(
        name: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()
        .jsonPath("$.state")
}
