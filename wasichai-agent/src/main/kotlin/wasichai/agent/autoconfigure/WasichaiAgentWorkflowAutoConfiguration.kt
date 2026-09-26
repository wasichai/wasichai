package wasichai.agent.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import wasichai.agent.RecordTransitions
import wasichai.agent.WorkflowRecordTransitions
import wasichai.workflow.WorkflowService

// workflow behind the assistant's transitions port, only when wasichai-workflow is on the classpath
// and switched on. names as strings: this config is skipped before anything loads a workflow class.
// the agent looks the port up lazily, so no order against the agent's own auto-config is needed.
// gated on wasichai.agent, not wasichai.workflow: this is the agent's own wiring, off with the agent.
@AutoConfiguration(afterName = ["wasichai.workflow.autoconfigure.WasichaiWorkflowAutoConfiguration"])
@ConditionalOnClass(name = ["wasichai.workflow.WorkflowService"])
@ConditionalOnBean(type = ["wasichai.workflow.WorkflowService"])
@ConditionalOnProperty(prefix = "wasichai.agent", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WasichaiAgentWorkflowAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RecordTransitions::class)
    fun workflowRecordTransitions(workflows: WorkflowService): WorkflowRecordTransitions = WorkflowRecordTransitions(workflows)
}
