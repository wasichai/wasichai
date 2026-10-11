package wasichai.core.platform

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import reactor.util.context.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers that hold for one request (issue 105, ADR-066): what the caller's roles allow on an object, asked once
 * however many record operations the request makes. It rides in the Reactor context, as [ChangeOrigin] does, so it
 * reaches transactions (ADR-038) and coroutines alike.
 *
 * Only [RequestMemoWebFilter] opens one. Without it (a job, `asPlatform`, the agent's own runs) [remember] loads every
 * time, as before. The key is a private instance: nothing a request carries can put or read a memo.
 */
internal object RequestMemo {
    private val KEY = Any()

    // [context] with a fresh, empty memo
    fun open(context: Context): Context = context.put(KEY, ConcurrentHashMap<Any, Any>())

    // a request's coroutines may run side by side: two may load the same key, the answer is the same
    suspend fun <T : Any> remember(
        key: Any,
        load: suspend () -> T
    ): T {
        val memo = memo() ?: return load()
        @Suppress("UNCHECKED_CAST")
        (memo[key] as T?)?.let { return it }
        return load().also { memo[key] = it }
    }

    // an answer learned on the way to another one, so asking it later costs nothing
    suspend fun put(
        key: Any,
        value: Any
    ) {
        memo()?.put(key, value)
    }

    // a write to roles or permissions inside the request: what was remembered may be wrong now
    suspend fun clear() {
        memo()?.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun memo(): ConcurrentHashMap<Any, Any>? =
        currentCoroutineContext()[ReactorContext]?.context?.getOrDefault<Any>(KEY, null) as ConcurrentHashMap<Any, Any>?
}
