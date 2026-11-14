package wasichai.core.metadata

import wasichai.core.common.ValidationException
import wasichai.core.identity.FieldAccess
import java.time.Instant
import java.util.UUID

// what a record write of an object leaves in the audit log (issue 164, ADR-079). links and state transitions too;
// a document's ISSUE entry always stays: a document never exists without its history entry
enum class AuditMode {
    // the operation and the record before and after, as always
    FULL,

    // the operation, who, when, from where and why: no states
    OPERATION,

    // no entry at all
    NONE;

    companion object {
        const val PROPERTY = "auditMode"

        // null stays null: "not sent". any case, trimmed
        fun parse(raw: String?): AuditMode? {
            val name = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown audit mode '$raw'", PROPERTY, "must be one of ${entries.joinToString(", ")}")
        }
    }
}

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
    val updatedAt: Instant?,
    // declared composite indexes, field names in index order (ADR-036)
    val indexes: List<List<String>> = emptyList(),
    // declared composite uniques, field names in constraint order, each per organization (ADR-037)
    val uniqueConstraints: List<List<String>> = emptyList(),
    // no UPDATE, no DELETE of its records, for anyone: ADMIN and the platform included (ADR-040)
    val appendOnly: Boolean = false,
    // the generic record api does not write it; only in-process callers do (ADR-040)
    val apiOnly: Boolean = false,
    // every write of its records says why: no reason, nothing stored (ADR-041)
    val requiresReason: Boolean = false,
    // label, pluralLabel and description per language tag; the single texts stay the fallback (ADR-078)
    val labels: LocalizedText = emptyMap(),
    val pluralLabels: LocalizedText = emptyMap(),
    val descriptions: LocalizedText = emptyMap(),
    // what its record writes leave in the audit log (ADR-079)
    val auditMode: AuditMode = AuditMode.FULL
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
    val editable: Boolean,
    // a single-column index on every organization's table (ADR-036). relations get one anyway.
    val indexed: Boolean = false,
    // a DATETIME field's IANA zone (ADR-063). the column keeps instants either way
    val timeZone: String? = null,
    // label and description per language tag, and an ENUM option's text per option value. the stored value,
    // its check and validation never read them (ADR-078)
    val labels: LocalizedText = emptyMap(),
    val descriptions: LocalizedText = emptyMap(),
    val enumOptionLabels: Map<String, LocalizedText> = emptyMap(),
    // false: its value stays out of the audited states, which name it as omitted (ADR-079)
    val audited: Boolean = true
)

// object plus its fields. what the UI needs to render anything.
data class ObjectDefinition(
    val obj: CustomObject,
    val fields: List<CustomField>
)

// what the caller may see: unreadable fields gone, unwritable ones locked, and no set naming a hidden field.
fun ObjectDefinition.readableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    val readable = fields.filter { access.canRead(it.id) }
    val names = readable.map { it.name }.toSet()
    return copy(
        obj =
            obj.copy(
                indexes = obj.indexes.filter { set -> names.containsAll(set) },
                uniqueConstraints = obj.uniqueConstraints.filter { set -> names.containsAll(set) }
            ),
        fields = readable.map { if (access.canWrite(it.id)) it else it.copy(editable = false) }
    )
}

// every field stays (the row still has to be read back), but the locked ones stop being written.
fun ObjectDefinition.writableBy(access: FieldAccess): ObjectDefinition {
    if (access.unrestricted) return this
    return copy(fields = fields.map { if (access.canWrite(it.id)) it else it.copy(editable = false) })
}

// field names the caller may read back
fun ObjectDefinition.readableNames(access: FieldAccess): Set<String> = fields.filter { access.canRead(it.id) }.map { it.name }.toSet()
