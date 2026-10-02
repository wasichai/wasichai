package wasichai.core.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import wasichai.core.platform.JwtProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtServiceTest {
    private val properties = JwtProperties(secret = "0123456789abcdef0123456789abcdef", issuer = "wasichai", ttl = Duration.ofHours(1))
    private val key = SecretKeySpec(properties.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Test
    fun `issues a token the resource server accepts, carrying tenant, email and roles`() {
        val user =
            User(
                id = UUID.randomUUID(),
                organizationId = UUID.randomUUID(),
                email = "ana@wasichai.local",
                passwordHash = "unused",
                displayName = "Ana"
            )

        val issued = JwtService(properties, WasichaiJwtKey(key)).issue(user, listOf("ADMIN"))

        val jwt =
            NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(issued.token)
                .block()!!
        assertThat(jwt.subject).isEqualTo(user.id.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(user.organizationId.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_EMAIL)).isEqualTo("ana@wasichai.local")
        assertThat(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES)).containsExactly("ADMIN")
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("wasichai")
    }

    @Test
    fun `a service account token names the account and lives for the service account ttl`() {
        val id = UUID.randomUUID()
        val organizationId = UUID.randomUUID()
        val withTtl = properties.copy(serviceAccountTtl = Duration.ofMinutes(5))
        val before = Instant.now()

        val issued =
            JwtService(withTtl, WasichaiJwtKey(key)).issueForServiceAccount(id, organizationId, "$id@service-accounts.invalid", "rentas", listOf("CAJA"))

        val jwt =
            NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(issued.token)
                .block()!!
        assertThat(jwt.subject).isEqualTo(id.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_ORGANIZATION)).isEqualTo(organizationId.toString())
        assertThat(jwt.getClaimAsString(JwtService.CLAIM_SERVICE_ACCOUNT)).isEqualTo("rentas")
        assertThat(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES)).containsExactly("CAJA")
        assertThat(issued.expiresAt).isBetween(before.plus(Duration.ofMinutes(5)).minusSeconds(2), before.plus(Duration.ofMinutes(5)).plusSeconds(2))
    }

    @Test
    fun `a user token carries no service account claim, and the service account ttl defaults short`() {
        val user = User(UUID.randomUUID(), UUID.randomUUID(), "ana@wasichai.local", "unused", "Ana")

        val issued = JwtService(properties, WasichaiJwtKey(key)).issue(user, emptyList())

        val jwt =
            NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(issued.token)
                .block()!!
        assertThat(jwt.hasClaim(JwtService.CLAIM_SERVICE_ACCOUNT)).isFalse()
        assertThat(JwtProperties().serviceAccountTtl).isEqualTo(Duration.ofMinutes(15))
    }
}
