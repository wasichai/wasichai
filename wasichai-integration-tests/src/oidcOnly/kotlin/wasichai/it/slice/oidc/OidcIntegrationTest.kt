package wasichai.it.slice.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.identity.oidc.RealmJwtDecoder
import wasichai.core.identity.oidc.realmValidator
import wasichai.test.WasichaiTestDatabase
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

// oidc mode against a real database. not a WasichaiIntegrationTest: that one turns the dev seed on, which oidc refuses.
// the realm is a test key: tokens signed here, checked by the app's own validator (iss, aud, timestamps).
// the organization, its ADMIN and the invited people are written with sql, as an operator would.
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@TestPropertySource(
    properties = [
        "wasichai.seed.dev=false",
        "wasichai.security.jwt.secret=oidc-integration-test-secret-0123456789",
        "wasichai.security.identity=oidc",
        "wasichai.security.oidc.issuer-uri=${OidcIntegrationTest.ISSUER}",
        "wasichai.security.oidc.client-id=${OidcIntegrationTest.CLIENT_ID}",
        "wasichai.security.oidc.roles-cache=0s",
        // service accounts' tokens are the app's own: revoked ones must be refused beside the realm
        "wasichai.security.jwt.revocation=true",
        "server.address=127.0.0.1"
    ]
)
@Import(OidcIntegrationTest.TestRealm::class)
abstract class OidcIntegrationTest {
    @Autowired
    protected lateinit var client: WebTestClient

    @Autowired
    protected lateinit var db: DatabaseClient

    @TestConfiguration(proxyBeanMethods = false)
    class TestRealm {
        @Bean
        fun realmJwtDecoder(): RealmJwtDecoder {
            val decoder = NimbusReactiveJwtDecoder.withPublicKey(KEYS.public as RSAPublicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build()
            decoder.setJwtValidator(realmValidator(ISSUER, CLIENT_ID))
            return RealmJwtDecoder(ISSUER, decoder)
        }
    }

    // the database is shared and kept between runs: every test its own names
    protected fun uniqueName(prefix: String): String =
        prefix +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    protected fun address(prefix: String): String = "${uniqueName(prefix)}@perene.gob.pe"

    // a token as keycloak signs it: sub is the realm's own id, never the app's
    protected fun realmToken(
        sub: String,
        email: String,
        organization: List<String> = listOf(ORGANIZATION),
        aud: List<String> = listOf(CLIENT_ID),
        emailVerified: Boolean = true
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(ISSUER)
                .subject(sub)
                .audience(aud)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("organization", organization)
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims)
        jwt.sign(RSASSASigner(KEYS.private as RSAPrivateKey))
        return "Bearer ${jwt.serialize()}"
    }

    // perene, with an ADMIN that may administer it: what V1__seed_dev grants its own
    protected fun organizationId(): UUID =
        runBlocking {
            db
                .sql("INSERT INTO wasichai.organizations (id, name, slug) VALUES (:id, 'Perené', :slug) ON CONFLICT (slug) DO NOTHING")
                .bind("id", UUID.randomUUID())
                .bind("slug", ORGANIZATION)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            val organizationId =
                db
                    .sql("SELECT id FROM wasichai.organizations WHERE slug = :slug")
                    .bind("slug", ORGANIZATION)
                    .map { row, _ -> row.get("id", UUID::class.java)!! }
                    .one()
                    .awaitSingle()
            db
                .sql(
                    "INSERT INTO wasichai.roles (id, organization_id, name, label) VALUES (:id, :org, 'ADMIN', 'Administrator') " +
                        "ON CONFLICT (organization_id, name) DO NOTHING"
                ).bind("id", UUID.randomUUID())
                .bind("org", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            db
                .sql(
                    """
                    INSERT INTO wasichai.permissions (role_id, object_id, action)
                    SELECT r.id, NULL, action
                    FROM wasichai.roles r, unnest(ARRAY['READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION']) AS action
                    WHERE r.organization_id = :org AND r.name = 'ADMIN'
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).bind("org", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            organizationId
        }

    // an invitation: a row with no password and no subject yet. the realm links it on the first sign-in
    protected fun invite(
        email: String,
        roles: List<String> = listOf("ADMIN")
    ): UUID {
        val organizationId = organizationId()
        val id = UUID.randomUUID()
        runBlocking {
            db
                .sql(
                    "INSERT INTO wasichai.users (id, organization_id, email, password_hash, display_name) " +
                        "VALUES (:id, :org, :email, NULL, 'Invited')"
                ).bind("id", id)
                .bind("org", organizationId)
                .bind("email", email)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            roles.forEach { role ->
                db
                    .sql(
                        "INSERT INTO wasichai.user_roles (user_id, role_id) " +
                            "SELECT :id, r.id FROM wasichai.roles r WHERE r.organization_id = :org AND r.name = :role"
                    ).bind("id", id)
                    .bind("org", organizationId)
                    .bind("role", role)
                    .fetch()
                    .rowsUpdated()
                    .awaitFirstOrNull()
            }
        }
        return id
    }

    companion object {
        const val ISSUER = "https://auth.example.org/realms/peru"
        const val CLIENT_ID = "caja"
        const val ORGANIZATION = "perene"

        private val KEYS: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            WasichaiTestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
