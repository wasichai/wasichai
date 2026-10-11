package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// cache.ttl: how long a node trusts an object's definition it loaded (ADR-066). this node drops it at once when it
// changes the model itself; another node within the ttl. 0 caches nothing, every read loads it as before.
// reconcileIndexes: read by WasichaiMetadataAutoConfiguration's condition, listed here so it binds as one prefix.
@ConfigurationProperties(prefix = "wasichai.metadata")
data class WasichaiMetadataProperties(
    val cache: Cache = Cache(),
    val reconcileIndexes: Boolean = true
) {
    data class Cache(
        val ttl: Duration = Duration.ofSeconds(5)
    ) {
        init {
            require(!ttl.isNegative) { "wasichai.metadata.cache.ttl must not be negative, was $ttl" }
        }
    }
}
