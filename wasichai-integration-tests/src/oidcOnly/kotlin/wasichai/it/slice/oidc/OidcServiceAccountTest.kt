package wasichai.it.slice.oidc

import com.nimbusds.jwt.JWTClaimsSet
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.metadata.DeclaredModel
import wasichai.core.metadata.DeclaredModels
import wasichai.core.metadata.DeclaredRole
import wasichai.core.metadata.PermissionEntry
import java.time.Instant
import java.util.Date
import java.util.UUID

// a realm client the app lists is one of its service accounts, in the token's organization (ADR-086)
@TestPropertySource(
    properties = [
        "wasichai.security.oidc.service-accounts[0].client-id=${OidcServiceAccountTest.CARGA}",
        "wasichai.security.oidc.service-accounts[0].roles=${OidcServiceAccountTest.CARGA_DATOS}"
    ]
)
@Import(OidcServiceAccountTest.Model::class)
class OidcServiceAccountTest : OidcIntegrationTest() {
    @Autowired
    private lateinit var declaredModels: DeclaredModels

    private val json = JsonMapper.builder().build()

    // the role the account is configured with, declared by the app so every tenant has it (ADR-085)
    @TestConfiguration(proxyBeanMethods = false)
    class Model {
        @Bean
        fun oidcItModel(): DeclaredModel = MODEL
    }

    @BeforeEach
    fun ensureRole() {
        runBlocking { declaredModels.ensure(organizationId(), MODEL) }
    }

    // keycloak's client-credentials token: azp, service-account-<client>, a sub of its own, no email
    private fun clientToken(
        clientId: String,
        organization: List<String> = listOf(ORGANIZATION),
        aud: List<String> = listOf(CLIENT_ID)
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(aud)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("azp", clientId)
                .claim("preferred_username", "service-account-$clientId")
                .claim("organization", organization)
                .build()
        return signed(claims)
    }

    private fun read(body: String?): JsonNode = json.readTree(body!!)

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

    @Test
    fun `a configured client reaches the api as a service account with its role`() {
        val me = me(clientToken(CARGA))

        assertThat(me.get("serviceAccount").asString()).isEqualTo(CARGA)
        assertThat(me.get("organizationId").asString()).isEqualTo(organizationId().toString())
        assertThat(me.get("email").asString()).endsWith("@service-accounts.invalid")
        assertThat(me.get("roles").let { roles -> (0 until roles.size()).map { roles.get(it).asString() } }).containsExactly(CARGA_DATOS)
        // a new sub, as a recreated client gets: the same account
        assertThat(me(clientToken(CARGA)).get("userId").asString()).isEqualTo(me.get("userId").asString())
    }

    @Test
    fun `it may define objects but never administer the tenant`() {
        val token = clientToken(CARGA)

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to uniqueName("carga_"), "label" to "Carga", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .get()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `a client the app does not list gets 403 saying so`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, clientToken("rentas-perene"))
            .exchange()
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("client rentas-perene is not a service account of this app")
    }

    @Test
    fun `a client token for another app is 401`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, clientToken(CARGA, aud = listOf("srtm")))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a client token for an organization the app does not have is 403`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, clientToken(CARGA, organization = listOf("satipo-none")))
            .exchange()
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("organization satipo-none is not in this app")
    }

    @Test
    fun `a person still signs in as before`() {
        val email = address("person")
        val userId = invite(email)

        val me = me(realmToken(UUID.randomUUID().toString(), email))

        assertThat(me.get("userId").asString()).isEqualTo(userId.toString())
        assertThat(me.has("serviceAccount")).isFalse()
    }

    companion object {
        const val CARGA = "carga-perene"
        const val CARGA_DATOS = "CARGA_DATOS"

        val MODEL =
            DeclaredModel(
                name = "oidc-it",
                objects = emptyList(),
                roles =
                    listOf(
                        DeclaredRole(
                            CARGA_DATOS,
                            "Carga de datos",
                            permissions = listOf(PermissionEntry(null, "READ"), PermissionEntry(null, "CREATE"), PermissionEntry(null, "MANAGE_METADATA"))
                        )
                    )
            )
    }
}
