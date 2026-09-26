package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

// states and transitions only. no engine, so every assertion is about one column moving.
class WorkflowApiTest : FullAppIntegrationTest() {
    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("wfobj")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Predio",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a generated page shows the workflow once the object has one`() {
        // before: no workflow, so no panel to draw
        client
            .get()
            .uri("/api/objects/$objectName/pages/record-detail")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            // details holds the form alone until a workflow is attached
            .jsonPath("$.definition.page.children[0].children[0].children[0].children.length()")
            .isEqualTo(1)

        putWorkflow(admin, uniqueName("wf").take(30), approvalDefinition()).expectStatus().isOk

        client
            .get()
            .uri("/api/objects/$objectName/pages/record-detail")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.definition.page.children[0].children[0].children[0].children[1].type")
            .isEqualTo("WORKFLOW")
    }

    @Test
    fun `creates a workflow, reads it back, replaces it and deletes it`() {
        val name = uniqueName("wf")
        val created =
            putWorkflow(admin, name, approvalDefinition())
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.objectName")
                .isEqualTo(objectName)
                .jsonPath("$.name")
                .isEqualTo(name)
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.definition.states.length()")
                .isEqualTo(2)
                .jsonPath("$.definition.transitions[0].name")
                .isEqualTo("approve")
                .returnResult()
                .responseBody!!
                .decodeToString()

        client
            .get()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.definition.states[0].type")
            .isEqualTo("INITIAL")
            .jsonPath("$.definition.states[1].label")
            .isEqualTo("Aprobado")

        // replacing keeps the same row: one workflow per object
        putWorkflow(admin, name, approvalDefinition(withReview = true), label = "Aprobacion v2")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(created.substringAfter("\"id\":\"").substringBefore("\""))
            .jsonPath("$.label")
            .isEqualTo("Aprobacion v2")
            .jsonPath("$.definition.states.length()")
            .isEqualTo(3)

        client
            .delete()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        client
            .get()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `rejects a definition with two initial states`() {
        putWorkflow(
            admin,
            uniqueName("wf"),
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                        mapOf("name" to "new", "label" to "Nuevo", "type" to "INITIAL")
                    ),
                "transitions" to emptyList<Any>()
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `rejects a transition whose from state does not exist`() {
        putWorkflow(
            admin,
            uniqueName("wf"),
            mapOf(
                "states" to listOf(mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL")),
                "transitions" to
                    listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "ghost", "to" to "draft"))
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `rejects duplicate state names`() {
        putWorkflow(
            admin,
            uniqueName("wf"),
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                        mapOf("name" to "draft", "label" to "Otra vez", "type" to "FINAL")
                    ),
                "transitions" to emptyList<Any>()
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `rejects a transition leaving a final state`() {
        putWorkflow(
            admin,
            uniqueName("wf"),
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                        mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                    ),
                "transitions" to
                    listOf(mapOf("name" to "reopen", "label" to "Reabrir", "from" to "approved", "to" to "draft"))
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `rejects a transition naming a role that does not exist`() {
        putWorkflow(
            admin,
            uniqueName("wf"),
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                        mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                    ),
                "transitions" to
                    listOf(
                        mapOf(
                            "name" to "approve",
                            "label" to "Aprobar",
                            "from" to "draft",
                            "to" to "approved",
                            "roles" to listOf("GHOSTROLE")
                        )
                    )
            )
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `a new record starts in the initial state`() {
        putWorkflow(admin, uniqueName("wf"), approvalDefinition()).expectStatus().isOk

        val body = createRecord(admin, "R-1")
        assertThat(body).contains("\"state\":\"draft\"")

        val id = idOf(body)
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
    }

    @Test
    fun `lists the transitions leaving the current state and says why one is closed`() {
        val role = newRole()
        putWorkflow(admin, uniqueName("wf"), approvalDefinition(approveRole = role)).expectStatus().isOk
        val id = idOf(createRecord(admin, "R-2"))

        // the admin bypasses the role, so both moves out of draft are open
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
            .jsonPath("$[0].to")
            .isEqualTo("approved")
            .jsonPath("$[0].toLabel")
            .isEqualTo("Aprobado")
            .jsonPath("$[0].allowed")
            .isEqualTo(true)

        val member = newUserToken(newRoleWith(listOf("READ", "CREATE", "UPDATE")))
        client
            .get()
            .uri("/api/objects/$objectName/records/$id/transitions")
            .header(HttpHeaders.AUTHORIZATION, member)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].allowed")
            .isEqualTo(false)
            .jsonPath("$[0].reason")
            .isEqualTo("requires role $role")
            // the transition with no roles stays open
            .jsonPath("$[1].name")
            .isEqualTo("reject")
            .jsonPath("$[1].allowed")
            .isEqualTo(true)
    }

    @Test
    fun `applies a transition, and applying it again from the wrong state is a conflict`() {
        putWorkflow(admin, uniqueName("wf"), approvalDefinition()).expectStatus().isOk
        val id = idOf(createRecord(admin, "R-3"))

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

        // approved is final; the move out of draft no longer fits
        val conflict =
            client
                .post()
                .uri("/api/objects/$objectName/records/$id/transitions/approve")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(conflict).contains("approved")
    }

    @Test
    fun `a caller without the required role is refused and the record does not move`() {
        val role = newRole()
        putWorkflow(admin, uniqueName("wf"), approvalDefinition(approveRole = role)).expectStatus().isOk
        val id = idOf(createRecord(admin, "R-4"))
        val member = newUserToken(newRoleWith(listOf("READ", "CREATE", "UPDATE")))

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, member)
            .exchange()
            .expectStatus()
            .isForbidden

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
    }

    @Test
    fun `records the transition in the audit log`() {
        putWorkflow(admin, uniqueName("wf"), approvalDefinition()).expectStatus().isOk
        val id = idOf(createRecord(admin, "R-5"))

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk

        val entries =
            client
                .get()
                .uri("/api/audit?objectName=$objectName&limit=50")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(entries).contains("\"recordId\":\"$id\"").contains("\"operation\":\"UPDATE\"")
    }

    @Test
    fun `an object with no workflow reports a null state and no transitions`() {
        val body = createRecord(admin, "R-6")
        assertThat(body).contains("\"state\":null")
        val id = idOf(body)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/transitions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(0)

        client
            .get()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a record that predates the workflow keeps a null state and no transition may fire`() {
        val id = idOf(createRecord(admin, "R-7"))
        putWorkflow(admin, uniqueName("wf"), approvalDefinition()).expectStatus().isOk

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
            .jsonPath("$[0].allowed")
            .isEqualTo(false)

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `keeps the coordinates the editor placed`() {
        val placed =
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL", "x" to 40, "y" to 120),
                        mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL", "x" to 360, "y" to 120)
                    ),
                "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
            )
        putWorkflow(admin, uniqueName("wf"), placed).expectStatus().isOk

        client
            .get()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.definition.states[0].x")
            .isEqualTo(40.0)
            .jsonPath("$.definition.states[1].y")
            .isEqualTo(120.0)
    }

    @Test
    fun `a workflow saved without coordinates still reads back`() {
        putWorkflow(admin, uniqueName("wf"), approvalDefinition()).expectStatus().isOk

        client
            .get()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.definition.states[0].x")
            .isEmpty
    }

    @Test
    fun `half a position is no position`() {
        val lopsided =
            mapOf(
                "states" to
                    listOf(
                        mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL", "x" to 40),
                        mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                    ),
                "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
            )

        putWorkflow(admin, uniqueName("wf"), lopsided)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.definition.states[0].x")
            .isEmpty
    }

    // ------------------------------------------------------------------ helpers

    private fun approvalDefinition(
        approveRole: String? = null,
        withReview: Boolean = false
    ): Map<String, Any> {
        val states =
            mutableListOf(
                mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
            )
        if (withReview) states += mapOf("name" to "review", "label" to "En revision", "type" to "INTERMEDIATE")
        return mapOf(
            "states" to states,
            "transitions" to
                listOf(
                    mapOf(
                        "name" to "approve",
                        "label" to "Aprobar",
                        "from" to "draft",
                        "to" to "approved",
                        "roles" to listOfNotNull(approveRole)
                    ),
                    mapOf("name" to "reject", "label" to "Rechazar", "from" to "draft", "to" to "draft")
                )
        )
    }

    private fun putWorkflow(
        token: String,
        name: String,
        definition: Map<String, Any>,
        label: String = "Aprobacion"
    ) = client
        .put()
        .uri("/api/objects/$objectName/workflow")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("name" to name, "label" to label, "enabled" to true, "definition" to definition))
        .exchange()

    private fun createRecord(
        token: String,
        codigo: String
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun idOf(body: String): String = body.substringAfter("\"id\":\"").substringBefore("\"")

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Supervisor", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun newRoleWith(actions: List<String>): String {
        val name = newRole()
        client
            .put()
            .uri("/api/roles/$name/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) })
            ).exchange()
            .expectStatus()
            .isOk
        return name
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Member",
                    "password" to "supersecret",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
