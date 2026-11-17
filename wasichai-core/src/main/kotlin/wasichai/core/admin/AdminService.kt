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
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.PasswordPolicy
import wasichai.core.identity.Passwords
import wasichai.core.identity.TokenRevocation
import wasichai.core.identity.UserInfo
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ModelActor
import wasichai.core.metadata.ModelOwner
import wasichai.core.metadata.ObjectActionRepository
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.RequestMemo
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

// users, roles and the rules attached to them. every entry point is MANAGE_ORGANIZATION,
// every query is pinned to the caller's tenant. every change leaves one admin audit entry (ADR-049).
// the role methods also take the platform, inside ModelOwner.run, ensuring an app's declared roles (ADR-085).
// disabling, a new password, new roles, unlinking and deleting sign the user out: their revocation marker moves (ADR-059).
@Service
class AdminService(
    private val db: DatabaseClient,
    private val metadata: MetadataService,
    private val actions: ObjectActionRepository,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val schemas: WasichaiSchemas,
    private val audit: AdminAudit,
    private val passwordPolicy: PasswordPolicy,
    private val revocation: TokenRevocation,
    // oidc: passwords live in the realm. a user is created without one, and one given is refused
    private val oidc: Boolean = false
) {
    private val roles = RoleAssignments(db, schemas)

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
        // before the permission check, where @NotBlank used to answer
        val password = Passwords.forMode(oidc, request.password, "password")
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val email = request.email.trim().lowercase()
        password?.let { requirePassword(it, UserInfo(email, request.displayName.trim(), admin.organizationId)) }
        if (findUserByEmail(admin.organizationId, email) != null) {
            throw ConflictException("User '$email' already exists")
        }
        val roleIds = roles.resolve(admin.organizationId, request.roles)
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
            .bindNullable("hash", password?.let { passwordEncoder.encode(it) })
            .bind("displayName", request.displayName.trim())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        roles.assign(id, roleIds)
        val created = userOrFail(admin.organizationId, id)
        audit.record(admin, AdminEntity.USER, id, AdminOperation.CREATE, null, AdminSnapshots.user(created))
        return created
    }

    @Transactional
    suspend fun updateUser(
        id: UUID,
        request: UpdateUserRequest
    ): AdminUserResponse {
        if (oidc && request.password != null) throw Passwords.inRealm("password")
        if (!oidc && request.unlink != null) {
            throw ValidationException("Request validation failed", "unlink", "there is no identity provider to unlink from")
        }
        val unlink = request.unlink == true
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val before = userOrFail(admin.organizationId, id)
        // locking yourself out is never a valid administration step
        if (request.enabled == false && id == admin.userId) {
            throw ForbiddenException("You cannot disable your own account")
        }
        request.password?.let { requirePassword(it, UserInfo(before.email, before.displayName, admin.organizationId)) }
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.users
                SET display_name = COALESCE(:displayName::text, display_name),
                    enabled = COALESCE(:enabled::boolean, enabled),
                    password_hash = COALESCE(:hash::text, password_hash),
                    external_subject = CASE WHEN :unlink::boolean THEN NULL ELSE external_subject END,
                    updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                """.trimIndent()
            ).bind("id", id)
            .bind("organizationId", admin.organizationId)
            .bindNullable("displayName", request.displayName?.trim()?.ifBlank { null })
            .bindNullable("enabled", request.enabled)
            .bindNullable("hash", request.password?.let { passwordEncoder.encode(it) })
            .bind("unlink", unlink)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (request.enabled == false || request.password != null || unlink) revocation.revoke(admin.organizationId, id)
        val updated = userOrFail(admin.organizationId, id)
        audit.record(
            admin,
            AdminEntity.USER,
            id,
            AdminOperation.UPDATE,
            AdminSnapshots.user(before),
            AdminSnapshots.userUpdated(updated, passwordChanged = request.password != null, unlinked = unlink && before.linked)
        )
        return updated
    }

    @Transactional
    suspend fun setUserRoles(
        id: UUID,
        request: UserRolesRequest
    ): AdminUserResponse {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val before = userOrFail(admin.organizationId, id)
        roles.replace(id, roles.resolve(admin.organizationId, request.roles))
        // the token carries the roles: the old ones must not outlive the change
        revocation.revoke(admin.organizationId, id)
        val updated = userOrFail(admin.organizationId, id)
        audit.record(admin, AdminEntity.USER, id, AdminOperation.UPDATE, AdminSnapshots.user(before), AdminSnapshots.user(updated))
        return updated
    }

    @Transactional
    suspend fun deleteUser(id: UUID) {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        if (id == admin.userId) {
            throw ForbiddenException("You cannot delete your own account")
        }
        val before = userOrFail(admin.organizationId, id)
        db
            .sql("DELETE FROM ${schemas.metadata}.users WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", admin.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        revocation.forget(admin.organizationId, id)
        audit.record(admin, AdminEntity.USER, id, AdminOperation.DELETE, AdminSnapshots.user(before), null)
    }

    // ------------------------------------------------------------------ roles

    suspend fun listRoles(): List<RoleResponse> {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        return loadRoles(user.organizationId, null)
    }

    @Transactional
    suspend fun createRole(request: CreateRoleRequest): RoleResponse {
        val actor = roleActor()
        val name = RoleGrants.name(request.name)
        if (!ROLE_NAME.matches(name)) {
            throw ValidationException("Invalid role name '$name'", "name", "must match ^[A-Z][A-Z0-9_]{1,48}$")
        }
        if (roles.findRole(actor.organizationId, name) != null) {
            throw ConflictException("Role '$name' already exists")
        }
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.roles (id, organization_id, name, label, own_records_only, declared_by)
                VALUES (:id, :organizationId, :name, :label, :ownRecordsOnly, :declaredBy)
                """.trimIndent()
            ).bind("id", UUID.randomUUID())
            .bind("organizationId", actor.organizationId)
            .bind("name", name)
            .bind("label", RoleGrants.label(request.label))
            .bind("ownRecordsOnly", request.ownRecordsOnly)
            // the platform's role is its model's; a person's is nobody's
            .bindNullable("declaredBy", actor.model)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        val created = roleOrFail(actor.organizationId, name)
        actor.record(audit, AdminEntity.ROLE, UUID.fromString(created.id), AdminOperation.CREATE, null, AdminSnapshots.role(created))
        // what this request remembered of the roles is wrong now (ADR-066)
        RequestMemo.clear()
        return created
    }

    @Transactional
    suspend fun updateRole(
        name: String,
        request: UpdateRoleRequest
    ): RoleResponse {
        val actor = roleActor()
        val role = roleIdOrFail(actor.organizationId, name)
        val before = roleOrFail(actor.organizationId, name.trim().uppercase())
        actor.requireMayChange(before.declaredBy, "Role '${before.name}'")
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.roles
                SET label = COALESCE(:label::text, label),
                    own_records_only = COALESCE(:ownRecordsOnly::boolean, own_records_only),
                    declared_by = COALESCE(:model::text, declared_by)
                WHERE id = :id AND organization_id = :organizationId
                """.trimIndent()
            ).bind("id", role)
            .bind("organizationId", actor.organizationId)
            .bindNullable("label", request.label?.trim()?.ifBlank { null })
            .bindNullable("ownRecordsOnly", request.ownRecordsOnly)
            // the platform takes a person's role over: it is the model's from now on (ADR-085)
            .bindNullable("model", actor.model)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        val updated = roleOrFail(actor.organizationId, name.trim().uppercase())
        actor.record(audit, AdminEntity.ROLE, role, AdminOperation.UPDATE, AdminSnapshots.role(before), AdminSnapshots.role(updated))
        // what this request remembered of the roles is wrong now (ADR-066)
        RequestMemo.clear()
        return updated
    }

    @Transactional
    suspend fun deleteRole(name: String) {
        val admin = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val normalized = RoleGrants.name(name)
        if (normalized == AuthenticatedUser.ADMIN_ROLE) {
            throw ConflictException("The ADMIN role administers the tenant and cannot be deleted")
        }
        val id = roleIdOrFail(admin.organizationId, normalized)
        val before = roleOrFail(admin.organizationId, normalized)
        ModelActor(admin.organizationId, admin, null).requireMayChange(before.declaredBy, "Role '$normalized'")
        db
            .sql("DELETE FROM ${schemas.metadata}.roles WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        audit.record(admin, AdminEntity.ROLE, id, AdminOperation.DELETE, AdminSnapshots.role(before), null)
        // what this request remembered of the roles is wrong now (ADR-066)
        RequestMemo.clear()
    }

    // replaces the whole action set of the role
    @Transactional
    suspend fun setPermissions(
        name: String,
        request: RolePermissionsRequest
    ): RoleResponse {
        val actor = roleActor()
        val normalized = RoleGrants.name(name)
        val roleId = roleIdOrFail(actor.organizationId, normalized)
        val before = roleOrFail(actor.organizationId, normalized)
        actor.requireMayChange(before.declaredBy, "Role '$normalized'")
        val objects = ObjectLookup(actor.organizationId)

        // RoleGrants: the same pass DeclaredRoles compares with
        val resolved =
            RoleGrants
                .grants(request.permissions)
                .map { entry ->
                    val action = entry.action
                    val objectName = entry.objectName
                    // the action is judged before the object, as it always was
                    if (action !in Actions.BUILT_IN && !objects.declares(objectName, action)) {
                        throw ValidationException(
                            "Unknown action '$action'",
                            "action",
                            "must be one of ${Actions.BUILT_IN.joinToString(", ")} or an action the object declares"
                        )
                    }
                    // tenant-wide by nature: an object-scoped row would mean nothing (ADR-055)
                    if (action == Actions.MANAGE_TENANTS && objectName != null) {
                        throw ValidationException("Action '$action' takes no object", "objectName", "must be empty for $action")
                    }
                    val objectId = objectName?.let { objects.require(it).obj.id }
                    Triple(objectId, action, entry.allowed)
                }

        // only a holder hands MANAGE_TENANTS on or takes it away, whatever the switch: else any tenant's
        // administrator could grant it to itself (ADR-055). the platform never grants it (DeclaredModels refuses
        // that), so it only takes it away, from a person's role it takes over (ADR-085)
        val tenantsBefore = before.permissions.filter { it.action == Actions.MANAGE_TENANTS }.map { it.allowed }
        val tenantsAfter = resolved.filter { it.second == Actions.MANAGE_TENANTS }.map { it.third }
        val person = actor.user
        if (tenantsBefore != tenantsAfter && person != null && !currentUser.holdsTenantsGrant(person)) {
            throw ForbiddenException("Missing permission ${Actions.MANAGE_TENANTS}")
        }

        db
            .sql("DELETE FROM ${schemas.metadata}.permissions WHERE role_id = :roleId")
            .bind("roleId", roleId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        resolved.forEach { (objectId, action, allowed) ->
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.permissions (role_id, object_id, action, allowed)
                    VALUES (:roleId, :objectId, :action, :allowed)
                    """.trimIndent()
                ).bind("roleId", roleId)
                .bindNullable("objectId", objectId)
                .bind("action", action)
                .bind("allowed", allowed)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val updated = roleOrFail(actor.organizationId, normalized)
        // both whole sets: the diff shows the grants that changed
        actor.record(
            audit,
            AdminEntity.PERMISSION,
            roleId,
            AdminOperation.UPDATE,
            AdminSnapshots.permissions(before),
            AdminSnapshots.permissions(updated)
        )
        // what this request remembered of the roles is wrong now (ADR-066)
        RequestMemo.clear()
        return updated
    }

    // replaces the rules for the objects named in the payload. other objects keep theirs.
    @Transactional
    suspend fun setFieldPermissions(
        name: String,
        request: RoleFieldPermissionsRequest
    ): RoleResponse = setFieldPermissions(name, request, wholeSet = false)

    // [wholeSet]: the payload is every rule of the role, so the rules on objects it does not name go too. a declared
    // role's (ADR-085): nobody else can have written them
    @Transactional
    internal suspend fun setFieldPermissions(
        name: String,
        request: RoleFieldPermissionsRequest,
        wholeSet: Boolean
    ): RoleResponse {
        val actor = roleActor()
        val normalized = RoleGrants.name(name)
        val roleId = roleIdOrFail(actor.organizationId, normalized)
        val before = roleOrFail(actor.organizationId, normalized)
        actor.requireMayChange(before.declaredBy, "Role '$normalized'")
        val objects = ObjectLookup(actor.organizationId)

        val touchedObjects = mutableSetOf<UUID>()
        val rows =
            // RoleGrants: the same pass DeclaredRoles compares with
            RoleGrants.rules(request.fields).map { entry ->
                val objectName = entry.objectName
                val definition = objects.require(objectName)
                val fieldName = entry.fieldName
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

        if (wholeSet) {
            db
                .sql("DELETE FROM ${schemas.metadata}.field_permissions WHERE role_id = :roleId")
                .bind("roleId", roleId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else if (touchedObjects.isNotEmpty()) {
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

        rows.forEach { (fieldId, read, write) ->
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.field_permissions (role_id, field_id, can_read, can_write)
                    VALUES (:roleId, :fieldId, :canRead, :canWrite)
                    """.trimIndent()
                ).bind("roleId", roleId)
                .bind("fieldId", fieldId)
                .bind("canRead", read)
                .bind("canWrite", write)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val updated = roleOrFail(actor.organizationId, normalized)
        actor.record(
            audit,
            AdminEntity.PERMISSION,
            roleId,
            AdminOperation.UPDATE,
            AdminSnapshots.fieldPermissions(before),
            AdminSnapshots.fieldPermissions(updated)
        )
        // what this request remembered of the roles is wrong now (ADR-066)
        RequestMemo.clear()
        return updated
    }

    // the role as it reads, or null: for DeclaredRoles, which compares it with the declaration (ADR-085)
    internal suspend fun roleOrNull(
        organizationId: UUID,
        name: String
    ): RoleResponse? = loadRoles(organizationId, name.trim().uppercase()).firstOrNull()

    // [model]'s roles in [organizationId] it no longer declares are nobody's again: grants and holders stay, and a
    // person may change or delete them (ADR-085). answers their names. the platform's act: no user in its entries
    @Transactional
    internal suspend fun release(
        organizationId: UUID,
        model: String,
        keep: Set<String>
    ): List<String> {
        val released =
            db
                .sql(
                    """
                    UPDATE ${schemas.metadata}.roles SET declared_by = NULL
                    WHERE organization_id = :organizationId AND declared_by = :model AND NOT (name = ANY(:keep))
                    RETURNING id, name
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("model", model)
                .bind("keep", keep.map { it.trim().uppercase() }.toTypedArray())
                .map { row, _ -> Rows.uuid(row, "id") to Rows.string(row, "name") }
                .all()
                .asFlow()
                .toList()
                .sortedBy { it.second }
        released.forEach { (id, name) ->
            val after = roleOrFail(organizationId, name)
            audit.recordPlatform(
                organizationId,
                AdminEntity.ROLE,
                id,
                AdminOperation.UPDATE,
                AdminSnapshots.role(after.copy(declaredBy = model)),
                AdminSnapshots.role(after)
            )
        }
        // what this request remembered of the roles is wrong now (ADR-066)
        if (released.isNotEmpty()) RequestMemo.clear()
        return released.map { it.second }
    }

    // the platform inside ModelOwner.run, ensuring a declared model's roles (ADR-085); else the caller, who must hold
    // MANAGE_ORGANIZATION
    private suspend fun roleActor(): ModelActor {
        ModelOwner.platform()?.let { (organizationId, model) -> return ModelActor(organizationId, null, model) }
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        return ModelActor(user.organizationId, user, null)
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
                SELECT u.id, u.email, u.display_name, u.enabled, u.external_subject IS NOT NULL AS linked, u.created_at,
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
                GROUP BY u.id, u.email, u.display_name, u.enabled, u.external_subject, u.created_at
                ORDER BY u.email
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bindNullable("id", id)
            .map { row, _ ->
                AdminUserResponse(
                    id = Rows.uuid(row, "id").toString(),
                    email = Rows.string(row, "email"),
                    displayName = Rows.string(row, "display_name"),
                    enabled = Rows.bool(row, "enabled"),
                    linked = Rows.bool(row, "linked"),
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
                SELECT id, name, label, own_records_only, declared_by
                FROM ${schemas.metadata}.roles
                WHERE organization_id = :organizationId AND (:name::text IS NULL OR name = :name::text)
                ORDER BY name
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bindNullable("name", name)
            .map { row, _ ->
                val id = Rows.uuid(row, "id")
                RoleResponse(
                    id = id.toString(),
                    name = Rows.string(row, "name"),
                    label = Rows.string(row, "label"),
                    ownRecordsOnly = Rows.bool(row, "own_records_only"),
                    permissions = permissions[id].orEmpty(),
                    fieldPermissions = fieldPermissions[id].orEmpty(),
                    declaredBy = Rows.stringOrNull(row, "declared_by")
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

    private suspend fun roleIdOrFail(
        organizationId: UUID,
        name: String
    ): UUID =
        roles.findRole(organizationId, name.trim().uppercase())
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

    // the objects one payload names, each read once however many entries name it
    private inner class ObjectLookup(
        private val organizationId: UUID
    ) {
        private val read = mutableMapOf<String, ObjectDefinition?>()

        suspend fun find(objectName: String): ObjectDefinition? {
            if (objectName in read) return read[objectName]
            val definition =
                try {
                    metadata.loadDefinition(organizationId, objectName)
                } catch (ignored: NotFoundException) {
                    null
                }
            read[objectName] = definition
            return definition
        }

        // an object the payload names but the tenant does not have is a bad request, not a missing page
        suspend fun require(objectName: String): ObjectDefinition =
            find(objectName) ?: throw ValidationException("Unknown object '$objectName'", "objectName", "object does not exist")

        // a declared action (ADR-042) exists only on the object that declares it, never tenant-wide
        suspend fun declares(
            objectName: String?,
            action: String
        ): Boolean {
            val definition = objectName?.let { find(it) } ?: return false
            return actions.exists(organizationId, definition.obj.id, action)
        }
    }

    private fun requirePassword(
        password: String,
        user: UserInfo
    ) = PasswordPolicy.enforce(passwordPolicy, password, user, "password")

    companion object {
        private val ROLE_NAME = Regex("^[A-Z][A-Z0-9_]{1,48}$")
    }
}
