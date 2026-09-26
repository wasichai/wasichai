package wasichai.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
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
 */
class AgentRun(
    val question: String,
    private val caller: CoroutineContext
) {
    private val recorded = CopyOnWriteArrayList<AgentStep>()

    /** The tool calls of this run, in the order the model made them. */
    fun steps(): List<AgentStep> = recorded.toList()

    fun record(step: AgentStep) {
        recorded.add(step)
    }

    /**
     * Run a suspending Wasichai call as the user who asked the question. Embabel hands us a plain
     * blocking method on one of its own threads; this is the only bridge back, and it reinstalls
     * the caller's security context on the coroutine it blocks on.
     */
    fun <T> asCaller(block: suspend CoroutineScope.() -> T): T = runBlocking(caller, block)
}
