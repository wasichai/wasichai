package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.time.Duration
import java.time.Instant

// issue 55 (ADR-059): with attempt limits on, the third failure for an account from one client is a 429 with
// Retry-After, a right password too until the window closes, and an unknown email answers the same. every test
// signs in from 127.0.0.1 and uses accounts of its own, so the counts never meet. the seeded administrator only
// ever succeeds here.
@TestPropertySource(
    properties = [
        "wasichai.security.login.enabled=true",
        "wasichai.security.login.max-attempts=3",
        "wasichai.security.login.window=5s"
    ]
)
class LoginThrottleApiTest : WasichaiIntegrationTest() {
    private val mapper = JsonMapper.builder().build()
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `the Nth failure is a 429 with Retry-After, a right password is refused during the lockout, and the window resets it`() {
        val email = newUser()
        assertThat(login(email, "wrong-password").status).isEqualTo(401)
        assertThat(login(email, "wrong-password").status).isEqualTo(401)
        val third = login(email, "wrong-password")
        assertThat(third.status).isEqualTo(429)
        assertThat(third.retryAfter).isBetween(1L, 5L)
        assertThat(third.body["detail"]).isEqualTo("Too many sign-in attempts, try again later")
        val lockedOut = Instant.now()

        val right = login(email, PASSWORD)
        assertThat(right.status).isEqualTo(429)
        assertThat(right.retryAfter).isNotNull()

        // after the window: a right password signs in, and the count starts over
        Thread.sleep(Duration.between(Instant.now(), lockedOut.plusSeconds(5).plusMillis(200)).toMillis().coerceAtLeast(0))
        assertThat(login(email, PASSWORD).status).isEqualTo(200)
        assertThat(login(email, "wrong-password").status).isEqualTo(401)
    }

    @Test
    fun `a known and an unknown email get the same answers`() {
        val known = newUser()
        val unknown = "${uniqueName("nobody")}@wasichai.local"
        val knownAnswers = (1..4).map { login(known, "wrong-password") }
        val unknownAnswers = (1..4).map { login(unknown, "wrong-password") }
        assertThat(knownAnswers.map { it.status }).containsExactly(401, 401, 429, 429)
        assertThat(unknownAnswers.map { it.status }).isEqualTo(knownAnswers.map { it.status })
        assertThat(unknownAnswers.map { it.body["detail"] }).isEqualTo(knownAnswers.map { it.body["detail"] })
        assertThat(unknownAnswers.map { it.retryAfter != null }).isEqualTo(knownAnswers.map { it.retryAfter != null })
    }

    @Test
    fun `one account locked out leaves the others alone`() {
        val locked = newUser()
        val other = newUser()
        repeat(3) { login(locked, "wrong-password") }
        assertThat(login(locked, PASSWORD).status).isEqualTo(429)
        assertThat(login(other, PASSWORD).status).isEqualTo(200)
    }

    @Test
    fun `the service account token endpoint is limited per client id`() {
        val created =
            json(
                client
                    .post()
                    .uri("/api/service-accounts")
                    .header(HttpHeaders.AUTHORIZATION, admin)
                    .bodyValue(mapOf("name" to uniqueName("sa")))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            )
        val id = created["id"] as String
        val secret = created["clientSecret"] as String
        assertThat(token(id, "wrong-secret").status).isEqualTo(401)
        assertThat(token(id, "wrong-secret").status).isEqualTo(401)
        val third = token(id, "wrong-secret")
        assertThat(third.status).isEqualTo(429)
        assertThat(third.retryAfter).isNotNull()
        assertThat(token(id, secret).status).isEqualTo(429)
        // an unknown client id is counted the same way
        val unknown = "00000000-0000-0000-0000-" + uniqueName("").take(12)
        assertThat((1..3).map { token(unknown, "wrong-secret").status }).containsExactly(401, 401, 429)
    }

    private class Answer(
        val status: Int,
        val retryAfter: Long?,
        val body: Map<String, Any?>
    )

    private fun login(
        email: String,
        password: String
    ): Answer = post("/api/auth/login", mapOf("email" to email, "password" to password))

    private fun token(
        clientId: String,
        secret: String
    ): Answer = post("/api/auth/token", mapOf("clientId" to clientId, "clientSecret" to secret))

    private fun post(
        uri: String,
        body: Map<String, String>
    ): Answer {
        val result =
            client
                .post()
                .uri(uri)
                .bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return Answer(
            result.status.value(),
            result.responseHeaders.getFirst(HttpHeaders.RETRY_AFTER)?.toLong(),
            json(result.responseBody ?: "{}")
        )
    }

    private fun newUser(): String {
        val email = "${uniqueName("throttled")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Throttled", "password" to PASSWORD))
            .exchange()
            .expectStatus()
            .isCreated
        return email
    }

    private fun json(body: String): Map<String, Any?> = mapper.readValue(body, object : TypeReference<Map<String, Any?>>() {})

    private companion object {
        const val PASSWORD = "caller-password"
    }
}
