package wasichai.core.admin

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.ValidationException
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// the roles a users row holds, a person's or a service account's (ADR-043): names resolved in the
// caller's tenant only, the whole set replaced at once. one place, so both admin apis judge a role
// name the same way.
internal class RoleAssignments(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun findRole(
        organizationId: UUID,
        name: String
    ): UUID? =
        db
            .sql("SELECT id FROM ${schemas.metadata}.roles WHERE organization_id = :organizationId AND name = :name")
            .bind("organizationId", organizationId)
            .bind("name", name)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitFirstOrNull()

    // each name once, trimmed and upper-cased, blank ones dropped. [vet] judges a name before it is
    // looked up, in the order given: a service account refuses ADMIN with it
    suspend fun resolve(
        organizationId: UUID,
        names: List<String>,
        vet: (String) -> Unit = {}
    ): List<UUID> =
        names
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { name ->
                vet(name)
                findRole(organizationId, name)
                    ?: throw ValidationException("Unknown role '$name'", "roles", "role does not exist in this organization")
            }

    // a role already held is no error: two admins saving the same set at once both succeed
    suspend fun assign(
        userId: UUID,
        roleIds: List<UUID>
    ) {
        roleIds.forEach { roleId ->
            db
                .sql("INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id) VALUES (:userId, :roleId) ON CONFLICT DO NOTHING")
                .bind("userId", userId)
                .bind("roleId", roleId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    // the whole set: whatever the user held before is gone
    suspend fun replace(
        userId: UUID,
        roleIds: List<UUID>
    ) {
        db
            .sql("DELETE FROM ${schemas.metadata}.user_roles WHERE user_id = :userId")
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        assign(userId, roleIds)
    }
}
