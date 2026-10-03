package wasichai.test

import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class LoginBody(
    val token: String
)

// base for api tests against a real postgres. the app under test is the @SpringBootConfiguration
// found above the test's package: give your tests one with @EnableAutoConfiguration and no component
// scan, so the app boots the way a real one gets wasichai. a subclass may override any property with
// its own @TestPropertySource (say, other schema names).
// do not add @ActiveProfiles("test"): a profile called exactly "test" can switch beans off in some
// libraries (embabel's agents, for one) without a word.
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
// server.address: the client calls localhost, i.e. 127.0.0.1. a wildcard listener lets another process bind
// 127.0.0.1 on the same port (macOS allows it), and that process then gets every request: #34's 404s.
// bound to 127.0.0.1 itself, the port is ours alone.
@TestPropertySource(
    properties = [
        "wasichai.seed.dev=true",
        "wasichai.security.jwt.secret=${WasichaiIntegrationTest.TEST_JWT_SECRET}",
        "server.address=127.0.0.1"
    ]
)
abstract class WasichaiIntegrationTest {
    @Autowired
    protected lateinit var client: WebTestClient

    // the database is shared, so every test names its own object
    protected fun uniqueName(prefix: String = "obj"): String =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    protected fun bearer(): String = bearer(ADMIN_EMAIL, ADMIN_PASSWORD)

    // tests that exercise permissions need a token that is not the seeded administrator's
    protected fun bearer(
        email: String,
        password: String
    ): String {
        val result =
            client
                .post()
                .uri("/api/auth/login")
                .bodyValue(mapOf("email" to email, "password" to password))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        // every test starts here: on failure say who answered and what, the result prints url, headers and body
        if (result.status.value() != HttpStatus.OK.value()) throw AssertionError("login as $email answered ${result.status}, expected 200 OK\n$result")
        return "Bearer ${json.readTree(result.responseBody).get("token").asString()}"
    }

    companion object {
        const val ADMIN_EMAIL = "admin@wasichai.local"
        const val ADMIN_PASSWORD = "admin"
        const val TEST_JWT_SECRET = "wasichai-integration-test-secret-0123456789"

        private val json = JsonMapper.builder().build()

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            WasichaiTestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
