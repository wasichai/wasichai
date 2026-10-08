package wasichai.automation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldDefaults
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// nullable on purpose: a missing field must read as a validation error, not a 500 from jackson
data class AutomationTriggerRequest(
    val type: String? = null,
    val transition: String? = null,
    val state: String? = null
)

data class AutomationConditionRequest(
    val field: String? = null,
    val operator: String? = null,
    val value: String? = null
)

data class AutomationActionRequest(
    val type: String? = null,
    val field: String? = null,
    val value: String? = null,
    val targetObject: String? = null,
    val values: Map<String, String> = emptyMap(),
    val url: String? = null,
    val documentType: String? = null
)

data class AutomationDefinitionRequest(
    val trigger: AutomationTriggerRequest? = null,
    val conditions: List<AutomationConditionRequest> = emptyList(),
    val actions: List<AutomationActionRequest> = emptyList()
)

data class SaveAutomationRequest(
    val name: String? = null,
    val label: String? = null,
    val enabled: Boolean = true,
    val definition: AutomationDefinitionRequest = AutomationDefinitionRequest()
)

@Service
class AutomationService(
    private val automations: AutomationRepository,
    private val runs: AutomationRunRepository,
    private val metadata: MetadataService,
    private val webhooks: WebhookSender,
    private val documents: DocumentIssuer,
    private val currentUser: CurrentUser
) {
    suspend fun list(objectName: String): Pair<List<Automation>, String> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.MANAGE_METADATA, definition.obj.id)
        return automations.findByObject(user.organizationId, definition.obj.id) to definition.obj.name
    }

    suspend fun byName(
        objectName: String,
        name: String
    ): Pair<Automation, String> {
        val (automation, definition) = load(objectName, name, Actions.MANAGE_METADATA)
        return automation to definition.obj.name
    }

    @Transactional
    suspend fun save(
        objectName: String,
        name: String?,
        request: SaveAutomationRequest
    ): Pair<Automation, String> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.MANAGE_METADATA, definition.obj.id)

        // name in the path means edit that one; the body may rename it
        val existing =
            name?.let {
                automations.findByName(user.organizationId, it)
                    ?: throw NotFoundException("Automation '$it' does not exist")
            }
        if (existing != null && existing.objectId != definition.obj.id) {
            throw NotFoundException("Object '${definition.obj.name}' has no automation '$name'")
        }
        val automationName = requireValidName(request.name?.trim()?.lowercase() ?: name, "name")
        // renaming onto another automation's name is a conflict, not a silent overwrite
        val clash = automations.findByName(user.organizationId, automationName)
        if (clash != null && clash.id != existing?.id) {
            throw ConflictException("Automation '$automationName' already exists")
        }

        val validated = validate(definition, request.definition)
        val automation =
            Automation(
                id = existing?.id ?: UUID.randomUUID(),
                organizationId = user.organizationId,
                objectId = definition.obj.id,
                name = automationName,
                label = request.label?.trim()?.ifBlank { null } ?: automationName,
                enabled = request.enabled,
                definition = validated
            )
        val stored = if (existing == null) automations.insert(automation) else automations.update(automation)
        return stored to definition.obj.name
    }

    @Transactional
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val (automation, _) = load(objectName, name, Actions.MANAGE_METADATA)
        automations.delete(automation.id)
    }

    // the log answers "why did my record change" and "why did nothing happen"
    suspend fun runsOf(
        objectName: String,
        name: String,
        limit: Int
    ): List<AutomationRun> {
        val (automation, _) = load(objectName, name, Actions.READ)
        return runs.list(automation.organizationId, automation.id, limit.coerceIn(1, 200))
    }

    suspend fun recentRuns(limit: Int): List<AutomationRun> {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        return runs.list(user.organizationId, null, limit.coerceIn(1, 200))
    }

    private suspend fun load(
        objectName: String,
        name: String,
        action: String
    ): Pair<Automation, ObjectDefinition> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, action, definition.obj.id)
        val automation =
            automations.findByName(user.organizationId, name)
                ?: throw NotFoundException("Automation '$name' does not exist")
        if (automation.objectId != definition.obj.id) {
            throw NotFoundException("Object '${definition.obj.name}' has no automation '$name'")
        }
        return automation to definition
    }

    private suspend fun validate(
        definition: ObjectDefinition,
        request: AutomationDefinitionRequest
    ): AutomationDefinition {
        val trigger = request.trigger ?: throw ValidationException("Automation has no trigger", "trigger", "is required")
        val type =
            TriggerType.entries.firstOrNull { it.name.equals(trigger.type?.trim(), ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown trigger '${trigger.type}'",
                    "trigger",
                    "must be one of ${TriggerType.entries.joinToString(", ") { it.name }}"
                )
        if (type == TriggerType.STATE_ENTERED && trigger.state.isNullOrBlank()) {
            throw ValidationException("Trigger has no state", "trigger", "STATE_ENTERED needs a state")
        }
        if (request.actions.isEmpty()) {
            throw ValidationException("Automation has no actions", "actions", "at least one action is required")
        }

        val conditions = request.conditions.map { condition(definition, it) }
        val actions = request.actions.map { action(definition, it) }
        return AutomationDefinition(
            trigger =
                AutomationTrigger(
                    type = type,
                    transition = trigger.transition?.trim()?.ifBlank { null },
                    state = trigger.state?.trim()?.ifBlank { null }
                ),
            conditions = conditions,
            actions = actions
        )
    }

    private fun condition(
        definition: ObjectDefinition,
        request: AutomationConditionRequest
    ): AutomationCondition {
        val field = request.field?.trim()?.ifBlank { null } ?: throw ValidationException("Condition has no field", "conditions", "field is required")
        if (field != STATE_FIELD && definition.fields.none { it.name == field }) {
            throw ValidationException("Unknown field '$field'", "conditions", "no field '$field' on '${definition.obj.name}'")
        }
        val operator =
            ConditionOperator.entries.firstOrNull { it.name.equals(request.operator?.trim(), ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown operator '${request.operator}'",
                    "conditions",
                    "must be one of ${ConditionOperator.entries.joinToString(", ") { it.name }}"
                )
        if (operator !in VALUELESS && request.value.isNullOrBlank()) {
            throw ValidationException("Condition has no value", "conditions", "$operator needs a value")
        }
        return AutomationCondition(field, operator, request.value)
    }

    private suspend fun action(
        definition: ObjectDefinition,
        request: AutomationActionRequest
    ): AutomationAction {
        val type =
            ActionType.entries.firstOrNull { it.name.equals(request.type?.trim(), ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown action '${request.type}'",
                    "actions",
                    "must be one of ${ActionType.entries.joinToString(", ") { it.name }}"
                )
        return when (type) {
            ActionType.UPDATE_FIELD -> updateField(definition, request)
            ActionType.CREATE_RECORD -> createRecord(definition, request)
            ActionType.WEBHOOK -> {
                val url = request.url?.trim().orEmpty()
                webhooks.validate(AutomationRules.withoutPlaceholders(url))
                AutomationAction(type = type, url = url)
            }
            ActionType.GENERATE_DOCUMENT -> generateDocument(definition, request)
        }
    }

    private suspend fun generateDocument(
        definition: ObjectDefinition,
        request: AutomationActionRequest
    ): AutomationAction {
        val name =
            request.documentType
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
                ?: throw ValidationException("Action has no document type", "actions", "documentType is required")
        if (!documents.typeExists(definition.obj.id, name)) {
            throw ValidationException("Unknown document type '$name'", "actions", "no document type '$name' on '${definition.obj.name}'")
        }
        return AutomationAction(type = ActionType.GENERATE_DOCUMENT, documentType = name)
    }

    private fun updateField(
        definition: ObjectDefinition,
        request: AutomationActionRequest
    ): AutomationAction {
        val name = request.field?.trim()?.ifBlank { null } ?: throw ValidationException("Action has no field", "actions", "field is required")
        val field =
            definition.fields.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown field '$name'", "actions", "no field '$name' on '${definition.obj.name}'")
        // the record store writes editable fields only: a locked one would be dropped in silence
        if (!field.editable) {
            throw ValidationException("Field '$name' is read only", "actions", "an automation cannot write a locked field")
        }
        if (request.value == null) {
            throw ValidationException("Action has no value", "actions", "value is required for UPDATE_FIELD")
        }
        return AutomationAction(type = ActionType.UPDATE_FIELD, field = name, value = request.value)
    }

    private suspend fun createRecord(
        definition: ObjectDefinition,
        request: AutomationActionRequest
    ): AutomationAction {
        val targetName =
            request.targetObject
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
                ?: throw ValidationException("Action has no target object", "actions", "targetObject is required")
        val target = metadata.loadDefinition(definition.obj.organizationId, targetName)
        request.values.keys.firstOrNull { key -> target.fields.none { it.name == key } }?.let {
            throw ValidationException("Unknown field '$it'", "actions", "no field '$it' on '${target.obj.name}'")
        }
        // a required field nobody fills fails at insert time, long after the admin left. a default fills it (issue 60)
        target.fields
            .firstOrNull { it.required && FieldDefaults.of(it) == null && it.name !in request.values }
            ?.let {
                throw ValidationException(
                    "Field '${it.name}' is required on '${target.obj.name}'",
                    "actions",
                    "give it a value"
                )
            }
        return AutomationAction(type = ActionType.CREATE_RECORD, targetObject = targetName, values = request.values)
    }

    private fun requireValidName(
        name: String?,
        field: String
    ): String {
        if (name.isNullOrBlank()) throw ValidationException("Missing automation name", field, "name is required")
        if (!VALID_NAME.matches(name)) {
            throw ValidationException("Invalid automation name '$name'", field, "must match ${VALID_NAME.pattern}")
        }
        return name
    }

    private companion object {
        const val STATE_FIELD = "state"
        val VALID_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
        val VALUELESS = setOf(ConditionOperator.IS_EMPTY, ConditionOperator.IS_NOT_EMPTY, ConditionOperator.CHANGED)
    }
}
