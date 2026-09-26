package wasichai.core.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import wasichai.core.platform.JwtProperties
import java.time.Instant
import java.util.Date

data class IssuedToken(
    val token: String,
    val expiresAt: Instant
)

// HS256, symmetric. swap for OIDC later without touching the permission model. ADR-010.
@Service
class JwtService(
    private val properties: JwtProperties,
    wasichaiJwtKey: WasichaiJwtKey
) {
    private val secretKey: javax.crypto.SecretKey = wasichaiJwtKey.key

    fun issue(
        user: User,
        roles: List<String>
    ): IssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(properties.ttl)
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(user.id.toString())
                .issuer(properties.issuer)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim(CLAIM_ORGANIZATION, user.organizationId.toString())
                .claim(CLAIM_EMAIL, user.email)
                .claim(CLAIM_ROLES, roles)
                .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(MACSigner(secretKey))
        return IssuedToken(jwt.serialize(), expiresAt)
    }

    companion object {
        const val CLAIM_ORGANIZATION = "org"
        const val CLAIM_EMAIL = "email"
        const val CLAIM_ROLES = "roles"
    }
}
