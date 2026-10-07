package wasichai.core.admin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
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
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// nullable: a missing field is a 400 on that field, not a jackson error
data class CreateOrgUnitRequest(
    val code: String? = null,
    val label: String? = null,
    val parentCode: String? = null
)

data class UserOrgUnitsRequest(
    val units: List<String> = emptyList()
)

data class OrgUnitResponse(
    val code: String,
    val label: String,
    val parentCode: String?,
    val memberCount: Int
)

data class OrgUnitMember(
    val id: String,
    val email: String,
    val displayName: String
)

data class OrgUnitDetailResponse(
    val code: String,
    val label: String,
    val parentCode: String?,
    val members: List<OrgUnitMember>
)

// organizational units of the caller's tenant (ADR-045). MANAGE_ORGANIZATION, so never a service account.
// every write takes the tenant's tree lock: two moves cannot cross into a cycle, a child cannot land on a unit
// being deleted, and depth holds against a concurrent move. every change leaves one admin audit entry (ADR-049):
// a unit's under admin:org-unit, a person's membership under admin:user.
@Service
@Transactional
class OrgUnitService(
    private val db: DatabaseClient,
    private val currentUser: CurrentUser,
    private val schemas: WasichaiSchemas,
    private val clusterLock: ClusterLock,
    private val directory: OrgUnitDirectory,
    private val admin: AdminService,
    private val audit: AdminAudit
) {
    suspend fun list(): List<OrgUnitResponse> = load(requireAdmin().organizationId, null)

    suspend fun get(code: String): OrgUnitDetailResponse {
        val organizationId = requireAdmin().organizationId
        val unit = unitOrFail(organizationId, code)
        val members =
            db
                .sql(
                    """
                    SELECT p.id, p.email, p.display_name
                    FROM ${schemas.metadata}.user_org_units m
                    JOIN ${schemas.metadata}.users p ON p.id = m.user_id
                    WHERE m.unit_id = :unitId AND p.organization_id = :organizationId
                    ORDER BY p.email
                    """.trimIndent()
                ).bind("unitId", unit.id)
                .bind("organizationId", organizationId)
                .map { row, _ ->
                    OrgUnitMember(
                        id = Rows.uuid(row, "id").toString(),
                        email = Rows.string(row, "email"),
                        displayName = Rows.string(row, "display_name")
                    )
                }.all()
                .asFlow()
                .toList()
        return OrgUnitDetailResponse(unit.response.code, unit.response.label, unit.response.parentCode, members)
    }

    suspend fun create(request: CreateOrgUnitRequest): OrgUnitResponse {
        val actor = requireAdmin()
        val organizationId = actor.organizationId
        val code = OrgUnitDirectory.normaliseCode(request.code.orEmpty())
        val violations = mutableListOf<FieldViolation>()
        if (!CODE.matches(code)) violations += FieldViolation("code", "must match ${CODE.pattern}")
        val label = validLabel(request.label, violations)
        if (violations.isNotEmpty()) throw ValidationException("Invalid organizational unit", violations)
        return locked(organizationId) {
            if (find(organizationId, code) != null) throw ConflictException("Organizational unit '$code' already exists")
            val parentId =
                parentOrNull(organizationId, request.parentCode)?.also { parent ->
                    if (depthOf(organizationId, parent) + 1 > MAX_DEPTH) throw tooDeep()
                }
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.org_units (organization_id, parent_id, code, label)
                    VALUES (:organizationId, :parentId, :code, :label)
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bindUuid("parentId", parentId)
                .bind("code", code)
                .bind("label", label!!)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            val created = unitOrFail(organizationId, code)
            audit.record(actor, AdminEntity.ORG_UNIT, created.id, AdminOperation.CREATE, null, AdminSnapshots.orgUnit(created.response))
            created.response
        }
    }

    // a map, as PUT /api/auth/me/preferences: a missing key keeps, parentCode null moves to the root
    suspend fun update(
        code: String,
        body: Map<String, Any?>
    ): OrgUnitResponse {
        val actor = requireAdmin()
        val organizationId = actor.organizationId
        val violations = mutableListOf<FieldViolation>()
        body.keys.filter { it !in FIELDS }.forEach { violations += FieldViolation(it, "is not a unit property") }
        val label = if ("label" in body) validLabel(body["label"], violations) else null
        val parentCode = body["parentCode"]
        if (parentCode != null && parentCode !is String) violations += FieldViolation("parentCode", "must be a unit code or null")
        if (violations.isNotEmpty()) throw ValidationException("Invalid organizational unit", violations)
        return locked(organizationId) {
            val unit = unitOrFail(organizationId, code)
            val parentId =
                if ("parentCode" in body) {
                    parentOrNull(organizationId, parentCode as String?).also { checkMove(organizationId, unit.id, it) }
                } else {
                    unit.parentId
                }
            db
                .sql(
                    """
                    UPDATE ${schemas.metadata}.org_units SET label = :label, parent_id = :parentId, updated_at = now()
                    WHERE id = :id AND organization_id = :organizationId
                    """.trimIndent()
                ).bind("label", label ?: unit.response.label)
                .bindUuid("parentId", parentId)
                .bind("id", unit.id)
                .bind("organizationId", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            val updated = unitOrFail(organizationId, unit.response.code).response
            audit.record(actor, AdminEntity.ORG_UNIT, unit.id, AdminOperation.UPDATE, AdminSnapshots.orgUnit(unit.response), AdminSnapshots.orgUnit(updated))
            updated
        }
    }

    // refused while it has sub-units or members: dropping people out of the tree silently is not an admin step
    suspend fun delete(code: String) {
        val actor = requireAdmin()
        val organizationId = actor.organizationId
        locked(organizationId) {
            val unit = unitOrFail(organizationId, code)
            if (hasChildren(organizationId, unit.id)) throw ConflictException("Organizational unit '${unit.response.code}' has sub-units")
            if (unit.response.memberCount > 0) throw ConflictException("Organizational unit '${unit.response.code}' has members")
            db
                .sql("DELETE FROM ${schemas.metadata}.org_units WHERE id = :id AND organization_id = :organizationId")
                .bind("id", unit.id)
                .bind("organizationId", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            audit.record(actor, AdminEntity.ORG_UNIT, unit.id, AdminOperation.DELETE, AdminSnapshots.orgUnit(unit.response), null)
        }
    }

    // replaces the whole set. a service account's backing user is no person: 404, as on /roles
    suspend fun setUserUnits(
        userId: UUID,
        request: UserOrgUnitsRequest
    ): AdminUserResponse {
        val actor = requireAdmin()
        val organizationId = actor.organizationId
        admin.user(userId)
        return locked(organizationId) {
            // read under the lock: the entry's before is what this change replaced
            val before = admin.user(userId)
            val ids = directory.idsByCode(organizationId, request.units)
            val violations =
                request.units.mapIndexedNotNull { index, code ->
                    if (OrgUnitDirectory.normaliseCode(code) in ids) null else FieldViolation("units[$index]", "unknown unit '$code'")
                }
            if (violations.isNotEmpty()) throw ValidationException("Unknown organizational unit", violations)
            db
                .sql("DELETE FROM ${schemas.metadata}.user_org_units WHERE user_id = :userId")
                .bind("userId", userId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            if (ids.isNotEmpty()) {
                db
                    .sql("INSERT INTO ${schemas.metadata}.user_org_units (user_id, unit_id) SELECT :userId, unnest(:unitIds::uuid[])")
                    .bind("userId", userId)
                    .bind("unitIds", ids.values.toTypedArray())
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
            val updated = admin.user(userId)
            audit.record(actor, AdminEntity.USER, userId, AdminOperation.UPDATE, AdminSnapshots.user(before), AdminSnapshots.user(updated))
            updated
        }
    }

    // ---------------------------------------------------------------- tree

    // a move under the unit itself or its own subtree would make a cycle. depth counts the subtree carried along.
    private suspend fun checkMove(
        organizationId: UUID,
        unitId: UUID,
        parentId: UUID?
    ) {
        val subtree = subtreeOf(organizationId, unitId)
        if (parentId != null && parentId in subtree) {
            throw ValidationException("A unit cannot move under itself", "parentCode", "is the unit itself or one of its sub-units")
        }
        val height = subtree.values.maxOrNull() ?: 1
        val parentDepth = parentId?.let { depthOf(organizationId, it) } ?: 0
        if (parentDepth + height > MAX_DEPTH) throw tooDeep()
    }

    // root = 1
    private suspend fun depthOf(
        organizationId: UUID,
        id: UUID
    ): Int =
        db
            .sql(
                """
                WITH RECURSIVE up (id, parent_id, depth) AS (
                    SELECT id, parent_id, 1 FROM ${schemas.metadata}.org_units WHERE id = :id AND organization_id = :organizationId
                    UNION ALL
                    SELECT u.id, u.parent_id, up.depth + 1
                    FROM ${schemas.metadata}.org_units u
                    JOIN up ON u.id = up.parent_id
                    WHERE u.organization_id = :organizationId AND up.depth < $MAX_WALK
                )
                SELECT max(depth) AS depth FROM up
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .map { row, _ -> Rows.intOrNull(row, "depth") ?: 0 }
            .one()
            .awaitSingle()

    // the unit and every unit below it, each with its level (the unit = 1)
    private suspend fun subtreeOf(
        organizationId: UUID,
        id: UUID
    ): Map<UUID, Int> =
        db
            .sql(
                """
                WITH RECURSIVE down (id, level) AS (
                    SELECT id, 1 FROM ${schemas.metadata}.org_units WHERE id = :id AND organization_id = :organizationId
                    UNION ALL
                    SELECT u.id, down.level + 1
                    FROM ${schemas.metadata}.org_units u
                    JOIN down ON u.parent_id = down.id
                    WHERE u.organization_id = :organizationId AND down.level < $MAX_WALK
                )
                SELECT id, level FROM down
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", organizationId)
            .map { row, _ -> Rows.uuid(row, "id") to Rows.int(row, "level") }
            .all()
            .asFlow()
            .toList()
            .toMap()

    // ---------------------------------------------------------------- loading

    private class UnitRow(
        val id: UUID,
        val parentId: UUID?,
        val response: OrgUnitResponse
    )

    private suspend fun load(
        organizationId: UUID,
        code: String?
    ): List<OrgUnitResponse> = rows(organizationId, code).map { it.response }

    private suspend fun rows(
        organizationId: UUID,
        code: String?
    ): List<UnitRow> =
        db
            .sql(
                """
                SELECT u.id, u.parent_id, u.code, u.label, p.code AS parent_code,
                       (SELECT count(*) FROM ${schemas.metadata}.user_org_units m WHERE m.unit_id = u.id)::int AS member_count
                FROM ${schemas.metadata}.org_units u
                LEFT JOIN ${schemas.metadata}.org_units p ON p.id = u.parent_id AND p.organization_id = u.organization_id
                WHERE u.organization_id = :organizationId AND (:code::text IS NULL OR u.code = :code::text)
                ORDER BY u.label, u.code
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .let { spec -> if (code == null) spec.bindNull("code", String::class.java) else spec.bind("code", code) }
            .map { row, _ ->
                UnitRow(
                    id = Rows.uuid(row, "id"),
                    parentId = Rows.uuidOrNull(row, "parent_id"),
                    response =
                        OrgUnitResponse(
                            code = Rows.string(row, "code"),
                            label = Rows.string(row, "label"),
                            parentCode = Rows.stringOrNull(row, "parent_code"),
                            memberCount = Rows.int(row, "member_count")
                        )
                )
            }.all()
            .asFlow()
            .toList()

    private suspend fun find(
        organizationId: UUID,
        code: String
    ): UnitRow? = rows(organizationId, OrgUnitDirectory.normaliseCode(code)).firstOrNull()

    // another tenant's unit is as missing as one that never existed
    private suspend fun unitOrFail(
        organizationId: UUID,
        code: String
    ): UnitRow = find(organizationId, code) ?: throw NotFoundException("Organizational unit '${OrgUnitDirectory.normaliseCode(code)}' does not exist")

    // a parent the payload names but the tenant does not have is a bad request. blank = the root
    private suspend fun parentOrNull(
        organizationId: UUID,
        code: String?
    ): UUID? {
        val normalised = OrgUnitDirectory.normaliseCode(code.orEmpty())
        if (normalised.isEmpty()) return null
        return directory.idsByCode(organizationId, listOf(normalised))[normalised]
            ?: throw ValidationException("Unknown organizational unit '$normalised'", "parentCode", "unit does not exist in this organization")
    }

    private suspend fun hasChildren(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("SELECT 1 FROM ${schemas.metadata}.org_units WHERE parent_id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .map { _, _ -> true }
            .first()
            .awaitFirstOrNull() ?: false

    // ---------------------------------------------------------------- helpers

    private suspend fun requireAdmin(): AuthenticatedUser = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)

    // joins this service's transaction: the lock goes with its commit or rollback
    private suspend fun <T> locked(
        organizationId: UUID,
        block: suspend () -> T
    ): T = clusterLock.withXactLock("wasichai.org-units.$organizationId", block)

    private fun validLabel(
        value: Any?,
        violations: MutableList<FieldViolation>
    ): String? {
        val label = (value as? String)?.trim()
        if (label == null || label.length !in 1..MAX_LABEL) {
            violations += FieldViolation("label", "must be text of 1 to $MAX_LABEL characters")
            return null
        }
        return label
    }

    private fun tooDeep() = ValidationException("Too deep", "parentCode", "units nest at most $MAX_DEPTH levels deep")

    private fun DatabaseClient.GenericExecuteSpec.bindUuid(
        name: String,
        value: UUID?
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, UUID::class.java) else bind(name, value)

    companion object {
        private val CODE = Regex("^[A-Z][A-Z0-9_]{1,48}$")
        private val FIELDS = setOf("label", "parentCode")
        private const val MAX_LABEL = 120
        private const val MAX_DEPTH = 10

        // bounds a walk should a cycle ever slip in
        private const val MAX_WALK = 64
    }
}

// addressed by code: it is what apps bind to and what never changes
@RestController
@RequestMapping("/api/org-units")
class OrgUnitController(
    private val units: OrgUnitService
) {
    @GetMapping
    suspend fun list(): List<OrgUnitResponse> = units.list()

    @GetMapping("/{code}")
    suspend fun get(
        @PathVariable code: String
    ): OrgUnitDetailResponse = units.get(code)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: CreateOrgUnitRequest
    ): OrgUnitResponse = units.create(request)

    @PutMapping("/{code}")
    suspend fun update(
        @PathVariable code: String,
        @RequestBody body: Map<String, Any?>
    ): OrgUnitResponse = units.update(code, body)

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable code: String
    ) = units.delete(code)
}

@RestController
@RequestMapping("/api/users")
class UserOrgUnitsController(
    private val units: OrgUnitService
) {
    @PutMapping("/{id}/org-units")
    suspend fun set(
        @PathVariable id: UUID,
        @RequestBody request: UserOrgUnitsRequest
    ): AdminUserResponse = units.setUserUnits(id, request)
}
