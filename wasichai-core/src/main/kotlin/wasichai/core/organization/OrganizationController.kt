package wasichai.core.organization

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class UpdateOrganizationRequest(
    @field:NotBlank val name: String
)

data class CreateOrganizationRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val slug: String,
    @field:NotBlank @field:Email val adminEmail: String,
    @field:NotBlank val adminPassword: String,
    val adminDisplayName: String? = null
)

// no list-all endpoint on purpose: a tenant must not be able to enumerate the others.
@RestController
@RequestMapping("/api/organizations")
class OrganizationController(
    private val organizations: OrganizationService
) {
    @GetMapping("/current")
    suspend fun current(): OrganizationResponse = organizations.current().toResponse()

    @PutMapping("/current")
    suspend fun update(
        @Valid @RequestBody request: UpdateOrganizationRequest
    ): OrganizationResponse = organizations.updateCurrent(request).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun provision(
        @Valid @RequestBody request: CreateOrganizationRequest
    ): OrganizationResponse = organizations.provision(request).toResponse()

    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete() = organizations.deleteCurrent()
}
