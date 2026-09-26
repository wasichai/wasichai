package wasichai.workflow

import java.util.UUID

enum class StateType { INITIAL, INTERMEDIATE, FINAL }

data class WorkflowState(
    val name: String,
    val label: String,
    val type: StateType,
    // where the editor put the box. null means the client places it.
    val x: Double? = null,
    val y: Double? = null
)

// roles empty = anyone who may update the object
data class WorkflowTransition(
    val name: String,
    val label: String,
    val from: String,
    val to: String,
    val roles: List<String> = emptyList()
)

data class WorkflowDefinition(
    val states: List<WorkflowState> = emptyList(),
    val transitions: List<WorkflowTransition> = emptyList()
)

// states and transitions. no BPM engine, no tokens, no parallel branches.
data class Workflow(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val enabled: Boolean,
    val definition: WorkflowDefinition
)

fun WorkflowDefinition.initialState(): WorkflowState? = states.firstOrNull { it.type == StateType.INITIAL }

fun WorkflowDefinition.state(name: String?): WorkflowState? = states.firstOrNull { it.name == name }
