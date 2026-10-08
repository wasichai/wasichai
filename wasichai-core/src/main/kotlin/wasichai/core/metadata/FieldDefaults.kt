package wasichai.core.metadata

import wasichai.core.common.ValidationException

/**
 * A field's `defaultValue` (issue 60, ADR-031 D40). Stored as text, parsed by the field type's own
 * handler, so a default is checked like any value a caller sends. Applied on create only, to an
 * attribute key the create left out: a key sent, `null` included, wins. Update never applies one.
 */
object FieldDefaults {
    // blank is no default: a form that sends "" for an empty input sets none
    fun of(field: CustomField): String? = field.defaultValue?.takeIf { it.isNotBlank() }

    // a section type refuses a default when the field is made: only attribute fields get one
    fun appliesTo(
        field: CustomField,
        types: FieldTypeRegistry
    ): Boolean = of(field) != null && types.handler(field.type).section == null

    /**
     * What a create writes: [attributes] plus the default of every field it left out, and the
     * definition with those fields writable, so a default lands even where the caller, or anyone
     * (`editable: false`), may not write the field. It is the field's value, not the caller's input.
     */
    fun applied(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        types: FieldTypeRegistry
    ): Pair<ObjectDefinition, Map<String, Any?>> {
        val missing = definition.fields.filter { it.name !in attributes && appliesTo(it, types) }
        if (missing.isEmpty()) return definition to attributes
        val defaults = missing.associate { it.name to attribute(it, types) }
        val names = defaults.keys
        val writable = definition.copy(fields = definition.fields.map { if (it.name in names) it.copy(editable = true) else it })
        return writable to attributes + defaults
    }

    // the default as the api carries a value: "5" -> 5, "true" -> true. a stored one that no longer
    // parses (written before defaults were checked) is a 400 on the field, not a silent null
    private fun attribute(
        field: CustomField,
        types: FieldTypeRegistry
    ): Any? {
        val raw = of(field)!!
        val handler = types.handler(field.type)
        val parsed =
            try {
                handler.toDatabase(field, raw)
            } catch (e: ValidationException) {
                throw ValidationException("Invalid default for '${field.name}'", field.name, "its defaultValue '$raw' ${reason(e)}")
            }
        return handler.fromDatabase(field, parsed)
    }

    /**
     * The default [field] stores: blank is none, anything else must parse as the field's type, or a
     * `400` on [property]. Called when a field is made and when an update touches its default or its
     * enum options.
     */
    fun checked(
        field: CustomField,
        types: FieldTypeRegistry,
        property: String = "defaultValue"
    ): String? {
        val raw = of(field) ?: return null
        val handler = types.handler(field.type)
        if (handler.section != null) {
            throw ValidationException("Field '${field.name}' cannot have a default", property, "a ${field.type.name} field takes no default")
        }
        try {
            // required plays no part: the default itself is never null here
            handler.toDatabase(field, raw)
        } catch (e: ValidationException) {
            throw ValidationException("Invalid default for '${field.name}'", property, "'$raw' ${reason(e)}")
        }
        return raw
    }

    private fun reason(e: ValidationException): String = e.violations.firstOrNull()?.message ?: "is not valid"
}
