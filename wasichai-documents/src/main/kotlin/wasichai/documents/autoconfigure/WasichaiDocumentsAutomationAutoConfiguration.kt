package wasichai.documents.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import wasichai.automation.DocumentIssuer
import wasichai.documents.DocumentIssuerAdapter
import wasichai.documents.DocumentService
import wasichai.documents.DocumentTypeRepository

// documents behind automation's port, only when wasichai-automation is on the classpath. the class
// is named as a string, so this config is skipped before anything tries to load it (M2).
// automation looks the port up lazily, so no order against its auto-config is needed.
@AutoConfiguration(after = [WasichaiDocumentsAutoConfiguration::class])
@ConditionalOnClass(name = ["wasichai.automation.DocumentIssuer"])
@ConditionalOnBean(DocumentService::class)
class WasichaiDocumentsAutomationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentIssuer::class)
    fun documentIssuerAdapter(
        types: DocumentTypeRepository,
        documents: DocumentService
    ): DocumentIssuerAdapter = DocumentIssuerAdapter(types, documents)
}
