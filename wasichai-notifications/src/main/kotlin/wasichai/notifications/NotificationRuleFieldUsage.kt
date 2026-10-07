package wasichai.notifications

import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldUsage

// a rule that reads a dropped field fails at its next run, far from the admin. say so while the delete is on screen.
// disabled rules count too: enabling one later would fail the same way.
class NotificationRuleFieldUsage(
    private val rules: NotificationRuleRepository
) : FieldUsage {
    override suspend fun whoUses(
        obj: CustomObject,
        fieldName: String
    ): List<String> = users(rules.listByObject(obj.organizationId, obj.id).map { it.rule }, fieldName)

    companion object {
        // the object's rules that read the field: its date, a condition or a placeholder
        fun users(
            rules: List<NotificationRuleDefinition>,
            fieldName: String
        ): List<String> = rules.filter { fieldName in NotificationRules.fieldsRead(it) }.map { "notification rule '${it.name}'" }
    }
}
