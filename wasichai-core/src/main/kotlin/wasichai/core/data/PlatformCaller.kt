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
 *
 * Each [run] is a [PlatformRun] of its own (ADR-068): a nested block gets a new one, the innermost wins.
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
        val labelled = ChangeOrigin.label(reactor.put(KEY, PlatformRun(organizationId, UUID.randomUUID())), source)
        return withContext(labelled.asCoroutineContext()) { block() }
    }

    // the organization the platform acts in, or null when the caller is not the platform
    suspend fun current(): UUID? = currentRun()?.organizationId

    // the block the platform runs in, or null when the caller is not the platform
    suspend fun currentRun(): PlatformRun? = currentCoroutineContext()[ReactorContext]?.context?.let(::runOf)

    // a security context next to the key means someone put a user in later: the user wins, never the wider caller
    private fun runOf(context: ContextView): PlatformRun? = if (Background.isRequest(context)) null else context.getOrDefault<PlatformRun>(KEY, null)
}

/**
 * One `RecordService.asPlatform` block (ADR-068): the organization it acts in and an id no other block has. A
 * module reads it to keep what the block made for that block alone (a file the platform staged is attached only by
 * the same run). Read only: nothing outside [PlatformCaller] builds one into a context.
 */
data class PlatformRun(
    val organizationId: UUID,
    val id: UUID
) {
    companion object {
        /** The block the caller runs in, or null outside `asPlatform` and inside any request. */
        suspend fun current(): PlatformRun? = PlatformCaller.currentRun()
    }
}
