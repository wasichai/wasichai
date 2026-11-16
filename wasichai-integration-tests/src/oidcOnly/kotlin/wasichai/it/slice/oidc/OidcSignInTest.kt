package wasichai.it.slice.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

// people sign in through the realm; the app keeps who may enter and with which roles
class OidcSignInTest : OidcIntegrationTest() {
    private val json = JsonMapper.builder().build()

    private val notInvited = "you have no access to this app; ask its administrator"

    private fun read(body: String?): JsonNode = json.readTree(body!!)

    private fun JsonNode.roles(): List<String> = get("roles").let { roles -> (0 until roles.size()).map { roles.get(it).asString() } }

    private fun me(token: String): JsonNode =
        read(
            client
                .get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        )

    private fun admin(): String {
        val email = address("admin")
        invite(email)
        return realmToken(UUID.randomUUID().toString(), email)
    }

    @Test
    fun `the config tells the ui to sign in through the realm`() {
        client
            .get()
            .uri("/api/auth/config")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"mode":"oidc","issuer":"$ISSUER","clientId":"$CLIENT_ID"}""", true)
    }

    @Test
    fun `an invited person reaches the api as the app's user`() {
        val email = address("invited")
        val userId = invite(email)
        val realmSubject = UUID.randomUUID().toString()

        val me = me(realmToken(realmSubject, email))

        // the app's users.id, never keycloak's sub: the converter and the decoder agree on the issuer
        assertThat(me.get("userId").asString()).isEqualTo(userId.toString()).isNotEqualTo(realmSubject)
        assertThat(me.get("organizationId").asString()).isEqualTo(organizationId().toString())
        assertThat(me.get("email").asString()).isEqualTo(email)
        assertThat(me.roles()).containsExactly("ADMIN")
        // and again, now linked by subject
        assertThat(me(realmToken(realmSubject, email)).get("userId").asString()).isEqualTo(userId.toString())
    }

    @Test
    fun `an administrator invites without a password, and the invitee then signs in`() {
        val email = address("newcomer")
        val created =
            read(
                client
                    .post()
                    .uri("/api/users")
                    .header(HttpHeaders.AUTHORIZATION, admin())
                    .bodyValue(mapOf("email" to email, "displayName" to "Newcomer", "roles" to listOf("ADMIN")))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody
            )

        val me = me(realmToken(UUID.randomUUID().toString(), email))

        assertThat(me.get("userId").asString()).isEqualTo(created.get("id").asString())
        assertThat(me.roles()).containsExactly("ADMIN")
    }

    @Test
    fun `a password in an invitation is refused`() {
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(mapOf("email" to address("withpass"), "displayName" to "With Password", "password" to "s3cret-enough"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("password")
            .jsonPath("$.errors[0].message")
            .isEqualTo("passwords live in the identity provider")
    }

    @Test
    fun `a new password for a user is refused`() {
        val id = invite(address("someone"))
        client
            .put()
            .uri("/api/users/$id")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(mapOf("password" to "s3cret-enough"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("password")
            .jsonPath("$.errors[0].message")
            .isEqualTo("passwords live in the identity provider")
    }

    @Test
    fun `a new organization's administrator has no password, and one given is refused`() {
        val slug = uniqueName("org").lowercase()
        val request = mapOf("name" to slug, "slug" to slug, "adminEmail" to address("orgadmin"))
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(request + ("adminPassword" to "s3cret-enough"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("adminPassword")
            .jsonPath("$.errors[0].message")
            .isEqualTo("passwords live in the identity provider")
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a person signed in to the realm but not invited gets 403 saying so`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, realmToken(UUID.randomUUID().toString(), address("stranger")))
            .exchange()
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo(notInvited)
    }

    @Test
    fun `a denied person is stopped before any endpoint, in the app's own error shape`() {
        val denied =
            client
                .get()
                .uri("/api/probe")
                .header(HttpHeaders.AUTHORIZATION, realmToken(UUID.randomUUID().toString(), address("stranger")))
                .exchange()
                .expectStatus()
                .isForbidden
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        // a ForbiddenException the app itself raised: someone invited, without a role
        val email = address("noroles")
        invite(email, roles = emptyList())
        val forbidden =
            client
                .get()
                .uri("/api/users")
                .header(HttpHeaders.AUTHORIZATION, realmToken(UUID.randomUUID().toString(), email))
                .exchange()
                .expectStatus()
                .isForbidden
                .expectBody(String::class.java)
                .returnResult()
                .responseBody

        val body = read(denied)
        assertThat(body.get("detail").asString()).isEqualTo(notInvited)
        assertThat(body.propertyNames().asSequence().toSet()).isEqualTo(read(forbidden).propertyNames().asSequence().toSet())
        listOf("type", "title", "status").forEach { assertThat(body.get(it)).isEqualTo(read(forbidden).get(it)) }
        assertThat(body.get("instance").asString()).isEqualTo("/api/probe")
    }

    @Test
    fun `an invited person reaches an app endpoint`() {
        val email = address("prober")
        invite(email)
        client
            .get()
            .uri("/api/probe")
            .header(HttpHeaders.AUTHORIZATION, realmToken(UUID.randomUUID().toString(), email))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a token of the realm for another app is 401`() {
        val email = address("otherapp")
        invite(email)
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, realmToken(UUID.randomUUID().toString(), email, aud = listOf("srtm")))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `local sign-in and sign-out are not there`() {
        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to address("local"), "password" to "anything"))
            .exchange()
            .expectStatus()
            .isNotFound
        client
            .post()
            .uri("/api/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `a service account of the app still gets and uses its token`() {
        val name = uniqueName("svc").lowercase()
        val account =
            read(
                client
                    .post()
                    .uri("/api/service-accounts")
                    .header(HttpHeaders.AUTHORIZATION, admin())
                    .bodyValue(mapOf("name" to name))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody
            )
        val token =
            read(
                client
                    .post()
                    .uri("/api/auth/token")
                    .bodyValue(mapOf("clientId" to account.get("clientId").asString(), "clientSecret" to account.get("clientSecret").asString()))
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody
            ).get("token").asString()

        assertThat(me("Bearer $token").get("serviceAccount").asString()).isEqualTo(name)
    }
}
