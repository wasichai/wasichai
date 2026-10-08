package wasichai.agent

import org.springframework.http.HttpStatus
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser

// what an app may change about what leaves the platform (ADR-056). each point has a no-op default,
// so an app that declares none gets the assistant it always had.

/**
 * Rewrites what a tool hands back before the model sees it: a pseudonym for a value the caller may
 * read but the organization will not send to a third-party model. Every bean runs, in `@Order`.
 *
 * Called twice per tool call, as the asking caller (same reactive context as the tool): once with
 * the JSON for the model, once with the one-line step summary the UI shows. A filter that parses
 * JSON must hand a non-JSON text back unchanged rather than throw.
 *
 * A filter that throws fails the run closed: the result is not sent, the model is not called
 * again, and `ask` answers with the filter's own [WasichaiException] or a 500 ([AgentFilterException]).
 */
fun interface AgentResultFilter {
    suspend fun filter(
        caller: AuthenticatedUser,
        tool: String,
        input: Map<String, Any?>,
        resultJson: String
    ): String
}

/**
 * Undoes what an [AgentResultFilter] did, on the model's final answer only (a reversible
 * pseudonym back to the value). Every bean runs, in `@Order`. Not applied to a truncated run.
 */
fun interface AgentAnswerFilter {
    suspend fun restore(
        caller: AuthenticatedUser,
        answer: String
    ): String
}

/** Whether the assistant is on for one caller: per organization, per role, per anything. */
sealed interface AgentAccess {
    data object Allowed : AgentAccess

    data class Denied(
        val reason: String
    ) : AgentAccess
}

/**
 * Consulted before anything is sent: `status` reports `enabled: false` for a denied caller and
 * `ask` answers 403 with the reason. Only asked when the server has a model at all.
 */
fun interface AgentAccessPolicy {
    suspend fun check(caller: AuthenticatedUser): AgentAccess

    companion object {
        // the default: everyone who may read something may ask, as before
        val ALLOW_ALL = AgentAccessPolicy { AgentAccess.Allowed }
    }
}

/** Tokens one question spent, summed over the run's model calls. `model` lists each model used once. */
data class AgentUsage(
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int
)

/**
 * Told once per question that reached the model: an answer, a truncated run, or a failure. Not told
 * about a question refused before the run (permission, policy, no model, blank question).
 *
 * [answer] is null exactly when [error] is not. [usage] is null when the platform reported no
 * model call. Every bean runs, in `@Order`, as the caller. A listener that throws fails the
 * request: an app that must record every interaction would rather not answer unrecorded.
 */
fun interface AgentRunListener {
    suspend fun onRun(
        caller: AuthenticatedUser,
        question: String,
        answer: AgentAnswer?,
        usage: AgentUsage?,
        error: Throwable?
    )
}

// a filter broke: nothing went out. our bug or the app's, never the model's, so not a 503.
class AgentFilterException :
    WasichaiException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "The AI assistant could not prepare the data for the model; nothing was sent"
    )

// the policy said no. the reason is the app's, shown as it is.
class AgentDeniedException(
    reason: String
) : WasichaiException(HttpStatus.FORBIDDEN, reason)
