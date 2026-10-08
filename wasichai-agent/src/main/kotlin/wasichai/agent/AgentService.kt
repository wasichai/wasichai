package wasichai.agent

import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.loop.MaxIterationsExceededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import java.util.concurrent.ExecutionException
import kotlin.coroutines.CoroutineContext

// one tool call, as the UI shows it: what was called, with what, and what came back
data class AgentStep(
    val tool: String,
    val input: Map<String, Any?>,
    val summary: String
)

data class AgentAnswer(
    val answer: String,
    val steps: List<AgentStep>,
    // true when the run stopped before the model had an answer
    val truncated: Boolean,
    // what the run spent, when the platform reported it (ADR-056)
    val usage: AgentUsage? = null
)

data class AgentStatus(
    val enabled: Boolean,
    val model: String
)

// the model is a downstream like geoserver: its outage is a 503, never our 500
class AgentUnavailableException(
    message: String
) : WasichaiException(HttpStatus.SERVICE_UNAVAILABLE, message)

private const val DISABLED =
    "The AI assistant is not configured on this server (set ANTHROPIC_API_KEY, or wasichai.agent.enabled=false to hide it)"

private const val CAP_REACHED =
    "I stopped after too many steps without reaching an answer. Try asking something narrower, " +
        "or name the object you are interested in."

/**
 * The HTTP side of the assistant. It no longer talks to a model: it hands the question to Embabel
 * and reads back what the run produced.
 *
 * Two things this class still owns, because Embabel cannot know them. The **caller**: the platform
 * runs the process on its own thread, so the asking user's reactive security context is captured
 * here, at the request boundary, and travels with the run. And the **contract**: the endpoint has
 * always answered with an answer, the steps taken and whether it ran out of room, and that has not
 * changed.
 *
 * And the app's say over what leaves the platform (ADR-056): the [AgentAccessPolicy] is asked
 * before anything is sent, the [AgentResultFilter]s ride on the run, the [AgentAnswerFilter]s
 * restore the final text and the [AgentRunListener]s hear about every run, with its token usage.
 */
@Service
class AgentService(
    private val properties: AgentProperties,
    private val currentUser: CurrentUser,
    // absent whenever EmbabelGate kept the platform out of the context, which is the no-key case
    private val platform: ObjectProvider<AgentPlatform>,
    private val policy: AgentAccessPolicy = AgentAccessPolicy.ALLOW_ALL,
    private val resultFilters: List<AgentResultFilter> = emptyList(),
    private val answerFilters: List<AgentAnswerFilter> = emptyList(),
    private val listeners: List<AgentRunListener> = emptyList()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // per caller: a server with a model may still have the assistant off for this organization
    suspend fun status(): AgentStatus {
        if (!properties.available) return AgentStatus(false, properties.model)
        val allowed = policy.check(currentUser.require()) == AgentAccess.Allowed
        return AgentStatus(allowed, properties.model)
    }

    // a tenant-wide READ check would lock out anyone granted READ on single objects, who is exactly
    // the person an assistant helps. reading anything is enough: each tool still enforces its own
    // object, so the agent can only reach what this caller may reach.
    private suspend fun requireSomethingToRead(): AuthenticatedUser {
        val user = currentUser.require()
        val permitted = currentUser.permittedObjects(user, Actions.READ)
        if (!permitted.all && permitted.ids.isEmpty()) {
            throw ForbiddenException("Missing permission ${Actions.READ}")
        }
        return user
    }

    suspend fun ask(question: String?): AgentAnswer {
        val user = requireSomethingToRead()
        val text =
            question?.trim().orEmpty().ifEmpty {
                throw ValidationException("Ask something", "question", "must not be blank")
            }
        if (!properties.available) throw AgentUnavailableException(DISABLED)
        val agentPlatform = platform.ifAvailable ?: throw AgentUnavailableException(DISABLED)
        // before anything is sent: a denied caller never reaches the model
        val access = policy.check(user)
        if (access is AgentAccess.Denied) throw AgentDeniedException(access.reason)

        val run = AgentRun(text, caller(), user, resultFilters)
        val answer =
            try {
                // the platform blocks the calling thread until the run is done. never on the event loop.
                val process = withContext(Dispatchers.IO) { start(agentPlatform, run) }
                // a broken filter wins over whatever the run made of it
                run.failure?.let { throw closed(it) }
                restored(answerOf(process, run), user).copy(usage = usageOf(run))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val error = run.failure?.let { closed(it) } ?: ex
                try {
                    notify(user, text, null, usageOf(run), error)
                } catch (listenerFailure: Exception) {
                    error.addSuppressed(listenerFailure)
                }
                throw error
            }
        notify(user, text, answer, answer.usage, null)
        return answer
    }

    // a filter's own refusal keeps its status; anything else is our 500, never the model's 503
    private fun closed(failure: Exception): WasichaiException = failure as? WasichaiException ?: AgentFilterException()

    private suspend fun restored(
        answer: AgentAnswer,
        user: AuthenticatedUser
    ): AgentAnswer {
        if (answer.truncated || answerFilters.isEmpty()) return answer
        val text = answerFilters.fold(answer.answer) { acc, filter -> filter.restore(user, acc) }
        return answer.copy(answer = text)
    }

    private suspend fun notify(
        user: AuthenticatedUser,
        question: String,
        answer: AgentAnswer?,
        usage: AgentUsage?,
        error: Exception?
    ) {
        listeners.forEach { it.onRun(user, question, answer, usage, error) }
    }

    // summed over every model call the process recorded, tool loop included. none recorded, null.
    private fun usageOf(run: AgentRun): AgentUsage? {
        val invocations =
            run.process
                ?.llmInvocations
                ?.toList()
                .orEmpty()
        if (invocations.isEmpty()) return null
        return AgentUsage(
            model = invocations.map { it.llmMetadata.name }.distinct().joinToString(","),
            inputTokens = invocations.sumOf { it.usage.promptTokens ?: 0 },
            outputTokens = invocations.sumOf { it.usage.completionTokens ?: 0 }
        )
    }

    /**
     * Capture who is asking, once, here. Everything the tools do downstream is done as this
     * authentication; if it is missing the request is not authenticated and there is nothing to run.
     */
    private suspend fun caller(): CoroutineContext {
        val authentication =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        return ReactiveSecurityContextHolder.withAuthentication(authentication).asCoroutineContext()
    }

    // null means the run ran out of room rather than failed
    private fun start(
        agentPlatform: AgentPlatform,
        run: AgentRun
    ): AgentProcess? =
        try {
            AgentInvocation
                .builder(agentPlatform)
                .options(ProcessOptions(budget = Budget(actions = properties.iterationCap)))
                .build(AgentReply::class.java)
                .runAsync(run)
                .get()
        } catch (ex: ExecutionException) {
            when (val cause = ex.cause ?: ex) {
                // a refusal from one of our services is the caller's answer, not an outage
                is WasichaiException -> throw cause
                is MaxIterationsExceededException -> null
                else -> {
                    log.error("Agent run failed", cause)
                    throw AgentUnavailableException("The AI service failed: ${cause.message}")
                }
            }
        } catch (ex: IllegalStateException) {
            // no agent deployed for AgentReply: the platform is up but the assistant is not in it
            log.error("Agent is not deployed on the platform", ex)
            throw AgentUnavailableException("The AI assistant is not available on this server")
        }

    private fun answerOf(
        process: AgentProcess?,
        run: AgentRun
    ): AgentAnswer {
        val steps = run.steps()
        val reply = process?.last(AgentReply::class.java)
        if (process?.status == AgentProcessStatusCode.COMPLETED && reply != null) {
            return AgentAnswer(reply.answer, steps, false)
        }
        log.warn("Agent run did not complete: status={} failure={}", process?.status, process?.failureInfo)
        return AgentAnswer(CAP_REACHED, steps, true)
    }
}
