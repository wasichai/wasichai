package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// who signs people in: local (our own passwords and tokens) or oidc (a Keycloak realm). local by default.
enum class IdentityMode { LOCAL, OIDC }

// oidc: issuer and client id are required, the client id is also the audience a realm token must carry.
// jwkSetUri empty means keycloak's certs path under the issuer. rolesCache bounds how stale a realm user's roles can be.
data class Oidc(
    val issuerUri: String = "",
    val clientId: String = "",
    val jwkSetUri: String = "",
    val rolesCache: Duration = Duration.ofSeconds(5)
) {
    init {
        require(!rolesCache.isNegative) { "wasichai.security.oidc.roles-cache must not be negative, was $rolesCache" }
    }
}

fun Oidc.jwkSetUriOrDefault(): String = jwkSetUri.ifBlank { "${issuerUri.trimEnd('/')}/protocol/openid-connect/certs" }

@ConfigurationProperties(prefix = "wasichai.security")
data class WasichaiIdentityProperties(
    val identity: IdentityMode = IdentityMode.LOCAL,
    val oidc: Oidc = Oidc()
) {
    val isOidc: Boolean get() = identity == IdentityMode.OIDC

    init {
        if (isOidc) {
            require(oidc.issuerUri.isNotBlank()) { "wasichai.security.oidc.issuer-uri is required when wasichai.security.identity=oidc" }
            // iss match is exact: a trailing slash would 401 every token and nobody would see why
            require(!oidc.issuerUri.endsWith("/")) {
                "wasichai.security.oidc.issuer-uri must be the realm's issuer exactly, without a trailing slash, was ${oidc.issuerUri}"
            }
            require(oidc.clientId.isNotBlank()) { "wasichai.security.oidc.client-id is required when wasichai.security.identity=oidc" }
        }
    }
}
