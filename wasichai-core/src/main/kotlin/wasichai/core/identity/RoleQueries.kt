package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// join table lookup. no entity needed for user_roles.
@Repository
class RoleQueries(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun roleNamesOf(userId: UUID): List<String> =
        db
            .sql(
                """
                SELECT r.name
                FROM ${schemas.metadata}.user_roles ur
                JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId
                """.trimIndent()
            ).bind("userId", userId)
            .map { row, _ -> row.get("name", String::class.java)!! }
            .all()
            .asFlow()
            .toList()

    // org-wide row = every object; otherwise only the objects the rows name.
    data class PermittedObjects(
        val all: Boolean,
        val ids: Set<UUID>
    ) {
        fun allows(objectId: UUID): Boolean = all || objectId in ids
    }

    suspend fun permittedObjects(
        roleNames: List<String>,
        organizationId: UUID,
        action: String
    ): PermittedObjects {
        if (roleNames.isEmpty()) return PermittedObjects(false, emptySet())
        // an org-wide row means every object, so ask that question separately from the named ones
        val everywhere = hasPermission(roleNames, organizationId, action)
        val ids =
            db
                .sql(
                    """
                    SELECT p.object_id
                    FROM ${schemas.metadata}.permissions p
                    JOIN ${schemas.metadata}.roles r ON r.id = p.role_id
                    WHERE r.organization_id = :organizationId
                      AND r.name IN (:roleNames)
                      AND p.action = :action
                      AND p.allowed
                      AND p.object_id IS NOT NULL
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("roleNames", roleNames)
                .bind("action", action)
                .map { row, _ -> row.get("object_id", UUID::class.java)!! }
                .all()
                .collectList()
                .awaitFirstOrNull()
                .orEmpty()
                .toSet()
        return PermittedObjects(everywhere, ids)
    }

    suspend fun hasPermission(
        roleNames: List<String>,
        organizationId: UUID,
        action: String,
        objectId: UUID? = null
    ): Boolean {
        if (roleNames.isEmpty()) return false
        val scope =
            if (objectId == null) {
                "AND p.object_id IS NULL"
            } else {
                "AND (p.object_id IS NULL OR p.object_id = :objectId)"
            }
        var spec =
            db
                .sql(
                    """
                    SELECT true
                    FROM ${schemas.metadata}.permissions p
                    JOIN ${schemas.metadata}.roles r ON r.id = p.role_id
                    WHERE r.organization_id = :organizationId
                      AND r.name IN (:roleNames)
                      AND p.action = :action
                      AND p.allowed
                      $scope
                    LIMIT 1
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("roleNames", roleNames)
                .bind("action", action)
        if (objectId != null) spec = spec.bind("objectId", objectId)
        return spec
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false
    }
}
