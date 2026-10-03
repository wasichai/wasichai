package wasichai.core.metadata

import org.springframework.stereotype.Component
import java.util.UUID

// metadata -> api. relation fields store a target id and the api speaks object names; installed
// field types add their own keys (R5).
@Component
class MetadataMapper(
    private val objects: CustomObjectRepository,
    private val types: FieldTypeRegistry
) {
    fun toObjectResponse(definition: ObjectDefinition): ObjectResponse =
        ObjectResponse(
            id = definition.obj.id.toString(),
            name = definition.obj.name,
            label = definition.obj.label,
            pluralLabel = definition.obj.pluralLabel,
            description = definition.obj.description,
            enabled = definition.obj.enabled,
            createdAt = definition.obj.createdAt,
            updatedAt = definition.obj.updatedAt,
            indexes = definition.obj.indexes,
            uniqueConstraints = definition.obj.uniqueConstraints,
            appendOnly = definition.obj.appendOnly,
            apiOnly = definition.obj.apiOnly,
            extensions = types.objectProperties(definition)
        )

    suspend fun toResponse(
        definition: ObjectDefinition,
        organizationId: UUID
    ): ObjectDefinitionResponse =
        ObjectDefinitionResponse(
            id = definition.obj.id.toString(),
            name = definition.obj.name,
            label = definition.obj.label,
            pluralLabel = definition.obj.pluralLabel,
            description = definition.obj.description,
            enabled = definition.obj.enabled,
            fields = toFieldResponses(definition.fields, organizationId),
            indexes = definition.obj.indexes,
            uniqueConstraints = definition.obj.uniqueConstraints,
            appendOnly = definition.obj.appendOnly,
            apiOnly = definition.obj.apiOnly,
            extensions = types.objectProperties(definition)
        )

    suspend fun toFieldResponses(
        fields: List<CustomField>,
        organizationId: UUID
    ): List<FieldResponse> {
        val targetNames =
            fields
                .mapNotNull { it.relationTargetObjectId }
                .distinct()
                .mapNotNull { id -> objects.findById(organizationId, id)?.let { id to it.name } }
                .toMap()
        return fields.map { toFieldResponse(it, it.relationTargetObjectId?.let(targetNames::get)) }
    }

    fun toFieldResponse(
        field: CustomField,
        relationTargetName: String?
    ): FieldResponse =
        FieldResponse(
            id = field.id.toString(),
            name = field.name,
            label = field.label,
            type = field.type.name,
            required = field.required,
            unique = field.unique,
            defaultValue = field.defaultValue,
            description = field.description,
            position = field.position,
            enumOptions = field.enumOptions,
            relationTarget = relationTargetName,
            visible = field.visible,
            editable = field.editable,
            indexed = field.indexed,
            extensions = types.fieldProperties(field)
        )
}
