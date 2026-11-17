package wasichai.core.identity.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.reactive.TransactionalOperator
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.JwtService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.RealmServiceAccount
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class RealmPrincipalConverterTest {
    private val realmIssuer = "https://auth.example.org/realms/peru"
    private val perene = UUID.randomUUID()
    private val ana = RealmUser(UUID.randomUUID(), perene, "ana@perene.gob.pe", enabled = true)
    private val luis = RealmUser(UUID.randomUUID(), perene, "luis@perene.gob.pe", enabled = false)

    // what the converter handed to resolve
    private data class Resolved(
        val subject: String,
        val email: String?,
        val emailVerified: Boolean,
        val displayName: String?
    )

    // the sql lives in RealmUsersTest; here only what the converter makes of the answers
    private class FakeUsers(
        private val organizations: Map<String, UUID>,
        private val bySubject: Map<String, RealmUser>,
        private val roles: Map<UUID, List<String>>
    ) : RealmUsers(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data"), Duration.ZERO) {
        val resolved = mutableListOf<Resolved>()

        override suspend fun organizationIdBySlug(slug: String): UUID? = organizations[slug]

        override suspend fun resolve(
            organizationId: UUID,
            subject: String,
            email: String?,
            emailVerified: Boolean,
            displayName: String?
        ): RealmUser? {
            resolved += Resolved(subject, email, emailVerified, displayName)
            return bySubject[subject]?.takeIf { it.organizationId == organizationId }
        }

        override suspend fun roles(userId: UUID): List<String> = roles[userId] ?: emptyList()
    }

    // the sql lives in RealmServiceAccountsTest; here the configured clients and what resolve answers
    private class FakeAccounts(
        configured: Map<String, RealmServiceAccount>,
        private val answers: Map<Pair<UUID, String>, RealmAccountResolution>
    ) : RealmServiceAccounts(
            mock(DatabaseClient::class.java),
            WasichaiSchemas("wasichai", "app_data"),
            configured,
            mock(ClusterLock::class.java),
            mock(AdminAudit::class.java),
            { mock(TransactionalOperator::class.java) },
            Duration.ZERO
        ) {
        val resolved = mutableListOf<Pair<UUID, String>>()

        override suspend fun resolve(
            organizationId: UUID,
            clientId: String
        ): RealmAccountResolution {
            resolved += organizationId to clientId
            return answers[organizationId to clientId] ?: RealmAccountResolution.Denied("client $clientId is not a service account of this app")
        }
    }

    private val carga = RealmAccount(UUID.randomUUID(), perene, "carga-perene", "x@service-accounts.invalid", listOf("CARGA_DATOS"))

    private val users = FakeUsers(mapOf("perene" to perene), mapOf("kc-ana" to ana, "kc-luis" to luis), mapOf(ana.id to listOf("CAJERO")))
    private val accounts =
        FakeAccounts(
            mapOf("carga-perene" to RealmServiceAccount("carga-perene", listOf("CARGA_DATOS"))),
            mapOf((perene to "carga-perene") to RealmAccountResolution.Found(carga))
        )
    private val converter = RealmPrincipalConverter(realmIssuer, users, accounts)

    private val issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun realmToken(
        subject: String = "kc-ana",
        organization: Any? = listOf("perene"),
        // null: no email_verified claim at all
        emailVerified: Any? = true
    ): Jwt {
        val builder =
            Jwt
                .withTokenValue("realm-token")
                .header("alg", "RS256")
                .header("kid", "k1")
                .issuer(realmIssuer)
                .subject(subject)
                .audience(listOf("caja"))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("email", "ana@perene.gob.pe")
                .claim("name", "Ana")
                .claim("azp", "caja")
        if (organization != null) builder.claim("organization", organization)
        if (emailVerified != null) builder.claim("email_verified", emailVerified)
        return builder.build()
    }

    private fun convert(jwt: Jwt): JwtAuthenticationToken = converter.convert(jwt).block() as JwtAuthenticationToken

    private fun denial(jwt: Jwt): String? {
        val auth = convert(jwt)
        assertThat(auth.isAuthenticated).isTrue()
        assertThat(auth.authorities).isEmpty()
        val translated = auth.token
        assertThat(translated.claims).doesNotContainKeys(JwtService.CLAIM_ORGANIZATION, JwtService.CLAIM_ROLES)
        assertThat(translated.subject).isNull()
        return translated.getClaimAsString(JwtService.CLAIM_DENIED)
    }

    @Test
    fun `an invited person is the app's user, with the app's roles`() {
        val jwt = realmToken()

        val translated = convert(jwt).token

        assertThat(translated.subject).isEqualTo(ana.id.toString())
        assertThat(translated.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(perene.toString())
        assertThat(translated.getClaimAsString(JwtService.CLAIM_EMAIL)).isEqualTo("ana@perene.gob.pe")
        assertThat(translated.getClaimAsStringList(JwtService.CLAIM_ROLES)).containsExactly("CAJERO")
        assertThat(translated.getClaimAsString(JwtService.CLAIM_DENIED)).isNull()
        // same token, same lifetime; nothing else of the realm's claims comes along
        assertThat(translated.tokenValue).isEqualTo("realm-token")
        assertThat(translated.headers).isEqualTo(jwt.headers)
        assertThat(translated.issuedAt).isEqualTo(jwt.issuedAt)
        assertThat(translated.expiresAt).isEqualTo(jwt.expiresAt)
        assertThat(translated.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(realmIssuer)
        assertThat(translated.claims).doesNotContainKeys("organization", "email_verified", "name", "azp", JwtClaimNames.AUD)
    }

    // the gate on linking by email: only a verified address may link a row (RealmUsers.resolve)
    @Test
    fun `email_verified reaches resolve as it is, with the email and the name`() {
        val cases = listOf(true to true, "true" to true, false to false, "false" to false, null to false)

        cases.forEach { (claim, expected) ->
            users.resolved.clear()

            convert(realmToken(emailVerified = claim))

            assertThat(users.resolved)
                .describedAs("email_verified = $claim")
                .containsExactly(Resolved("kc-ana", "ana@perene.gob.pe", expected, "Ana"))
        }
    }

    @Test
    fun `the organization claim may be a map keyed by alias`() {
        val translated = convert(realmToken(organization = mapOf("perene" to mapOf("id" to "x")))).token

        assertThat(translated.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(perene.toString())
        assertThat(translated.subject).isEqualTo(ana.id.toString())
    }

    @Test
    fun `no organization, or two, is a denial that says which`() {
        val none = "no organization in the token: sign in to one"
        val several = "several organizations in the token: sign in to one"

        assertThat(denial(realmToken(organization = null))).isEqualTo(none)
        assertThat(denial(realmToken(organization = emptyList<String>()))).isEqualTo(none)
        assertThat(denial(realmToken(organization = emptyMap<String, Any>()))).isEqualTo(none)
        assertThat(denial(realmToken(organization = listOf("perene", "satipo")))).isEqualTo(several)
        assertThat(denial(realmToken(organization = mapOf("perene" to emptyMap<String, Any>(), "satipo" to emptyMap<String, Any>())))).isEqualTo(several)
    }

    @Test
    fun `an organization the app does not have is a denial`() {
        assertThat(denial(realmToken(organization = listOf("satipo")))).isEqualTo("organization satipo is not in this app")
    }

    @Test
    fun `a person who was not invited is a denial, not an empty user`() {
        assertThat(denial(realmToken(subject = "kc-stranger"))).isEqualTo("you have no access to this app; ask its administrator")
    }

    @Test
    fun `a disabled row is a denial`() {
        assertThat(denial(realmToken(subject = "kc-luis"))).isEqualTo("your account in this app is disabled")
    }

    @Test
    fun `a service-account token passes through untouched`() {
        val local =
            Jwt
                .withTokenValue("local-token")
                .header("alg", "HS256")
                .issuer("wasichai")
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_ORGANIZATION, perene.toString())
                .claim(JwtService.CLAIM_ROLES, listOf("INTEGRADOR"))
                .claim(JwtService.CLAIM_SERVICE_ACCOUNT, "rentas")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .build()

        assertThat(convert(local).token).isSameAs(local)
    }

    // keycloak's client-credentials token: azp, service-account-<client>, no email
    private fun clientToken(
        azp: String = "carga-perene",
        username: String = "service-account-$azp",
        organization: Any? = listOf("perene"),
        email: String? = null
    ): Jwt {
        val builder =
            Jwt
                .withTokenValue("client-token")
                .header("alg", "RS256")
                .issuer(realmIssuer)
                .subject("kc-sa-${UUID.randomUUID()}")
                .audience(listOf("caja"))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .claim("azp", azp)
                .claim("preferred_username", username)
        if (organization != null) builder.claim("organization", organization)
        if (email != null) builder.claim("email", email)
        return builder.build()
    }

    @Test
    fun `a client token of a configured client is the app's service account`() {
        val translated = convert(clientToken()).token

        assertThat(translated.subject).isEqualTo(carga.id.toString())
        assertThat(translated.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(perene.toString())
        assertThat(translated.getClaimAsString(JwtService.CLAIM_EMAIL)).isEqualTo(carga.email)
        assertThat(translated.getClaimAsStringList(JwtService.CLAIM_ROLES)).containsExactly("CARGA_DATOS")
        assertThat(translated.getClaimAsString(JwtService.CLAIM_SERVICE_ACCOUNT)).isEqualTo("carga-perene")
        assertThat(translated.getClaimAsString(JwtService.CLAIM_DENIED)).isNull()
        assertThat(translated.claims).doesNotContainKeys("organization", "azp", "preferred_username", JwtClaimNames.AUD)
        assertThat(accounts.resolved).containsExactly(perene to "carga-perene")
        assertThat(users.resolved).isEmpty()
    }

    @Test
    fun `a token with an email is a person's even when its username looks like a service account`() {
        val translated = convert(clientToken(email = "ana@perene.gob.pe")).token

        // the person's path: resolved by subject, not invited, so denied as a person
        assertThat(accounts.resolved).isEmpty()
        assertThat(users.resolved).hasSize(1)
        assertThat(translated.getClaimAsString(JwtService.CLAIM_SERVICE_ACCOUNT)).isNull()
        assertThat(translated.getClaimAsString(JwtService.CLAIM_DENIED)).isEqualTo("you have no access to this app; ask its administrator")
    }

    @Test
    fun `a username that does not match azp is a person's`() {
        listOf(clientToken(azp = "x", username = "service-account-y"), clientToken(azp = "carga-perene", username = "carga-perene")).forEach { jwt ->
            accounts.resolved.clear()
            users.resolved.clear()

            val translated = convert(jwt).token

            assertThat(accounts.resolved).isEmpty()
            assertThat(users.resolved).hasSize(1)
            assertThat(translated.getClaimAsString(JwtService.CLAIM_SERVICE_ACCOUNT)).isNull()
        }
    }

    @Test
    fun `the rule reads azp, the username in lower case and the absence of email`() {
        assertThat(serviceAccountClientId(clientToken())).isEqualTo("carga-perene")
        // keycloak lower-cases the username, never the client id
        assertThat(serviceAccountClientId(clientToken(azp = "Carga", username = "service-account-carga"))).isEqualTo("Carga")
        assertThat(serviceAccountClientId(clientToken(azp = "Carga", username = "service-account-Carga"))).isNull()
        assertThat(serviceAccountClientId(clientToken(email = ""))).isNull()
        assertThat(serviceAccountClientId(clientToken(azp = " ", username = "service-account- "))).isNull()
        assertThat(serviceAccountClientId(realmToken())).isNull()
    }

    @Test
    fun `a client the app does not list is a denial that says so`() {
        assertThat(denial(clientToken(azp = "rentas-perene"))).isEqualTo("client rentas-perene is not a service account of this app")
        assertThat(users.resolved).isEmpty()
    }

    @Test
    fun `a client token with no organization is the same denial a person gets`() {
        assertThat(denial(clientToken(organization = null))).isEqualTo("no organization in the token: sign in to one")
        assertThat(denial(clientToken(organization = listOf("satipo")))).isEqualTo("organization satipo is not in this app")
        assertThat(accounts.resolved).isEmpty()
    }
}
