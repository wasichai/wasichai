package wasichai.core.metadata

import wasichai.core.identity.FieldAccess
import java.time.Instant
import java.util.UUID

data class CustomObject(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val physicalTable: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

data class CustomField(
    val id: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val type: FieldType,
    val columnName: String,
    val required: Boolean,
    val unique: Boolean,
    val defaultValue: String?,
    val description: String?,
    val position: Int,
    val enumOptions: List<String>?,
    val relationTargetObjectId: UUID?,
    // what a module's field type keeps in its own custom_fields columns, by column name
    val attributes: Map<String, Any?> = emptyMap(),
    val visible: Boolean,
    val editable: Boolean
)

// object plus its fields. what the UI needs to render anything.
data class ObjectDefinition(
    val obj: CustomObject,
    val fields: List<CustomField>
)

// what the caller may see: unreadable fields gone, unwritable ones locked.
fun ObjectDefinition.readableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    return copy(
        fields =
            fields
                .filter { access.canRead(it.id) }
                .map { if (access.canWrite(it.id)) it else it.copy(editable = false) }
    )
}

// every field stays (the row still has to be read back), but the locked ones stop being written.
fun ObjectDefinition.writableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    return copy(fields = fields.map { if (access.canWrite(it.id)) it else it.copy(editable = false) })
}

// field names the caller may read back
fun ObjectDefinition.readableNames(access: FieldAccess): Set<String> = fields.filter { access.canRead(it.id) }.map { it.name }.toSet()
