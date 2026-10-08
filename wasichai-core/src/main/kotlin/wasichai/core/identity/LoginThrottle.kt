package wasichai.core.identity

import org.springframework.http.HttpStatus
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnauthorizedException
import wasichai.core.platform.WasichaiLoginProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant

// attempts on one key within its window, and when that window closes
data class LoginAttempts(
    val count: Int,
    val windowEndsAt: Instant
)

/**
 * Where sign-in attempts are counted (ADR-059). The default keeps them in memory, one node's view: a cluster
 * backs it with something shared (PostgreSQL, Redis) by declaring its own bean. Keys are opaque strings.
 */
interface LoginAttemptStore {
    /** Counts one more attempt on [key]; the first one opens a window of [window]. Answers the count so far. */
    suspend fun increment(
        key: String,
        window: Duration
    ): LoginAttempts

    /** Forgets [key]'s attempts: a successful sign-in. */
    suspend fun reset(key: String)
}

// fixed windows in a map. bounded: past maxKeys the oldest key goes, expired ones first
class InMemoryLoginAttemptStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxKeys: Int = 100_000
) : LoginAttemptStore {
    private val attempts = LinkedHashMap<String, LoginAttempts>()

    override suspend fun increment(
        key: String,
        window: Duration
    ): LoginAttempts {
        val now = Instant.now(clock)
        synchronized(attempts) {
            val current = attempts[key]?.takeIf { now.isBefore(it.windowEndsAt) }
            val next = if (current == null) LoginAttempts(1, now.plus(window)) else current.copy(count = current.count + 1)
            // re-inserted, so insertion order is the order of last attempt
            attempts.remove(key)
            if (attempts.size >= maxKeys) evict(now)
            attempts[key] = next
            return next
        }
    }

    override suspend fun reset(key: String) {
        synchronized(attempts) { attempts.remove(key) }
    }

    private fun evict(now: Instant) {
        attempts.entries.removeIf { !now.isBefore(it.value.windowEndsAt) }
        val iterator = attempts.entries.iterator()
        while (attempts.size >= maxKeys && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

// sign-in attempt limits (ADR-059). an attempt is counted before the credentials are checked, so concurrent
// guesses cannot all slip under the limit. over a key's limit: 429 with Retry-After for the rest of its window,
// without checking anything, so a right password is refused too and a known email answers as an unknown one.
// the attempt that reaches the limit and fails is a 429 as well. a success forgets the keys it counted on.
class LoginThrottle(
    private val properties: WasichaiLoginProperties,
    private val store: LoginAttemptStore,
    private val clock: Clock = Clock.systemUTC()
) {
    val enabled: Boolean get() = properties.enabled

    // /api/auth/login: per (email, client address) and per email from anywhere
    suspend fun <T> login(
        email: String,
        clientAddress: String?,
        attempt: suspend () -> T
    ): T =
        guard(
            listOf(
                "login:${email.take(MAX_KEY_PART)}|${clientAddress ?: UNKNOWN_ADDRESS}" to properties.maxAttempts,
                "account:${email.take(MAX_KEY_PART)}" to properties.accountMaxAttempts
            ),
            attempt
        )

    // /api/auth/token: per client id, whatever it is (an unknown one is counted like a known one)
    suspend fun <T> token(
        clientId: String,
        attempt: suspend () -> T
    ): T = guard(listOf("token:${clientId.trim().lowercase().take(MAX_KEY_PART)}" to properties.maxAttempts), attempt)

    private suspend fun <T> guard(
        keys: List<Pair<String, Int>>,
        attempt: suspend () -> T
    ): T {
        if (!properties.enabled) return attempt()
        val counted = keys.map { (key, limit) -> Triple(key, limit, store.increment(key, properties.window)) }
        val over = counted.filter { (_, limit, attempts) -> attempts.count > limit }
        if (over.isNotEmpty()) throw tooMany(over.maxOf { it.third.windowEndsAt })
        val result =
            try {
                attempt()
            } catch (e: UnauthorizedException) {
                val reached = counted.filter { (_, limit, attempts) -> attempts.count >= limit }
                if (reached.isNotEmpty()) throw tooMany(reached.maxOf { it.third.windowEndsAt })
                throw e
            }
        keys.forEach { (key, _) -> store.reset(key) }
        return result
    }

    private fun tooMany(until: Instant): RetryLaterException =
        RetryLaterException(
            "Too many sign-in attempts, try again later",
            Duration.between(Instant.now(clock), until),
            status = HttpStatus.TOO_MANY_REQUESTS
        )

    private companion object {
        // an email can be any length: keys stay small whatever is sent
        const val MAX_KEY_PART = 320
        const val UNKNOWN_ADDRESS = "unknown"
    }
}
