package wasichai.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.platform.ModuleMigration
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiPagesAutoConfigurationTest {
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiFormsAutoConfiguration::class.java, WasichaiPagesAutoConfiguration::class.java))

    private val map =
        object : PageComponentProvider {
            override val type = ComponentType("MAP")
        }

    @Test
    fun `pages wires on core and forms, with only its built-in components`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(PageService::class.java)
            assertThat(context).hasSingleBean(PageController::class.java)
            assertThat(context).hasSingleBean(ObjectPageController::class.java)
            assertThat(context).hasSingleBean(PageTemplateController::class.java)
            assertThat(context).hasSingleBean(PageMetadataController::class.java)
            assertThat(context.getBean(PageComponentTypes::class.java).types).isEqualTo(ComponentType.BUILT_IN)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms", "pages")
        }
    }

    @Test
    fun `a module's provider joins the registry`() {
        runner.withBean("mapComponent", PageComponentProvider::class.java, { map }).run { context ->
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isSameAs(map)
        }
    }

    // pages cannot work without forms: it backs off instead of failing the boot
    @Test
    fun `with forms switched off, pages backs off too`() {
        runner.withPropertyValues("wasichai.forms.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PageService::class.java)
        }
    }

    @Test
    fun `switched off, core and forms boot without any pages bean`() {
        runner.withPropertyValues("wasichai.pages.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(PageService::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "forms")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration")
    }
}
