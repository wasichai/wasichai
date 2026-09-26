package wasichai.views.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.views")
data class WasichaiViewsProperties(
    // false: no views beans, routes or migration
    val enabled: Boolean = true
)
