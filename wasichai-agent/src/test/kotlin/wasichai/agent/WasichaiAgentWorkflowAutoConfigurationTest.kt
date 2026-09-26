package wasichai.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.test.util.ReflectionTestUtils
import wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration
import wasichai.agent.autoconfigure.WasichaiAgentWorkflowAutoConfiguration
import wasichai.test.WasichaiContextRunner
import wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration

class WasichaiAgentWorkflowAutoConfigurationTest {
    private val all =
        AutoConfigurations.of(
            WasichaiWorkflowAutoConfiguration::class.java,
            WasichaiAgentAutoConfiguration::class.java,
            WasichaiAgentWorkflowAutoConfiguration::class.java
        )

    @Test
    fun `with workflow installed, the assistant asks it`() {
        WasichaiContextRunner.core().withConfiguration(all).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(WorkflowRecordTransitions::class.java)
        }
    }

    @Test
    fun `with workflow switched off, the assistant says there are none`() {
        WasichaiContextRunner.core().withConfiguration(all).withPropertyValues("wasichai.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                .isInstanceOf(NoRecordTransitions::class.java)
        }
    }

    @Test
    fun `with the agent itself switched off, no RecordTransitions adapter is wired`() {
        WasichaiContextRunner.core().withConfiguration(all).withPropertyValues("wasichai.agent.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("workflowRecordTransitions")
            assertThat(context).doesNotHaveBean(AgentTools::class.java)
        }
    }

    @Test
    fun `without workflow on the classpath, the agent boots alone`() {
        WasichaiContextRunner
            .core()
            .withClassLoader(FilteredClassLoader("wasichai.workflow"))
            .withConfiguration(AutoConfigurations.of(WasichaiAgentAutoConfiguration::class.java, WasichaiAgentWorkflowAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean("workflowRecordTransitions")
                assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions"))
                    .isInstanceOf(NoRecordTransitions::class.java)
            }
    }

    @Test
    fun `the imports file registers both agent auto-configs`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration", "wasichai.agent.autoconfigure.WasichaiAgentWorkflowAutoConfiguration")
    }
}
