package wasichai.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.platform.ModuleMigration
import wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiDocumentsAutoConfigurationTest {
    private val runner = WasichaiContextRunner.core().withConfiguration(AutoConfigurations.of(WasichaiDocumentsAutoConfiguration::class.java))

    @Test
    fun `documents wires on core alone, no automation needed`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DocumentService::class.java)
            assertThat(context).hasSingleBean(DocumentTypeService::class.java)
            assertThat(context).hasSingleBean(DocumentController::class.java)
            assertThat(context).hasSingleBean(DocumentTypeController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "documents")
        }
    }

    @Test
    fun `switched off, core boots without any documents bean`() {
        runner.withPropertyValues("wasichai.documents.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DocumentService::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `an app's own service wins`() {
        val mine = mock(DocumentService::class.java)
        runner.withBean(DocumentService::class.java, { mine }).run { context ->
            assertThat(context.getBean(DocumentService::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.documents.autoconfigure.WasichaiDocumentsAutoConfiguration")
    }
}
