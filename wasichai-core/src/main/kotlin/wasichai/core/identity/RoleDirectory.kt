package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// role names for features that reference a role without administering one (a module's transition
// rules, say). administering roles still goes through AdminService and MANAGE_ORGANIZATION.
@Service
class RoleDirectory(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun namesOf(organizationId: UUID): Set<String> =
        db
            .sql("SELECT name FROM ${schemas.metadata}.roles WHERE organization_id = :organizationId")
            .bind("organizationId", organizationId)
            .map { row, _ -> Rows.string(row, "name") }
            .all()
            .asFlow()
            .toList()
            .toSet()

    // the enabled people holding any of names (stored roles, as the next sign-in puts them in the token)
    suspend fun holderIds(
        organizationId: UUID,
        names: Collection<String>
    ): Set<UUID> {
        if (names.isEmpty()) return emptySet()
        return db
            .sql(
                "SELECT DISTINCT u.id FROM ${schemas.metadata}.user_roles ur " +
                    "JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id " +
                    "JOIN ${schemas.metadata}.users u ON u.id = ur.user_id " +
                    "WHERE r.organization_id = :organizationId AND u.organization_id = :organizationId AND u.enabled " +
                    "AND r.name = ANY(:names)"
            ).bind("organizationId", organizationId)
            .bind("names", names.distinct().toTypedArray())
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }
}
