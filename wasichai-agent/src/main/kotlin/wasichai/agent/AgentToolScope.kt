package wasichai.agent

import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser

/**
 * One question, as an app tool sees it (ADR-087). The same bridge [AgentToolbox] uses for the
 * platform's tools, opened to the app: [call] runs the work as the asking user, turns a refusal into
 * an answer for the model, passes the result through the app's [AgentResultFilter]s and writes the
 * step down. An app tool that skipped it would query as nobody, so this is the only way in.
 */
class AgentToolScope internal constructor(
    private val run: AgentRun,
    private val json: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The user who asked. Who the tool runs as, whatever thread Embabel calls it on. */
    val caller: AuthenticatedUser get() = run.user

    /**
     * Run one tool call as the caller. [input] is what the step log shows (nulls dropped); [block]
     * does the work through the app's services. A [WasichaiException] becomes `{"error": …}` for the
     * model instead of failing the turn, as it does for the platform's tools.
     */
    fun call(
        tool: String,
        input: Map<String, Any?>,
        block: suspend () -> AgentToolReply
    ): String {
        val arguments = input.filterValues { it != null }
        run.requireOpen()
        val (text, summary) =
            run.asCaller {
                val result =
                    try {
                        val reply = block()
                        AgentToolResult(json.writeValueAsString(reply.payload), reply.summary, false)
                    } catch (ex: WasichaiException) {
                        log.debug("Agent tool {} refused: {}", tool, ex.message)
                        AgentToolResult(json.writeValueAsString(mapOf("error" to ex.message)), "refused: ${ex.message}", true)
                    }
                run.screened(tool, arguments, result)
            }
        run.record(AgentStep(tool, arguments, summary))
        return text
    }
}
