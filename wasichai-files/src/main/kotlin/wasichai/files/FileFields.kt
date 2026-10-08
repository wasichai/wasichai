package wasichai.files

import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import java.util.UUID

val FILE = FieldType("FILE")
val IMAGE = FieldType("IMAGE")

// custom_fields columns this module owns (R3); its migration creates them
const val MAX_BYTES_COLUMN = "file_max_bytes"
const val CONTENT_TYPES_COLUMN = "file_content_types"

// what an IMAGE field takes when its admin names no list
val DEFAULT_IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/webp")

val CustomField.isFile: Boolean get() = type == FILE || type == IMAGE

// the field's own cap, null = the configured one
val CustomField.maxBytes: Long? get() = (attributes[MAX_BYTES_COLUMN] as Number?)?.toLong()

// the field's own allow-list, null = its type's default (any type for FILE)
val CustomField.contentTypes: List<String>?
    get() = (attributes[CONTENT_TYPES_COLUMN] as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

// what a FILE or IMAGE value reads as in the record json and the audit trail: never the bytes
data class FileDescriptor(
    val id: UUID,
    val name: String,
    val contentType: String,
    val size: Long,
    val sha256: String
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id.toString(), "name" to name, "contentType" to contentType, "size" to size, "sha256" to sha256)
}

// the file id a record value names: a descriptor (what a read gave), its id as text, or the id itself.
// null for null. anything else is not a file reference.
fun fileIdOf(value: Any?): UUID? =
    when (value) {
        null -> null
        is UUID -> value
        is String -> UUID.fromString(value.trim())
        is Map<*, *> -> fileIdOf(value["id"] ?: throw IllegalArgumentException("no id"))
        else -> throw IllegalArgumentException("not a file reference")
    }
