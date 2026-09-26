package wasichai.forms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.platform.ModuleMigration
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiFormsAutoConfigurationTest {
    private val runner = WasichaiContextRunner.core().withConfiguration(AutoConfigurations.of(WasichaiFormsAutoConfiguration::class.java))

    @Test
    fun `forms wires on core and brings its migration`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(FormService::class.java)
            assertThat(context).hasSingleBean(FormController::class.java)
            assertThat(context).hasSingleBean(FormMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms")
        }
    }

    @Test
    fun `switched off, core boots without any forms bean`() {
        runner.withPropertyValues("wasichai.forms.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(FormService::class.java)
            assertThat(context).doesNotHaveBean(FormMetadataController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `an app's own service wins`() {
        val mine = mock(FormService::class.java)
        runner.withBean(FormService::class.java, { mine }).run { context ->
            assertThat(context.getBean(FormService::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration")
    }
}
