package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// sign-in attempt limits on /api/auth/login and /api/auth/token (ADR-059). off by default.
// maxAttempts: per (email, client address) and per client id. accountMaxAttempts: per email from any address,
// so guessing spread over many addresses stops too, while one client's typos lock out only that client.
// window: fixed, opened by the first attempt; the lockout lasts until it closes.
@ConfigurationProperties(prefix = "wasichai.security.login")
data class WasichaiLoginProperties(
    val enabled: Boolean = false,
    val maxAttempts: Int = 5,
    val accountMaxAttempts: Int = 20,
    val window: Duration = Duration.ofMinutes(15)
) {
    init {
        require(maxAttempts >= 1) { "wasichai.security.login.max-attempts must be at least 1, was $maxAttempts" }
        require(accountMaxAttempts >= 1) { "wasichai.security.login.account-max-attempts must be at least 1, was $accountMaxAttempts" }
        require(window.isPositive) { "wasichai.security.login.window must be positive, was $window" }
    }
}
