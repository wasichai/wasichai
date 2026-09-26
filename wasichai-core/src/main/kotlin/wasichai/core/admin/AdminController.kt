package wasichai.core.admin

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserAdminController(
    private val admin: AdminService
) {
    @GetMapping
    suspend fun list(): List<AdminUserResponse> = admin.listUsers()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @Valid @RequestBody request: CreateUserRequest
    ): AdminUserResponse = admin.createUser(request)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateUserRequest
    ): AdminUserResponse = admin.updateUser(id, request)

    @PutMapping("/{id}/roles")
    suspend fun setRoles(
        @PathVariable id: UUID,
        @RequestBody request: UserRolesRequest
    ): AdminUserResponse = admin.setUserRoles(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = admin.deleteUser(id)
}

// roles are addressed by name: it is what the token carries and what the UI shows
@RestController
@RequestMapping("/api/roles")
class RoleAdminController(
    private val admin: AdminService
) {
    @GetMapping
    suspend fun list(): List<RoleResponse> = admin.listRoles()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @Valid @RequestBody request: CreateRoleRequest
    ): RoleResponse = admin.createRole(request)

    @PutMapping("/{name}")
    suspend fun update(
        @PathVariable name: String,
        @RequestBody request: UpdateRoleRequest
    ): RoleResponse = admin.updateRole(name, request)

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable name: String
    ) = admin.deleteRole(name)

    @PutMapping("/{name}/permissions")
    suspend fun setPermissions(
        @PathVariable name: String,
        @Valid @RequestBody request: RolePermissionsRequest
    ): RoleResponse = admin.setPermissions(name, request)

    @PutMapping("/{name}/field-permissions")
    suspend fun setFieldPermissions(
        @PathVariable name: String,
        @Valid @RequestBody request: RoleFieldPermissionsRequest
    ): RoleResponse = admin.setFieldPermissions(name, request)
}
