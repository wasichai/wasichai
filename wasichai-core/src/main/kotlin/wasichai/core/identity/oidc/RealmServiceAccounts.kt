package wasichai.core.identity.oidc

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.JwtService
import wasichai.core.identity.ServiceAccountSecrets
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.RealmServiceAccount
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val AZP_CLAIM = "azp"
private const val USERNAME_CLAIM = "preferred_username"
private const val SERVICE_ACCOUNT_USERNAME = "service-account-"

// keycloak's client-credentials token: no email, an azp, and its username service-account-<azp in lower case>.
// answers the azp; null = a person's token, whatever else it carries (ADR-086)
fun isServiceAccountToken(jwt: Jwt): String? {
    if (jwt.claims[JwtService.CLAIM_EMAIL] != null) return null
    val azp = jwt.getClaimAsString(AZP_CLAIM)?.takeIf { it.isNotBlank() } ?: return null
    return azp.takeIf { jwt.getClaimAsString(USERNAME_CLAIM) == SERVICE_ACCOUNT_USERNAME + azp.lowercase() }
}

// the app's account behind a realm client, in one organization. email: its backing user's
data class RealmAccount(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val email: String,
    val roles: List<String>
)

sealed interface RealmAccountResolution {
    data class Found(
        val account: RealmAccount
    ) : RealmAccountResolution

    // the reason, for the denied token's 403
    data class Denied(
        val reason: String
    ) : RealmAccountResolution
}

// realm client -> the app's service account (oidc mode, ADR-086). open: unit tests fake it.
// the row is found by (organization, client id), never by sub: a recreated client keeps its row. made on the first
// token of a configured client; roles follow the configuration. every statement filters by organization.
open class RealmServiceAccounts(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    configured: Map<String, RealmServiceAccount>,
    private val lock: ClusterLock,
    private val audit: AdminAudit,
    // resolved on first write, as ClusterLock's: a context with no transaction manager still starts
    transactions: () -> TransactionalOperator,
    private val cache: Duration,
    private val clock: Clock = Clock.systemUTC()
) {
    private class Entry(
        val account: RealmAccount,
        val loadedAt: Instant
    )

    private class Row(
        val id: UUID,
        val name: String,
        val email: String,
        val enabled: Boolean
    )

    // whoever built the map: roles as stored
    private val configured = configured.mapValues { it.value.normalizedRoles() }

    private val transactions by lazy(transactions)

    private val found = ConcurrentHashMap<Pair<UUID, String>, Entry>()

    open suspend fun resolve(
        organizationId: UUID,
        clientId: String
    ): RealmAccountResolution {
        val roles = configured[clientId] ?: return RealmAccountResolution.Denied("client $clientId is not a service account of this app")
        val key = organizationId to clientId
        found[key]?.let { if (Instant.now(clock).isBefore(it.loadedAt.plus(cache))) return RealmAccountResolution.Found(it.account) }
        // before any write: a missing role leaves nothing behind
        val roleIds = mutableMapOf<String, UUID>()
        for (role in roles) {
            roleIds[role] = roleId(organizationId, role) ?: return RealmAccountResolution.Denied("role $role of client $clientId is not in this organization")
        }
        val row = byClientId(organizationId, clientId)
        val resolution =
            if (row != null && rolesOf(organizationId, row.id) == roles.sorted()) {
                answer(organizationId, row, roles.sorted())
            } else {
                write(organizationId, clientId, roleIds)
            }
        if (resolution is RealmAccountResolution.Found) remember(key, Entry(resolution.account, Instant.now(clock)))
        return resolution
    }

    // a missing row or roles that differ: one transaction, one lock per organization and client, so two first tokens
    // make one row. re-read under the lock: the other may have written it
    private suspend fun write(
        organizationId: UUID,
        clientId: String,
        roleIds: Map<String, UUID>
    ): RealmAccountResolution =
        ChangeOrigin.within("realm:$clientId") {
            transactions.executeAndAwait {
                lock.withXactLock("wasichai:realm-service-account:$organizationId:$clientId") {
                    val row =
                        byClientId(organizationId, clientId)
                            ?: if (localNamed(organizationId, clientId)) {
                                return@withXactLock RealmAccountResolution.Denied(
                                    "a local service account of this organization is already named $clientId"
                                )
                            } else {
                                create(organizationId, clientId, roleIds)
                            }
                    val wanted = roleIds.keys.sorted()
                    val held = rolesOf(organizationId, row.id)
                    if (held != wanted) {
                        replaceRoles(organizationId, row.id, roleIds.values)
                        audit.recordPlatform(
                            organizationId,
                            AdminEntity.SERVICE_ACCOUNT,
                            row.id,
                            AdminOperation.UPDATE,
                            snapshot(row, held),
                            snapshot(row, wanted)
                        )
                    }
                    answer(organizationId, row, wanted)
                }
            }
        }

    private fun answer(
        organizationId: UUID,
        row: Row,
        roles: List<String>
    ): RealmAccountResolution =
        if (row.enabled) {
            RealmAccountResolution.Found(RealmAccount(row.id, organizationId, row.name, row.email, roles))
        } else {
            RealmAccountResolution.Denied("service account ${row.name} is disabled in this app")
        }

    // the backing user as ADR-043 makes it, with no password at all; the row with no secret, the client id instead
    private suspend fun create(
        organizationId: UUID,
        clientId: String,
        roleIds: Map<String, UUID>
    ): Row {
        val id = UUID.randomUUID()
        val email = ServiceAccountSecrets.backingEmail(id)
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name, enabled)
                VALUES (:id, :organizationId, :email, NULL, :name, false)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .bind("email", email)
            .bind("name", clientId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.service_accounts (id, organization_id, name, secret_hash, external_client_id)
                VALUES (:id, :organizationId, :name, NULL, :clientId)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .bind("name", clientId)
            .bind("clientId", clientId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        replaceRoles(organizationId, id, roleIds.values)
        val row = Row(id, clientId, email, enabled = true)
        audit.recordPlatform(organizationId, AdminEntity.SERVICE_ACCOUNT, id, AdminOperation.CREATE, null, snapshot(row, roleIds.keys.sorted()))
        return row
    }

    // AdminSnapshots.serviceAccount's shape: name, enabled, roles. never a secret
    private fun snapshot(
        row: Row,
        roles: List<String>
    ): Map<String, Any?> = linkedMapOf("name" to row.name, "enabled" to row.enabled, "roles" to roles)

    private suspend fun byClientId(
        organizationId: UUID,
        clientId: String
    ): Row? =
        db
            .sql(
                """
                SELECT s.id, s.name, s.enabled, u.email FROM ${schemas.metadata}.service_accounts s
                JOIN ${schemas.metadata}.users u ON u.id = s.id AND u.organization_id = s.organization_id
                WHERE s.organization_id = :organizationId AND s.external_client_id = :clientId
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("clientId", clientId)
            .map { r, _ -> Row(Rows.uuid(r, "id"), Rows.string(r, "name"), Rows.string(r, "email"), Rows.bool(r, "enabled")) }
            .one()
            .awaitFirstOrNull()

    // a local account (with a secret) under the client's name: never adopted
    private suspend fun localNamed(
        organizationId: UUID,
        name: String
    ): Boolean =
        db
            .sql(
                "SELECT true AS taken FROM ${schemas.metadata}.service_accounts " +
                    "WHERE organization_id = :organizationId AND name = :name AND external_client_id IS NULL"
            ).bind("organizationId", organizationId)
            .bind("name", name)
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false

    private suspend fun roleId(
        organizationId: UUID,
        name: String
    ): UUID? =
        db
            .sql("SELECT id FROM ${schemas.metadata}.roles WHERE organization_id = :organizationId AND name = :name")
            .bind("organizationId", organizationId)
            .bind("name", name)
            .map { r, _ -> Rows.uuid(r, "id") }
            .one()
            .awaitFirstOrNull()

    // sorted names, the order a comparison and the token use
    private suspend fun rolesOf(
        organizationId: UUID,
        id: UUID
    ): List<String> =
        db
            .sql(
                """
                SELECT r.name FROM ${schemas.metadata}.user_roles ur
                JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id
                WHERE ur.user_id = :id AND r.organization_id = :organizationId
                ORDER BY r.name
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .map { r, _ -> Rows.string(r, "name") }
            .all()
            .asFlow()
            .toList()

    private suspend fun replaceRoles(
        organizationId: UUID,
        id: UUID,
        roleIds: Collection<UUID>
    ) {
        db
            .sql(
                """
                DELETE FROM ${schemas.metadata}.user_roles ur
                USING ${schemas.metadata}.users u
                WHERE ur.user_id = :id AND u.id = ur.user_id AND u.organization_id = :organizationId
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        roleIds.forEach { roleId ->
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id)
                    SELECT :id, r.id FROM ${schemas.metadata}.roles r WHERE r.id = :roleId AND r.organization_id = :organizationId
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).bind("id", id)
                .bind("roleId", roleId)
                .bind("organizationId", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    // RealmUsers' shape: nothing kept with a zero ttl, expired entries swept once the map grows
    private fun remember(
        key: Pair<UUID, String>,
        entry: Entry
    ) {
        if (cache.isZero) return
        if (found.size >= SWEEP_AT) {
            val now = Instant.now(clock)
            found.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(cache)) }
        }
        found[key] = entry
    }

    private companion object {
        const val SWEEP_AT = 10_000
    }
}
