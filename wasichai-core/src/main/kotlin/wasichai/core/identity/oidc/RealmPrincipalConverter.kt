package wasichai.core.identity.oidc

import kotlinx.coroutines.reactor.mono
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter
import reactor.core.publisher.Mono
import wasichai.core.identity.JwtService

private const val ORGANIZATION_CLAIM = "organization"
private const val EMAIL_VERIFIED_CLAIM = "email_verified"
private const val NAME_CLAIM = "name"

// keycloak's organization claim: list of aliases, or map keyed by alias (with attributes). exactly one, or a reason.
fun organizationAlias(jwt: Jwt): Result<String> {
    val aliases =
        when (val claim = jwt.claims[ORGANIZATION_CLAIM]) {
            is Collection<*> -> claim.map { it?.toString() }
            is Map<*, *> -> claim.keys.map { it?.toString() }
            is String -> listOf(claim)
            else -> emptyList()
        }.filterNotNull().filter { it.isNotBlank() }.distinct()
    return when (aliases.size) {
        0 -> Result.failure(IllegalArgumentException("no organization in the token: sign in to one"))
        1 -> Result.success(aliases.single())
        else -> Result.failure(IllegalArgumentException("several organizations in the token: sign in to one"))
    }
}

// realm token -> the app's own claims, so CurrentUser reads it like a local one: sub = users.id, org, email, roles
// (roles from user_roles, never the realm's). no access: still authenticated, wasichai_denied = reason, no sub/org,
// no authorities; CurrentUser.require() answers 403 with it. any other issuer: as today, untouched.
// a realm client's token (isServiceAccountToken) is the app's service account instead, with service_account (ADR-086)
class RealmPrincipalConverter(
    private val realmIssuer: String,
    private val users: RealmUsers,
    private val accounts: RealmServiceAccounts
) : Converter<Jwt, Mono<AbstractAuthenticationToken>> {
    private val passThrough = ReactiveJwtAuthenticationConverter()

    override fun convert(jwt: Jwt): Mono<AbstractAuthenticationToken> {
        if (jwt.getClaimAsString(JwtClaimNames.ISS) != realmIssuer) return passThrough.convert(jwt)
        return mono { translate(jwt) }
    }

    private suspend fun translate(jwt: Jwt): AbstractAuthenticationToken {
        val alias = organizationAlias(jwt).getOrElse { return denied(jwt, it.message!!) }
        val organizationId = users.organizationIdBySlug(alias) ?: return denied(jwt, "organization $alias is not in this app")
        isServiceAccountToken(jwt)?.let { clientId ->
            return when (val resolution = accounts.resolve(organizationId, clientId)) {
                is RealmAccountResolution.Found -> serviceAccount(jwt, resolution.account)
                is RealmAccountResolution.Denied -> denied(jwt, resolution.reason)
            }
        }
        val user =
            jwt.subject?.let { subject ->
                users.resolve(
                    organizationId,
                    subject,
                    jwt.getClaimAsString(JwtService.CLAIM_EMAIL),
                    jwt.getClaimAsBoolean(EMAIL_VERIFIED_CLAIM) == true,
                    jwt.getClaimAsString(NAME_CLAIM)
                )
            } ?: return denied(jwt, "you have no access to this app; ask its administrator")
        if (!user.enabled) return denied(jwt, "your account in this app is disabled")
        val roles = users.roles(user.id)
        val translated =
            base(jwt)
                .subject(user.id.toString())
                .claim(JwtService.CLAIM_ORGANIZATION, user.organizationId.toString())
                .claim(JwtService.CLAIM_EMAIL, user.email)
                .claim(JwtService.CLAIM_ROLES, roles)
                .build()
        return JwtAuthenticationToken(translated, emptyList())
    }

    // ADR-043 as for a local account's token: never ADMIN, never MANAGE_ORGANIZATION, whatever its roles grant
    private fun serviceAccount(
        jwt: Jwt,
        account: RealmAccount
    ): AbstractAuthenticationToken {
        val translated =
            base(jwt)
                .subject(account.id.toString())
                .claim(JwtService.CLAIM_ORGANIZATION, account.organizationId.toString())
                .claim(JwtService.CLAIM_EMAIL, account.email)
                .claim(JwtService.CLAIM_ROLES, account.roles)
                .claim(JwtService.CLAIM_SERVICE_ACCOUNT, account.name)
                .build()
        return JwtAuthenticationToken(translated, emptyList())
    }

    // name = the realm's sub, for logs only: there is no app user behind it
    private fun denied(
        jwt: Jwt,
        reason: String
    ): AbstractAuthenticationToken {
        val translated = base(jwt).claim(JwtService.CLAIM_DENIED, reason).build()
        return JwtAuthenticationToken(translated, emptyList(), jwt.subject ?: "denied")
    }

    // same token value, headers, iat, exp; iss = the realm. nothing else of the realm's claims
    private fun base(jwt: Jwt): Jwt.Builder {
        val builder =
            Jwt
                .withTokenValue(jwt.tokenValue)
                .headers { it.putAll(jwt.headers) }
                .issuer(realmIssuer)
        jwt.issuedAt?.let { builder.issuedAt(it) }
        jwt.expiresAt?.let { builder.expiresAt(it) }
        return builder
    }
}
