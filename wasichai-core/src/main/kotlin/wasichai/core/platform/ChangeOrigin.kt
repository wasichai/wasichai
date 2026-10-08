package wasichai.core.platform

import io.micrometer.context.ContextRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.util.UUID

/**
 * Where a change comes from (ADR-050): the correlation id of the request that started it, and the source that wrote
 * it (`api`, `platform`, `automation:<rule>`, an app's own label, `app` when nothing says). Both ride in the Reactor
 * context, so they reach the audit row through transactions (ADR-038) and coroutines alike.
 *
 * Only code sets the source: the request filter, `RecordService.asPlatform`, a module through [within]. No header or
 * claim reaches it. The correlation id comes from `X-Correlation-Id` when it is well formed, generated otherwise.
 */
object ChangeOrigin {
    const val HEADER = "X-Correlation-Id"

    // the MDC key a log pattern prints (%X{correlationId}), and the Reactor context key it is restored from
    const val MDC_KEY = "correlationId"

    const val API = "api"
    const val PLATFORM = "platform"
    const val APP = "app"

    // what a client may send and what the audit column holds
    private val CORRELATION_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

    // a source is code's own label: the same, plus ':' for a kind and a name (automation:<rule>, job:retention)
    private val SOURCE = Regex("^[A-Za-z0-9._:-]{1,64}$")

    // private: only this object puts a source in a context
    private val SOURCE_KEY = Any()

    init {
        // the MDC follows the Reactor context on every thread hop once propagation is on
        // (spring.reactor.context-propagation=auto, a wasichai default)
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            MDC_KEY,
            { MDC.get(MDC_KEY) },
            { MDC.put(MDC_KEY, it) },
            { MDC.remove(MDC_KEY) }
        )
    }

    // a rule's writes. names saved today match ^[a-z][a-z0-9_-]{0,48}$, so this fits in 64; a name from before
    // that rule that does not fit is labelled plain "automation" rather than failing the run
    fun automation(rule: String): String = "automation:$rule".takeIf { SOURCE.matches(it) } ?: "automation"

    // the client's id when well formed, null otherwise: a bad one is replaced, never echoed
    fun acceptCorrelationId(raw: String?): String? = raw?.takeIf { CORRELATION_ID.matches(it) }

    fun newCorrelationId(): String = UUID.randomUUID().toString()

    internal fun requireValidSource(source: String): String {
        require(SOURCE.matches(source)) { "source '$source' must match ${SOURCE.pattern}" }
        return source
    }

    /** The correlation id of the work in progress, null when nothing gave one. */
    suspend fun correlationId(): String? = reactorContext()?.let(::correlationIdOf)

    /** The source of the work in progress, null when nothing labelled it (the audit row says [APP]). */
    suspend fun source(): String? = reactorContext()?.let(::sourceOf)

    /**
     * Runs [block] with [source] as the origin of every change it makes, and [correlationId] when given (an outer one
     * stays otherwise). For work no request carries: a queued automation run, a job. The innermost label wins.
     */
    suspend fun <T> within(
        source: String,
        correlationId: String? = null,
        block: suspend () -> T
    ): T {
        val context = reactorContext() ?: Context.empty()
        return withContext(label(context, source, correlationId).asCoroutineContext()) { block() }
    }

    // [context] with this origin: the request filter and the platform caller build on it
    internal fun label(
        context: Context,
        source: String,
        correlationId: String? = null
    ): Context {
        val labelled = context.put(SOURCE_KEY, requireValidSource(source))
        if (correlationId == null) return labelled
        require(CORRELATION_ID.matches(correlationId)) { "correlation id '$correlationId' must match ${CORRELATION_ID.pattern}" }
        return labelled.put(MDC_KEY, correlationId)
    }

    // the key is a plain string (the MDC accessor needs one): anything that is not a well-formed id counts as none
    internal fun correlationIdOf(context: ContextView): String? = acceptCorrelationId(context.getOrDefault<Any>(MDC_KEY, null) as? String)

    internal fun sourceOf(context: ContextView): String? = context.getOrDefault<String>(SOURCE_KEY, null)

    private suspend fun reactorContext(): Context? = currentCoroutineContext()[ReactorContext]?.context
}
