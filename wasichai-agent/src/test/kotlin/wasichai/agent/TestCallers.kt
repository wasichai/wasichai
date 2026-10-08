package wasichai.agent

import kotlinx.coroutines.reactor.asCoroutineContext
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.JwtService
import java.util.UUID
import kotlin.coroutines.CoroutineContext

// a signed-in caller without a token server: the reactive security context the request would carry
object TestCallers {
    fun jwt(
        email: String = "admin@wasichai.local",
        roles: List<String> = listOf(AuthenticatedUser.ADMIN_ROLE),
        organizationId: UUID = UUID.randomUUID()
    ): Jwt =
        Jwt
            .withTokenValue("test")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .claim(JwtService.CLAIM_ORGANIZATION, organizationId.toString())
            .claim(JwtService.CLAIM_EMAIL, email)
            .claim(JwtService.CLAIM_ROLES, roles)
            .build()

    fun context(jwt: Jwt = jwt()): CoroutineContext = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)).asCoroutineContext()
}
