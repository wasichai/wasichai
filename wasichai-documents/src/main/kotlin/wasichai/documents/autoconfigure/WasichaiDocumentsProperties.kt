package wasichai.documents.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.documents")
data class WasichaiDocumentsProperties(
    // false: no documents beans, routes or migration
    val enabled: Boolean = true
)
