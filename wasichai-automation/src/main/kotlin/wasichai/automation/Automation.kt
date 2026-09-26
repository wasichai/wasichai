package wasichai.automation

import wasichai.core.data.RecordChangeKind
import java.util.UUID

enum class TriggerType(
    val kind: RecordChangeKind
) {
    RECORD_CREATED(RecordChangeKind.CREATED),
    RECORD_UPDATED(RecordChangeKind.UPDATED),
    RECORD_DELETED(RecordChangeKind.DELETED),

    // a named transition fired
    TRANSITION_APPLIED(RecordChangeKind.TRANSITIONED),

    // the record arrived in a state, whichever transition brought it
    STATE_ENTERED(RecordChangeKind.TRANSITIONED)
}

enum class ConditionOperator { EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, CONTAINS, IS_EMPTY, IS_NOT_EMPTY, CHANGED }

enum class ActionType { UPDATE_FIELD, CREATE_RECORD, WEBHOOK, GENERATE_DOCUMENT }

// transition/state only mean something for the trigger that names them
data class AutomationTrigger(
    val type: TriggerType,
    val transition: String? = null,
    val state: String? = null
)

// field is a field name, or "state" for the workflow state
data class AutomationCondition(
    val field: String,
    val operator: ConditionOperator,
    val value: String? = null
)

// one shape for every action. a field only matters to the action type that reads it.
data class AutomationAction(
    val type: ActionType,
    // UPDATE_FIELD
    val field: String? = null,
    val value: String? = null,
    // CREATE_RECORD
    val targetObject: String? = null,
    val values: Map<String, String> = emptyMap(),
    // WEBHOOK
    val url: String? = null,
    // GENERATE_DOCUMENT
    val documentType: String? = null
)

data class AutomationDefinition(
    val trigger: AutomationTrigger,
    val conditions: List<AutomationCondition> = emptyList(),
    val actions: List<AutomationAction> = emptyList()
)

data class Automation(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val enabled: Boolean,
    val definition: AutomationDefinition
)

enum class RunStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

// one line of provenance per action, so the log says what happened and not just that it did
data class RunStep(
    val action: ActionType,
    val detail: String
)
