package wasichai.core.platform

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.security.core.context.SecurityContext
import reactor.util.context.ContextView

/**
 * The tripwire of background-only calls (ADR-039, ADR-057): `RecordService.asPlatform` and the
 * [TenantDirectory]. Spring Security's filters put a security context key in every request's
 * Reactor context, with a token or anonymous, so its presence means "serving a request".
 */
internal object Background {
    // spring security's own key (ReactiveSecurityContextHolder): present on every request through its filters
    private val SECURITY = SecurityContext::class.java

    fun isRequest(context: ContextView): Boolean = context.hasKey(SECURITY)

    // throws IllegalStateException inside a request. what: the caller, why: what a request never gets
    suspend fun require(
        what: String,
        why: String
    ) {
        val reactor = currentCoroutineContext()[ReactorContext]?.context ?: return
        check(!isRequest(reactor)) { "$what is for background work: a request, with a token or without one, $why" }
    }
}
