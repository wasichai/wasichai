package wasichai.automation

import org.springframework.stereotype.Component
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldUsage

// a rule that names a dropped field fails only when it next fires, long after the admin left.
// so speak up while the delete is still on screen.
@Component
class AutomationFieldUsage(
    private val automations: AutomationRepository
) : FieldUsage {
    override suspend fun whoUses(
        obj: CustomObject,
        fieldName: String
    ): List<String> =
        automations
            .findAll(obj.organizationId)
            .filter { it.uses(obj, fieldName) }
            .map { "automation '${it.name}'" }

    // own object: conditions and the field it writes. any object: values it creates here.
    private fun Automation.uses(
        obj: CustomObject,
        fieldName: String
    ): Boolean {
        val mine = objectId == obj.id
        if (mine && definition.conditions.any { it.field == fieldName }) return true
        return definition.actions.any { action ->
            when (action.type) {
                ActionType.UPDATE_FIELD -> mine && action.field == fieldName
                ActionType.CREATE_RECORD -> action.targetObject == obj.name && fieldName in action.values
                ActionType.WEBHOOK -> false
                // names a document type, not a field
                ActionType.GENERATE_DOCUMENT -> false
                // {{field}} in a template is read when it runs, like a webhook url: a missing one prints empty
                ActionType.NOTIFY -> false
            }
        }
    }
}
