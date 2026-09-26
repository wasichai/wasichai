package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wasichai.web")
data class WasichaiWebProperties(
    // problem+json "type" is this plus "/<status>"
    val problemBaseUri: String = "https://wasichai.dev/problems",
    // browser origins the api answers. dev servers by default.
    val corsAllowedOriginPatterns: List<String> = listOf("http://localhost:*")
)
