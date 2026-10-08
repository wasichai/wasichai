package wasichai.it.full

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.automation.AutomationRunner

// trigger -> conditions -> actions, end to end. the queue is drained by hand here: the background
// drain is off in tests so nothing races these assertions.
class AutomationApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var runner: AutomationRunner

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("autoobj")
        createObject(objectName)
    }

    @Test
    fun `creates, reads back, renames and deletes an automation`() {
        val name = uniqueName("auto")
        save(name, definition(field = "revisado", value = "si"))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.objectName")
            .isEqualTo(objectName)
            .jsonPath("$.name")
            .isEqualTo(name)
            .jsonPath("$.definition.trigger.type")
            .isEqualTo("RECORD_CREATED")
            .jsonPath("$.definition.actions[0].type")
            .isEqualTo("UPDATE_FIELD")

        client
            .get()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)

        val renamed = uniqueName("auto")
        client
            .put()
            .uri("/api/objects/$objectName/automations/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to renamed, "label" to "Marca revisado", "enabled" to false, "definition" to definition(field = "revisado", value = "no")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(renamed)
            .jsonPath("$.enabled")
            .isEqualTo(false)

        client
            .delete()
            .uri("/api/objects/$objectName/automations/$renamed")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.NO_CONTENT)

        client
            .get()
            .uri("/api/objects/$objectName/automations/$renamed")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a definition that could never work is refused when it is saved`() {
        // unknown trigger
        save(uniqueName("auto"), definition().plus("trigger" to mapOf("type" to "WHENEVER")))
            .expectStatus()
            .isBadRequest
        // condition on a field the object does not have
        save(
            uniqueName("auto"),
            definition().plus("conditions" to listOf(mapOf("field" to "inexistente", "operator" to "EQUALS", "value" to "x")))
        ).expectStatus()
            .isBadRequest
        // nothing to do
        save(uniqueName("auto"), definition().plus("actions" to emptyList<Any>()))
            .expectStatus()
            .isBadRequest
        // a webhook pointing back at us is a request forgery, not an integration
        save(
            uniqueName("auto"),
            definition().plus("actions" to listOf(mapOf("type" to "WEBHOOK", "url" to "http://localhost:9999/hook")))
        ).expectStatus()
            .isBadRequest
        // a field the object exposes as read only would be dropped in silence by the record store
        save(
            uniqueName("auto"),
            definition().plus("actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "bloqueado", "value" to "x")))
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `a matching automation writes the field, and says so in its run`() {
        val name = uniqueName("auto")
        save(
            name,
            definition(field = "revisado", value = "{{uso}} de {{area}} m2")
                .plus("conditions" to listOf(mapOf("field" to "area", "operator" to "GREATER_THAN", "value" to "1000")))
        ).expectStatus().isCreated

        val id = createRecord("A-1", "comercial", 1500)
        assertThat(drain()).isEqualTo(1)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.revisado")
            .isEqualTo("comercial de 1500 m2")

        runsOf(name)
            .jsonPath("$[0].status")
            .isEqualTo("SUCCEEDED")
            .jsonPath("$[0].trigger")
            .isEqualTo("RECORD_CREATED")
            .jsonPath("$[0].steps[0].action")
            .isEqualTo("UPDATE_FIELD")
            .jsonPath("$[0].steps[0].detail")
            .value<String> { assertThat(it).contains("revisado", "comercial de 1500 m2") }
    }

    @Test
    fun `an automation whose conditions do not hold leaves a run that says why`() {
        val name = uniqueName("auto")
        save(
            name,
            definition(field = "revisado", value = "si")
                .plus("conditions" to listOf(mapOf("field" to "area", "operator" to "GREATER_THAN", "value" to "1000")))
        ).expectStatus().isCreated

        val id = createRecord("A-2", "residencial", 50)
        // nothing to drain: the run was decided when the change landed
        assertThat(drain()).isZero()

        runsOf(name)
            .jsonPath("$[0].status")
            .isEqualTo("SKIPPED")
            .jsonPath("$[0].error")
            .value<String> { assertThat(it).contains("area", "GREATER_THAN") }
            .jsonPath("$[0].recordId")
            .isEqualTo(id)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.revisado")
            .doesNotExist()
    }

    @Test
    fun `an automation creates a record on another object`() {
        val target = uniqueName("autolog")
        createObject(target)
        val name = uniqueName("auto")
        save(
            name,
            mapOf(
                "trigger" to mapOf("type" to "RECORD_CREATED"),
                "actions" to
                    listOf(
                        mapOf(
                            "type" to "CREATE_RECORD",
                            "targetObject" to target,
                            "values" to mapOf("codigo" to "LOG-{{codigo}}", "uso" to "{{uso}}")
                        )
                    )
            )
        ).expectStatus().isCreated

        createRecord("A-3", "comercial", 200)
        assertThat(drain()).isEqualTo(1)

        client
            .get()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("LOG-A-3")
    }

    @Test
    fun `two automations feeding each other stop at the depth limit`() {
        // ping writes uso, pong writes revisado, both on update. without a cap this never ends.
        save(
            uniqueName("auto"),
            mapOf(
                "trigger" to mapOf("type" to "RECORD_UPDATED"),
                "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "uso", "value" to "ping"))
            )
        ).expectStatus().isCreated
        save(
            uniqueName("auto"),
            mapOf(
                "trigger" to mapOf("type" to "RECORD_UPDATED"),
                "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "revisado", "value" to "pong"))
            )
        ).expectStatus().isCreated

        val id = createRecord("A-4", "residencial", 10)
        updateRecord(id, "A-4", "comercial", 10)

        var drained = 0
        var rounds = 0
        while (rounds++ < 20) {
            val taken = drain()
            if (taken == 0) break
            drained += taken
        }
        assertThat(rounds).isLessThan(20)

        val skipped =
            client
                .get()
                .uri("/api/automation-runs?limit=200")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(skipped).contains("chain reached depth")
    }

    @Test
    fun `a transition fires the automation watching it`() {
        val workflow = uniqueName("wf").take(30)
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to workflow,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "borrador", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "aprobado", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "borrador", "to" to "aprobado"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val name = uniqueName("auto")
        save(
            name,
            mapOf(
                "trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"),
                "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "revisado", "value" to "aprobado el {{today}}"))
            )
        ).expectStatus().isCreated

        val id = createRecord("A-5", "comercial", 900)
        // the create itself matches nothing: this automation only watches the state
        assertThat(drain()).isZero()

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
        assertThat(drain()).isEqualTo(1)

        runsOf(name)
            .jsonPath("$[0].status")
            .isEqualTo("SUCCEEDED")
            .jsonPath("$[0].trigger")
            .isEqualTo("STATE_ENTERED")
    }

    // ADR-060: NOTIFY on the same STATE_ENTERED machinery, served by the notifications module of the full app.
    // {{user}} is whoever moved the record: here the admin, who then finds it in their inbox
    @Test
    fun `a record reaching a workflow state notifies the user it names`() {
        val workflow = uniqueName("wf").take(30)
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to workflow,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "borrador", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "aprobado", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "borrador", "to" to "aprobado"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val name = uniqueName("auto")
        save(
            name,
            mapOf(
                "trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"),
                "actions" to listOf(mapOf("type" to "NOTIFY", "to" to "{{user}}", "title" to "Predio {{codigo}} aprobado", "body" to "Uso {{uso}}"))
            )
        ).expectStatus().isCreated

        val id = createRecord("A-9", "comercial", 900)
        assertThat(drain()).isZero()
        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
        assertThat(drain()).isEqualTo(1)

        runsOf(name)
            .jsonPath("$[0].status")
            .isEqualTo("SUCCEEDED")
            .jsonPath("$[0].steps[0].action")
            .isEqualTo("NOTIFY")
        client
            .get()
            .uri("/api/auth/me/notifications?size=100")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content[?(@.source == 'automation:$name')].title")
            .isEqualTo("Predio A-9 aprobado")
            .jsonPath("$.content[?(@.source == 'automation:$name')].body")
            .isEqualTo("Uso comercial")
            .jsonPath("$.content[?(@.source == 'automation:$name')].link.recordId")
            .isEqualTo(id)
    }

    // GENERATE_DOCUMENT is one more action on the same STATE_ENTERED machinery above -- not a new
    // mechanism, so this mirrors that test almost line for line.
    @Test
    fun `a record reaching a workflow state issues a document automatically`() {
        val workflow = uniqueName("wf").take(30)
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to workflow,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "borrador", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "aprobado", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "borrador", "to" to "aprobado"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val typeName = "oficio"
        val prefix = uniqueName("s").uppercase().take(10)
        createDocumentType(typeName, prefix)

        val name = uniqueName("auto")
        save(
            name,
            mapOf(
                "trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"),
                "actions" to listOf(mapOf("type" to "GENERATE_DOCUMENT", "documentType" to typeName))
            )
        ).expectStatus().isCreated

        val id = createRecord("A-6", "comercial", 900)
        assertThat(drain()).isZero()

        client
            .post()
            .uri("/api/objects/$objectName/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
        assertThat(drain()).isEqualTo(1)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id/documents")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].status")
            .isEqualTo("VALID")
            .jsonPath("$[0].number")
            .value<String> { assertThat(it).startsWith(prefix) }

        runsOf(name)
            .jsonPath("$[0].status")
            .isEqualTo("SUCCEEDED")
            .jsonPath("$[0].steps[0].action")
            .isEqualTo("GENERATE_DOCUMENT")
            .jsonPath("$[0].steps[0].detail")
            .value<String> { assertThat(it).contains(prefix, id) }
    }

    @Test
    fun `a GENERATE_DOCUMENT naming a type the object has not got is refused when it is saved`() {
        save(
            uniqueName("auto"),
            mapOf(
                "trigger" to mapOf("type" to "RECORD_CREATED"),
                "actions" to listOf(mapOf("type" to "GENERATE_DOCUMENT", "documentType" to "fantasma"))
            )
        ).expectStatus().isBadRequest
    }

    @Test
    fun `building automations needs MANAGE_METADATA, reading their runs needs READ`() {
        val name = uniqueName("auto")
        save(name, definition(field = "revisado", value = "si")).expectStatus().isCreated

        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to objectName, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("auto")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Reader", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        val reader = bearer(email, "supersecret")

        client
            .get()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, reader)
            .exchange()
            .expectStatus()
            .isForbidden

        // but the reader may ask why a record of theirs changed
        client
            .get()
            .uri("/api/objects/$objectName/automations/$name/runs")
            .header(HttpHeaders.AUTHORIZATION, reader)
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun definition(
        field: String = "revisado",
        value: String = "si"
    ): Map<String, Any> =
        mapOf(
            "trigger" to mapOf("type" to "RECORD_CREATED"),
            "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to field, "value" to value))
        )

    private fun save(
        name: String,
        definition: Map<String, Any>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Automatizacion", "definition" to definition))
            .exchange()

    private fun runsOf(name: String) =
        client
            .get()
            .uri("/api/objects/$objectName/automations/$name/runs")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun drain(): Int = runBlocking { runner.drainOnce(50) }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "uso", "type" to "TEXT"),
                            mapOf("name" to "area", "type" to "DECIMAL"),
                            mapOf("name" to "revisado", "type" to "TEXT"),
                            mapOf("name" to "bloqueado", "type" to "TEXT", "editable" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    // a template that names one field is enough: this test is about the automation firing it,
    // not about what a document renders
    private fun createDocumentType(
        name: String,
        prefix: String
    ) {
        client
            .post()
            .uri("/api/objects/$objectName/document-types")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "prefix" to prefix,
                    "label" to "Oficio",
                    "template" to
                        mapOf(
                            "type" to "doc",
                            "content" to
                                listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(mapOf("type" to "objectField", "attrs" to mapOf("field" to "codigo")))
                                    )
                                )
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        codigo: String,
        uso: String,
        area: Int
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo, "uso" to uso, "area" to area)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .returnResult()
            .responseBody!!
            .decodeToString()
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun updateRecord(
        id: String,
        codigo: String,
        uso: String,
        area: Int
    ) {
        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo, "uso" to uso, "area" to area)))
            .exchange()
            .expectStatus()
            .isOk
    }
}
