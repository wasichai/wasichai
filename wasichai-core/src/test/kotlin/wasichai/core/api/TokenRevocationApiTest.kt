package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

// issue 55 (ADR-059): with revocation on, a change that must sign someone out kills the tokens they hold, at once on
// this node and within revocation-cache on any other; tokens issued after it work at once. the seeded administrator
// is never the one signed out: every other class shares it.
@TestPropertySource(properties = ["wasichai.security.jwt.revocation=true", "wasichai.security.jwt.revocation-cache=2s"])
class TokenRevocationApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val mapper = JsonMapper.builder().build()
    private lateinit var admin: String
    private lateinit var role: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        role = createRole()
    }

    @Test
    fun `disabling a user kills their token, nobody else's, and a token after re-enabling works`() {
        val ana = newUser()
        val bob = newUser()
        assertThat(me(ana.token)).isEqualTo(200)

        send(HttpMethod.PUT, "/api/users/${ana.id}", mapOf("enabled" to false))
        assertThat(me(ana.token)).isEqualTo(401)
        assertThat(me(bob.token)).isEqualTo(200)
        assertThat(me(admin)).isEqualTo(200)

        send(HttpMethod.PUT, "/api/users/${ana.id}", mapOf("enabled" to true))
        // the old token stays dead after re-enabling; a new sign-in works at once, same second or not
        assertThat(me(ana.token)).isEqualTo(401)
        assertThat(me(bearer(ana.email, PASSWORD))).isEqualTo(200)
        // a rename signs nobody out
        val fresh = bearer(ana.email, PASSWORD)
        send(HttpMethod.PUT, "/api/users/${ana.id}", mapOf("displayName" to "Renamed"))
        assertThat(me(fresh)).isEqualTo(200)
    }

    @Test
    fun `a new password kills the old token, and the new password's token works at once`() {
        val ana = newUser()
        send(HttpMethod.PUT, "/api/users/${ana.id}", mapOf("password" to "another-password"))
        assertThat(me(ana.token)).isEqualTo(401)
        assertThat(me(bearer(ana.email, "another-password"))).isEqualTo(200)
    }

    @Test
    fun `new roles kill the token that carries the old ones`() {
        val ana = newUser()
        val other = createRole()
        send(HttpMethod.PUT, "/api/users/${ana.id}/roles", mapOf("roles" to listOf(other)))
        assertThat(me(ana.token)).isEqualTo(401)
        val fresh = bearer(ana.email, PASSWORD)
        assertThat(json(get("/api/auth/me", fresh))["roles"]).isEqualTo(listOf(other))
    }

    @Test
    fun `a deleted user's token is refused`() {
        val ana = newUser()
        assertThat(me(ana.token)).isEqualTo(200)
        send(HttpMethod.DELETE, "/api/users/${ana.id}", status = HttpStatus.NO_CONTENT)
        assertThat(me(ana.token)).isEqualTo(401)
    }

    @Test
    fun `logout kills every token the caller holds, not another user's, and signing in again works at once`() {
        val ana = newUser()
        val second = bearer(ana.email, PASSWORD)
        val bob = newUser()

        send(HttpMethod.POST, "/api/auth/logout", status = HttpStatus.NO_CONTENT, token = ana.token)
        assertThat(me(ana.token)).isEqualTo(401)
        assertThat(me(second)).isEqualTo(401)
        assertThat(me(bob.token)).isEqualTo(200)
        val again = bearer(ana.email, PASSWORD)
        assertThat(me(again)).isEqualTo(200)
        // logging out with a dead token is a 401 like any other call
        send(HttpMethod.POST, "/api/auth/logout", status = HttpStatus.UNAUTHORIZED, token = ana.token)
    }

    // another node moved the marker: this one learns it when its cached entry expires, within revocation-cache
    @Test
    fun `a marker moved elsewhere bites within the cache window`() {
        val ana = newUser()
        assertThat(me(ana.token)).isEqualTo(200)
        val written = Instant.now()
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.users SET tokens_valid_after = :marker WHERE id = :id")
                .bind("marker", written.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1).atOffset(ZoneOffset.UTC))
                .bind("id", UUID.fromString(ana.id))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        var refusedAt: Instant? = null
        while (refusedAt == null && Instant.now().isBefore(written.plusSeconds(10))) {
            if (me(ana.token) == 401) refusedAt = Instant.now() else Thread.sleep(100)
        }
        assertThat(refusedAt).describedAs("refused within the 2s cache window").isNotNull()
        assertThat(refusedAt).isBefore(written.plusSeconds(3))
    }

    @Test
    fun `a disabled, re-roled, rotated or deleted service account's tokens are refused`() {
        val account = createAccount()
        val disabled = accountToken(account)
        send(HttpMethod.PUT, "/api/service-accounts/${account.id}", mapOf("enabled" to false))
        assertThat(me(disabled)).isEqualTo(401)

        send(HttpMethod.PUT, "/api/service-accounts/${account.id}", mapOf("enabled" to true))
        val reRoled = accountToken(account)
        assertThat(me(reRoled)).isEqualTo(200)
        send(HttpMethod.PUT, "/api/service-accounts/${account.id}", mapOf("roles" to listOf(createRole())))
        assertThat(me(reRoled)).isEqualTo(401)

        val beforeRotation = accountToken(account)
        val rotated = json(send(HttpMethod.POST, "/api/service-accounts/${account.id}/secret"))
        assertThat(me(beforeRotation)).isEqualTo(401)
        val withNewSecret = accountToken(account.copy(secret = rotated["clientSecret"] as String))
        assertThat(me(withNewSecret)).isEqualTo(200)

        send(HttpMethod.DELETE, "/api/service-accounts/${account.id}", status = HttpStatus.NO_CONTENT)
        assertThat(me(withNewSecret)).isEqualTo(401)
    }

    private data class Person(
        val id: String,
        val email: String,
        val token: String
    )

    private data class Account(
        val id: String,
        val secret: String
    )

    private fun newUser(): Person {
        val email = "${uniqueName("revoked")}@wasichai.local"
        val created =
            json(
                send(
                    HttpMethod.POST,
                    "/api/users",
                    mapOf("email" to email, "displayName" to "Revoked", "password" to PASSWORD, "roles" to listOf(role)),
                    HttpStatus.CREATED
                )
            )
        return Person(created["id"] as String, email, bearer(email, PASSWORD))
    }

    private fun createRole(): String {
        val name = "R" + uniqueName("").uppercase()
        send(HttpMethod.POST, "/api/roles", mapOf("name" to name, "label" to "Revocation"), HttpStatus.CREATED)
        return name
    }

    private fun createAccount(): Account {
        val created = json(send(HttpMethod.POST, "/api/service-accounts", mapOf("name" to uniqueName("sa"), "roles" to listOf(role)), HttpStatus.CREATED))
        return Account(created["id"] as String, created["clientSecret"] as String)
    }

    private fun accountToken(account: Account): String =
        "Bearer " +
            json(
                client
                    .post()
                    .uri("/api/auth/token")
                    .bodyValue(mapOf("clientId" to account.id, "clientSecret" to account.secret))
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            )["token"]

    private fun me(token: String): Int =
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun get(
        uri: String,
        token: String
    ): String = send(HttpMethod.GET, uri, token = token)

    private fun send(
        method: HttpMethod,
        uri: String,
        body: Any? = null,
        status: HttpStatus = HttpStatus.OK,
        token: String = admin
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
