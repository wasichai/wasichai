package wasichai.core.admin

// what the admin trail stores of each entity (ADR-049). built from the api answers, which never carry a hash or a
// secret, and without timestamps, so a diff holds only what someone changed. the id is the entry's recordId.
internal object AdminSnapshots {
    fun user(user: AdminUserResponse): Map<String, Any?> =
        linkedMapOf(
            "email" to user.email,
            "displayName" to user.displayName,
            "enabled" to user.enabled,
            "roles" to user.roles,
            "orgUnits" to user.orgUnits
        )

    // a password is never stored, not even hashed: only that it changed
    fun userUpdated(
        user: AdminUserResponse,
        passwordChanged: Boolean
    ): Map<String, Any?> = if (passwordChanged) user(user) + ("passwordChanged" to true) else user(user)

    // the grants ride along, so a deleted role's entry says what it could do
    fun role(role: RoleResponse): Map<String, Any?> =
        linkedMapOf(
            "name" to role.name,
            "label" to role.label,
            "ownRecordsOnly" to role.ownRecordsOnly,
            "permissions" to grants(role.permissions),
            "fieldPermissions" to fieldRules(role.fieldPermissions)
        )

    // one key per grant, not one list: the audit diff then names only the grants that changed
    fun permissions(role: RoleResponse): Map<String, Any?> = linkedMapOf<String, Any?>("role" to role.name) + grants(role.permissions)

    fun fieldPermissions(role: RoleResponse): Map<String, Any?> = linkedMapOf<String, Any?>("role" to role.name) + fieldRules(role.fieldPermissions)

    // "<object>.<ACTION>", "*.<ACTION>" for every object. an object name has no '.', so keys never clash
    fun grants(permissions: List<PermissionResponse>): Map<String, Boolean> =
        permissions.associate { "${it.objectName ?: ALL_OBJECTS}.${it.action}" to it.allowed }.toSortedMap()

    // "<object>.<field>"
    fun fieldRules(rules: List<FieldPermissionResponse>): Map<String, Map<String, Boolean>> =
        rules.associate { "${it.objectName}.${it.fieldName}" to mapOf("read" to it.read, "write" to it.write) }.toSortedMap()

    // never clientSecret: the response carries it on create and rotate, the trail never does
    fun serviceAccount(account: ServiceAccountResponse): Map<String, Any?> =
        linkedMapOf(
            "name" to account.name,
            "enabled" to account.enabled,
            "roles" to account.roles
        )

    fun orgUnit(unit: OrgUnitResponse): Map<String, Any?> =
        linkedMapOf(
            "code" to unit.code,
            "label" to unit.label,
            "parentCode" to unit.parentCode
        )

    private const val ALL_OBJECTS = "*"
}
