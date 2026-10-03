package wasichai.core.identity

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.UnauthorizedException
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class ServiceTokenRequest(
    @field:NotBlank val clientId: String,
    @field:NotBlank val clientSecret: String
)

data class ServiceTokenResponse(
    val token: String,
    val expiresAt: Instant,
    val serviceAccount: ServiceAccountSummary
)

data class ServiceAccountSummary(
    val id: String,
    val name: String,
    val organizationId: String,
    val roles: List<String>
)

// the secret: 256 bits from a csprng, url-safe. shown once, stored as a PasswordEncoder hash.
object ServiceAccountSecrets {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // the users row behind an account (ADR-043). .invalid never delivers mail; the id keeps it unique everywhere.
    fun backingEmail(id: UUID): String = "$id@service-accounts.invalid"
}

// client credentials -> short-lived jwt. one answer for every refusal, and one hash check for every
// request, so neither the message nor the timing tells an unknown id from a wrong secret.
@Service
class ServiceAccountTokenService(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val roleQueries: RoleQueries,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService
) {
    private class Credentials(
        val id: UUID,
        val organizationId: UUID,
        val name: String,
        val secretHash: String,
        val enabled: Boolean
    )

    // hashed once, at startup, with the same encoder: an unknown id pays what a wrong secret pays, the first one too
    private val decoyHash: String = passwordEncoder.encode(ServiceAccountSecrets.generate()) as String

    suspend fun token(
        clientId: String,
        clientSecret: String
    ): ServiceTokenResponse {
        val id = runCatching { UUID.fromString(clientId.trim()) }.getOrNull()
        val account = id?.let { find(it) }
        val matches = secretMatches(clientSecret, account?.secretHash ?: decoyHash)
        if (account == null || !account.enabled || !matches) throw invalidCredentials()
        val roles = roleQueries.roleNamesOf(account.id)
        val issued = jwtService.issueForServiceAccount(account.id, account.organizationId, ServiceAccountSecrets.backingEmail(account.id), account.name, roles)
        return ServiceTokenResponse(
            token = issued.token,
            expiresAt = issued.expiresAt,
            serviceAccount = ServiceAccountSummary(account.id.toString(), account.name, account.organizationId.toString(), roles)
        )
    }

    // an encoder may throw on input it cannot hash (bcrypt past 72 bytes): that is a wrong secret, not a 500.
    // off the event loop: a public endpoint that hashes on netty's threads is a cheap way to stall the server.
    private suspend fun secretMatches(
        secret: String,
        hash: String
    ): Boolean = withContext(Dispatchers.Default) { runCatching { passwordEncoder.matches(secret, hash) }.getOrDefault(false) }

    // no tenant to filter by yet: the client id is the account's own id, unique across tenants
    private suspend fun find(id: UUID): Credentials? =
        db
            .sql("SELECT id, organization_id, name, secret_hash, enabled FROM ${schemas.metadata}.service_accounts WHERE id = :id")
            .bind("id", id)
            .map { row, _ ->
                Credentials(
                    id = Rows.uuid(row, "id"),
                    organizationId = Rows.uuid(row, "organization_id"),
                    name = Rows.string(row, "name"),
                    secretHash = Rows.string(row, "secret_hash"),
                    enabled = Rows.bool(row, "enabled")
                )
            }.one()
            .awaitFirstOrNull()

    private fun invalidCredentials() = UnauthorizedException("Invalid client credentials")
}

@RestController
@RequestMapping("/api/auth")
class ServiceAccountTokenController(
    private val tokens: ServiceAccountTokenService
) {
    @PostMapping("/token")
    suspend fun token(
        @Valid @RequestBody request: ServiceTokenRequest
    ): ServiceTokenResponse = tokens.token(request.clientId, request.clientSecret)
}
