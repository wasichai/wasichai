package wasichai.agent

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.agent.test.integration.ScriptedLlmOperations
import com.embabel.chat.Message
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.support.StaticListableBeanFactory
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleQueries
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADR-087 through the real agent on an in-memory Embabel platform: an app's own tools, its own
 * rules, and a question asked with the conversation so far. The model is Embabel's scripted double.
 */
class AgentAppExtensionsTest {
    private val script = ScriptedLlmOperations()

    // the script, plus what it was told besides the messages: the system prompt and the model
    class Recording(
        private val script: ScriptedLlmOperations
    ) : LlmOperations by script {
        val systemPrompts = CopyOnWriteArrayList<String>()
        val models = CopyOnWriteArrayList<Any?>()

        override fun <O> createObject(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            agentProcess: AgentProcess,
            action: Action?
        ): O {
            systemPrompts.add(interaction.promptContributors.joinToString("\n") { it.contribution() })
            models.add(interaction.llm.criteria)
            return script.createObject(messages, interaction, outputClass, agentProcess, action)
        }
    }

    private val recording = Recording(script)
    private val properties = AgentProperties(apiKey = "not-a-real-key")
    private val tools = mock(AgentTools::class.java)

    // an app tool: answers through the scope, like every app tool must
    class Taxes(
        private val scope: AgentToolScope
    ) {
        @LlmTool(name = "tax_gap", description = "The tax gap of one year")
        fun taxGap(
            @LlmTool.Param(description = "Fiscal year") year: Int
        ): String =
            scope.call("tax_gap", mapOf("year" to year)) {
                if (year < 2000) throw ForbiddenException("no data before 2000")
                AgentToolReply(mapOf("year" to year, "owner" to scope.caller.email, "gap" to 1234), "gap of $year")
            }
    }

    private val contributor = AgentToolContributor { scope -> listOf(Taxes(scope)) }

    private fun service(
        instructions: List<AgentInstructions> = emptyList(),
        filters: List<AgentResultFilter> = emptyList()
    ): AgentService {
        val platform: AgentPlatform = IntegrationTestUtils.dummyAgentPlatform(recording)
        val agent = WasichaiAgent(properties, tools, listOf(contributor), JsonMapper.builder().build())
        platform.deploy(AgentMetadataReader().createAgentMetadata(agent)!!)
        val provider = StaticListableBeanFactory().apply { addBean("platform", platform) }.getBeanProvider(AgentPlatform::class.java)
        return AgentService(
            properties,
            CurrentUser(mock(RoleQueries::class.java)),
            provider,
            resultFilters = filters,
            instructions = instructions
        )
    }

    private fun <T> asCaller(block: suspend () -> T): T = runBlocking(TestCallers.context(TestCallers.jwt(email = "ana@muni.pe"))) { block() }

    @Test
    fun `an app tool runs as the caller and shows up as a step`() {
        script.callTool("tax_gap", """{"year": 2026}""").returnObject("The gap is 1234.")

        val answer = asCaller { service().ask("What is the 2026 gap?") }

        assertThat(answer.answer).isEqualTo("The gap is 1234.")
        assertThat(answer.steps.single()).isEqualTo(AgentStep("tax_gap", mapOf("year" to 2026), "gap of 2026"))
        assertThat(script.toolCallsMade.single().result).contains("\"owner\":\"ana@muni.pe\"").contains("1234")
    }

    @Test
    fun `an app tool's refusal is an answer for the model, not a failed turn`() {
        script.callTool("tax_gap", """{"year": 1990}""").returnObject("No data for 1990.")

        val answer = asCaller { service().ask("And 1990?") }

        assertThat(answer.truncated).isFalse()
        assertThat(answer.steps.single().summary).isEqualTo("refused: no data before 2000")
        assertThat(script.toolCallsMade.single().result).contains("no data before 2000")
    }

    @Test
    fun `the app's filters see an app tool's result too`() {
        script.callTool("tax_gap", """{"year": 2026}""").returnObject("ok")
        val hide = AgentResultFilter { _, _, _, text -> text.replace("ana@muni.pe", "USER-1") }

        asCaller { service(filters = listOf(hide)).ask("gap?") }

        assertThat(script.toolCallsMade.single().result).contains("USER-1").doesNotContain("ana@muni.pe")
    }

    @Test
    fun `the app's instructions follow the platform's system prompt`() {
        script.returnObject("ok")
        val rules = AgentInstructions { caller -> "Only answer about the municipality of ${caller.email}." }

        asCaller { service(instructions = listOf(rules, AgentInstructions { null })).ask("hello") }

        assertThat(recording.systemPrompts.single())
            .contains("You are the assistant built into Wasichai")
            .contains("Only answer about the municipality of ana@muni.pe.")
    }

    @Test
    fun `a question in a conversation carries the earlier exchanges, oldest first`() {
        script.returnObject("Still 1234.")
        val history = listOf(AgentTurn("What is the 2026 gap?", "The gap is 1234."), AgentTurn("Which year?", "2026."))

        val answer = asCaller { service().ask("Has it changed?", history) }

        assertThat(answer.answer).isEqualTo("Still 1234.")
        val prompt = script.promptsReceived.single()
        val order = listOf("What is the 2026 gap?", "The gap is 1234.", "Which year?", "2026.", "Has it changed?").map { prompt.indexOf(it) }
        assertThat(order).allMatch { it >= 0 }.isSorted()
    }

    @Test
    fun `an answer names the model it was asked of`() {
        script.returnObject("ok")

        val answer = asCaller { service().ask("hello") }

        assertThat(answer.model).isEqualTo(properties.model)
        assertThat(recording.models.single().toString()).contains(properties.model)
    }

    @Test
    fun `allowed models keep the default first, once`() {
        val p = AgentProperties(model = "claude-sonnet-5-5", models = listOf("claude-haiku-5-5", " claude-sonnet-5-5 ", ""))

        assertThat(p.allowedModels).containsExactly("claude-sonnet-5-5", "claude-haiku-5-5")
    }
}
