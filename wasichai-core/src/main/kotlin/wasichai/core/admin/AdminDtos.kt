package wasichai.core.admin

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import wasichai.core.metadata.FieldPermissionEntry
import wasichai.core.metadata.PermissionEntry
import java.time.Instant

// ---------------------------------------------------------------------------
// requests. a null field means "leave as it is".
// ---------------------------------------------------------------------------

data class CreateUserRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val displayName: String,
    // required in local mode, refused in oidc: the service decides, it knows the mode
    val password: String? = null,
    val roles: List<String> = emptyList()
)

data class UpdateUserRequest(
    val displayName: String? = null,
    val enabled: Boolean? = null,
    val password: String? = null,
    // oidc: true forgets the realm account linked to the user; the next sign-in with the verified email links again
    val unlink: Boolean? = null
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

// PermissionEntry: wasichai.core.metadata, where a declared role takes it too (ADR-085)
data class RolePermissionsRequest(
    val permissions: List<PermissionEntry> = emptyList()
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
    // a realm account is linked (oidc): external_subject is set. always false in local mode
    val linked: Boolean,
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
    val fieldPermissions: List<FieldPermissionResponse>,
    // the model that declares the role; absent for a role a person made
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val declaredBy: String? = null
)
