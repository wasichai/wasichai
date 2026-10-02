package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// no default secret on purpose: a library must not ship one that works.
// service accounts get a short ttl: revoking one only stops new tokens (ADR-043).
@ConfigurationProperties(prefix = "wasichai.security.jwt")
data class JwtProperties(
    val secret: String = "",
    val issuer: String = "wasichai",
    val ttl: Duration = Duration.ofHours(8),
    val serviceAccountTtl: Duration = Duration.ofMinutes(15)
)
