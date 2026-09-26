package wasichai.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import wasichai.forms.autoconfigure.WasichaiFormsAutoConfiguration
import wasichai.pages.ComponentType
import wasichai.pages.PageComponentTypes
import wasichai.pages.autoconfigure.WasichaiPagesAutoConfiguration
import wasichai.test.WasichaiContextRunner
import wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration
import wasichai.workflow.autoconfigure.WasichaiWorkflowPagesAutoConfiguration

class WasichaiWorkflowPagesAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            WasichaiFormsAutoConfiguration::class.java,
            WasichaiPagesAutoConfiguration::class.java,
            WasichaiWorkflowAutoConfiguration::class.java,
            WasichaiWorkflowPagesAutoConfiguration::class.java
        )

    @Test
    fun `with pages installed, WORKFLOW is a page component`() {
        WasichaiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("WORKFLOW")))
                .isInstanceOf(WorkflowPageComponent::class.java)
        }
    }

    @Test
    fun `with workflow switched off, pages has no WORKFLOW`() {
        WasichaiContextRunner.core().withConfiguration(all).withPropertyValues("wasichai.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PageComponentTypes::class.java).provider(ComponentType("WORKFLOW"))).isNull()
        }
    }

    @Test
    fun `without pages on the classpath, workflow boots alone`() {
        WasichaiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("wasichai.pages"))
            .withConfiguration(AutoConfigurations.of(WasichaiWorkflowAutoConfiguration::class.java, WasichaiWorkflowPagesAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(WorkflowService::class.java)
                assertThat(context).doesNotHaveBean("workflowPageComponent")
            }
    }

    @Test
    fun `the imports file registers both workflow auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains(
                "wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration",
                "wasichai.workflow.autoconfigure.WasichaiWorkflowPagesAutoConfiguration"
            )
    }
}
