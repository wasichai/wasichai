package wasichai.core.identity.oidc

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import wasichai.core.platform.Oidc
import wasichai.core.platform.jwkSetUriOrDefault

// own type, like WasichaiJwtKey: a test can swap the realm decoder without touching the app's one.
class RealmJwtDecoder(
    val issuer: String,
    val decoder: ReactiveJwtDecoder
)

// timestamps + iss + aud has clientId. aud check stops a token minted for another client of the realm.
fun realmValidator(
    issuer: String,
    clientId: String
): OAuth2TokenValidator<Jwt> {
    val audience =
        OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.audience?.contains(clientId) == true) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "The aud claim does not contain $clientId", null))
            }
        }
    return DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(issuer), audience)
}

// lazy: jwk set is fetched on first token, so boot never calls keycloak.
fun realmJwtDecoder(oidc: Oidc): RealmJwtDecoder {
    val decoder =
        NimbusReactiveJwtDecoder
            .withJwkSetUri(oidc.jwkSetUriOrDefault())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build()
    decoder.setJwtValidator(realmValidator(oidc.issuerUri, oidc.clientId))
    return RealmJwtDecoder(oidc.issuerUri, decoder)
}
