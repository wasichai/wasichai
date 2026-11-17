package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// who signs people in: local (our own passwords and tokens) or oidc (a Keycloak realm). local by default.
enum class IdentityMode { LOCAL, OIDC }

// a realm client the app takes as one of its service accounts (ADR-086), with the roles it holds in every tenant.
// defaults: spring binds it by index from plain environment variables (..._SERVICEACCOUNTS_0_CLIENTID, ..._0_ROLES_0)
data class RealmServiceAccount(
    val clientId: String = "",
    val roles: List<String> = emptyList()
) {
    // as the role methods store a name: trimmed, upper-cased, each once
    fun normalizedRoles(): List<String> = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
}

// oidc: issuer and client id are required, the client id is also the audience a realm token must carry.
// jwkSetUri empty means keycloak's certs path under the issuer. rolesCache bounds how stale a realm user's roles can be.
// serviceAccounts: a list, not a map keyed by client id: a '-' cannot be a map key in an environment variable
data class Oidc(
    val issuerUri: String = "",
    val clientId: String = "",
    val jwkSetUri: String = "",
    val rolesCache: Duration = Duration.ofSeconds(5),
    val serviceAccounts: List<RealmServiceAccount> = emptyList()
) {
    init {
        require(!rolesCache.isNegative) { "wasichai.security.oidc.roles-cache must not be negative, was $rolesCache" }
        serviceAccounts.forEachIndexed { index, account ->
            val at = "$SERVICE_ACCOUNTS[$index]"
            // it becomes the account's name: same rule as a local one's
            require(ACCOUNT_NAME.matches(account.clientId)) { "$at.client-id must match ${ACCOUNT_NAME.pattern}, was '${account.clientId}'" }
            val roles = account.normalizedRoles()
            require(roles.isNotEmpty()) { "$at (${account.clientId}) needs at least one role" }
            // ADR-043: an account calls the api, it never administers the tenant
            require(ADMIN !in roles) { "$at (${account.clientId}) cannot hold $ADMIN" }
        }
        serviceAccounts.indices
            .groupBy { serviceAccounts[it].clientId }
            .filterValues { it.size > 1 }
            .entries
            .firstOrNull()
            ?.let { (clientId, indexes) -> error("${indexes.joinToString(" and ") { "$SERVICE_ACCOUNTS[$it]" }} have the same client-id $clientId") }
    }

    // by client id, roles normalized: what a realm client's token is looked up in
    val serviceAccountsByClientId: Map<String, RealmServiceAccount> =
        serviceAccounts.associate { it.clientId to it.copy(roles = it.normalizedRoles()) }

    private companion object {
        const val SERVICE_ACCOUNTS = "wasichai.security.oidc.service-accounts"
        const val ADMIN = "ADMIN"
        val ACCOUNT_NAME = Regex("^[a-z][a-z0-9_-]{1,48}$")
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
