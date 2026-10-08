package wasichai.agent

import com.embabel.agent.core.AgentProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import wasichai.core.identity.AuthenticatedUser
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * One question, one run.
 *
 * Embabel plans the run and calls tools on threads of its own choosing, so it carries the two
 * things a tool call needs and the framework knows nothing about: **who is asking**, captured as
 * the caller's reactive security context, and **where to write down what was called**, so the UI
 * can still show the steps.
 *
 * The identity is not optional. A tool that ran without it would query as nobody, and every
 * permission check downstream would be answering the wrong question, so the run refuses to be
 * created without one and [asCaller] is the only way the tools reach a Wasichai service.
 *
 * It also carries the app's [AgentResultFilter]s (ADR-056) and remembers when one of them broke:
 * from then on the run is closed and nothing more goes to the model.
 */
class AgentRun(
    val question: String,
    private val caller: CoroutineContext,
    // the same caller, resolved once: what the filters are told
    val user: AuthenticatedUser,
    private val filters: List<AgentResultFilter> = emptyList()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recorded = CopyOnWriteArrayList<AgentStep>()

    // embabel's process for this run, once the action started: where the token counts live
    @Volatile
    var process: AgentProcess? = null
        private set

    // the first filter failure. read by the action and by AgentService.
    @Volatile
    var failure: Exception? = null
        private set

    /** The tool calls of this run, in the order the model made them. */
    fun steps(): List<AgentStep> = recorded.toList()

    fun record(step: AgentStep) {
        recorded.add(step)
    }

    fun attach(process: AgentProcess) {
        this.process = process
    }

    /**
     * Run a suspending Wasichai call as the user who asked the question. Embabel hands us a plain
     * blocking method on one of its own threads; this is the only bridge back, and it reinstalls
     * the caller's security context on the coroutine it blocks on.
     */
    fun <T> asCaller(block: suspend CoroutineScope.() -> T): T = runBlocking(caller, block)

    // once closed, every later tool call and every replanned action stops here
    fun requireOpen() {
        if (failure != null) throw AgentFilterException()
    }

    /**
     * A tool's result through every filter: the JSON for the model, then the step summary. Called
     * inside [asCaller], so the filters see the caller's reactive context. Any failure closes the run.
     */
    suspend fun screened(
        tool: String,
        input: Map<String, Any?>,
        result: AgentToolResult
    ): Pair<String, String> =
        try {
            filtered(tool, input, result.json) to filtered(tool, input, result.summary)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw close(ex)
        }

    private suspend fun filtered(
        tool: String,
        input: Map<String, Any?>,
        text: String
    ): String = filters.fold(text) { acc, filter -> filter.filter(user, tool, input, acc) }

    // fail closed. embabel turns a tool exception into a tool error for the model, so the one thrown
    // says nothing about the data, and the action termination stops its tool loop before that error
    // is ever sent.
    private fun close(ex: Exception): AgentFilterException {
        log.error("Agent result filter failed; the run is closed and nothing more is sent", ex)
        if (failure == null) failure = ex
        process?.terminateAction("result filter failed")
        return AgentFilterException()
    }
}
