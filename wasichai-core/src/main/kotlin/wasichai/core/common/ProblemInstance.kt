package wasichai.core.common

import org.springframework.http.HttpRequest
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponse
import org.springframework.web.reactive.config.WebFluxConfigurer
import java.net.URI

// instance = the request path, as framework 7.0 filled it in for a problem a handler returns.
// 7.1 dropped that; the body is the contract with wasichai-ui, so it comes back here.
class ProblemInstance : ErrorResponse.Interceptor {
    override fun handleError(
        detail: ProblemDetail,
        errorResponse: ErrorResponse?
    ) = Unit

    override fun handleError(
        detail: ProblemDetail,
        errorResponse: ErrorResponse?,
        request: HttpRequest
    ) {
        if (detail.instance == null) detail.instance = URI.create(request.uri.rawPath)
    }

    // a configurer, not a bean the handlers find: interceptors reach them only through WebFluxConfigurer
    class Registration : WebFluxConfigurer {
        override fun addErrorResponseInterceptors(interceptors: MutableList<ErrorResponse.Interceptor>) {
            interceptors.add(ProblemInstance())
        }
    }
}
