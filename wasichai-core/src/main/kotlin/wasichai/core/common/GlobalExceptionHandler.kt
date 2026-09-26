package wasichai.core.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import java.net.URI

// every error leaves as RFC 7807 problem+json. the type uri is the app's (wasichai.web.problem-base-uri).
@RestControllerAdvice
class GlobalExceptionHandler(
    problemBaseUri: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = problemBaseUri.trimEnd('/')

    @ExceptionHandler(WasichaiException::class)
    fun handleWasichai(ex: WasichaiException): ProblemDetail = problem(ex.status, ex.message, ex.violations)

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleBinding(ex: WebExchangeBindException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "Request validation failed",
            ex.fieldErrors.map { FieldViolation(it.field, it.defaultMessage ?: "is invalid") }
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(ex.statusCode.value()), ex.reason ?: "Request failed", emptyList())

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled error", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", emptyList())
    }

    private fun problem(
        status: HttpStatus,
        detail: String,
        violations: List<FieldViolation>
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("$base/${status.value()}")
            title = status.reasonPhrase
            if (violations.isNotEmpty()) {
                setProperty("errors", violations)
            }
        }
}
