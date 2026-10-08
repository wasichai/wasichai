package wasichai.agent

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.Usage
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.loop.MaxIterationsExceededException
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.thinking.ThinkingResponse
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleQueries
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three extension points of ADR-056, through the real agent on a real (in-memory) Embabel
 * platform. The model is Embabel's scripted double behind a recorder: it remembers every call,
 * books the tokens a provider would report, and can fail the way a provider or the tool loop does.
 * The tools are a stub that always hands back the same person, so what the filters do is all there
 * is to see.
 */
class AgentExtensionsTest {
    private val value = "Ana Pérez"
    private val pseudonym = "PERSON-1"
    private val record = """{"id":"7","attributes":{"name":"$value"}}"""

    /** Embabel's scripted model, recorded: calls counted, tokens booked, failures on demand. */
    class RecordingModel : LlmOperations {
        val script = ScriptedLlmOperations()
        val calls = AtomicInteger()

        // thrown after the call was booked, like a provider failing mid-run
        @Volatile
        var failure: RuntimeException? = null

        private fun book(process: AgentProcess) {
            calls.incrementAndGet()
            process.recordLlmInvocation(
                LlmInvocation(LlmMetadata.create(MODEL, "test"), Usage(INPUT, OUTPUT, null), "wasichai-assistant", Instant.now(), Duration.ZERO)
            )
            failure?.let { throw it }
        }

        override fun supportsThinking(options: LlmOptions): Boolean = script.supportsThinking(options)

        override fun <O> createObject(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): O {
            book(agentProcess)
            return script.createObject(messages, interaction, outputClass, agentProcess, action)
        }

        override fun <O> createObjectIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): Result<O> {
            book(agentProcess)
            return script.createObjectIfPossible(messages, interaction, outputClass, agentProcess, action)
        }

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
        ): ThinkingResponse<O> {
            book(agentProcess)
            return script.createObjectWithThinking(messages, interaction, outputClass, agentProcess, action)
        }

        override fun <O> createObjectIfPossibleWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): Result<ThinkingResponse<O>> {
            book(agentProcess)
            return script.createObjectIfPossibleWithThinking(messages, interaction, outputClass, agentProcess, action)
        }

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

    data class Heard(
        val caller: AuthenticatedUser,
        val question: String,
        val answer: AgentAnswer?,
        val usage: AgentUsage?,
        val error: Throwable?
    )

    private val model = RecordingModel()
    private val invocations = AtomicInteger()
    private val heard = CopyOnWriteArrayList<Heard>()
    private val listener = AgentRunListener { caller, question, answer, usage, error -> heard.add(Heard(caller, question, answer, usage, error)) }

    // the person, always: a tool the caller may read, with a value the organization keeps home
    private val tools: AgentTools =
        mock(AgentTools::class.java) { invocation ->
            if (invocation.method.name == "invoke") {
                invocations.incrementAndGet()
                AgentToolResult(record, "person 7: $value", false)
            } else {
                null
            }
        }

    private val pseudonymize = AgentResultFilter { _, _, _, text -> text.replace(value, pseudonym) }
    private val restore = AgentAnswerFilter { _, text -> text.replace(pseudonym, value) }

    private val properties = AgentProperties(apiKey = "not-a-real-key")

    private fun service(
        policy: AgentAccessPolicy = AgentAccessPolicy.ALLOW_ALL,
        filters: List<AgentResultFilter> = emptyList(),
        answerFilters: List<AgentAnswerFilter> = emptyList(),
        listeners: List<AgentRunListener> = listOf(listener)
    ): AgentService =
        AgentService(
            properties,
            CurrentUser(mock(RoleQueries::class.java)),
            platformOf(model),
            policy,
            filters,
            answerFilters,
            listeners
        )

    private fun <T> asCaller(
        jwt: Jwt = TestCallers.jwt(),
        block: suspend () -> T
    ): T = runBlocking(TestCallers.context(jwt)) { block() }

    private fun AgentService.askAs(question: String): AgentAnswer = asCaller { ask(question) }

    private fun scriptPersonThenAnswer(answer: String) {
        model.script
            .callTool(AgentToolCatalog.GET_RECORD, """{"object": "person", "id": "7"}""")
            .returnObject(answer)
    }

    // ------------------------------------------------------------------ result and answer filters

    @Test
    fun `the model is handed the pseudonym, never the value`() {
        scriptPersonThenAnswer("$pseudonym is on record.")

        service(filters = listOf(pseudonymize)).askAs("Who is person 7?")

        val sent =
            model.script.toolCallsMade
                .single()
                .result
        assertThat(sent).contains(pseudonym).doesNotContain(value)
    }

    @Test
    fun `the steps the UI shows obey the same filter`() {
        scriptPersonThenAnswer("$pseudonym is on record.")

        val answer = service(filters = listOf(pseudonymize)).askAs("Who is person 7?")

        assertThat(answer.steps.single().summary).isEqualTo("person 7: $pseudonym")
    }

    @Test
    fun `an answer filter puts the value back in the text the caller reads`() {
        scriptPersonThenAnswer("$pseudonym is on record.")

        val answer = service(filters = listOf(pseudonymize), answerFilters = listOf(restore)).askAs("Who is person 7?")

        assertThat(answer.answer).isEqualTo("$value is on record.")
        // the model still only ever saw the pseudonym
        assertThat(
            model.script.toolCallsMade
                .single()
                .result
        ).doesNotContain(value)
    }

    @Test
    fun `filters run in order, each on what the one before it left`() {
        scriptPersonThenAnswer("done")
        val second = AgentResultFilter { _, _, _, text -> text.replace(pseudonym, "[hidden]") }

        service(filters = listOf(pseudonymize, second)).askAs("Who is person 7?")

        assertThat(
            model.script.toolCallsMade
                .single()
                .result
        ).contains("[hidden]").doesNotContain(pseudonym).doesNotContain(value)
    }

    // the filter is told who asks, and its reactive context is that caller's: the same bridge the tools use
    @Test
    fun `filters run as the asking caller`() {
        scriptPersonThenAnswer("done")
        val seen = CopyOnWriteArrayList<String>()
        val probe =
            AgentResultFilter { caller, tool, _, text ->
                val authentication = ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                seen.add("${caller.email}|${authentication?.name}|$tool")
                text
            }
        val jwt = TestCallers.jwt(email = "asker@wasichai.local")

        asCaller(jwt) { service(filters = listOf(probe)).ask("Who is person 7?") }

        // once for the model's json, once for the summary, both as the asker
        assertThat(seen).containsOnly("asker@wasichai.local|${jwt.subject}|${AgentToolCatalog.GET_RECORD}").hasSize(2)
    }

    @Test
    fun `a filter that throws fails the run closed`() {
        model.script
            .callTool(AgentToolCatalog.GET_RECORD, """{"object": "person", "id": "7"}""")
            .callTool(AgentToolCatalog.LIST_OBJECTS)
            .returnObject("It went through anyway.")
        val broken = AgentResultFilter { _, _, _, _ -> throw IllegalStateException("cannot pseudonymize $value") }
        val restores = AtomicInteger()

        assertThatThrownBy {
            service(
                filters = listOf(broken),
                answerFilters = listOf(AgentAnswerFilter { _, text -> text.also { restores.incrementAndGet() } })
            ).askAs("Who is person 7?")
        }.isInstanceOf(AgentFilterException::class.java)
            .satisfies({ assertThat((it as WasichaiException).status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR) })
            .hasMessageNotContaining(value)

        // the result never went out, nor did the exception's text, and no later tool ran
        assertThat(model.script.toolCallsMade.map { it.result }).allSatisfy { assertThat(it).doesNotContain(value) }
        assertThat(invocations.get()).isEqualTo(1)
        // embabel retries a failed action; a closed run does not ask the model again
        assertThat(model.calls.get()).isEqualTo(1)
        assertThat(restores.get()).isZero()
        assertThat(heard.single().error).isInstanceOf(AgentFilterException::class.java)
        assertThat(heard.single().answer).isNull()
    }

    @Test
    fun `a filter's own refusal keeps its status`() {
        scriptPersonThenAnswer("done")
        val refusing = AgentResultFilter { _, _, _, _ -> throw ForbiddenException("This data does not leave the organization") }

        assertThatThrownBy { service(filters = listOf(refusing)).askAs("Who is person 7?") }
            .isInstanceOf(ForbiddenException::class.java)
            .hasMessage("This data does not leave the organization")
    }

    // ------------------------------------------------------------------ access policy

    @Test
    fun `a denied caller sees the assistant off and is refused before the model is called`() {
        scriptPersonThenAnswer("never")
        val policy = AgentAccessPolicy { caller -> AgentAccess.Denied("The assistant is off for ${caller.email}'s organization") }
        val service = service(policy = policy)

        assertThat(asCaller { service.status() }.enabled).isFalse()
        assertThatThrownBy { service.askAs("Who is person 7?") }
            .isInstanceOf(AgentDeniedException::class.java)
            .satisfies({ assertThat((it as WasichaiException).status).isEqualTo(HttpStatus.FORBIDDEN) })
            .hasMessage("The assistant is off for admin@wasichai.local's organization")

        assertThat(model.calls.get()).isZero()
        assertThat(model.script.promptsReceived).isEmpty()
        assertThat(invocations.get()).isZero()
        // a question refused before the run is not a run
        assertThat(heard).isEmpty()
    }

    @Test
    fun `the policy decides per caller`() {
        val home = TestCallers.jwt()
        val policy =
            AgentAccessPolicy { caller ->
                if (caller.organizationId.toString() == home.getClaimAsString("org")) AgentAccess.Allowed else AgentAccess.Denied("no")
            }
        val service = service(policy = policy)

        assertThat(asCaller(home) { service.status() }.enabled).isTrue()
        assertThat(asCaller(TestCallers.jwt()) { service.status() }.enabled).isFalse()
    }

    // ------------------------------------------------------------------ run listener and usage

    @Test
    fun `a listener hears one call per answered question, with the tokens it cost`() {
        scriptPersonThenAnswer("done")

        val answer = service().askAs("Who is person 7?")

        val expected = AgentUsage(MODEL, INPUT * model.calls.get(), OUTPUT * model.calls.get())
        assertThat(model.calls.get()).isPositive()
        assertThat(answer.usage).isEqualTo(expected)
        val run = heard.single()
        assertThat(run.question).isEqualTo("Who is person 7?")
        assertThat(run.caller.email).isEqualTo("admin@wasichai.local")
        assertThat(run.answer).isEqualTo(answer)
        assertThat(run.usage).isEqualTo(expected)
        assertThat(run.error).isNull()
    }

    @Test
    fun `a listener hears a truncated run, with what it spent`() {
        model.failure = MaxIterationsExceededException(20)

        val answer = service().askAs("Who is person 7?")

        assertThat(answer.truncated).isTrue()
        val run = heard.single()
        assertThat(run.answer).isEqualTo(answer)
        // embabel retries the action before it gives up: every attempt is paid for, and counted
        assertThat(run.usage).isEqualTo(AgentUsage(MODEL, INPUT * model.calls.get(), OUTPUT * model.calls.get()))
        assertThat(run.error).isNull()
    }

    @Test
    fun `a listener hears a model failure, with what it spent`() {
        model.failure = IllegalStateException("provider down")

        assertThatThrownBy { service().askAs("Who is person 7?") }.isInstanceOf(AgentUnavailableException::class.java)

        val run = heard.single()
        assertThat(run.answer).isNull()
        assertThat(run.usage).isEqualTo(AgentUsage(MODEL, INPUT * model.calls.get(), OUTPUT * model.calls.get()))
        assertThat(run.error).isInstanceOf(AgentUnavailableException::class.java)
    }

    @Test
    fun `a listener that throws fails the request rather than answer unrecorded`() {
        scriptPersonThenAnswer("done")
        val broken = AgentRunListener { _, _, _, _, _ -> throw IllegalStateException("audit log down") }

        assertThatThrownBy { service(listeners = listOf(broken)).askAs("Who is person 7?") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("audit log down")
    }

    // ------------------------------------------------------------------ nothing declared

    @Test
    fun `with no extension the answer reads as it always did`() {
        scriptPersonThenAnswer("$value is on record.")
        // the scripted model books no tokens: the platform reported none
        val plain = AgentService(properties, CurrentUser(mock(RoleQueries::class.java)), platformOf(ScriptedOnly(model.script)))

        val response = asCaller { AgentController(plain).ask(AgentAskRequest("Who is person 7?")) }

        assertThat(
            model.script.toolCallsMade
                .single()
                .result
        ).contains(value)
        assertThat(response.answer).isEqualTo("$value is on record.")
        assertThat(response.usage).isNull()
        val mapper = JsonMapper.builder().build()
        val json = mapper.readTree(mapper.writeValueAsString(response))
        assertThat(json.properties().map { it.key }).containsExactly("answer", "steps", "truncated")
        assertThat(asCaller { plain.status() }.enabled).isTrue()
    }

    // the bare script, no recorder: what AgentEmbabelTest runs against
    class ScriptedOnly(
        script: ScriptedLlmOperations
    ) : LlmOperations by script

    private fun platformOf(llm: LlmOperations) =
        StaticListableBeanFactory()
            .apply {
                val platform: AgentPlatform = IntegrationTestUtils.dummyAgentPlatform(llm)
                platform.deploy(AgentMetadataReader().createAgentMetadata(WasichaiAgent(properties, tools))!!)
                addBean("platform", platform)
            }.getBeanProvider(AgentPlatform::class.java)

    companion object {
        const val MODEL = "claude-haiku-4-5"
        const val INPUT = 100
        const val OUTPUT = 20
    }
}
