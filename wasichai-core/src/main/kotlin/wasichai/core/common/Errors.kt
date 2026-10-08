package wasichai.core.common

import org.springframework.http.HttpStatus

data class FieldViolation(
    val field: String,
    val message: String
)

// domain errors carry their http status. handler turns them into problem+json.
// not sealed: modules add their own.
abstract class WasichaiException(
    val status: HttpStatus,
    override val message: String,
    val violations: List<FieldViolation> = emptyList()
) : RuntimeException(message)

class NotFoundException(
    message: String
) : WasichaiException(HttpStatus.NOT_FOUND, message)

class ConflictException(
    message: String,
    violations: List<FieldViolation> = emptyList()
) : WasichaiException(HttpStatus.CONFLICT, message, violations)

// a precondition the caller sent (If-Match) no longer holds: RFC 9110's 412, not a 409 conflict (ADR-051)
class PreconditionFailedException(
    message: String,
    violations: List<FieldViolation> = emptyList()
) : WasichaiException(HttpStatus.PRECONDITION_FAILED, message, violations)

class ForbiddenException(
    message: String
) : WasichaiException(HttpStatus.FORBIDDEN, message)

class UnauthorizedException(
    message: String
) : WasichaiException(HttpStatus.UNAUTHORIZED, message)

class ValidationException : WasichaiException {
    constructor(message: String, violations: List<FieldViolation>) :
        super(HttpStatus.BAD_REQUEST, message, violations)

    constructor(message: String, field: String, reason: String) :
        super(HttpStatus.BAD_REQUEST, message, listOf(FieldViolation(field, reason)))
}
