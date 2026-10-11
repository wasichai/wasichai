package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.RequestMemo
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

    // on an object: answered from objectAccess, one query per object and request whatever the action.
    // org-wide (no object): its own query, remembered for the request too (ADR-066)
    suspend fun hasPermission(
        roleNames: List<String>,
        organizationId: UUID,
        action: String,
        objectId: UUID? = null
    ): Boolean {
        if (roleNames.isEmpty()) return false
        if (objectId != null) return action in objectAccess(roleNames, organizationId, objectId).actions
        return RequestMemo.remember(OrgWideKey(organizationId, roleNames.toSet(), action)) {
            db
                .sql(permissionQuery(schemas, objectScoped = false))
                .bind("organizationId", organizationId)
                .bind("roleNames", roleNames)
                .bind("action", action)
                .map { _, _ -> true }
                .one()
                .awaitFirstOrNull() ?: false
        }
    }

    /**
     * Everything the caller's roles say about one object, in one statement (ADR-066): the actions they allow on it
     * (org-wide rows count), whether every role keeps them to their own records, and the fields some rule restricts.
     * Remembered for the request, by roles and object: the same object again costs nothing. No role: allows nothing,
     * restricts nothing.
     */
    suspend fun objectAccess(
        roleNames: List<String>,
        organizationId: UUID,
        objectId: UUID
    ): ObjectAccess {
        if (roleNames.isEmpty()) return ObjectAccess(emptySet(), ownRecordsOnly = false, FieldAccess.FULL)
        val roles = roleNames.toSet()
        return RequestMemo.remember(ObjectKey(organizationId, roles, objectId)) {
            loadObjectAccess(roleNames, organizationId, objectId).also {
                // the owner rule has no object: whatever object asks next gets it for free
                RequestMemo.put(OwnRecordsKey(organizationId, roles), it.ownRecordsOnly)
            }
        }
    }

    // the owner rule alone, for a read that has not asked about an object yet
    suspend fun ownRecordsOnly(
        roleNames: List<String>,
        organizationId: UUID
    ): Boolean {
        if (roleNames.isEmpty()) return false
        return RequestMemo.remember(OwnRecordsKey(organizationId, roleNames.toSet())) { loadOwnRecordsOnly(roleNames, organizationId) }
    }

    // the two reads behind the memo. open for unit tests only
    internal open suspend fun loadOwnRecordsOnly(
        roleNames: List<String>,
        organizationId: UUID
    ): Boolean =
        db
            .sql(ownRecordsOnlyQuery(schemas))
            .bind("organizationId", organizationId)
            .bind("roleNames", roleNames)
            .map { row, _ -> row.get("restricted") as Boolean? ?: false }
            .one()
            .awaitFirstOrNull() ?: false

    internal open suspend fun loadObjectAccess(
        roleNames: List<String>,
        organizationId: UUID,
        objectId: UUID
    ): ObjectAccess {
        val actions = mutableSetOf<String>()
        var ownRecordsOnly = false
        val read = mutableMapOf<UUID, Boolean>()
        val write = mutableMapOf<UUID, Boolean>()
        db
            .sql(objectAccessQuery(schemas))
            .bind("organizationId", organizationId)
            .bind("roleNames", roleNames)
            .bind("objectId", objectId)
            .map { row, _ ->
                when (row.get("kind", String::class.java)) {
                    "action" -> actions += row.get("action", String::class.java)!!
                    "owner" -> ownRecordsOnly = row.get("own_records_only") as Boolean? ?: false
                    else -> {
                        val id = row.get("field_id", UUID::class.java)!!
                        read[id] = row.get("can_read") as Boolean
                        write[id] = row.get("can_write") as Boolean
                    }
                }
            }.all()
            .asFlow()
            .toList()
        val fields = if (read.isEmpty()) FieldAccess.FULL else FieldAccess(read, write)
        return ObjectAccess(actions, ownRecordsOnly, fields)
    }

    // request memo keys: roles as a set, so their order in the token does not matter
    private data class ObjectKey(
        val organizationId: UUID,
        val roles: Set<String>,
        val objectId: UUID
    )

    private data class OrgWideKey(
        val organizationId: UUID,
        val roles: Set<String>,
        val action: String
    )

    private data class OwnRecordsKey(
        val organizationId: UUID,
        val roles: Set<String>
    )

    companion object {
        // the owner rule: true when every role of the caller says own records only. a query of its own
        // here, a subquery where a read folds it in (RelationTargets, ADR-031 D30).
        // binds :organizationId and :roleNames (never empty). no row of those roles: null, read as false.
        internal fun ownRecordsOnlyQuery(schemas: WasichaiSchemas): String =
            """
            SELECT bool_and(own_records_only) AS restricted
            FROM ${schemas.metadata}.roles
            WHERE organization_id = :organizationId AND name IN (:roleNames)
            """.trimIndent()

        // the permission rule, the owner rule and the field rules of one object, one row per kind:
        // 'action' per allowed action, one 'owner', 'field' per field some role restricts (a role without
        // a rule grants the field: the permissive union wins). binds :organizationId, :roleNames, :objectId
        internal fun objectAccessQuery(schemas: WasichaiSchemas): String =
            """
            WITH my_roles AS (
                SELECT id, own_records_only FROM ${schemas.metadata}.roles
                WHERE organization_id = :organizationId AND name IN (:roleNames)
            )
            SELECT 'action'::text AS kind, p.action AS action, NULL::boolean AS own_records_only,
                   NULL::uuid AS field_id, NULL::boolean AS can_read, NULL::boolean AS can_write
            FROM ${schemas.metadata}.permissions p
            JOIN my_roles r ON r.id = p.role_id
            WHERE p.allowed AND (p.object_id IS NULL OR p.object_id = :objectId)
            UNION ALL
            SELECT 'owner', NULL, bool_and(own_records_only), NULL, NULL, NULL
            FROM my_roles
            UNION ALL
            SELECT 'field', NULL, NULL, f.id,
                   bool_or(COALESCE(fp.can_read, true)), bool_or(COALESCE(fp.can_write, true))
            FROM my_roles mr
            CROSS JOIN ${schemas.metadata}.custom_fields f
            LEFT JOIN ${schemas.metadata}.field_permissions fp ON fp.role_id = mr.id AND fp.field_id = f.id
            WHERE f.object_id = :objectId
            GROUP BY f.id
            HAVING NOT (bool_or(COALESCE(fp.can_read, true)) AND bool_or(COALESCE(fp.can_write, true)))
            """.trimIndent()

        // the permission rule, one row when one of the roles allows [action]. a query of its own here, a
        // subquery where a read folds it in (RelationTargets, ADR-031 D30).
        // binds :organizationId, :roleNames (never empty), :action, and :objectId when [objectScoped].
        internal fun permissionQuery(
            schemas: WasichaiSchemas,
            objectScoped: Boolean
        ): String {
            val scope = if (objectScoped) "AND (p.object_id IS NULL OR p.object_id = :objectId)" else "AND p.object_id IS NULL"
            return """
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
        }
    }
}
