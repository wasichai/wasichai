package wasichai.core.admin

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant

// ---------------------------------------------------------------------------
// requests. a null field means "leave as it is".
// ---------------------------------------------------------------------------

data class CreateUserRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val displayName: String,
    @field:NotBlank val password: String,
    val roles: List<String> = emptyList()
)

data class UpdateUserRequest(
    val displayName: String? = null,
    val enabled: Boolean? = null,
    val password: String? = null
)

data class UserRolesRequest(
    val roles: List<String> = emptyList()
)

data class CreateRoleRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val label: String,
    val ownRecordsOnly: Boolean = false
)

data class UpdateRoleRequest(
    val label: String? = null,
    val ownRecordsOnly: Boolean? = null
)

// objectName null = every object of the organization
data class PermissionEntry(
    val objectName: String? = null,
    @field:NotBlank val action: String,
    val allowed: Boolean = true
)

data class RolePermissionsRequest(
    val permissions: List<PermissionEntry> = emptyList()
)

data class FieldPermissionEntry(
    @field:NotBlank val objectName: String,
    @field:NotBlank val fieldName: String,
    val read: Boolean = true,
    val write: Boolean = true
)

data class RoleFieldPermissionsRequest(
    val fields: List<FieldPermissionEntry> = emptyList()
)

// ---------------------------------------------------------------------------
// responses. no password hash ever leaves here.
// ---------------------------------------------------------------------------

data class AdminUserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val enabled: Boolean,
    val roles: List<String>,
    // unit codes, sorted (ADR-045)
    val orgUnits: List<String>,
    val createdAt: Instant?
)

data class PermissionResponse(
    val objectName: String?,
    val action: String,
    val allowed: Boolean
)

data class FieldPermissionResponse(
    val objectName: String,
    val fieldName: String,
    val read: Boolean,
    val write: Boolean
)

data class RoleResponse(
    val id: String,
    val name: String,
    val label: String,
    val ownRecordsOnly: Boolean,
    val permissions: List<PermissionResponse>,
    val fieldPermissions: List<FieldPermissionResponse>
)
