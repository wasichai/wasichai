package wasichai.workflow

import org.springframework.stereotype.Component
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.WorkflowStates
import java.util.UUID

// the data module asks here instead of reading the workflows table itself.
@Component
class WorkflowStatesAdapter(
    private val workflows: WorkflowRepository
) : WorkflowStates {
    override suspend fun stateOf(
        organizationId: UUID,
        objectId: UUID
    ): ObjectWorkflowState {
        // deleting a workflow keeps the column and its data but stops surfacing it. the state is
        // still on disk if the admin reattaches a workflow.
        val workflow = workflows.findByObject(organizationId, objectId) ?: return ObjectWorkflowState.NONE
        return ObjectWorkflowState(
            attached = true,
            initialState = if (workflow.enabled) workflow.definition.initialState()?.name else null
        )
    }

    override suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String> {
        // no workflow and a disabled one are the same answer: nothing can be fired.
        val workflow = workflows.findByObject(organizationId, objectId) ?: return emptySet()
        if (!workflow.enabled) return emptySet()
        return workflow.definition.transitions
            .map { it.name }
            .toSet()
    }
}
