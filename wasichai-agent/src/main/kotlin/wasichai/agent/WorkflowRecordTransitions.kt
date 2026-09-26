package wasichai.agent

import wasichai.workflow.WorkflowService
import java.util.UUID

// the assistant's transitions port over wasichai-workflow. compiled against it, loaded only when an
// app has it (WasichaiAgentWorkflowAutoConfiguration). permissions and tenancy stay the service's.
class WorkflowRecordTransitions(
    private val workflows: WorkflowService
) : RecordTransitions {
    override suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition> =
        workflows.transitionsOf(objectName, id).map {
            AgentTransition(it.name, it.label, it.to, it.toLabel, it.allowed, it.reason)
        }
}
