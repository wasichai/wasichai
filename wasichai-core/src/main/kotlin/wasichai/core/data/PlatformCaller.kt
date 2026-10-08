package wasichai.core.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import reactor.util.context.Context
import reactor.util.context.ContextView
import wasichai.core.platform.Background
import wasichai.core.platform.ChangeOrigin
import java.util.UUID

/**
 * The platform as a caller of [RecordService] (ADR-039). It lives in the Reactor context, not the
 * coroutine context: a TransactionalOperator block keeps the Reactor context (ADR-038), so the
 * platform composes with the caller's transaction either way round.
 *
 * The key is a private instance: only [run] can put it in a context. No filter, header or claim
 * reaches it, and a context that carries a security context (every request through Spring Security,
 * with a token or anonymous) is refused, so a request never becomes the platform.
 */
internal object PlatformCaller {
    private val KEY = Any()

    // source: what the audit rows of this block say wrote them (ADR-050), checked before anything runs
    suspend fun <T> run(
        organizationId: UUID,
        source: String,
        block: suspend () -> T
    ): T {
        Background.require("RecordService.asPlatform", "never becomes the platform")
        val reactor = currentCoroutineContext()[ReactorContext]?.context ?: Context.empty()
        val labelled = ChangeOrigin.label(reactor.put(KEY, organizationId), source)
        return withContext(labelled.asCoroutineContext()) { block() }
    }

    // the organization the platform acts in, or null when the caller is not the platform
    suspend fun current(): UUID? = currentCoroutineContext()[ReactorContext]?.context?.let(::organizationOf)

    // a security context next to the key means someone put a user in later: the user wins, never the wider caller
    private fun organizationOf(context: ContextView): UUID? = if (Background.isRequest(context)) null else context.getOrDefault<UUID>(KEY, null)
}
