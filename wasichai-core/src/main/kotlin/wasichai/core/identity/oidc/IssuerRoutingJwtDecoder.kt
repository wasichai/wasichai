package wasichai.core.identity.oidc

import com.nimbusds.jwt.JWTParser
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono

// picks decoder by unverified iss. safe: the chosen decoder still verifies signature and iss.
// unknown iss never reaches a key.
class IssuerRoutingJwtDecoder(
    private val realm: RealmJwtDecoder,
    private val local: ReactiveJwtDecoder,
    private val localIssuer: String
) : ReactiveJwtDecoder {
    override fun decode(token: String): Mono<Jwt> {
        val issuer =
            try {
                JWTParser.parse(token).jwtClaimsSet.issuer
            } catch (e: Exception) {
                return Mono.error(BadJwtException("Unreadable token", e))
            }
        return when (issuer) {
            realm.issuer -> realm.decoder.decode(token)
            localIssuer -> local.decode(token)
            else -> Mono.error(BadJwtException("Unknown token issuer"))
        }
    }
}
