package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import wasichai.agent.AgentAccess
import wasichai.agent.AgentAccessPolicy
import wasichai.agent.AgentToolCatalog

/**
 * An app's AgentAccessPolicy (ADR-056) over HTTP, with a key configured so the assistant is
 * otherwise on. The policy here switches it off for one caller by e-mail; a real app would look
 * at the organization. The scripted model behind it must never hear from that caller.
 */
@TestPropertySource(
    properties = [
        "wasichai.agent.api-key=sk-ant-not-a-real-key",
        "embabel.agent.platform.models.anthropic.api-key=sk-ant-not-a-real-key",
        "spring.ai.anthropic.api-key=sk-ant-not-a-real-key"
    ]
)
@Import(AgentEmbabelTest.ScriptedModel::class, AgentAccessPolicyApiTest.OffForSome::class)
class AgentAccessPolicyApiTest : FullAppIntegrationTest() {
    @TestConfiguration
    class OffForSome {
        @Bean
        fun agentAccessPolicy(): AgentAccessPolicy =
            AgentAccessPolicy { caller ->
                if (caller.email.startsWith(OFF)) AgentAccess.Denied(REASON) else AgentAccess.Allowed
            }
    }

    @Autowired
    private lateinit var model: AgentEmbabelTest.SwitchableLlmOperations

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("policy")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to objectName, "label" to "Policy", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `status reports the assistant off for a denied caller and on for the others`() {
        val denied = readerToken(OFF + uniqueName("u"))

        status(denied).jsonPath("$.enabled").isEqualTo(false)
        status(admin).jsonPath("$.enabled").isEqualTo(true)
    }

    @Test
    fun `a denied caller's question is a 403 with the reason, and the model never hears it`() {
        val scripted = model.fresh()
        scripted.callTool(AgentToolCatalog.LIST_OBJECTS).returnObject("should never be said")
        val denied = readerToken(OFF + uniqueName("u"))

        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, denied)
            .bodyValue(mapOf("question" to "How many $objectName are there?"))
            .exchange()
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo(REASON)

        assertThat(scripted.promptsReceived).isEmpty()
        assertThat(scripted.toolCallsMade).isEmpty()
    }

    @Test
    fun `an allowed caller still gets an answer`() {
        model.fresh().returnObject("Yes.")

        client
            .post()
            .uri("/api/agent/ask")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("question" to "Are you there?"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.answer")
            .isEqualTo("Yes.")
    }

    private fun status(token: String) =
        client
            .get()
            .uri("/api/agent/status")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    // READ on everything, so the permission gate lets them through and only the policy decides
    private fun readerToken(local: String): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Policy reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "$local@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Policy reader", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    companion object {
        private const val OFF = "off"
        private const val REASON = "The AI assistant is not enabled for your organization"
    }
}
