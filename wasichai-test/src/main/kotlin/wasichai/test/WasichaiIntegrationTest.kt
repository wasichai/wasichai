package wasichai.test

import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
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
@TestPropertySource(properties = ["wasichai.seed.dev=true", "wasichai.security.jwt.secret=${WasichaiIntegrationTest.TEST_JWT_SECRET}"])
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
        val body =
            client
                .post()
                .uri("/api/auth/login")
                .bodyValue(mapOf("email" to email, "password" to password))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(LoginBody::class.java)
                .returnResult()
                .responseBody!!
        return "Bearer ${body.token}"
    }

    companion object {
        const val ADMIN_EMAIL = "admin@wasichai.local"
        const val ADMIN_PASSWORD = "admin"
        const val TEST_JWT_SECRET = "wasichai-integration-test-secret-0123456789"

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            WasichaiTestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
