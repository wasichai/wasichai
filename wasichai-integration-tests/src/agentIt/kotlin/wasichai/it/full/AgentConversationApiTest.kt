package wasichai.it.full

import com.embabel.agent.test.integration.ScriptedLlmOperations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode

/**
 * ADR-087 over HTTP: conversations kept per user, a question asked with the earlier exchanges, and
 * the model a user picks. The model is Embabel's scripted double, as in [AgentEmbabelTest].
 */
@TestPropertySource(
    properties = [
        "wasichai.agent.api-key=sk-ant-not-a-real-key",
        "embabel.agent.platform.models.anthropic.api-key=sk-ant-not-a-real-key",
        "spring.ai.anthropic.api-key=sk-ant-not-a-real-key",
        "wasichai.agent.models=claude-sonnet-5-5"
    ]
)
@Import(AgentEmbabelTest.ScriptedModel::class)
class AgentConversationApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var model: AgentEmbabelTest.SwitchableLlmOperations

    private lateinit var scripted: ScriptedLlmOperations
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        scripted = model.fresh()
        admin = bearer()
    }

    @Test
    fun `a conversation keeps both sides and is named by its first question`() {
        val id = create(admin)
        scripted.returnObject("Hello, I can help.")

        val exchange = send(admin, id, "Hi there, what can you do?")

        assertThat(exchange["question"]["role"].asString()).isEqualTo("USER")
        assertThat(exchange["answer"]["content"].asString()).isEqualTo("Hello, I can help.")
        assertThat(exchange["answer"]["model"].asString()).isEqualTo("claude-haiku-5-5")
        val detail = get(admin, "/api/agent/conversations/$id")
        assertThat(detail["conversation"]["title"].asString()).isEqualTo("Hi there, what can you do?")
        assertThat(detail["messages"].values().map { it["content"].asString() }).containsExactly("Hi there, what can you do?", "Hello, I can help.")
        assertThat(get(admin, "/api/agent/conversations").values().map { it["id"].asString() }).contains(id)
    }

    @Test
    fun `a follow-up is asked with the earlier exchange`() {
        val id = create(admin)
        scripted.returnObject("There are 3 parcels.")
        send(admin, id, "How many parcels are there?")

        scripted = model.fresh()
        scripted.returnObject("All 3 are active.")
        send(admin, id, "Are they active?")

        val prompt = scripted.promptsReceived.single()
        assertThat(prompt).contains("How many parcels are there?").contains("There are 3 parcels.").contains("Are they active?")
        assertThat(prompt.indexOf("How many parcels")).isLessThan(prompt.indexOf("Are they active?"))
    }

    @Test
    fun `someone else's conversation does not exist for you`() {
        val id = create(admin)
        val other = newUser()

        client
            .get()
            .uri("/api/agent/conversations/$id")
            .header(HttpHeaders.AUTHORIZATION, other)
            .exchange()
            .expectStatus()
            .isNotFound
        client
            .post()
            .uri("/api/agent/conversations/$id/messages")
            .header(HttpHeaders.AUTHORIZATION, other)
            .bodyValue(mapOf("question" to "peek"))
            .exchange()
            .expectStatus()
            .isNotFound
        assertThat(get(other, "/api/agent/conversations").values().map { it["id"].asString() }).doesNotContain(id)
        assertThat(scripted.promptsReceived).isEmpty()
    }

    @Test
    fun `an archived conversation is gone from the list and from reach`() {
        val id = create(admin)

        client
            .delete()
            .uri("/api/agent/conversations/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        assertThat(get(admin, "/api/agent/conversations").values().map { it["id"].asString() }).doesNotContain(id)
        client
            .get()
            .uri("/api/agent/conversations/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a failed run keeps nothing`() {
        val id = create(admin)

        client
            .post()
            .uri("/api/agent/conversations/$id/messages")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("question" to "  "))
            .exchange()
            .expectStatus()
            .isBadRequest

        assertThat(get(admin, "/api/agent/conversations/$id")["messages"].size()).isZero()
    }

    @Test
    fun `a user picks a model from the app's list, and only from it`() {
        val user = newUser()
        val settings = get(user, "/api/agent/settings")
        assertThat(settings["model"].asString()).isEqualTo("claude-haiku-5-5")
        assertThat(settings["models"].values().map { it.asString() }).containsExactly("claude-haiku-5-5", "claude-sonnet-5-5")

        client
            .put()
            .uri("/api/agent/settings")
            .header(HttpHeaders.AUTHORIZATION, user)
            .bodyValue(mapOf("model" to "claude-opus-5-5"))
            .exchange()
            .expectStatus()
            .isBadRequest
        client
            .put()
            .uri("/api/agent/settings")
            .header(HttpHeaders.AUTHORIZATION, user)
            .bodyValue(mapOf("model" to "claude-sonnet-5-5"))
            .exchange()
            .expectStatus()
            .isOk

        assertThat(get(user, "/api/agent/status")["model"].asString()).isEqualTo("claude-sonnet-5-5")
        val id = create(user)
        scripted.returnObject("ok")
        assertThat(send(user, id, "hello")["answer"]["model"].asString()).isEqualTo("claude-sonnet-5-5")
        // the others keep the default
        assertThat(get(admin, "/api/agent/status")["model"].asString()).isEqualTo("claude-haiku-5-5")
    }

    // ------------------------------------------------------------------ helpers

    private fun create(token: String): String =
        client
            .post()
            .uri("/api/agent/conversations")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(emptyMap<String, Any>())
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(JsonNode::class.java)
            .returnResult()
            .responseBody!!["id"]
            .asString()

    private fun send(
        token: String,
        id: String,
        question: String
    ): JsonNode =
        client
            .post()
            .uri("/api/agent/conversations/$id/messages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("question" to question))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(JsonNode::class.java)
            .returnResult()
            .responseBody!!

    private fun get(
        token: String,
        uri: String
    ): JsonNode =
        client
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(JsonNode::class.java)
            .returnResult()
            .responseBody!!

    // a reader of everything: enough to ask
    private fun newUser(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Conversation reader", "ownRecordsOnly" to false))
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
        val email = "${uniqueName("conv")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Conversation member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
