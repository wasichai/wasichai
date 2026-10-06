package wasichai.core.admin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectActionRepository
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// users, roles and the rules attached to them. every entry point is MANAGE_ORGANIZATION,
// every query is pinned to the caller's tenant.
@Service
class AdminService(
    private val db: DatabaseClient,
    private val metadata: MetadataService,
    private val actions: ObjectActionRepository,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val schemas: WasichaiSchemas
) {
    // ------------------------------------------------------------------ users

    suspend fun listUsers(): List<AdminUserResponse> {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        return loadUsers(user.organizationId, null)
    }

    // for routes beside these that answer a user, as PUT /api/users/{id}/org-units does
    suspend fun user(id: UUID): AdminUserResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        return userOrFail(admin.organizationId, id)
    }

    @Transactional
    suspend fun createUser(request: CreateUserRequest): AdminUserResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val email = request.email.trim().lowercase()
        requirePassword(request.password)
        if (findUserByEmail(admin.organizationId, email) != null) {
            throw ConflictException("User '$email' already exists")
        }
        val roleIds = resolveRoles(admin.organizationId, request.roles)
        val id = UUID.randomUUID()
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name)
                VALUES (:id, :organizationId, :email, :hash, :displayName)
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .bind("email", email)
            .bind("hash", passwordEncoder.encode(request.password) as String)
            .bind("displayName", request.displayName.trim())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        assignRoles(id, roleIds)
        return userOrFail(admin.organizationId, id)
    }

    @Transactional
    suspend fun updateUser(
        id: UUID,
        request: UpdateUserRequest
    ): AdminUserResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        userOrFail(admin.organizationId, id)
        // locking yourself out is never a valid administration step
        if (request.enabled == false && id == admin.userId) {
            throw ForbiddenException("You cannot disable your own account")
        }
        request.password?.let { requirePassword(it) }
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.users
                SET display_name = COALESCE(:displayName::text, display_name),
                    enabled = COALESCE(:enabled::boolean, enabled),
                    password_hash = COALESCE(:hash::text, password_hash),
                    updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .bindNullableString("displayName", request.displayName?.trim()?.ifBlank { null })
            .let { spec ->
                if (request.enabled == null) spec.bindNull("enabled", Boolean::class.javaObjectType) else spec.bind("enabled", request.enabled)
            }.bindNullableString("hash", request.password?.let { passwordEncoder.encode(it) })
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return userOrFail(admin.organizationId, id)
    }

    @Transactional
    suspend fun setUserRoles(
        id: UUID,
        request: UserRolesRequest
    ): AdminUserResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        userOrFail(admin.organizationId, id)
        val roleIds = resolveRoles(admin.organizationId, request.roles)
        db
            .sql("DELETE FROM ${schemas.metadata}.user_roles WHERE user_id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        assignRoles(id, roleIds)
        return userOrFail(admin.organizationId, id)
    }

    @Transactional
    suspend fun deleteUser(id: UUID) {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        if (id == admin.userId) {
            throw ForbiddenException("You cannot delete your own account")
        }
        userOrFail(admin.organizationId, id)
        db
            .sql("DELETE FROM ${schemas.metadata}.users WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", admin.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // ------------------------------------------------------------------ roles

    suspend fun listRoles(): List<RoleResponse> {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        return loadRoles(user.organizationId, null)
    }

    @Transactional
    suspend fun createRole(request: CreateRoleRequest): RoleResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val name = request.name.trim().uppercase()
        if (!ROLE_NAME.matches(name)) {
            throw ValidationException("Invalid role name '$name'", "name", "must match ^[A-Z][A-Z0-9_]{1,48}$")
        }
        if (findRole(admin.organizationId, name) != null) {
            throw ConflictException("Role '$name' already exists")
        }
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.roles (id, organization_id, name, label, own_records_only)
                VALUES (:id, :organizationId, :name, :label, :ownRecordsOnly)
                """.trimIndent()
            ).bind("id", UUID.randomUUID())
            .bind("organizationId", admin.organizationId)
            .bind("name", name)
            .bind("label", request.label.trim())
            .bind("ownRecordsOnly", request.ownRecordsOnly)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return roleOrFail(admin.organizationId, name)
    }

    @Transactional
    suspend fun updateRole(
        name: String,
        request: UpdateRoleRequest
    ): RoleResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val role = roleIdOrFail(admin.organizationId, name)
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.roles
                SET label = COALESCE(:label::text, label),
                    own_records_only = COALESCE(:ownRecordsOnly::boolean, own_records_only)
                WHERE id = :id
                """.trimIndent()
            ).bind("id", role)
            .bindNullableString("label", request.label?.trim()?.ifBlank { null })
            .let { spec ->
                if (request.ownRecordsOnly == null) {
                    spec.bindNull("ownRecordsOnly", Boolean::class.javaObjectType)
                } else {
                    spec.bind("ownRecordsOnly", request.ownRecordsOnly)
                }
            }.fetch()
            .rowsUpdated()
            .awaitSingle()
        return roleOrFail(admin.organizationId, name.trim().uppercase())
    }

    @Transactional
    suspend fun deleteRole(name: String) {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val normalized = name.trim().uppercase()
        if (normalized == AuthenticatedUser.ADMIN_ROLE) {
            throw ConflictException("The ADMIN role administers the tenant and cannot be deleted")
        }
        val id = roleIdOrFail(admin.organizationId, normalized)
        db
            .sql("DELETE FROM ${schemas.metadata}.roles WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // replaces the whole action set of the role
    @Transactional
    suspend fun setPermissions(
        name: String,
        request: RolePermissionsRequest
    ): RoleResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val normalized = name.trim().uppercase()
        val roleId = roleIdOrFail(admin.organizationId, normalized)

        val resolved =
            request.permissions.map { entry ->
                val action = entry.action.trim().uppercase()
                val objectName =
                    entry.objectName
                        ?.trim()
                        ?.lowercase()
                        ?.ifBlank { null }
                // the action is judged before the object, as it always was
                if (action !in Actions.BUILT_IN && !isDeclared(admin.organizationId, objectName, action)) {
                    throw ValidationException(
                        "Unknown action '$action'",
                        "action",
                        "must be one of ${Actions.BUILT_IN.joinToString(", ")} or an action the object declares"
                    )
                }
                val objectId = objectName?.let { objectOrBadRequest(admin.organizationId, it).obj.id }
                Triple(objectId, action, entry.allowed)
            }

        db
            .sql("DELETE FROM ${schemas.metadata}.permissions WHERE role_id = :roleId")
            .bind("roleId", roleId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        resolved.distinctBy { it.first to it.second }.forEach { (objectId, action, allowed) ->
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.permissions (role_id, object_id, action, allowed)
                    VALUES (:roleId, :objectId, :action, :allowed)
                    """.trimIndent()
                ).bind("roleId", roleId)
                .let { spec -> if (objectId == null) spec.bindNull("objectId", UUID::class.java) else spec.bind("objectId", objectId) }
                .bind("action", action)
                .bind("allowed", allowed)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return roleOrFail(admin.organizationId, normalized)
    }

    // replaces the rules for the objects named in the payload. other objects keep theirs.
    @Transactional
    suspend fun setFieldPermissions(
        name: String,
        request: RoleFieldPermissionsRequest
    ): RoleResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val normalized = name.trim().uppercase()
        val roleId = roleIdOrFail(admin.organizationId, normalized)

        val touchedObjects = mutableSetOf<UUID>()
        val rows =
            request.fields.map { entry ->
                val objectName = entry.objectName.trim().lowercase()
                val definition = objectOrBadRequest(admin.organizationId, objectName)
                val fieldName = entry.fieldName.trim().lowercase()
                val field =
                    definition.fields.firstOrNull { it.name == fieldName }
                        ?: throw ValidationException(
                            "Unknown field '$fieldName'",
                            "fieldName",
                            "is not a field of '$objectName'"
                        )
                touchedObjects += definition.obj.id
                Triple(field.id, entry.read, entry.write)
            }

        if (touchedObjects.isNotEmpty()) {
            db
                .sql(
                    """
                    DELETE FROM ${schemas.metadata}.field_permissions
                    WHERE role_id = :roleId
                      AND field_id IN (SELECT id FROM ${schemas.metadata}.custom_fields WHERE object_id = ANY(:objectIds))
                    """.trimIndent()
                ).bind("roleId", roleId)
                .bind("objectIds", touchedObjects.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        rows.distinctBy { it.first }.forEach { (fieldId, read, write) ->
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.field_permissions (role_id, field_id, can_read, can_write)
                    VALUES (:roleId, :fieldId, :canRead, :canWrite)
                    """.trimIndent()
                ).bind("roleId", roleId)
                .bind("fieldId", fieldId)
                .bind("canRead", read)
                // cannot write what you cannot read: a blind overwrite is not an edit
                .bind("canWrite", write && read)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return roleOrFail(admin.organizationId, normalized)
    }

    // ---------------------------------------------------------------- loading

    // a service account's backing row is no person: it is managed at /api/service-accounts (ADR-043)
    private suspend fun loadUsers(
        organizationId: UUID,
        id: UUID?
    ): List<AdminUserResponse> =
        db
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.enabled, u.created_at,
                       string_agg(r.name, ',' ORDER BY r.name) AS role_names,
                       ARRAY(
                           SELECT ou.code
                           FROM ${schemas.metadata}.user_org_units m
                           JOIN ${schemas.metadata}.org_units ou ON ou.id = m.unit_id
                           WHERE m.user_id = u.id AND ou.organization_id = u.organization_id
                           ORDER BY ou.code
                       ) AS org_units
                FROM ${schemas.metadata}.users u
                LEFT JOIN ${schemas.metadata}.user_roles ur ON ur.user_id = u.id
                LEFT JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id
                WHERE u.organization_id = :organizationId
                  AND (:id::uuid IS NULL OR u.id = :id::uuid)
                  AND NOT EXISTS (SELECT 1 FROM ${schemas.metadata}.service_accounts sa WHERE sa.id = u.id)
                GROUP BY u.id, u.email, u.display_name, u.enabled, u.created_at
                ORDER BY u.email
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .let { spec -> if (id == null) spec.bindNull("id", UUID::class.java) else spec.bind("id", id) }
            .map { row, _ ->
                AdminUserResponse(
                    id = Rows.uuid(row, "id").toString(),
                    email = Rows.string(row, "email"),
                    displayName = Rows.string(row, "display_name"),
                    enabled = Rows.bool(row, "enabled"),
                    roles = Rows.stringOrNull(row, "role_names")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    orgUnits = row.get("org_units", Array<String>::class.java)?.toList() ?: emptyList(),
                    createdAt = Rows.instantOrNull(row, "created_at")
                )
            }.all()
            .asFlow()
            .toList()

    private suspend fun userOrFail(
        organizationId: UUID,
        id: UUID
    ): AdminUserResponse =
        loadUsers(organizationId, id).firstOrNull()
            ?: throw NotFoundException("User $id does not exist")

    private suspend fun findUserByEmail(
        organizationId: UUID,
        email: String
    ): UUID? =
        db
            .sql("SELECT id FROM ${schemas.metadata}.users WHERE organization_id = :organizationId AND email = :email")
            .bind("organizationId", organizationId)
            .bind("email", email)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitFirstOrNull()

    private suspend fun loadRoles(
        organizationId: UUID,
        name: String?
    ): List<RoleResponse> {
        val permissions = permissionsOf(organizationId)
        val fieldPermissions = fieldPermissionsOf(organizationId)
        return db
            .sql(
                """
                SELECT id, name, label, own_records_only
                FROM ${schemas.metadata}.roles
                WHERE organization_id = :organizationId AND (:name::text IS NULL OR name = :name::text)
                ORDER BY name
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .let { spec -> if (name == null) spec.bindNull("name", String::class.java) else spec.bind("name", name) }
            .map { row, _ ->
                val id = Rows.uuid(row, "id")
                RoleResponse(
                    id = id.toString(),
                    name = Rows.string(row, "name"),
                    label = Rows.string(row, "label"),
                    ownRecordsOnly = Rows.bool(row, "own_records_only"),
                    permissions = permissions[id].orEmpty(),
                    fieldPermissions = fieldPermissions[id].orEmpty()
                )
            }.all()
            .asFlow()
            .toList()
    }

    private suspend fun roleOrFail(
        organizationId: UUID,
        name: String
    ): RoleResponse =
        loadRoles(organizationId, name).firstOrNull()
            ?: throw NotFoundException("Role '$name' does not exist")

    private suspend fun findRole(
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

    private suspend fun roleIdOrFail(
        organizationId: UUID,
        name: String
    ): UUID =
        findRole(organizationId, name.trim().uppercase())
            ?: throw NotFoundException("Role '$name' does not exist")

    private suspend fun permissionsOf(organizationId: UUID): Map<UUID, List<PermissionResponse>> =
        db
            .sql(
                """
                SELECT p.role_id, o.name AS object_name, p.action, p.allowed
                FROM ${schemas.metadata}.permissions p
                JOIN ${schemas.metadata}.roles r ON r.id = p.role_id
                LEFT JOIN ${schemas.metadata}.custom_objects o ON o.id = p.object_id
                WHERE r.organization_id = :organizationId
                ORDER BY p.action
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .map { row, _ ->
                Rows.uuid(row, "role_id") to
                    PermissionResponse(
                        objectName = Rows.stringOrNull(row, "object_name"),
                        action = Rows.string(row, "action"),
                        allowed = Rows.bool(row, "allowed")
                    )
            }.all()
            .asFlow()
            .toList()
            .groupBy({ it.first }, { it.second })

    private suspend fun fieldPermissionsOf(organizationId: UUID): Map<UUID, List<FieldPermissionResponse>> =
        db
            .sql(
                """
                SELECT fp.role_id, o.name AS object_name, f.name AS field_name, fp.can_read, fp.can_write
                FROM ${schemas.metadata}.field_permissions fp
                JOIN ${schemas.metadata}.roles r ON r.id = fp.role_id
                JOIN ${schemas.metadata}.custom_fields f ON f.id = fp.field_id
                JOIN ${schemas.metadata}.custom_objects o ON o.id = f.object_id
                WHERE r.organization_id = :organizationId
                ORDER BY o.name, f.name
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .map { row, _ ->
                Rows.uuid(row, "role_id") to
                    FieldPermissionResponse(
                        objectName = Rows.string(row, "object_name"),
                        fieldName = Rows.string(row, "field_name"),
                        read = Rows.bool(row, "can_read"),
                        write = Rows.bool(row, "can_write")
                    )
            }.all()
            .asFlow()
            .toList()
            .groupBy({ it.first }, { it.second })

    // ---------------------------------------------------------------- helpers

    // an object the payload names but the tenant does not have is a bad request, not a missing page
    private suspend fun objectOrBadRequest(
        organizationId: UUID,
        objectName: String
    ): ObjectDefinition =
        try {
            metadata.loadDefinition(organizationId, objectName)
        } catch (ignored: NotFoundException) {
            throw ValidationException("Unknown object '$objectName'", "objectName", "object does not exist")
        }

    // a declared action (ADR-042) exists only on the object that declares it, never tenant-wide
    private suspend fun isDeclared(
        organizationId: UUID,
        objectName: String?,
        action: String
    ): Boolean {
        if (objectName == null) return false
        val objectId =
            try {
                metadata.loadDefinition(organizationId, objectName).obj.id
            } catch (ignored: NotFoundException) {
                return false
            }
        return actions.exists(organizationId, objectId, action)
    }

    private suspend fun resolveRoles(
        organizationId: UUID,
        names: List<String>
    ): List<UUID> =
        names
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { name ->
                findRole(organizationId, name)
                    ?: throw ValidationException("Unknown role '$name'", "roles", "role does not exist in this organization")
            }

    private suspend fun assignRoles(
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

    private fun requirePassword(password: String) {
        if (password.length < MIN_PASSWORD) {
            throw ValidationException("Password too short", "password", "must be at least $MIN_PASSWORD characters")
        }
    }

    private fun DatabaseClient.GenericExecuteSpec.bindNullableString(
        name: String,
        value: String?
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)

    companion object {
        private const val MIN_PASSWORD = 8
        private val ROLE_NAME = Regex("^[A-Z][A-Z0-9_]{1,48}$")
    }
}
