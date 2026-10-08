package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import javax.crypto.spec.SecretKeySpec

// issue 55 (ADR-059): with none of the new properties set, sign-in and tokens behave as they always did. the
// only differences are the jti claim and POST /api/auth/logout, a 204 that changes nothing while revocation is off
// (ADR-031 D44).
class AuthDefaultsApiTest : WasichaiIntegrationTest() {
    private val mapper = JsonMapper.builder().build()
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `logout is a 204, and with revocation off the token goes on working, as a disabled user's does`() {
        val (id, email) = newUser()
        val token = bearer(email, PASSWORD)
        send(HttpMethod.POST, "/api/auth/logout", token, HttpStatus.NO_CONTENT)
        send(HttpMethod.GET, "/api/auth/me", token, HttpStatus.OK)

        send(HttpMethod.PUT, "/api/users/$id", admin, HttpStatus.OK, mapOf("enabled" to false))
        // stateless as ever: the live token runs out its ttl; new sign-ins are refused
        send(HttpMethod.GET, "/api/auth/me", token, HttpStatus.OK)
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(401)

        client
            .post()
            .uri("/api/auth/logout")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `failed sign-ins are never throttled, and a token carries a jti`() {
        val (_, email) = newUser()
        repeat(8) { assertThat(loginStatus(email, "wrong-password")).isEqualTo(401) }
        val token = bearer(email, PASSWORD).removePrefix("Bearer ")
        val key = SecretKeySpec(TEST_JWT_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val jwt =
            NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(token)
                .block()!!
        assertThat(jwt.id).isNotBlank()
    }

    @Test
    fun `the password rule is the 8 characters it always was, with the same answer`() {
        val problem =
            json(
                send(
                    HttpMethod.POST,
                    "/api/users",
                    admin,
                    HttpStatus.BAD_REQUEST,
                    mapOf("email" to "${uniqueName("short")}@wasichai.local", "displayName" to "Short", "password" to "1234567")
                )
            )
        assertThat(problem["detail"]).isEqualTo("Password too short")
        assertThat(problem["errors"]).isEqualTo(listOf(mapOf("field" to "password", "message" to "must be at least 8 characters")))
        // anything of 8 goes, the email itself included
        val email = "${uniqueName("e")}@x.io"
        send(HttpMethod.POST, "/api/users", admin, HttpStatus.CREATED, mapOf("email" to email, "displayName" to "Plain", "password" to email))
    }

    private fun newUser(): Pair<String, String> {
        val email = "${uniqueName("defaults")}@wasichai.local"
        val created =
            json(send(HttpMethod.POST, "/api/users", admin, HttpStatus.CREATED, mapOf("email" to email, "displayName" to "Defaults", "password" to PASSWORD)))
        return created["id"] as String to email
    }

    private fun loginStatus(
        email: String,
        password: String
    ): Int {
        val result =
            client
                .post()
                .uri("/api/auth/login")
                .bodyValue(mapOf("email" to email, "password" to password))
                .exchange()
                .returnResult(String::class.java)
        assertThat(result.responseHeaders.getFirst(HttpHeaders.RETRY_AFTER)).isNull()
        return result.status.value()
    }

    private fun send(
        method: HttpMethod,
        uri: String,
        token: String,
        status: HttpStatus,
        body: Any? = null
    ): String {
        val request = client.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, token)
        val spec = if (body != null) request.bodyValue(body) else request
        return spec
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: ""
    }

    private fun json(body: String): Map<String, Any?> = mapper.readValue(body, object : TypeReference<Map<String, Any?>>() {})

    private companion object {
        const val PASSWORD = "caller-password"
    }
}
