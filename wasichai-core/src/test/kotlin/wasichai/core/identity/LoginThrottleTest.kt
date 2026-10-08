package wasichai.core.identity

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnauthorizedException
import wasichai.core.platform.WasichaiLoginProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// issue 55 (ADR-059): attempt limits, counted before the check, a 429 with the rest of the window
class LoginThrottleTest {
    private class MutableClock(
        var now: Instant = Instant.parse("2026-10-08T10:00:00Z")
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MutableClock()
    private val properties = WasichaiLoginProperties(enabled = true, maxAttempts = 3, accountMaxAttempts = 5, window = Duration.ofMinutes(15))

    private fun throttle(props: WasichaiLoginProperties = properties) = LoginThrottle(props, InMemoryLoginAttemptStore(clock), clock)

    private fun fails(): Nothing = throw UnauthorizedException("Invalid email or password")

    private fun LoginThrottle.failLogin(
        email: String = "ana@example.com",
        address: String = "10.0.0.1"
    ): Throwable = runCatching { runBlocking { login(email, address) { fails() } } }.exceptionOrNull()!!

    @Test
    fun `the Nth failure is a 429 with the rest of the window, and a right password is refused during the lockout`() {
        val throttle = throttle()
        assertThat(throttle.failLogin()).isInstanceOf(UnauthorizedException::class.java)
        clock.now = clock.now.plusSeconds(60)
        assertThat(throttle.failLogin()).isInstanceOf(UnauthorizedException::class.java)
        val third = throttle.failLogin()
        assertThat(third).isInstanceOf(RetryLaterException::class.java)
        third as RetryLaterException
        assertThat(third.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(third.retryAfter).isEqualTo(Duration.ofMinutes(14))

        var checked = false
        assertThatThrownBy {
            runBlocking {
                throttle.login("ana@example.com", "10.0.0.1") {
                    checked = true
                    "token"
                }
            }
        }.isInstanceOf(RetryLaterException::class.java)
        // refused before the credentials are looked at
        assertThat(checked).isFalse()
    }

    @Test
    fun `the count starts over once the window closes, and a success forgets it`() {
        val throttle = throttle()
        repeat(3) { throttle.failLogin() }
        clock.now = clock.now.plus(Duration.ofMinutes(15))
        assertThat(runBlocking { throttle.login("ana@example.com", "10.0.0.1") { "token" } }).isEqualTo("token")

        repeat(2) { assertThat(throttle.failLogin()).isInstanceOf(UnauthorizedException::class.java) }
        runBlocking { throttle.login("ana@example.com", "10.0.0.1") { "token" } }
        // the success reset the count: two more failures are plain 401s again
        repeat(2) { assertThat(throttle.failLogin()).isInstanceOf(UnauthorizedException::class.java) }
    }

    @Test
    fun `one client is locked out of one account, other clients only once the account limit is reached`() {
        val throttle = throttle()
        repeat(3) { throttle.failLogin(address = "10.0.0.1") }
        assertThat(throttle.failLogin(address = "10.0.0.1")).isInstanceOf(RetryLaterException::class.java)
        // another address: its own count, the account's goes on (4 so far, limit 5)
        assertThat(throttle.failLogin(address = "10.0.0.2")).isInstanceOf(RetryLaterException::class.java)
        assertThat(throttle.failLogin(address = "10.0.0.3")).isInstanceOf(RetryLaterException::class.java)
        // another account from the first address is not touched
        assertThat(throttle.failLogin(email = "bob@example.com", address = "10.0.0.1")).isInstanceOf(UnauthorizedException::class.java)
    }

    @Test
    fun `a known and an unknown account get the same answer`() {
        val throttle = throttle()
        val known = (1..4).map { throttle.failLogin(email = "ana@example.com") }
        val unknown = (1..4).map { throttle.failLogin(email = "nobody@example.com") }
        assertThat(known.map { it.javaClass to it.message }).isEqualTo(unknown.map { it.javaClass to it.message })
        assertThat((known.last() as RetryLaterException).retryAfter).isEqualTo((unknown.last() as RetryLaterException).retryAfter)
    }

    @Test
    fun `the token endpoint is limited per client id`() {
        val throttle = throttle()
        repeat(2) {
            assertThatThrownBy { runBlocking { throttle.token(" Client-1 ") { fails() } } }.isInstanceOf(UnauthorizedException::class.java)
        }
        assertThatThrownBy { runBlocking { throttle.token("client-1") { fails() } } }.isInstanceOf(RetryLaterException::class.java)
        assertThat(runBlocking { throttle.token("client-2") { "token" } }).isEqualTo("token")
    }

    @Test
    fun `off, nothing is counted and every failure stays a 401`() {
        val throttle = throttle(WasichaiLoginProperties())
        assertThat(throttle.enabled).isFalse()
        repeat(20) { assertThat(throttle.failLogin()).isInstanceOf(UnauthorizedException::class.java) }
    }

    @Test
    fun `the in-memory store forgets the oldest keys past its bound`() {
        val store = InMemoryLoginAttemptStore(clock, maxKeys = 2)
        runBlocking {
            store.increment("a", Duration.ofMinutes(1))
            store.increment("b", Duration.ofMinutes(1))
            store.increment("a", Duration.ofMinutes(1))
            store.increment("c", Duration.ofMinutes(1))
            // b was the least recently counted: it went, a kept its count
            assertThat(store.increment("a", Duration.ofMinutes(1)).count).isEqualTo(3)
            assertThat(store.increment("b", Duration.ofMinutes(1)).count).isEqualTo(1)
        }
    }
}
