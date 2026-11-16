package wasichai.core.identity

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.NotFoundException

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String
)

// oidc: the realm signs people in and out, so login and logout are not there (404)
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val currentUser: CurrentUser,
    private val oidc: Boolean = false
) {
    // the remote address is the client's only when Spring says so: server.forward-headers-strategy behind a proxy.
    // a raw X-Forwarded-For is never read, any client can send one (ADR-059)
    @PostMapping("/login")
    suspend fun login(
        @Valid @RequestBody request: LoginRequest,
        http: ServerHttpRequest
    ): LoginResponse {
        localOnly()
        return authService.login(request.email, request.password, clientAddress(http))
    }

    // the caller's own tokens, all of them. effective with wasichai.security.jwt.revocation (ADR-059)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun logout() {
        localOnly()
        authService.logout(currentUser.require())
    }

    @GetMapping("/me")
    suspend fun me(): AuthenticatedUser = currentUser.require()

    private fun localOnly() {
        if (oidc) throw NotFoundException("Not found")
    }
}

internal fun clientAddress(http: ServerHttpRequest): String? =
    http.remoteAddress
        ?.address
        ?.hostAddress
