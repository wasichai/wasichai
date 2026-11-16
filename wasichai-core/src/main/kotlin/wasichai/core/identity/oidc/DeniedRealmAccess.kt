package wasichai.core.identity.oidc

import org.springframework.core.ResolvableType
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler
import org.springframework.security.web.server.authorization.AuthorizationContext
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import wasichai.core.common.ForbiddenException
import wasichai.core.common.GlobalExceptionHandler
import wasichai.core.identity.JwtService
import java.net.URI

// the reason a realm token was let in without access (RealmPrincipalConverter), or null
fun deniedReason(authentication: Authentication?): String? = (authentication?.principal as? Jwt)?.getClaimAsString(JwtService.CLAIM_DENIED)

// chain level, so no endpoint depends on calling CurrentUser.require(): authenticated, and not denied.
// a denied token is refused (403) by [handler], with its reason, in the body a ForbiddenException gets.
class DeniedRealmAccess(
    private val errors: GlobalExceptionHandler,
    private val codecs: ServerCodecConfigurer
) {
    private val authenticated = AuthenticatedReactiveAuthorizationManager.authenticated<AuthorizationContext>()
    private val bearerDenied = BearerTokenServerAccessDeniedHandler()

    val authorization =
        ReactiveAuthorizationManager<AuthorizationContext> { authentication, context ->
            authenticated.authorize(authentication, context).flatMap { result: AuthorizationResult ->
                if (!result.isGranted) {
                    Mono.just(result)
                } else {
                    authentication.map { AuthorizationDecision(deniedReason(it) == null) }
                }
            }
        }

    // anything else denied: what the resource server answers today
    val handler =
        ServerAccessDeniedHandler { exchange, denied ->
            exchange
                .getPrincipal<Authentication>()
                .mapNotNull { deniedReason(it) }
                .flatMap { reason -> write(exchange, reason!!) }
                .switchIfEmpty(Mono.defer { bearerDenied.handle(exchange, denied) })
        }

    // instance = the path, as spring fills it in for a problem an @ExceptionHandler returns
    private fun write(
        exchange: ServerWebExchange,
        reason: String
    ): Mono<Void> {
        val problem = errors.handleWasichai(ForbiddenException(reason))
        if (problem.instance == null) problem.instance = URI.create(exchange.request.path.value())
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        @Suppress("UNCHECKED_CAST")
        val writer =
            codecs.writers.first { it.canWrite(PROBLEM, MediaType.APPLICATION_PROBLEM_JSON) } as HttpMessageWriter<ProblemDetail>
        return writer.write(Mono.just(problem), PROBLEM, MediaType.APPLICATION_PROBLEM_JSON, response, emptyMap())
    }

    private companion object {
        val PROBLEM: ResolvableType = ResolvableType.forClass(ProblemDetail::class.java)
    }
}
