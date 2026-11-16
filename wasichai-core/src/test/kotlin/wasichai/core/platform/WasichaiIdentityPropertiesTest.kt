package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class WasichaiIdentityPropertiesTest {
    @Test
    fun `local by default, with no oidc settings needed`() {
        assertThat(WasichaiIdentityProperties().isOidc).isFalse()
    }

    @Test
    fun `oidc refuses a blank issuer or client id`() {
        assertThatThrownBy { WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = "", clientId = "caja")) }
            .hasMessageContaining("wasichai.security.oidc.issuer-uri")
        val realm = "https://auth.example.org/realms/peru"
        assertThatThrownBy { WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = realm, clientId = " ")) }
            .hasMessageContaining("wasichai.security.oidc.client-id")
        assertThat(WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = realm, clientId = "caja")).isOidc).isTrue()
    }

    @Test
    fun `the jwk set defaults to keycloak's path under the issuer`() {
        assertThat(Oidc(issuerUri = "https://auth.example.org/realms/peru/").jwkSetUriOrDefault())
            .isEqualTo("https://auth.example.org/realms/peru/protocol/openid-connect/certs")
        assertThat(Oidc(issuerUri = "https://a/realms/x", jwkSetUri = "https://internal/certs").jwkSetUriOrDefault())
            .isEqualTo("https://internal/certs")
    }

    @Test
    fun `a negative roles cache is refused`() {
        assertThatThrownBy { Oidc(rolesCache = Duration.ofSeconds(-1)) }
            .hasMessageContaining("wasichai.security.oidc.roles-cache")
    }
}
