package wasichai.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.data.NoWorkflowStates
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.WorkflowStates
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.SystemColumns
import wasichai.test.WasichaiContextRunner
import wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration

class WasichaiWorkflowAutoConfigurationTest {
    private val runner = WasichaiContextRunner.core().withConfiguration(AutoConfigurations.of(WasichaiWorkflowAutoConfiguration::class.java))

    @Test
    fun `workflow replaces the null object and reserves the state column`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WorkflowService::class.java)
            assertThat(context).hasSingleBean(WorkflowController::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(WorkflowStatesAdapter::class.java)
            assertThat(context.getBean(SystemColumns::class.java).all.map { it.name })
                .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "workflow_state", "version", "count", "after")
            assertThat(
                context
                    .getBean(SystemColumns::class.java)
                    .all
                    .first { it.name == "workflow_state" }
                    .scope
            ).isEqualTo("WORKFLOW")
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "workflow")
        }
    }

    @Test
    fun `a neighbour's change listener is handed to the service too`() {
        val neighbour =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) = Unit
            }
        runner.withBean("neighbourListener", RecordChangeListener::class.java, { neighbour }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WorkflowService::class.java)
        }
    }

    @Test
    fun `switched off, records have no state again`() {
        runner.withPropertyValues("wasichai.workflow.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WorkflowService::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(NoWorkflowStates::class.java)
            assertThat(context.getBean(SystemColumns::class.java).names).doesNotContain("workflow_state")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration")
    }
}
