package wasichai.forms.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.forms")
data class WasichaiFormsProperties(
    // false: no forms beans, routes or migration (and wasichai-pages backs off with it)
    val enabled: Boolean = true
)
