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
}
