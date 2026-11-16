package wasichai.core.admin

import wasichai.core.common.Actions
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.DeclaredModelRoles
import wasichai.core.metadata.DeclaredRole
import wasichai.core.metadata.FieldPermissionEntry
import wasichai.core.metadata.ObjectActionRepository
import wasichai.core.metadata.PermissionEntry
import wasichai.core.metadata.RoleLines
import java.util.UUID

/**
 * A model's declared roles in one tenant (ADR-085), through [AdminService]'s own role methods, which take the
 * platform as actor inside the model's run. Per role: every grant is checked against the tenant first, and one it
 * cannot hold (an object, field or declared action it lacks) leaves the role as it was, reported; another model's role
 * is reported too; a missing role is made; one that differs, or a person's under that name, is made as declared. Then
 * the model's roles it no longer declares are released. What already matches is left alone: no write, no entry.
 */
class DeclaredRoles(
    private val admin: AdminService,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val actions: ObjectActionRepository
) : DeclaredModelRoles {
    override suspend fun ensure(
        organizationId: UUID,
        model: String,
        roles: List<DeclaredRole>
    ): RoleLines {
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val incompatible = mutableListOf<String>()
        roles.forEach { declared ->
            val role = Declared(declared)
            val missing = missing(organizationId, role)
            if (missing.isNotEmpty()) {
                missing.forEach { incompatible += "role ${role.name}: $it is not in this tenant" }
                return@forEach
            }
            val existing = admin.roleOrNull(organizationId, role.name)
            when {
                existing == null -> {
                    create(role)
                    created += "role ${role.name}"
                }
                existing.declaredBy != null && existing.declaredBy != model -> incompatible += "role ${role.name} is declared by '${existing.declaredBy}'"
                change(existing, role, model) -> updated += if (existing.declaredBy == null) "role ${role.name} (taken over)" else "role ${role.name}"
            }
        }
        admin.release(organizationId, model, roles.map { Declared(it).name }.toSet()).forEach { updated += "released role $it" }
        return RoleLines(created, updated, incompatible)
    }

    // every grant the tenant cannot hold, said as "object x", "field x.y" or "action x.Y"
    private suspend fun missing(
        organizationId: UUID,
        role: Declared
    ): List<String> {
        val found = mutableMapOf<String, UUID?>()

        suspend fun objectId(name: String): UUID? = found.getOrPut(name) { objects.findByName(organizationId, name)?.id }

        val missing = mutableListOf<String>()
        role.permissions.forEach { (objectName, action) ->
            if (objectName == null) return@forEach
            val id = objectId(objectName)
            when {
                id == null -> missing += "object $objectName"
                action !in Actions.BUILT_IN && !actions.exists(organizationId, id, action) -> missing += "action $objectName.$action"
            }
        }
        role.fieldPermissions.forEach { (objectName, fieldName) ->
            val id = objectId(objectName)
            when {
                id == null -> missing += "object $objectName"
                fields.findByName(id, fieldName) == null -> missing += "field $objectName.$fieldName"
            }
        }
        return missing.distinct()
    }

    private suspend fun create(role: Declared) {
        admin.createRole(CreateRoleRequest(role.name, role.label, role.ownRecordsOnly))
        if (role.permissions.isNotEmpty()) admin.setPermissions(role.name, role.permissionsRequest())
        if (role.fieldPermissions.isNotEmpty()) admin.setFieldPermissions(role.name, role.fieldPermissionsRequest(), wholeSet = true)
    }

    // each part that differs, and only that, made as declared. true when anything was written
    private suspend fun change(
        existing: RoleResponse,
        role: Declared,
        model: String
    ): Boolean {
        val grants = existing.permissions.map { Grant(it.objectName, it.action, it.allowed) }.toSet()
        val rules = existing.fieldPermissions.map { Rule(it.objectName, it.fieldName, it.read, it.write) }.toSet()
        // a person's role becomes the model's even when it already reads as declared
        val details = existing.declaredBy != model || existing.label != role.label || existing.ownRecordsOnly != role.ownRecordsOnly
        val permissions = grants != role.permissions.toSet()
        val fieldPermissions = rules != role.fieldPermissions.toSet()
        if (details) admin.updateRole(role.name, UpdateRoleRequest(label = role.label, ownRecordsOnly = role.ownRecordsOnly))
        if (permissions) admin.setPermissions(role.name, role.permissionsRequest())
        if (fieldPermissions) admin.setFieldPermissions(role.name, role.fieldPermissionsRequest(), wholeSet = true)
        return details || permissions || fieldPermissions
    }

    private data class Grant(
        val objectName: String?,
        val action: String,
        val allowed: Boolean
    )

    private data class Rule(
        val objectName: String,
        val fieldName: String,
        val read: Boolean,
        val write: Boolean
    )

    // the declaration as the role methods store it: names trimmed and cased, the first entry of a repeated grant
    // kept, and no write without read
    private class Declared(
        declared: DeclaredRole
    ) {
        val name = declared.name.trim().uppercase()
        val label = declared.label.trim()
        val ownRecordsOnly = declared.ownRecordsOnly
        val permissions =
            declared.permissions
                .map {
                    Grant(
                        it.objectName
                            ?.trim()
                            ?.lowercase()
                            ?.ifBlank { null },
                        it.action.trim().uppercase(),
                        it.allowed
                    )
                }.distinctBy { it.objectName to it.action }
        val fieldPermissions =
            declared.fieldPermissions
                .map { Rule(it.objectName.trim().lowercase(), it.fieldName.trim().lowercase(), it.read, it.write && it.read) }
                .distinctBy { it.objectName to it.fieldName }

        fun permissionsRequest() = RolePermissionsRequest(permissions.map { PermissionEntry(it.objectName, it.action, it.allowed) })

        fun fieldPermissionsRequest() =
            RoleFieldPermissionsRequest(fieldPermissions.map { FieldPermissionEntry(it.objectName, it.fieldName, it.read, it.write) })
    }
}
