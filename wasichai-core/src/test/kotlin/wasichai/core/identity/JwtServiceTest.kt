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

    // issue 55 (ADR-059): every token is named; a login in the second after a change is not taken for an older one
    @Test
    fun `every token has its own jti, and iat is never before the user's revocation marker`() {
        val service = JwtService(properties, WasichaiJwtKey(key))
        val decoder = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
        val user = User(UUID.randomUUID(), UUID.randomUUID(), "ana@wasichai.local", "unused", "Ana")

        val first = decoder.decode(service.issue(user, emptyList()).token).block()!!
        val second = decoder.decode(service.issue(user, emptyList()).token).block()!!
        assertThat(first.id).isNotBlank()
        assertThat(first.id).isNotEqualTo(second.id)

        val marker = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1)
        val after = decoder.decode(service.issue(user.copy(tokensValidAfter = marker), emptyList()).token).block()!!
        assertThat(after.issuedAt).isEqualTo(marker)
        val account = decoder.decode(service.issueForServiceAccount(user.id, user.organizationId, "x", "rentas", emptyList(), marker).token).block()!!
        assertThat(account.issuedAt).isEqualTo(marker)

        // a marker in the past changes nothing
        val past = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val later = decoder.decode(service.issue(user.copy(tokensValidAfter = past), emptyList()).token).block()!!
        assertThat(later.issuedAt).isAfter(past)
    }
}
