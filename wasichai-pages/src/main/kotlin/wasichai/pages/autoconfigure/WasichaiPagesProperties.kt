package wasichai.pages.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.pages")
data class WasichaiPagesProperties(
    // false: no pages beans, routes or migration
    val enabled: Boolean = true
)
