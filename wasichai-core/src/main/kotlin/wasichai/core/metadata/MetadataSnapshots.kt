package wasichai.core.metadata

import java.util.UUID

// what the admin trail stores of the model (ADR-049): an object whole, with its fields and declared actions, so a
// dropped one can be told apart from what replaced it. no timestamps, so a diff holds only what someone changed.
// relation targets by name, as the api speaks them. a relationship's is RelationshipService's.
internal class MetadataSnapshots(
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val actions: ObjectActionRepository
) {
    // indexes, uniques, the write flags and declared actions live on the object, so they are its entry's to tell
    suspend fun obj(obj: CustomObject): Map<String, Any?> {
        val objectFields = fields.findByObject(obj.id)
        val targets = targetNames(obj.organizationId, objectFields)
        return linkedMapOf(
            "name" to obj.name,
            "label" to obj.label,
            "pluralLabel" to obj.pluralLabel,
            "description" to obj.description,
            "enabled" to obj.enabled,
            "physicalTable" to obj.physicalTable,
            "indexes" to obj.indexes,
            "uniqueConstraints" to obj.uniqueConstraints,
            "appendOnly" to obj.appendOnly,
            "apiOnly" to obj.apiOnly,
            "requiresReason" to obj.requiresReason,
            "fields" to objectFields.map { state(it, it.relationTargetObjectId?.let(targets::get)) },
            "actions" to actions.findByObject(obj.organizationId, obj.id).associate { it.name to it.label }
        )
    }

    suspend fun field(
        obj: CustomObject,
        field: CustomField
    ): Map<String, Any?> {
        val target = targetNames(obj.organizationId, listOf(field)).values.firstOrNull()
        return linkedMapOf<String, Any?>("object" to obj.name) + state(field, target)
    }

    private fun state(
        field: CustomField,
        relationTarget: String?
    ): Map<String, Any?> {
        val state =
            linkedMapOf<String, Any?>(
                "name" to field.name,
                "label" to field.label,
                "type" to field.type.name,
                "required" to field.required,
                "unique" to field.unique,
                "defaultValue" to field.defaultValue,
                "description" to field.description,
                "position" to field.position,
                "enumOptions" to field.enumOptions,
                "relationTarget" to relationTarget,
                "visible" to field.visible,
                "editable" to field.editable,
                "indexed" to field.indexed,
                "timeZone" to field.timeZone
            )
        // an installed field type's own columns, by column name
        if (field.attributes.isNotEmpty()) state["attributes"] = field.attributes
        return state
    }

    private suspend fun targetNames(
        organizationId: UUID,
        fields: List<CustomField>
    ): Map<UUID, String> =
        fields
            .mapNotNull { it.relationTargetObjectId }
            .distinct()
            .mapNotNull { id -> objects.findById(organizationId, id)?.let { id to it.name } }
            .toMap()
}
