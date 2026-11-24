package wasichai.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import tools.jackson.databind.ObjectMapper

/** What the run puts on the blackboard when it is done. Reaching it is the goal. */
data class AgentReply(
    val answer: String
)

/**
 * The assistant, as Embabel sees it: one goal, reached by one action.
 *
 * Embabel plans; we do not. The loop that used to live in AgentService — send, read tool calls,
 * run them, send the results, repeat — is now the framework's tool loop, and all this action does
 * is say which model to use, what the rules are, and which tools exist.
 *
 * The tools are built here rather than injected because they belong to the question, not to the
 * application: [AgentToolbox] closes over the [AgentRun] that carries the asking user.
 */
@Agent(
    name = "wasichai-assistant",
    description = "Answers a signed-in Wasichai user's questions about the data of their own organization"
)
class WasichaiAgent(
    private val properties: AgentProperties,
    private val tools: AgentTools,
    // the app's own tools and the mapper their replies are written with (ADR-087)
    private val contributors: List<AgentToolContributor> = emptyList(),
    private val json: ObjectMapper? = null
) {
    @Action
    @AchievesGoal(
        description = "The user's question about their organization's data is answered from the data they may see",
        examples = [
            "How many parcels are there?",
            "Which permits changed last week?",
            "What fields does the parcel object have?"
        ]
    )
    fun answer(
        run: AgentRun,
        context: OperationContext
    ): AgentReply {
        // the token counts live on the process; AgentService reads them, whatever the outcome
        run.attach(context.agentProcess)
        // a replanned action after a filter failure must not ask again
        run.requireOpen()
        val text =
            try {
                val runner =
                    context
                        .ai()
                        .withLlm(LlmOptions.withModel(run.model ?: properties.model).withMaxTokens(properties.tokenCap.toInt()))
                        .withSystemPrompt(systemPrompt(run))
                        .withToolObjects(toolObjects(run))
                // no history: the single question, exactly as before. with one: the earlier turns, then this one.
                if (run.history.isEmpty()) {
                    runner.generateText(run.question)
                } else {
                    runner.respond(messages(run)).content
                }
            } finally {
                // a filter broke inside the tool loop: whatever came back or was thrown, the run ends here
                run.requireOpen()
            }
        return AgentReply(text.trim().ifEmpty { "I could not put an answer together for that." })
    }

    private fun systemPrompt(run: AgentRun): String = if (run.instructions.isBlank()) SYSTEM_PROMPT else SYSTEM_PROMPT + "\n\n" + run.instructions.trim()

    private fun toolObjects(run: AgentRun): List<Any> {
        val own = AgentToolbox(tools, run)
        if (contributors.isEmpty()) return listOf(own)
        val mapper = json ?: error("an AgentToolContributor needs the ObjectMapper")
        val scope = AgentToolScope(run, mapper)
        return listOf(own) + contributors.flatMap { it.tools(scope) }
    }

    private fun messages(run: AgentRun): List<Message> =
        run.history.flatMap { listOf(UserMessage(it.question), AssistantMessage(it.answer)) } + UserMessage(run.question)

    companion object {
        val SYSTEM_PROMPT =
            """
            You are the assistant built into Wasichai, a metadata-driven GIS platform. An administrator defines
            custom objects (entity types), their fields, geometry, relationships and workflows, and the platform
            generates the application from that metadata at runtime.

            You are answering a signed-in user about the data of their own organization. You reach that data only
            through the tools you have been given, which run as that user: anything they may not see, you may not
            see either. Treat that as a feature, never as an obstacle to work around.

            How to work:
            - Never guess at object names, field names or values. Call ${AgentToolCatalog.LIST_OBJECTS} first, then ${AgentToolCatalog.DESCRIBE_OBJECT}
              to learn the real field names before you query anything.
            - Prefer ${AgentToolCatalog.COUNT_RECORDS} over fetching records when the question is "how many".
            - Every query is capped at $MAX_TOOL_LIMIT records. The tools also report the true total: when you have
              only seen part of the data, say so instead of presenting a page as if it were everything.
            - A tool may answer with an error because the user lacks permission. Say plainly that they are not
              allowed to see it. Do not try another route to the same data.
            - If the data does not answer the question, say so. Never invent a record, a field or a number.
            - You are read-only. You cannot create, edit, delete or move records. If asked, say what the user
              should do in the application instead.
            - Answer in the language the user wrote to you in, briefly, and cite the object and field names you used.
            """.trimIndent()
    }
}
