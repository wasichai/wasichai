package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

/** A unit the user sits in. [path] holds the codes from the root down to this unit. */
data class OrgUnitRef(
    val id: UUID,
    val code: String,
    val label: String,
    val path: List<String>
)

// ADR-045: modules read units through this port, never through core's tables.
// not authorization: a unit grants nothing, and it is read when needed, never put in a token.
class OrgUnitDirectory(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    // the user's units and every unit above them: an alert for a unit reaches its subtree, so this is the
    // match set. UNION, not UNION ALL: duplicates collapse, and a cycle, should one ever exist, ends.
    suspend fun closureOf(
        organizationId: UUID,
        userId: UUID
    ): Set<UUID> =
        db
            .sql(
                """
                WITH RECURSIVE closure (id, parent_id) AS (
                    SELECT u.id, u.parent_id
                    FROM ${schemas.metadata}.user_org_units m
                    JOIN ${schemas.metadata}.org_units u ON u.id = m.unit_id
                    JOIN ${schemas.metadata}.users p ON p.id = m.user_id
                    WHERE m.user_id = :userId AND u.organization_id = :organizationId AND p.organization_id = :organizationId
                    UNION
                    SELECT u.id, u.parent_id
                    FROM ${schemas.metadata}.org_units u
                    JOIN closure c ON u.id = c.parent_id
                    WHERE u.organization_id = :organizationId
                )
                SELECT id FROM closure
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("userId", userId)
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()

    // the enabled people sitting in any of unitIds or in a unit below one: closureOf the other way round.
    // UNION ends a cycle, should one ever exist.
    suspend fun memberIdsWithin(
        organizationId: UUID,
        unitIds: Collection<UUID>
    ): Set<UUID> {
        if (unitIds.isEmpty()) return emptySet()
        return db
            .sql(
                """
                WITH RECURSIVE subtree (id) AS (
                    SELECT u.id FROM ${schemas.metadata}.org_units u
                    WHERE u.organization_id = :organizationId AND u.id = ANY(:unitIds)
                    UNION
                    SELECT u.id FROM ${schemas.metadata}.org_units u
                    JOIN subtree s ON u.parent_id = s.id
                    WHERE u.organization_id = :organizationId
                )
                SELECT DISTINCT p.id
                FROM subtree s
                JOIN ${schemas.metadata}.user_org_units m ON m.unit_id = s.id
                JOIN ${schemas.metadata}.users p ON p.id = m.user_id
                WHERE p.organization_id = :organizationId AND p.enabled
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("unitIds", unitIds.distinct().toTypedArray())
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }

    // key = the normalised code. an unknown code is simply missing from the map
    suspend fun idsByCode(
        organizationId: UUID,
        codes: Collection<String>
    ): Map<String, UUID> {
        val normalised = codes.map { normaliseCode(it) }.filter { it.isNotEmpty() }.distinct()
        if (normalised.isEmpty()) return emptyMap()
        return db
            .sql("SELECT id, code FROM ${schemas.metadata}.org_units WHERE organization_id = :organizationId AND code = ANY(:codes)")
            .bind("organizationId", organizationId)
            .bind("codes", normalised.toTypedArray())
            .map { row, _ -> Rows.string(row, "code") to Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toMap()
    }

    suspend fun codesById(
        organizationId: UUID,
        ids: Collection<UUID>
    ): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return db
            .sql("SELECT id, code FROM ${schemas.metadata}.org_units WHERE organization_id = :organizationId AND id = ANY(:ids)")
            .bind("organizationId", organizationId)
            .bind("ids", ids.distinct().toTypedArray())
            .map { row, _ -> Rows.uuid(row, "id") to Rows.string(row, "code") }
            .all()
            .asFlow()
            .toList()
            .toMap()
    }

    // direct units only, each with its path root -> unit. siblings have no order of their own: by label
    suspend fun unitsOf(
        organizationId: UUID,
        userId: UUID
    ): List<OrgUnitRef> =
        db
            .sql(
                """
                WITH RECURSIVE direct AS (
                    SELECT u.id, u.code, u.label
                    FROM ${schemas.metadata}.user_org_units m
                    JOIN ${schemas.metadata}.org_units u ON u.id = m.unit_id
                    JOIN ${schemas.metadata}.users p ON p.id = m.user_id
                    WHERE m.user_id = :userId AND u.organization_id = :organizationId AND p.organization_id = :organizationId
                ),
                up (start_id, parent_id, depth, code) AS (
                    SELECT u.id, u.parent_id, 0, u.code
                    FROM direct d
                    JOIN ${schemas.metadata}.org_units u ON u.id = d.id
                    UNION ALL
                    SELECT up.start_id, u.parent_id, up.depth + 1, u.code
                    FROM up
                    JOIN ${schemas.metadata}.org_units u ON u.id = up.parent_id
                    WHERE u.organization_id = :organizationId AND up.depth < $MAX_WALK
                )
                SELECT d.id, d.code, d.label, array_agg(up.code ORDER BY up.depth DESC) AS path
                FROM direct d
                JOIN up ON up.start_id = d.id
                GROUP BY d.id, d.code, d.label
                ORDER BY d.label, d.code
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("userId", userId)
            .map { row, _ ->
                OrgUnitRef(
                    id = Rows.uuid(row, "id"),
                    code = Rows.string(row, "code"),
                    label = Rows.string(row, "label"),
                    path = row.get("path", Array<String>::class.java)!!.toList()
                )
            }.all()
            .asFlow()
            .toList()

    companion object {
        // the admin caps depth at 10; this only bounds a walk should a cycle ever slip in
        private const val MAX_WALK = 64

        /** Codes are tokens, like role names: `trim().uppercase()`. */
        fun normaliseCode(code: String): String = code.trim().uppercase()
    }
}

/** A unit of the caller, as `GET /api/auth/me/org-units` answers it: no id, apps bind to the code. */
data class MyOrgUnit(
    val code: String,
    val label: String,
    val path: List<String>
)

// any signed-in caller, a service account too (it sits nowhere, so it reads [])
@RestController
@RequestMapping("/api/auth/me/org-units")
class MyOrgUnitsController(
    private val units: OrgUnitDirectory,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun mine(): List<MyOrgUnit> {
        val user = currentUser.require()
        return units.unitsOf(user.organizationId, user.userId).map { MyOrgUnit(it.code, it.label, it.path) }
    }
}
