package wasichai.files

import tools.jackson.databind.ObjectMapper
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.UpdateFieldRequest
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// FILE and IMAGE: a uuid column naming a stored_files row. the record json reads the row's descriptor
// through stored_file_descriptor(), so the bytes never travel with a record. ADR-0061.
class FileFieldType(
    override val type: FieldType,
    private val schemas: WasichaiSchemas,
    private val objectMapper: ObjectMapper,
    // wasichai.files.max-bytes: the default cap and the highest a field may set
    private val ceiling: Long
) : FieldTypeHandler {
    init {
        require(type == FILE || type == IMAGE) { "FileFieldType handles FILE and IMAGE, not $type" }
        require(ceiling > 0) { "wasichai.files.max-bytes must be positive" }
    }

    override val attributeColumns: Map<String, Class<*>> =
        linkedMapOf(MAX_BYTES_COLUMN to Long::class.javaObjectType, CONTENT_TYPES_COLUMN to String::class.java)

    // ---- metadata ----

    override fun attributesOf(
        fieldName: String,
        request: FieldRequest
    ): Map<String, Any?> {
        if (request.unique) {
            throw ValidationException("File field '$fieldName' cannot be unique", "unique", "has no meaning on a file")
        }
        if (request.defaultValue != null) {
            throw ValidationException("File field '$fieldName' cannot have a default", "defaultValue", "a file is uploaded, never defaulted")
        }
        val maxBytes = maxBytesOf(request.extensions["maxBytes"])
        val contentTypes = contentTypesOf(request.extensions["contentTypes"])
        return mapOf(MAX_BYTES_COLUMN to maxBytes, CONTENT_TYPES_COLUMN to contentTypes?.joinToString(","))
    }

    override fun checkUpdate(
        field: CustomField,
        request: UpdateFieldRequest
    ) {
        if (request.unique == true) {
            throw ValidationException("File field '${field.name}' cannot be unique", "unique", "has no meaning on a file")
        }
        if (!request.defaultValue.isNullOrBlank()) {
            throw ValidationException("File field '${field.name}' cannot have a default", "defaultValue", "a file is uploaded, never defaulted")
        }
    }

    // only on file fields: every other field's json stays exactly what it was
    override fun fieldProperties(field: CustomField): Map<String, Any?> =
        if (field.isFile) mapOf("file" to linkedMapOf("maxBytes" to maxBytes(field), "contentTypes" to contentTypes(field))) else emptyMap()

    // ---- ddl ----

    override fun columnType(field: CustomField): String = "uuid"

    // the cleanup asks every file column "do you name this id": an index each, not a scan each
    override fun indexes(
        obj: CustomObject,
        table: String,
        field: CustomField
    ): List<String> {
        val name = SqlIdentifier.indexName(obj.physicalTable, field.columnName, "fix")
        return listOf("CREATE INDEX ${SqlIdentifier.quote(name)} ON $table (${SqlIdentifier.quote(field.columnName)})")
    }

    // ---- records ----

    // a reference only. whether this caller may point the field at that file is StoredFileGuard's call.
    override fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        if (value == null) {
            if (field.required) throw ValidationException("Missing value", field.name, "is required")
            return null
        }
        return try {
            fileIdOf(value)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("Invalid file", field.name, "must be the id of an uploaded file")
        }
    }

    override fun javaType(field: CustomField): Class<*> = UUID::class.java

    // the descriptor, in the row's own organization. schema names are checked once (WasichaiSchemas).
    override fun select(
        field: CustomField,
        column: String
    ): String = "${schemas.metadata}.stored_file_descriptor($column, organization_id) AS ${SqlIdentifier.quote(readName(field))}"

    // field names stop at 49 characters, so this stays inside postgres's 63
    override fun readName(field: CustomField): String = "${field.columnName}__file"

    override fun fromDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        val json = value as String? ?: return null
        val raw = objectMapper.readValue(json, Map::class.java)
        return FileDescriptor(
            id = UUID.fromString(raw["id"] as String),
            name = raw["name"] as String,
            contentType = raw["contentType"] as String,
            size = (raw["size"] as Number).toLong(),
            sha256 = raw["sha256"] as String
        ).toMap()
    }

    override fun rejectFilterOrSort(field: CustomField): ValidationException =
        ValidationException("Cannot filter or sort by file '${field.name}'", field.name, "a file is not a value to compare")

    // ---- settings ----

    fun maxBytes(field: CustomField): Long = minOf(field.maxBytes ?: ceiling, ceiling)

    fun contentTypes(field: CustomField): List<String>? = field.contentTypes ?: if (field.type == IMAGE) DEFAULT_IMAGE_TYPES else null

    private fun maxBytesOf(raw: Any?): Long? {
        if (raw == null) return null
        val value =
            when (raw) {
                is Int, is Long -> (raw as Number).toLong()
                is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
        if (value == null || value <= 0 || value > ceiling) {
            throw ValidationException("Invalid maxBytes $raw", "maxBytes", "must be a whole number of bytes from 1 to $ceiling")
        }
        return value
    }

    private fun contentTypesOf(raw: Any?): List<String>? {
        if (raw == null) return null
        val entries =
            when (raw) {
                is String -> raw.split(',')
                is List<*> -> raw.map { it as? String ?: throw invalidContentTypes() }
                else -> throw invalidContentTypes()
            }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (entries.isEmpty() || entries.any { !MEDIA_TYPE.matches(it) }) throw invalidContentTypes()
        return entries
    }

    private fun invalidContentTypes() =
        ValidationException("Invalid contentTypes", "contentTypes", "must be a list of media types such as image/png or image/*")

    private companion object {
        // type/subtype or type/*, no parameters: what the sniffer can answer
        val MEDIA_TYPE = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/([a-z0-9][a-z0-9!#$&^_.+-]{0,62}|\\*)$")
    }
}
