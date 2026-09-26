package wasichai.agent

import java.util.UUID

// one transition leaving a record's state, as the assistant reports it. same json as
// wasichai-workflow's AvailableTransition, so the tool answer did not change with the split.
data class AgentTransition(
    val name: String,
    val label: String,
    val to: String,
    val toLabel: String,
    val allowed: Boolean,
    val reason: String? = null
)

// what the assistant may ask about transitions. wasichai-workflow answers when it is installed;
// the agent never depends on it (M2).
fun interface RecordTransitions {
    suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition>
}

// no workflow module: no object has a workflow, and "no workflow" has always answered an empty list
class NoRecordTransitions : RecordTransitions {
    override suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AgentTransition> = emptyList()
}
