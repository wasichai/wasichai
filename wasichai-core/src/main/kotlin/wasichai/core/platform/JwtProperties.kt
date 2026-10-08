package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// no default secret on purpose: a library must not ship one that works.
// service accounts get a short ttl: without revocation, revoking one only stops new tokens (ADR-043).
// revocation: a token issued before its user's marker is refused, the marker read through a per-user cache of
// revocationCache, which bounds how late a revocation bites on another node (ADR-059). off by default for 0.x.
@ConfigurationProperties(prefix = "wasichai.security.jwt")
data class JwtProperties(
    val secret: String = "",
    val issuer: String = "wasichai",
    val ttl: Duration = Duration.ofHours(8),
    val serviceAccountTtl: Duration = Duration.ofMinutes(15),
    val revocation: Boolean = false,
    val revocationCache: Duration = Duration.ofSeconds(5)
) {
    init {
        require(!revocationCache.isNegative) { "wasichai.security.jwt.revocation-cache must not be negative, was $revocationCache" }
    }
}
