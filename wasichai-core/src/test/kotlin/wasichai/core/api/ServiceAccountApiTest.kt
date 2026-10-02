package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.test.WasichaiIntegrationTest
import java.time.Duration
import java.time.Instant

// service accounts (ADR-043): a server-to-server caller of one tenant, client id + secret for a short-lived token
class ServiceAccountApiTest : WasichaiIntegrationTest() {
    private lateinit var admin: String
    private lateinit var objectName: String
    private lateinit var role: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("orden")
        createObject(objectName)
        role = newRole()
        grant(role, listOf(objectName to "READ", objectName to "CREATE"))
    }

    @Test
    fun `an account gets a token and calls the api as itself, and its writes are audited as it`() {
        val name = uniqueName("rentas")
        val created = createAccount(name, listOf(role))
        val before = Instant.now()

        val token =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to created.clientId, "clientSecret" to created.secret))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val expiresAt = Instant.parse(token.substringAfter("\"expiresAt\":\"").substringBefore("\""))
        // short default ttl, not the 8 h of a person
        assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(14)), before.plus(Duration.ofMinutes(16)))
        val sa = "Bearer " + token.substringAfter("\"token\":\"").substringBefore("\"")

        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, sa)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.serviceAccount")
            .isEqualTo(name)
            .jsonPath("$.userId")
            .isEqualTo(created.id)
            .jsonPath("$.roles[0]")
            .isEqualTo(role)
        // a person has none
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.serviceAccount")
            .doesNotExist()

        val recordId =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, sa)
                .bodyValue(mapOf("attributes" to mapOf("numero" to "OC-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
        // granted CREATE and READ only: nothing more
        client
            .delete()
            .uri("/api/objects/$objectName/records/$recordId")
            .header(HttpHeaders.AUTHORIZATION, sa)
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .get()
            .uri("/api/objects/$objectName/records/$recordId/history")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].operation")
            .isEqualTo("CREATE")
            .jsonPath("$[0].serviceAccount")
            .isEqualTo(name)
    }

    @Test
    fun `the secret is shown once and never again`() {
        val created = createAccount(uniqueName("rentas"), listOf(role))
        assertThat(created.secret).hasSizeGreaterThanOrEqualTo(43)

        val listed = get("/api/service-accounts")
        assertThat(listed)
            .contains(created.id)
            .doesNotContain(created.secret)
            .doesNotContain("clientSecret")
            .doesNotContain("secretHash")
        val one = get("/api/service-accounts/${created.id}")
        assertThat(one).contains("\"clientId\":\"${created.clientId}\"").doesNotContain(created.secret).doesNotContain("clientSecret")
        // its backing user is no person: not listed, cannot sign in
        assertThat(get("/api/users")).doesNotContain(created.id)
        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to "${created.id}@service-accounts.invalid", "password" to created.secret))
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .put()
            .uri("/api/users/${created.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("enabled" to true, "password" to "supersecret"))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `wrong, unknown, malformed and disabled credentials get the same 401`() {
        val created = createAccount(uniqueName("rentas"), listOf(role))
        val other = createAccount(uniqueName("catastro"), listOf(role))
        client
            .put()
            .uri("/api/service-accounts/${other.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("enabled" to false))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)

        val refusals =
            listOf(
                created.clientId to "not-the-secret",
                "00000000-0000-0000-0000-000000000000" to created.secret,
                "rentas" to created.secret,
                other.clientId to other.secret,
                created.clientId to "x".repeat(200)
            ).map { (id, secret) -> refusedDetail(id, secret) }

        assertThat(refusals.toSet()).containsExactly("Invalid client credentials")
        client
            .post()
            .uri("/api/auth/token")
            .bodyValue(mapOf("clientId" to created.clientId))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `rotating replaces the secret, revoking and deleting stop new tokens, a live token runs out its ttl`() {
        val created = createAccount(uniqueName("rentas"), listOf(role))
        val live = tokenFor(created.clientId, created.secret)

        val rotated =
            client
                .post()
                .uri("/api/service-accounts/${created.id}/secret")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"clientSecret\":\"")
                .substringBefore("\"")
        assertThat(rotated).isNotEqualTo(created.secret)
        assertThat(refusedDetail(created.clientId, created.secret)).isEqualTo("Invalid client credentials")
        tokenFor(created.clientId, rotated)

        client
            .put()
            .uri("/api/service-accounts/${created.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("enabled" to false))
            .exchange()
            .expectStatus()
            .isOk
        assertThat(refusedDetail(created.clientId, rotated)).isEqualTo("Invalid client credentials")
        // stateless jwt: a token issued before the revocation lives until it expires (ADR-043)
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, live)
            .exchange()
            .expectStatus()
            .isOk

        client
            .delete()
            .uri("/api/service-accounts/${created.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent
        client
            .get()
            .uri("/api/service-accounts/${created.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
        assertThat(refusedDetail(created.clientId, rotated)).isEqualTo("Invalid client credentials")
    }

    @Test
    fun `roles are replaced, ADMIN is refused, and a bad name or a repeated one is refused`() {
        val name = uniqueName("rentas")
        val created = createAccount(name, emptyList())
        val other = newRole()

        client
            .put()
            .uri("/api/service-accounts/${created.id}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("roles" to listOf(other.lowercase())))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.roles.length()")
            .isEqualTo(1)
            .jsonPath("$.roles[0]")
            .isEqualTo(other)
            .jsonPath("$.enabled")
            .isEqualTo(true)

        assertThat(createStatus(mapOf("name" to uniqueName("adm"), "roles" to listOf("ADMIN")))).isEqualTo(400)
        assertThat(
            client
                .put()
                .uri("/api/service-accounts/${created.id}")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("roles" to listOf("admin")))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value()
        ).isEqualTo(400)
        assertThat(createStatus(mapOf("name" to name))).isEqualTo(409)
        assertThat(createStatus(mapOf("name" to "Bad Name"))).isEqualTo(400)
        assertThat(createStatus(mapOf("name" to "x"))).isEqualTo(400)
        assertThat(createStatus(mapOf("name" to uniqueName("r"), "roles" to listOf("NO_SUCH_ROLE")))).isEqualTo(400)
    }

    @Test
    fun `a service account never administers the tenant, even with MANAGE_ORGANIZATION`() {
        val manager = newRole()
        grant(manager, listOf(null to "MANAGE_ORGANIZATION"))
        val created = createAccount(uniqueName("rentas"), listOf(manager))
        val sa = tokenFor(created.clientId, created.secret)

        listOf("/api/service-accounts", "/api/users", "/api/roles").forEach { path ->
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, sa)
                .exchange()
                .expectStatus()
                .isForbidden
        }
        client
            .post()
            .uri("/api/service-accounts")
            .header(HttpHeaders.AUTHORIZATION, sa)
            .bodyValue(mapOf("name" to uniqueName("child")))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `an organization manages only its own accounts`() {
        val created = createAccount(uniqueName("rentas"), listOf(role))
        val slug = "sa-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Other tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        val stranger = bearer("$slug@wasichai.local", "supersecret")

        val theirs =
            client
                .get()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, stranger)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(theirs).doesNotContain(created.id)
        foreign(stranger, client.get().uri("/api/service-accounts/${created.id}"))
        foreign(stranger, client.put().uri("/api/service-accounts/${created.id}").bodyValue(mapOf("enabled" to false)))
        foreign(stranger, client.post().uri("/api/service-accounts/${created.id}/secret"))
        foreign(stranger, client.delete().uri("/api/service-accounts/${created.id}"))
        // a role of the other tenant is not one of theirs
        client
            .post()
            .uri("/api/service-accounts")
            .header(HttpHeaders.AUTHORIZATION, stranger)
            .bodyValue(mapOf("name" to uniqueName("x"), "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isBadRequest

        // untouched: still issues tokens
        tokenFor(created.clientId, created.secret)
    }

    // ------------------------------------------------------------------ helpers

    private data class Created(
        val id: String,
        val clientId: String,
        val secret: String
    )

    private fun createAccount(
        name: String,
        roles: List<String>
    ): Created {
        val body =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to name, "roles" to roles))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        fun field(key: String) = body.substringAfter("\"$key\":\"").substringBefore("\"")
        assertThat(field("name")).isEqualTo(name)
        return Created(field("id"), field("clientId"), field("clientSecret"))
    }

    private fun createStatus(body: Map<String, Any>): Int =
        client
            .post()
            .uri("/api/service-accounts")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun tokenFor(
        clientId: String,
        secret: String
    ): String =
        "Bearer " +
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to clientId, "clientSecret" to secret))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"token\":\"")
                .substringBefore("\"")

    private fun refusedDetail(
        clientId: String,
        secret: String
    ): String =
        client
            .post()
            .uri("/api/auth/token")
            .bodyValue(mapOf("clientId" to clientId, "clientSecret" to secret))
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"detail\":\"")
            .substringBefore("\"")

    private fun foreign(
        token: String,
        request: WebTestClient.RequestHeadersSpec<*>
    ) {
        request
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound
    }

    private fun get(path: String): String =
        client
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Orden", "fields" to listOf(mapOf("name" to "numero", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Sistema", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun grant(
        role: String,
        entries: List<Pair<String?, String>>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to entries.map { (target, action) -> mapOf("objectName" to target, "action" to action, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }
}
