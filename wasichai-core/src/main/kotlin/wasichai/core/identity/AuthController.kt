package wasichai.core.identity

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val currentUser: CurrentUser
) {
    @PostMapping("/login")
    suspend fun login(
        @Valid @RequestBody request: LoginRequest
    ): LoginResponse = authService.login(request.email, request.password)

    @GetMapping("/me")
    suspend fun me(): AuthenticatedUser = currentUser.require()
}
