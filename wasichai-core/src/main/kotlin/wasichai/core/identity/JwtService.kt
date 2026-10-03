package wasichai.core.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import wasichai.core.platform.JwtProperties
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

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
    ): IssuedToken = sign(user.id, user.organizationId, user.email, roles, properties.ttl, null)

    // same token shape as a person's, plus the account name: the app binds its caller to it (ADR-043)
    fun issueForServiceAccount(
        id: UUID,
        organizationId: UUID,
        email: String,
        name: String,
        roles: List<String>
    ): IssuedToken = sign(id, organizationId, email, roles, properties.serviceAccountTtl, name)

    private fun sign(
        subject: UUID,
        organizationId: UUID,
        email: String,
        roles: List<String>,
        ttl: Duration,
        serviceAccount: String?
    ): IssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(ttl)
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject.toString())
                .issuer(properties.issuer)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim(CLAIM_ORGANIZATION, organizationId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
        if (serviceAccount != null) builder.claim(CLAIM_SERVICE_ACCOUNT, serviceAccount)
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build())
        jwt.sign(MACSigner(secretKey))
        return IssuedToken(jwt.serialize(), expiresAt)
    }

    companion object {
        const val CLAIM_ORGANIZATION = "org"
        const val CLAIM_EMAIL = "email"
        const val CLAIM_ROLES = "roles"
        const val CLAIM_SERVICE_ACCOUNT = "service_account"
    }
}
