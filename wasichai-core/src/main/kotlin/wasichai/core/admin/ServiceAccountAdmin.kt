package wasichai.core.admin

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.ServiceAccountSecrets
import wasichai.core.identity.TokenRevocation
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.time.Instant
import java.util.UUID

data class CreateServiceAccountRequest(
    @field:NotBlank val name: String,
    val roles: List<String> = emptyList()
)

// null = leave as it is. roles replace the whole set.
data class UpdateServiceAccountRequest(
    val enabled: Boolean? = null,
    val roles: List<String>? = null
)

// clientSecret only on create and rotate. the hash never leaves the database.
data class ServiceAccountResponse(
    val id: String,
    val clientId: String,
    val name: String,
    val enabled: Boolean,
    val roles: List<String>,
    val createdAt: Instant?,
    val secretRotatedAt: Instant?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val clientSecret: String? = null
)

// service accounts of the caller's tenant (ADR-043). MANAGE_ORGANIZATION, which a service account never passes.
// every change leaves one admin audit entry, never the secret (ADR-049).
// disabling, new roles, a new secret and deleting revoke the tokens it holds, with revocation on (ADR-059).
@Service
class ServiceAccountService(
    private val db: DatabaseClient,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val schemas: WasichaiSchemas,
    private val audit: AdminAudit,
    private val revocation: TokenRevocation
) {
    private val roles = RoleAssignments(db, schemas)

    suspend fun list(): List<ServiceAccountResponse> = load(requireAdmin().organizationId, null)

    suspend fun get(id: UUID): ServiceAccountResponse = accountOrFail(requireAdmin().organizationId, id)

    @Transactional
    suspend fun create(request: CreateServiceAccountRequest): ServiceAccountResponse {
        val admin = requireAdmin()
        val name = request.name.trim().lowercase()
        if (!NAME.matches(name)) {
            throw ValidationException("Invalid service account name '$name'", "name", "must match ${NAME.pattern}")
        }
        if (exists(admin.organizationId, name)) throw ConflictException("Service account '$name' already exists")
        val roleIds = resolveRoles(admin.organizationId, request.roles)
        val id = UUID.randomUUID()
        val secret = ServiceAccountSecrets.generate()
        // the backing users row: disabled, and a password nobody is ever told, so it can never sign in
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name, enabled)
                VALUES (:id, :organizationId, :email, :hash, :displayName, false)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .bind("email", ServiceAccountSecrets.backingEmail(id))
            .bind("hash", passwordEncoder.encode(ServiceAccountSecrets.generate()) as String)
            .bind("displayName", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.service_accounts (id, organization_id, name, secret_hash)
                VALUES (:id, :organizationId, :name, :hash)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .bind("name", name)
            .bind("hash", passwordEncoder.encode(secret) as String)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        roles.replace(id, roleIds)
        val created = accountOrFail(admin.organizationId, id)
        audit.record(admin, AdminEntity.SERVICE_ACCOUNT, id, AdminOperation.CREATE, null, AdminSnapshots.serviceAccount(created))
        return created.copy(clientSecret = secret)
    }

    @Transactional
    suspend fun update(
        id: UUID,
        request: UpdateServiceAccountRequest
    ): ServiceAccountResponse {
        val admin = requireAdmin()
        val before = accountOrFail(admin.organizationId, id)
        val roleIds = request.roles?.let { resolveRoles(admin.organizationId, it) }
        if (request.enabled != null) {
            db
                .sql("UPDATE ${schemas.metadata}.service_accounts SET enabled = :enabled WHERE id = :id AND organization_id = :organizationId")
                .bind("enabled", request.enabled)
                .bind("id", id)
                .bind("organizationId", admin.organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        roleIds?.let { roles.replace(id, it) }
        if (request.enabled == false || roleIds != null) revocation.revoke(admin.organizationId, id)
        val updated = accountOrFail(admin.organizationId, id)
        audit.record(
            admin,
            AdminEntity.SERVICE_ACCOUNT,
            id,
            AdminOperation.UPDATE,
            AdminSnapshots.serviceAccount(before),
            AdminSnapshots.serviceAccount(updated)
        )
        return updated
    }

    // the old secret stops working at once. tokens it already got run out their ttl, unless revocation is on.
    @Transactional
    suspend fun rotateSecret(id: UUID): ServiceAccountResponse {
        val admin = requireAdmin()
        val before = accountOrFail(admin.organizationId, id)
        val secret = ServiceAccountSecrets.generate()
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.service_accounts SET secret_hash = :hash, secret_rotated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                """.trimIndent()
            ).bind("hash", passwordEncoder.encode(secret) as String)
            .bind("id", id)
            .bind("organizationId", admin.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        revocation.revoke(admin.organizationId, id)
        val rotated = accountOrFail(admin.organizationId, id)
        // that it rotated, never what to
        audit.record(
            admin,
            AdminEntity.SERVICE_ACCOUNT,
            id,
            AdminOperation.UPDATE,
            AdminSnapshots.serviceAccount(before),
            AdminSnapshots.serviceAccount(rotated) + ("secretRotated" to true)
        )
        return rotated.copy(clientSecret = secret)
    }

    // deleting the backing user cascades to the account and its roles
    @Transactional
    suspend fun delete(id: UUID) {
        val admin = requireAdmin()
        val before = accountOrFail(admin.organizationId, id)
        db
            .sql(
                """
                DELETE FROM ${schemas.metadata}.users u
                WHERE u.id = :id AND u.organization_id = :organizationId
                  AND EXISTS (SELECT 1 FROM ${schemas.metadata}.service_accounts sa WHERE sa.id = u.id)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        revocation.forget(admin.organizationId, id)
        audit.record(admin, AdminEntity.SERVICE_ACCOUNT, id, AdminOperation.DELETE, AdminSnapshots.serviceAccount(before), null)
    }

    private suspend fun requireAdmin(): AuthenticatedUser = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)

    private suspend fun load(
        organizationId: UUID,
        id: UUID?
    ): List<ServiceAccountResponse> =
        db
            .sql(
                """
                SELECT sa.id, sa.name, sa.enabled, sa.created_at, sa.secret_rotated_at,
                       string_agg(r.name, ',' ORDER BY r.name) AS role_names
                FROM ${schemas.metadata}.service_accounts sa
                LEFT JOIN ${schemas.metadata}.user_roles ur ON ur.user_id = sa.id
                LEFT JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id
                WHERE sa.organization_id = :organizationId
                  AND (:id::uuid IS NULL OR sa.id = :id::uuid)
                GROUP BY sa.id, sa.name, sa.enabled, sa.created_at, sa.secret_rotated_at
                ORDER BY sa.name
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bindNullable("id", id)
            .map { row, _ ->
                val accountId = Rows.uuid(row, "id").toString()
                ServiceAccountResponse(
                    id = accountId,
                    clientId = accountId,
                    name = Rows.string(row, "name"),
                    enabled = Rows.bool(row, "enabled"),
                    roles = Rows.stringOrNull(row, "role_names")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    createdAt = Rows.instantOrNull(row, "created_at"),
                    secretRotatedAt = Rows.instantOrNull(row, "secret_rotated_at")
                )
            }.all()
            .asFlow()
            .toList()

    // another tenant's account is as missing as one that never existed
    private suspend fun accountOrFail(
        organizationId: UUID,
        id: UUID
    ): ServiceAccountResponse = load(organizationId, id).firstOrNull() ?: throw NotFoundException("Service account $id does not exist")

    private suspend fun exists(
        organizationId: UUID,
        name: String
    ): Boolean =
        db
            .sql("SELECT true FROM ${schemas.metadata}.service_accounts WHERE organization_id = :organizationId AND name = :name")
            .bind("organizationId", organizationId)
            .bind("name", name)
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false

    // ADMIN is refused: an account calls the api, it does not administer the tenant
    private suspend fun resolveRoles(
        organizationId: UUID,
        names: List<String>
    ): List<UUID> =
        roles.resolve(organizationId, names) { name ->
            if (name == AuthenticatedUser.ADMIN_ROLE) {
                throw ValidationException("A service account cannot hold ADMIN", "roles", "grant the actions it needs through another role")
            }
        }

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_-]{1,48}$")
    }
}

@RestController
@RequestMapping("/api/service-accounts")
class ServiceAccountController(
    private val accounts: ServiceAccountService
) {
    @GetMapping
    suspend fun list(): List<ServiceAccountResponse> = accounts.list()

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): ServiceAccountResponse = accounts.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @Valid @RequestBody request: CreateServiceAccountRequest
    ): ServiceAccountResponse = accounts.create(request)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateServiceAccountRequest
    ): ServiceAccountResponse = accounts.update(id, request)

    @PostMapping("/{id}/secret")
    suspend fun rotateSecret(
        @PathVariable id: UUID
    ): ServiceAccountResponse = accounts.rotateSecret(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = accounts.delete(id)
}
