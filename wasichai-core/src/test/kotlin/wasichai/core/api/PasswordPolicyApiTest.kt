package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest

// issue 55 (ADR-059): a configured policy refuses a weak password on user create, password change and tenant
// provisioning with a 400 naming the field, one entry per broken rule
@TestPropertySource(
    properties = [
        "wasichai.security.password.min-length=12",
        "wasichai.security.password.require-digit=true",
        "wasichai.security.password.require-symbol=true",
        "wasichai.security.password.not-equal-email=true"
    ]
)
class PasswordPolicyApiTest : WasichaiIntegrationTest() {
    private val mapper = JsonMapper.builder().build()
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `a password that breaks the policy is refused on create, naming every broken rule`() {
        val email = "${uniqueName("policy")}@wasichai.local"
        val refused = send(HttpMethod.POST, "/api/users", user(email, "longbutplain"), 400)
        assertThat(refused["detail"]).isEqualTo("Password does not meet the password policy")
        assertThat(errors(refused)).containsExactly(
            "password" to "must contain a digit",
            "password" to "must contain a symbol"
        )
        val tooShort = send(HttpMethod.POST, "/api/users", user(email, "a-1"), 400)
        assertThat(tooShort["detail"]).isEqualTo("Password too short")
        assertThat(errors(tooShort)).containsExactly("password" to "must be at least 12 characters")
        val sameAsEmail = "${uniqueName("p-1")}@x.io"
        assertThat(errors(send(HttpMethod.POST, "/api/users", user(sameAsEmail, sameAsEmail), 400)))
            .containsExactly("password" to "must not be the email address")

        send(HttpMethod.POST, "/api/users", user(email, STRONG), 201)
        assertThat(bearer(email, STRONG)).startsWith("Bearer ")
    }

    @Test
    fun `a password change that breaks the policy is refused and the old password still works`() {
        val email = "${uniqueName("policy")}@wasichai.local"
        val id = send(HttpMethod.POST, "/api/users", user(email, STRONG), 201)["id"] as String
        val refused = send(HttpMethod.PUT, "/api/users/$id", mapOf("password" to "short"), 400)
        assertThat(errors(refused).map { it.first }.distinct()).containsExactly("password")
        assertThat(bearer(email, STRONG)).startsWith("Bearer ")

        send(HttpMethod.PUT, "/api/users/$id", mapOf("password" to "$STRONG-2"), 200)
        assertThat(bearer(email, "$STRONG-2")).startsWith("Bearer ")
    }

    @Test
    fun `provisioning a tenant whose administrator password breaks the policy is refused on adminPassword`() {
        val slug = "policy-" + uniqueName("").take(8)
        val body = mapOf("name" to "Policy", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret")
        val refused = send(HttpMethod.POST, "/api/organizations", body, 400)
        assertThat(errors(refused)).containsExactly(
            "adminPassword" to "must be at least 12 characters",
            "adminPassword" to "must contain a digit",
            "adminPassword" to "must contain a symbol"
        )
        // nothing was created: the same slug provisions once the password is good
        send(HttpMethod.POST, "/api/organizations", body + ("adminPassword" to STRONG), 201)
        assertThat(bearer("$slug@wasichai.local", STRONG)).startsWith("Bearer ")
    }

    private fun user(
        email: String,
        password: String
    ) = mapOf("email" to email, "displayName" to "Policy", "password" to password)

    @Suppress("UNCHECKED_CAST")
    private fun errors(problem: Map<String, Any?>): List<Pair<String, String>> =
        (problem["errors"] as List<Map<String, String>>).map { it.getValue("field") to it.getValue("message") }

    private fun send(
        method: HttpMethod,
        uri: String,
        body: Any,
        status: Int
    ): Map<String, Any?> {
        val result =
            client
                .method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
        return mapper.readValue(result.responseBody ?: "{}", object : TypeReference<Map<String, Any?>>() {})
    }

    private companion object {
        const val STRONG = "correct-horse-battery-9"
    }
}
