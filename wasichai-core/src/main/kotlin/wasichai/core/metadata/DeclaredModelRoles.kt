package wasichai.core.metadata

import jakarta.validation.constraints.NotBlank
import java.util.UUID

// an entry of PUT /api/roles/{name}/permissions, and a declared role's grant (ADR-085). objectName null = every
// object of the organization; the action is built in or one the object declares (ADR-042)
data class PermissionEntry(
    val objectName: String? = null,
    @field:NotBlank val action: String,
    val allowed: Boolean = true
)

// an entry of PUT /api/roles/{name}/field-permissions, and a declared role's field rule (ADR-085)
data class FieldPermissionEntry(
    @field:NotBlank val objectName: String,
    @field:NotBlank val fieldName: String,
    val read: Boolean = true,
    val write: Boolean = true
)

/**
 * A role an app declares in its [DeclaredModel] (ADR-085), in the API's shapes: the whole role. Its name matches
 * `^[A-Z][A-Z0-9_]{1,48}$` once upper-cased, is never `ADMIN`, and it grants neither `MANAGE_ORGANIZATION` nor
 * `MANAGE_TENANTS`: tenant administration is the administrator's to hand out. [permissions] and [fieldPermissions] are
 * the whole sets; a field rule on an object it no longer names is removed.
 */
data class DeclaredRole(
    val name: String,
    val label: String,
    val ownRecordsOnly: Boolean = false,
    val permissions: List<PermissionEntry> = emptyList(),
    val fieldPermissions: List<FieldPermissionEntry> = emptyList()
)

// what ensuring a model's roles in one tenant did, line by line, as DeclaredModelReport's
data class RoleLines(
    val created: List<String>,
    val updated: List<String>,
    val incompatible: List<String>
)

/**
 * How [DeclaredModels] ensures a model's roles (ADR-085). `metadata` may not reach `admin`, which owns roles, so
 * `admin` implements this port and the auto-configuration hands it over. Called inside the model's run
 * (`ModelOwner.run`), so the changes are the platform's. [roles] empty still releases what the model used to declare.
 */
fun interface DeclaredModelRoles {
    suspend fun ensure(
        organizationId: UUID,
        model: String,
        roles: List<DeclaredRole>
    ): RoleLines
}
