package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// Idempotency-Key on record creation (ADR-058). ttl: how long a key replays its answer; after that it is
// gone and the same key creates again. purgeInterval: how often one replica deletes the expired keys;
// 0 keeps the purge off (an expired key never replays either way).
@ConfigurationProperties(prefix = "wasichai.idempotency")
data class WasichaiIdempotencyProperties(
    val ttl: Duration = Duration.ofHours(24),
    val purgeInterval: Duration = Duration.ofHours(1)
) {
    init {
        require(ttl.isPositive) { "wasichai.idempotency.ttl must be positive, was $ttl" }
        require(!purgeInterval.isNegative) { "wasichai.idempotency.purge-interval must not be negative, was $purgeInterval" }
    }
}
