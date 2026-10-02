package wasichai.core.metadata

import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import java.util.UUID

// every installed field type: core's first, then the modules' in bean order
class FieldTypeRegistry(
    extra: List<FieldTypeHandler>
) {
    val handlers: List<FieldTypeHandler> = ScalarFieldTypes.ALL + extra

    private val byName: Map<String, FieldTypeHandler>

    val types: List<FieldType>

    // payload sections in declaration order, each once
    val sections: List<String>

    // custom_fields columns owned by installed modules
    val attributeColumns: Map<String, Class<*>>

    init {
        val twice = handlers.groupBy { it.type.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "field type declared twice: ${twice.joinToString(", ")}" }
        byName = handlers.associateBy { it.type.name }
        types = handlers.map { it.type }

        // a section is a payload key a module owns in the record json: two handlers writing the same one would clobber each other
        val sharedSections =
            handlers
                .filter { it.section != null }
                .groupBy { it.section }
                .filterValues { it.size > 1 }
                .keys
        check(sharedSections.isEmpty()) { "section claimed by more than one field type: ${sharedSections.joinToString(", ")}" }
        sections = handlers.mapNotNull { it.section }.distinct()

        // a section is flattened next to id/attributes/state in the record json (R6): a section
        // named after one of them would silently overwrite it instead of sitting beside it
        val reservedSections = sections.filter { it in CORE_RECORD_KEYS }
        check(reservedSections.isEmpty()) {
            "section name collides with a core record json key: ${reservedSections.joinToString(", ")}"
        }

        // two handlers on the same custom_fields column but disagreeing on its java type would
        // bind garbage the moment either one reads a row the other one wrote
        val columns = linkedMapOf<String, Class<*>>()
        for (handler in handlers) {
            for ((column, klass) in handler.attributeColumns) {
                val existing = columns[column]
                check(existing == null || existing == klass) {
                    "attribute column '$column' declared with different types: $existing and $klass"
                }
                columns[column] = klass
            }
        }
        attributeColumns = columns

        // a handler's extension key would silently shadow a core key (R5): probe with a stand-in
        // field/object, since fieldProperties/objectProperties are computed, not declared
        for (handler in handlers) {
            val probeField = probeField(handler)
            val fieldKeys = handler.fieldProperties(probeField).keys.intersect(CORE_FIELD_KEYS)
            check(fieldKeys.isEmpty()) {
                "field type '${handler.type.name}' contributes a field json key core already owns: ${fieldKeys.joinToString(", ")}"
            }
            val probeDefinition = ObjectDefinition(probeObject(), listOf(probeField))
            val objectKeys = handler.objectProperties(probeDefinition).keys.intersect(CORE_OBJECT_KEYS)
            check(objectKeys.isEmpty()) {
                "field type '${handler.type.name}' contributes an object json key core already owns: ${objectKeys.joinToString(", ")}"
            }
        }
    }

    fun isInstalled(type: FieldType): Boolean = type.name in byName

    fun parse(
        raw: String,
        field: String = "type"
    ): FieldType =
        byName[raw.trim().uppercase()]?.type
            ?: throw ValidationException(
                "Unknown field type '$raw'",
                field,
                "must be one of ${types.joinToString(", ") { it.name }}"
            )

    // a stored field whose module was removed: its records stay unreadable until the module is back
    fun handler(type: FieldType): FieldTypeHandler =
        byName[type.name]
            ?: throw ConflictException("Field type '${type.name}' is not installed. Add the module that provides it.")

    fun sectionOwner(section: String): FieldTypeHandler = handlers.firstOrNull { it.section == section } ?: error("no field type owns section '$section'")

    fun fieldProperties(field: CustomField): Map<String, Any?> =
        handlers.fold(linkedMapOf()) { acc, handler ->
            acc.putAll(handler.fieldProperties(field))
            acc
        }

    fun objectProperties(definition: ObjectDefinition): Map<String, Any?> =
        handlers.fold(linkedMapOf()) { acc, handler ->
            acc.putAll(handler.objectProperties(definition))
            acc
        }

    // a stand-in field/object of the handler's own type, only to read the static set of json keys
    // it contributes. values do not matter: a well-behaved handler's key set never depends on them.
    private fun probeField(handler: FieldTypeHandler) =
        CustomField(
            id = PROBE_ID,
            objectId = PROBE_ID,
            name = "probe",
            label = "Probe",
            type = handler.type,
            columnName = "probe",
            required = false,
            unique = false,
            defaultValue = null,
            description = null,
            position = 0,
            enumOptions = null,
            relationTargetObjectId = null,
            attributes = handler.attributeColumns.mapValues { null },
            visible = true,
            editable = true
        )

    private fun probeObject() =
        CustomObject(
            id = PROBE_ID,
            organizationId = PROBE_ID,
            name = "probe",
            label = "Probe",
            pluralLabel = "Probes",
            description = null,
            enabled = true,
            physicalTable = "probe",
            createdAt = null,
            updatedAt = null
        )

    companion object {
        private val PROBE_ID = UUID(0, 0)

        // FieldResponse's own constructor properties, minus `extensions`
        private val CORE_FIELD_KEYS =
            setOf(
                "id",
                "name",
                "label",
                "type",
                "required",
                "unique",
                "defaultValue",
                "description",
                "position",
                "enumOptions",
                "relationTarget",
                "visible",
                "editable",
                "indexed"
            )

        // ObjectResponse's and ObjectDefinitionResponse's own constructor properties, minus `extensions`
        private val CORE_OBJECT_KEYS =
            setOf(
                "id",
                "name",
                "label",
                "pluralLabel",
                "description",
                "enabled",
                "createdAt",
                "updatedAt",
                "fields",
                "indexes",
                "uniqueConstraints",
                "appendOnly",
                "apiOnly"
            )

        // data.RecordResponse's own constructor properties, minus the flattened `sections`. metadata
        // cannot import data (R1), so this list is kept here, matched to RecordResponse by RecordJsonTest.
        private val CORE_RECORD_KEYS = setOf("id", "createdAt", "updatedAt", "attributes", "state")
    }
}
