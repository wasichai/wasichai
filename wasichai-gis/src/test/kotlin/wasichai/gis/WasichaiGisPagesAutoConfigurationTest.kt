package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration
import wasichai.gis.autoconfigure.WasichaiGisPagesAutoConfiguration
import wasichai.pages.ComponentType
import wasichai.pages.PageComponentTypes
import wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiGisPagesAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            WasichaiFormsAutoConfiguration::class.java,
            WasichaiPagesAutoConfiguration::class.java,
            WasichaiGisAutoConfiguration::class.java,
            WasichaiGisPagesAutoConfiguration::class.java
        )

    @Test
    fun `with pages installed, MAP is a page component`() {
        WasichaiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isInstanceOf(MapPageComponent::class.java)
        }
    }

    @Test
    fun `with gis switched off, pages has no MAP`() {
        WasichaiContextRunner.core().withConfiguration(all).withPropertyValues("wasichai.gis.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("MAP"))).isNull()
        }
    }

    @Test
    fun `without pages on the classpath, gis boots alone`() {
        WasichaiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("wasichai.pages"))
            .withConfiguration(AutoConfigurations.of(WasichaiGisAutoConfiguration::class.java, WasichaiGisPagesAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(FeatureController::class.java)
                assertThat(context).doesNotHaveBean("mapPageComponent")
            }
    }

    @Test
    fun `the imports file registers both gis auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration", "wasichai.gis.autoconfigure.WasichaiGisPagesAutoConfiguration")
    }
}
