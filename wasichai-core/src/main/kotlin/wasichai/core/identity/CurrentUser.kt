package wasichai.core.identity

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.common.UnauthorizedException
import java.util.UUID

data class AuthenticatedUser(
    val userId: UUID,
    val organizationId: UUID,
    val email: String,
    val roles: List<String>
) {
    val isAdmin: Boolean get() = ADMIN_ROLE in roles

    companion object {
        const val ADMIN_ROLE = "ADMIN"
    }
}

// tenant comes from the token, never from the request body. every query filters by it.
@Component
class CurrentUser(
    private val roleQueries: RoleQueries
) {
    suspend fun require(): AuthenticatedUser {
        val authentication =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        val jwt =
            authentication.principal as? Jwt
                ?: throw UnauthorizedException("Authentication required")
        return AuthenticatedUser(
            userId = UUID.fromString(jwt.subject),
            organizationId = UUID.fromString(jwt.getClaimAsString(JwtService.CLAIM_ORGANIZATION)),
            email = jwt.getClaimAsString(JwtService.CLAIM_EMAIL) ?: "",
            roles = jwt.getClaimAsStringList(JwtService.CLAIM_ROLES) ?: emptyList()
        )
    }

    suspend fun requireWithPermission(
        action: String,
        objectId: UUID? = null
    ): AuthenticatedUser {
        val user = require()
        requirePermission(user, action, objectId)
        return user
    }

    suspend fun permittedObjects(
        user: AuthenticatedUser,
        action: String
    ): RoleQueries.PermittedObjects =
        if (user.isAdmin) {
            RoleQueries.PermittedObjects(true, emptySet())
        } else {
            roleQueries.permittedObjects(user.roles, user.organizationId, action)
        }

    // for callers that had to resolve the object before they could scope the check
    suspend fun requirePermission(
        user: AuthenticatedUser,
        action: String,
        objectId: UUID? = null
    ) {
        if (user.isAdmin) return
        if (!roleQueries.hasPermission(user.roles, user.organizationId, action, objectId)) {
            throw ForbiddenException("Missing permission $action")
        }
    }
}
