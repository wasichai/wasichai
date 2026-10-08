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

// throttle: attempt limits when wasichai.security.login.enabled (ADR-059); a pass-through otherwise.
// revocation: the caller's own marker, for logout.
@Service
class AuthService(
    private val users: UserRepository,
    private val roleQueries: RoleQueries,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val throttle: LoginThrottle,
    private val revocation: TokenRevocation
) {
    // clientAddress: the request's remote address, the key of the per-client limit
    suspend fun login(
        email: String,
        password: String,
        clientAddress: String? = null
    ): LoginResponse {
        val normalized = email.trim().lowercase()
        return throttle.login(normalized, clientAddress) { authenticate(normalized, password) }
    }

    // every token the caller holds stops working, with revocation on. the marker moves either way
    suspend fun logout(caller: AuthenticatedUser) {
        revocation.revoke(caller.organizationId, caller.userId)
    }

    private suspend fun authenticate(
        email: String,
        password: String
    ): LoginResponse {
        val user = users.findByEmail(email) ?: throw invalidCredentials()
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
