package wasichai.it.full

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource

// no key on purpose: the developer machine may well have ANTHROPIC_API_KEY exported, and this
// test is about the server that does not.
@TestPropertySource(properties = ["wasichai.agent.api-key="])
class AgentApiTest : FullAppIntegrationTest() {
    @Test
    fun `status reports the agent disabled when no key is configured`() {
        client
            .get()
            .uri("/api/agent/status")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)
            .jsonPath("$.model")
            .isEqualTo("claude-haiku-4-5")
    }

    @Test
    fun `asking without a key fails with a clear message, not a 500`() {
        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(mapOf("question" to "How many parcels are there?"))
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { detail ->
                assert(detail.contains("ANTHROPIC_API_KEY")) { "expected a configuration hint, got: $detail" }
            }
    }

    @Test
    fun `a role granted READ on a single object may use the assistant`() {
        val admin = bearer()
        val objectName = uniqueName("assistobj")
        createObject(admin, objectName)
        val role = newRole(admin)
        grantOn(admin, role, objectName)
        val member = memberToken(admin, role)

        // the permission gate runs before the availability check, so getting past it shows as the
        // 503 for the missing key rather than a 403. a tenant-wide READ is not required.
        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, member)
            .bodyValue(mapOf("question" to "What can I see?"))
            .exchange()
            .expectStatus()
            .isEqualTo(503)
    }

    @Test
    fun `a role that may read nothing is refused before the model is consulted`() {
        val admin = bearer()
        val role = newRole(admin)
        val member = memberToken(admin, role)

        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, member)
            .bodyValue(mapOf("question" to "What can I see?"))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    private fun createObject(
        admin: String,
        name: String
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Assist",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun newRole(admin: String): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Assistant user", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun grantOn(
        admin: String,
        role: String,
        objectName: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf("permissions" to listOf(mapOf("objectName" to objectName, "action" to "READ", "allowed" to true)))
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun memberToken(
        admin: String,
        role: String
    ): String {
        val email = "${uniqueName("assist")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Assistant user",
                    "password" to "assistant123",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "assistant123")
    }

    @Test
    fun `a blank question is rejected before anything else`() {
        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(mapOf("question" to "   "))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("question")
    }

    @Test
    fun `the agent endpoints need a token`() {
        client
            .get()
            .uri("/api/agent/status")
            .exchange()
            .expectStatus()
            .isUnauthorized

        client
            .post()
            .uri("/api/agent/ask")
            .bodyValue(mapOf("question" to "hello"))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
