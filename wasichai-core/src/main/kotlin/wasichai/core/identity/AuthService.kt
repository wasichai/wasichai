package wasichai.core.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import wasichai.core.common.UnauthorizedException
import java.time.Instant

data class LoginResponse(
    val token: String,
    val expiresAt: Instant,
    val user: UserSummary
)

data class UserSummary(
    val id: String,
    val email: String,
    val displayName: String,
    val organizationId: String,
    val roles: List<String>
)

@Service
class AuthService(
    private val users: UserRepository,
    private val roleQueries: RoleQueries,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService
) {
    suspend fun login(
        email: String,
        password: String
    ): LoginResponse {
        val user = users.findByEmail(email.trim().lowercase()) ?: throw invalidCredentials()
        // bcrypt is slow on purpose: off the event loop, or a burst of logins stalls every request
        if (!user.enabled || !withContext(Dispatchers.Default) { passwordEncoder.matches(password, user.passwordHash) }) {
            throw invalidCredentials()
        }
        val roles = roleQueries.roleNamesOf(user.id)
        val issued = jwtService.issue(user, roles)
        return LoginResponse(
            token = issued.token,
            expiresAt = issued.expiresAt,
            user =
                UserSummary(
                    id = user.id.toString(),
                    email = user.email,
                    displayName = user.displayName,
                    organizationId = user.organizationId.toString(),
                    roles = roles
                )
        )
    }

    // same error for unknown user and bad password. no account probing.
    private fun invalidCredentials() = UnauthorizedException("Invalid email or password")
}
