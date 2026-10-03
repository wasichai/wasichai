package wasichai.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.net.URI

// every error leaves as RFC 7807 problem+json. the type uri is the app's (wasichai.web.problem-base-uri).
@RestControllerAdvice
class GlobalExceptionHandler(
    problemBaseUri: String,
    // the fields a violated unique covers. metadata knows them, and common sits below it: passed in
    // (WasichaiPlatformAutoConfiguration). the default names none, the answer is still a 409.
    private val uniqueFields: suspend (DuplicateKeyException) -> List<String> = { emptyList() }
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

    // a unique violation is the caller's conflict (ADR-037). values never echoed back. resolved
    // after the write's transaction ended, so the catalog read behind uniqueFields can run.
    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicateKey(ex: DuplicateKeyException): Mono<ProblemDetail> =
        mono {
            val fields =
                try {
                    uniqueFields(ex)
                } catch (e: CancellationException) {
                    // the request went away: let the coroutine end as cancelled
                    throw e
                } catch (e: Exception) {
                    log.warn("could not resolve the fields of a unique violation: {}", e.message)
                    emptyList()
                }
            val detail =
                if (fields.isEmpty()) "Conflicts with an existing record" else "Another record already has this ${fields.joinToString(", ")}"
            val violations =
                fields.map { field ->
                    val others = fields - field
                    FieldViolation(field, if (others.isEmpty()) "must be unique" else "must be unique together with ${others.joinToString(", ")}")
                }
            problem(HttpStatus.CONFLICT, detail, violations)
        }

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
