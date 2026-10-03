package wasichai.core.metadata

import wasichai.core.common.ValidationException

// object-level lists of field sets: `indexes` and `uniqueConstraints`. one path to check and normalize
// both, so they are stored and validated the same way. names, not ids: fields are never renamed.
// ADR-036, ADR-037.
object FieldSets {
    // postgres: INDEX_MAX_KEYS
    const val MAX_FIELDS = 32

    // trimmed, lower case, order kept, a repeated set counted once. `property` is what a 400 names.
    fun normalize(
        property: String,
        sets: List<List<String>>,
        fields: List<CustomField>,
        types: FieldTypeRegistry,
        // 2 for uniqueConstraints: one field is `unique: true`, and its constraint is the field's own (ADR-037)
        minFields: Int = 1
    ): List<List<String>> =
        sets
            .map { raw ->
                val set = raw.map { it.trim().lowercase() }
                if (set.isEmpty()) throw ValidationException("An entry of $property is empty", property, "every entry names at least one field")
                if (set.size < minFields) {
                    throw ValidationException(
                        "An entry of $property has ${set.size} field",
                        property,
                        "names at least $minFields fields; make a single field unique with unique: true"
                    )
                }
                if (set.size > MAX_FIELDS) {
                    throw ValidationException("An entry of $property has ${set.size} fields", property, "at most $MAX_FIELDS fields per entry")
                }
                set.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                    throw ValidationException("Field '$it' appears twice in ${set.joinToString(", ", "(", ")")}", property, "names each field once")
                }
                set.forEach { name ->
                    val field =
                        fields.firstOrNull { it.name == name }
                            ?: throw ValidationException("Unknown field '$name' in $property", property, "is not a field of this object")
                    requireIndexable(field, property, types)
                }
                set
            }.distinct()

    // a btree over a type its handler keeps out of filters means nothing, and long
    // text can outgrow a btree entry (~2.7 kB) and make a later write fail
    fun requireIndexable(
        field: CustomField,
        property: String,
        types: FieldTypeRegistry
    ) {
        val refused = field.type == FieldType.LONG_TEXT || types.handler(field.type).rejectFilterOrSort(field) != null
        if (refused) {
            throw ValidationException("Field '${field.name}' cannot be indexed", property, "a ${field.type.name} field cannot be indexed")
        }
    }

    // the first set of either list a field takes part in, and the list it sits in: it cannot be
    // dropped while one still names it, or postgres would drop the set with the column
    fun blocking(
        field: String,
        obj: CustomObject
    ): Blocking? =
        containing(field, obj.indexes).firstOrNull()?.let { Blocking("index", "indexes", it) }
            ?: containing(field, obj.uniqueConstraints).firstOrNull()?.let { Blocking("unique constraint", "uniqueConstraints", it) }

    // "index (a, b)" or "unique constraint (a, b)", and the object property to remove it from
    data class Blocking(
        val kind: String,
        val property: String,
        val set: List<String>
    ) {
        override fun toString(): String = "$kind ${set.joinToString(", ", "(", ")")}"
    }

    // the sets a field takes part in
    fun containing(
        field: String,
        sets: List<List<String>>
    ): List<List<String>> = sets.filter { field in it }
}
