package wasichai.core.data

import java.util.UUID

// what the record path needs to know about an object's record state (ADR-013). nothing more.
data class ObjectWorkflowState(
    // the physical table carries the state column, so it can be selected
    val attached: Boolean,
    // where a brand new record starts. null when no state machine is enabled.
    val initialState: String?
) {
    companion object {
        val NONE = ObjectWorkflowState(false, null)
    }
}

// port. a module that gives records a state implements it; data never reads that module's tables.
interface WorkflowStates {
    suspend fun stateOf(
        organizationId: UUID,
        objectId: UUID
    ): ObjectWorkflowState

    // other modules validate actions against these. the record path never asks.
    suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String>
}

// no module installed: no object has a state
class NoWorkflowStates : WorkflowStates {
    override suspend fun stateOf(
        organizationId: UUID,
        objectId: UUID
    ): ObjectWorkflowState = ObjectWorkflowState.NONE

    override suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String> = emptySet()
}
