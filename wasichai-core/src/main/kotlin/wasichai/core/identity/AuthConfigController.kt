package wasichai.core.identity

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.platform.WasichaiIdentityProperties

// how the ui signs people in. local: {"mode":"local"}, nothing else.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthConfig(
    val mode: String,
    val issuer: String? = null,
    val clientId: String? = null
)

// public: asked before anyone has a token
@RestController
@RequestMapping("/api/auth")
class AuthConfigController(
    identity: WasichaiIdentityProperties
) {
    private val config =
        if (identity.isOidc) {
            AuthConfig("oidc", identity.oidc.issuerUri, identity.oidc.clientId)
        } else {
            AuthConfig("local")
        }

    @GetMapping("/config")
    fun config(): AuthConfig = config
}
