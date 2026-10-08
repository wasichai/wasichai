package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

// the default PasswordPolicy's rules (ADR-059). the defaults are the one rule there always was: 8 characters.
@ConfigurationProperties(prefix = "wasichai.security.password")
data class WasichaiPasswordProperties(
    val minLength: Int = 8,
    val requireUppercase: Boolean = false,
    val requireLowercase: Boolean = false,
    val requireDigit: Boolean = false,
    val requireSymbol: Boolean = false,
    val notEqualEmail: Boolean = false
) {
    init {
        require(minLength >= 1) { "wasichai.security.password.min-length must be at least 1, was $minLength" }
    }
}
