package wasichai.core.identity

import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiPasswordProperties
import java.util.UUID

// who the password is for, as far as a policy may care
data class UserInfo(
    val email: String,
    val displayName: String?,
    val organizationId: UUID
)

/**
 * The rules a new password must meet (ADR-059): on user create, password change and tenant provisioning.
 * Answers one reason per broken rule, phrased to follow the field name ("must be at least 8 characters");
 * empty when the password is fine. An app declares its own bean to replace the default.
 */
fun interface PasswordPolicy {
    fun check(
        password: String,
        user: UserInfo
    ): List<String>

    companion object {
        private val TOO_SHORT = Regex("must be at least \\d+ characters")

        // a 400 on [field] naming every broken rule. one length failure keeps the detail it always had
        fun enforce(
            policy: PasswordPolicy,
            password: String,
            user: UserInfo,
            field: String
        ) {
            val failures = policy.check(password, user)
            if (failures.isEmpty()) return
            val detail = if (failures.size == 1 && TOO_SHORT.matches(failures[0])) "Password too short" else "Password does not meet the password policy"
            throw ValidationException(detail, failures.map { FieldViolation(field, it) })
        }
    }
}

// wasichai.security.password.*. defaults: 8 characters, nothing else, the one rule there always was
class ConfiguredPasswordPolicy(
    private val properties: WasichaiPasswordProperties
) : PasswordPolicy {
    override fun check(
        password: String,
        user: UserInfo
    ): List<String> =
        buildList {
            if (password.length < properties.minLength) add("must be at least ${properties.minLength} characters")
            if (properties.requireUppercase && password.none { it.isUpperCase() }) add("must contain an uppercase letter")
            if (properties.requireLowercase && password.none { it.isLowerCase() }) add("must contain a lowercase letter")
            if (properties.requireDigit && password.none { it.isDigit() }) add("must contain a digit")
            if (properties.requireSymbol && password.all { it.isLetterOrDigit() }) add("must contain a symbol")
            if (properties.notEqualEmail && password.trim().equals(user.email.trim(), ignoreCase = true)) add("must not be the email address")
        }
}
