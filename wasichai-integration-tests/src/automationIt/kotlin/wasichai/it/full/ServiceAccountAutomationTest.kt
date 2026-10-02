package wasichai.it.full

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import wasichai.automation.AutomationRunner

// a service account's token subject is a real users row (ADR-043): what a module stores against the
// caller, behind a foreign key to users, takes it like a person's id
class ServiceAccountAutomationTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var runner: AutomationRunner

    @Test
    fun `a service account's write queues and runs an automation, and it keeps preferences`() {
        val admin = bearer()
        val objectName = uniqueName("orden")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Orden",
                    "fields" to listOf(mapOf("name" to "numero", "type" to "TEXT"), mapOf("name" to "revisado", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val automation = uniqueName("auto")
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to automation,
                    "label" to "Marca",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "revisado", "value" to "si"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Sistema"))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf("READ", "CREATE").map { mapOf("objectName" to objectName, "action" to it) }))
            .exchange()
            .expectStatus()
            .isOk
        val created =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to uniqueName("rentas"), "roles" to listOf(role)))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val token =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(
                    mapOf(
                        "clientId" to created.substringAfter("\"clientId\":\"").substringBefore("\""),
                        "clientSecret" to created.substringAfter("\"clientSecret\":\"").substringBefore("\"")
                    )
                ).exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sa = "Bearer " + token.substringAfter("\"token\":\"").substringBefore("\"")

        // automation_runs.user_id references users: the run is queued with the account's id
        val id =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, sa)
                .bodyValue(mapOf("attributes" to mapOf("numero" to "OC-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)
        client
            .get()
            .uri("/api/objects/$objectName/automations/$automation/runs")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].status")
            .isEqualTo("SUCCEEDED")
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, sa)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.revisado")
            .isEqualTo("si")

        // user_preferences.user_id references users too
        client
            .put()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, sa)
            .bodyValue(mapOf("locale" to "es"))
            .exchange()
            .expectStatus()
            .isOk
    }
}
