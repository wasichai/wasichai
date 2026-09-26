package wasichai.it.full

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.thinking.ThinkingResponse
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.TestPropertySource
import wasichai.agent.AgentAnswer
import wasichai.agent.AgentService
import wasichai.agent.AgentToolCatalog

/**
 * The agent path, end to end, with a scripted model.
 *
 * A key is configured so Embabel's platform is in the context, but nothing here ever reaches
 * Anthropic: the platform's LlmOperations is replaced by Embabel's own scripted double, which calls
 * the tools we name and then hands back the text we name. That is enough to drive
 * [AgentService.ask] through the real agent, the real tool loop and the real security bridge, and
 * to watch the two things the HTTP contract is made of come back out: the answer, and the steps.
 */
@TestPropertySource(
    properties = [
        // not a real key, and never used: the scripted double stands in front of it
        "wasichai.agent.api-key=sk-ant-not-a-real-key",
        "embabel.agent.platform.models.anthropic.api-key=sk-ant-not-a-real-key",
        "spring.ai.anthropic.api-key=sk-ant-not-a-real-key"
    ]
)
class AgentEmbabelTest : FullAppIntegrationTest() {
    /**
     * The platform resolves LlmOperations once, at startup, and a script is single-use, so the
     * bean is a switch rather than a script: every test hangs a fresh one behind it.
     */
    class SwitchableLlmOperations : LlmOperations {
        @Volatile
        var script: ScriptedLlmOperations = ScriptedLlmOperations()
            private set

        fun fresh(): ScriptedLlmOperations {
            script = ScriptedLlmOperations()
            return script
        }

        override fun supportsThinking(options: LlmOptions): Boolean = script.supportsThinking(options)

        override fun <O> createObject(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): O = script.createObject(messages, interaction, outputClass, agentProcess, action)

        override fun <O> createObjectIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): Result<O> = script.createObjectIfPossible(messages, interaction, outputClass, agentProcess, action)

        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?
        ): O = script.doTransform(messages, interaction, outputClass, llmRequestEvent)

        override fun <O> createObjectWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): ThinkingResponse<O> = script.createObjectWithThinking(messages, interaction, outputClass, agentProcess, action)

        override fun <O> createObjectIfPossibleWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): Result<ThinkingResponse<O>> = script.createObjectIfPossibleWithThinking(messages, interaction, outputClass, agentProcess, action)

        override fun <O> doTransformWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?
        ): ThinkingResponse<O> = script.doTransformWithThinking(messages, interaction, outputClass, llmRequestEvent)

        override fun <O> doTransformWithThinkingIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?
        ): Result<ThinkingResponse<O>> = script.doTransformWithThinkingIfPossible(messages, interaction, outputClass, llmRequestEvent)
    }

    @TestConfiguration
    class ScriptedModel {
        @Bean
        @Primary
        fun switchableLlmOperations(): SwitchableLlmOperations = SwitchableLlmOperations()
    }

    @Autowired
    private lateinit var agent: AgentService

    @Autowired
    private lateinit var model: SwitchableLlmOperations

    @Autowired
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private lateinit var scripted: ScriptedLlmOperations
    private lateinit var admin: String
    private lateinit var member: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        scripted = model.fresh()
        admin = bearer()
        objectName = uniqueName("embabel")
        createObject(objectName)
        createRecord(objectName, "E-1")
        member = newReaderToken()
    }

    @Test
    fun `the assistant answers through embabel and reports the steps the model took`() {
        scripted
            .callTool(AgentToolCatalog.LIST_OBJECTS)
            .callTool(AgentToolCatalog.COUNT_RECORDS, """{"object": "$objectName"}""")
            .returnObject("There is 1 $objectName.")

        val answer = ask(admin, "How many $objectName are there?")

        assertThat(answer.answer).isEqualTo("There is 1 $objectName.")
        assertThat(answer.truncated).isFalse()
        assertThat(answer.steps.map { it.tool })
            .containsExactly(AgentToolCatalog.LIST_OBJECTS, AgentToolCatalog.COUNT_RECORDS)
        assertThat(answer.steps[0].input).isEmpty()
        assertThat(answer.steps[1].input).containsEntry("object", objectName)
        assertThat(answer.steps[1].summary).contains("1 records match")
    }

    // the one that matters. embabel plans on its own threads and calls the tools there, so the
    // caller has to survive the trip: if the security context were lost, the same tool call would
    // come back the same way for everybody, and every permission check below it would be answering
    // the wrong question.
    @Test
    fun `the tools run as the user who asked, not as the server`() {
        scripted
            .callTool(AgentToolCatalog.DESCRIBE_OBJECT, """{"object": "$objectName"}""")
            .returnObject("It has a codigo field.")
        ask(member, "What fields does $objectName have?")
        val theirs = scripted.toolCallsMade.single().result

        scripted = model.fresh()
        scripted
            .callTool(AgentToolCatalog.DESCRIBE_OBJECT, """{"object": "$objectName"}""")
            .returnObject("It has codigo and secreto.")
        ask(admin, "What fields does $objectName have?")
        val mine = scripted.toolCallsMade.single().result

        // one tool, one object, two callers, two answers
        assertThat(theirs).contains("codigo").doesNotContain("secreto")
        assertThat(mine).contains("codigo").contains("secreto")
    }

    @Test
    fun `a refusal from a tool is a step, not a failed request`() {
        scripted
            .callTool(AgentToolCatalog.QUERY_RECORDS, """{"object": "no_such_object"}""")
            .returnObject("There is no such object.")

        val answer = ask(admin, "How many widgets are there?")

        assertThat(answer.truncated).isFalse()
        assertThat(answer.steps.single().summary).startsWith("refused")
        assertThat(answer.answer).isEqualTo("There is no such object.")
    }

    @Test
    fun `an answer with no tool calls is still an answer`() {
        scripted.returnObject("I am the WASICHAI assistant.")

        val answer = ask(admin, "Who are you?")

        assertThat(answer.answer).isEqualTo("I am the WASICHAI assistant.")
        assertThat(answer.steps).isEmpty()
        assertThat(answer.truncated).isFalse()
    }

    @Test
    fun `the assistant reports itself enabled when a key is configured`() {
        val status = agent.status()
        assertThat(status.enabled).isTrue()
        assertThat(status.model).isEqualTo("claude-haiku-4-5")
    }

    // ------------------------------------------------------------------ helpers

    private fun ask(
        token: String,
        question: String
    ): AgentAnswer {
        val jwt = jwtDecoder.decode(token.removePrefix("Bearer ")).block()!!
        val context = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt))
        return runBlocking(context.asCoroutineContext()) { agent.ask(question) }
    }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Embabel target",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "secreto", "type" to "TEXT")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        target: String,
        codigo: String
    ) {
        client
            .post()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo, "secreto" to "classified")))
            .exchange()
            .expectStatus()
            .isCreated
    }

    // a reader of everything, except one field of one object
    private fun newReaderToken(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Embabel reader", "ownRecordsOnly" to false))
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
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "fields" to
                        listOf(
                            mapOf("objectName" to objectName, "fieldName" to "secreto", "read" to false, "write" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk

        val email = "${uniqueName("embabel")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Embabel member",
                    "password" to "supersecret",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
