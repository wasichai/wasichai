package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
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
    fun `oidc refuses an issuer with a trailing slash, local does not care`() {
        assertThatThrownBy { WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = "https://a/realms/x/", clientId = "caja")) }
            .hasMessageContaining("without a trailing slash")
        assertThat(WasichaiIdentityProperties(IdentityMode.LOCAL, Oidc(issuerUri = "https://a/realms/x/")).isOidc).isFalse()
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

    @Test
    fun `a service-account client id that is no account name is refused`() {
        assertThatThrownBy { Oidc(serviceAccounts = listOf(RealmServiceAccount("Carga Ica", listOf("CARGA_DATOS")))) }
            .hasMessageContaining("wasichai.security.oidc.service-accounts[0]")
            .hasMessageContaining("Carga Ica")
        assertThatThrownBy {
            Oidc(serviceAccounts = listOf(RealmServiceAccount("carga-ica", listOf("CARGA_DATOS")), RealmServiceAccount("", listOf("CARGA_DATOS"))))
        }.hasMessageContaining("wasichai.security.oidc.service-accounts[1]")
    }

    @Test
    fun `a client id twice in the list is refused`() {
        val twice =
            listOf(
                RealmServiceAccount("carga-ica", listOf("CARGA_DATOS")),
                RealmServiceAccount("srtm-ica", listOf("SRTM_LECTOR")),
                RealmServiceAccount("carga-ica", listOf("OTRO"))
            )

        assertThatThrownBy { Oidc(serviceAccounts = twice) }
            .hasMessageContaining("wasichai.security.oidc.service-accounts[0]")
            .hasMessageContaining("wasichai.security.oidc.service-accounts[2]")
            .hasMessageContaining("carga-ica")
    }

    @Test
    fun `a service-account client with no role, or with ADMIN, is refused`() {
        assertThatThrownBy { Oidc(serviceAccounts = listOf(RealmServiceAccount("carga-ica", emptyList()))) }
            .hasMessageContaining("wasichai.security.oidc.service-accounts[0]")
            .hasMessageContaining("role")
        assertThatThrownBy { Oidc(serviceAccounts = listOf(RealmServiceAccount("carga-ica", listOf(" ")))) }
            .hasMessageContaining("wasichai.security.oidc.service-accounts[0]")
        assertThatThrownBy {
            Oidc(
                serviceAccounts = listOf(RealmServiceAccount("carga-ica", listOf("CARGA_DATOS")), RealmServiceAccount("srtm-ica", listOf("lector", " admin ")))
            )
        }.hasMessageContaining("wasichai.security.oidc.service-accounts[1]")
            .hasMessageContaining("ADMIN")
    }

    @Test
    fun `the lookup by client id has the roles upper-cased, each once`() {
        val oidc = Oidc(serviceAccounts = listOf(RealmServiceAccount("carga-ica", listOf(" carga_datos", "CARGA_DATOS", "Lector"))))

        assertThat(oidc.serviceAccountsByClientId).containsOnlyKeys("carga-ica")
        assertThat(oidc.serviceAccountsByClientId.getValue("carga-ica").roles).containsExactly("CARGA_DATOS", "LECTOR")
    }

    // the kit sets the list with plain environment variables, one index per client
    @Test
    fun `the list binds from environment variables by index, dashes in the client id kept`() {
        val env =
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf<String, Any>(
                    "WASICHAI_SECURITY_IDENTITY" to "oidc",
                    "WASICHAI_SECURITY_OIDC_ISSUERURI" to "https://auth.example.org/realms/peru",
                    "WASICHAI_SECURITY_OIDC_CLIENTID" to "catastro",
                    "WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_CLIENTID" to "srtm-catastro-muni-ica",
                    "WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_ROLES_0" to "SRTM_LECTOR",
                    "WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_ROLES_1" to "CATASTRO_LECTOR",
                    "WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_1_CLIENTID" to "carga-muni-ica",
                    "WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_1_ROLES_0" to "CARGA_DATOS"
                )
            )

        val bound = Binder(ConfigurationPropertySource.from(env)!!).bind("wasichai.security", WasichaiIdentityProperties::class.java).get()

        assertThat(bound.isOidc).isTrue()
        assertThat(bound.oidc.clientId).isEqualTo("catastro")
        assertThat(bound.oidc.serviceAccounts).containsExactly(
            RealmServiceAccount("srtm-catastro-muni-ica", listOf("SRTM_LECTOR", "CATASTRO_LECTOR")),
            RealmServiceAccount("carga-muni-ica", listOf("CARGA_DATOS"))
        )
        assertThat(bound.oidc.serviceAccountsByClientId.keys).containsExactly("srtm-catastro-muni-ica", "carga-muni-ica")
    }
}
