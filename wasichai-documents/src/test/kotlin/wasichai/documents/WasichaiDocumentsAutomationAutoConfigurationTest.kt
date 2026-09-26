package wasichai.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.test.util.ReflectionTestUtils
import wasichai.automation.AutomationRunner
import wasichai.automation.AutomationService
import wasichai.automation.autoconfigure.WasichaiAutomationAutoConfiguration
import wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration
import wasichai.documents.autoconfigure.WasichaiDocumentsAutomationAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiDocumentsAutomationAutoConfigurationTest {
    @Test
    fun `with automation installed, a GENERATE_DOCUMENT action issues through documents`() {
        WasichaiContextRunner
            .core()
            .withConfiguration(
                AutoConfigurations.of(
                    WasichaiAutomationAutoConfiguration::class.java,
                    WasichaiDocumentsAutoConfiguration::class.java,
                    WasichaiDocumentsAutomationAutoConfiguration::class.java
                )
            ).withPropertyValues("wasichai.automation.poll-interval=0s")
            .run { context ->
                assertThat(context).hasNotFailed()
                val adapter = context.getBean(DocumentIssuerAdapter::class.java)
                assertThat(ReflectionTestUtils.getField(context.getBean(AutomationService::class.java), "documents")).isSameAs(adapter)
                assertThat(ReflectionTestUtils.getField(context.getBean(AutomationRunner::class.java), "documents")).isSameAs(adapter)
            }
    }

    // an app with documents and no automation: the adapter's class would not even load
    @Test
    fun `without automation on the classpath, documents boots alone`() {
        WasichaiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("wasichai.automation"))
            .withConfiguration(AutoConfigurations.of(WasichaiDocumentsAutoConfiguration::class.java, WasichaiDocumentsAutomationAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DocumentService::class.java)
                assertThat(context).doesNotHaveBean("documentIssuerAdapter")
            }
    }

    @Test
    fun `with documents switched off, no adapter is left behind`() {
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiDocumentsAutoConfiguration::class.java, WasichaiDocumentsAutomationAutoConfiguration::class.java))
            .withPropertyValues("wasichai.documents.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("documentIssuerAdapter")
            }
    }

    @Test
    fun `the imports file registers both documents auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains(
                "wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration",
                "wasichai.documents.autoconfigure.WasichaiDocumentsAutomationAutoConfiguration"
            )
    }
}
