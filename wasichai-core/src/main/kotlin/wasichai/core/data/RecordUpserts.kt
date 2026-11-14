package wasichai.core.data

import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.math.BigDecimal

/**
 * What [RecordService.upsertAll] did (issue 163, ADR-080): how many records it created, updated, found as they
 * were and deleted, and one answer per request, in request order: what the create or the update answered, or the
 * record as the caller reads it when nothing changed.
 */
data class UpsertResult(
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val deleted: Int,
    val records: List<RecordResponse>
)

/**
 * A natural key of an object: a field with `unique: true`, or one of its declared composite `uniqueConstraints`
 * (ADR-037), so at most one record carries each value. A key's values are compared as a read answers them: `"5"`
 * and `5` are one INTEGER, `"1.50"` and `1.5` one DECIMAL.
 */
internal class NaturalKey private constructor(
    val fields: List<CustomField>,
    private val types: FieldTypeRegistry
) {
    // a request's key, as stored. a missing or null part is no key: postgres lets many nulls through a unique
    fun valuesOf(
        request: RecordRequest,
        index: Int
    ): List<Any?> =
        fields.map { field ->
            val value = request.attributes[field.name] ?: throw ValidationException("Record $index has no '${field.name}'", field.name, "is part of the key")
            key(normalized(field, value, types))
        }

    fun valuesOf(row: RecordRow): List<Any?> = fields.map { key(row.attributes[it.name]) }

    // two requests for one record would write it twice in one call, the second over the first
    fun rejectRepeats(keys: List<List<Any?>>) {
        val seen = hashMapOf<List<Any?>, Int>()
        keys.forEachIndexed { index, values ->
            seen.put(values, index)?.let { first ->
                throw ValidationException(
                    "Records $first and $index have the same key",
                    fields.first().name,
                    "repeats ${fields.joinToString(", ") { it.name }} of record $first"
                )
            }
        }
    }

    // the rows that carry one of [keys]: (a = :c0 AND b = :c1) OR (...), every value bound as its column takes it
    fun criterion(keys: List<List<Any?>>): RecordCriterion =
        RecordCriterion { _, bind ->
            keys.joinToString(" OR ") { values ->
                fields
                    .mapIndexed { index, field ->
                        val handler = types.handler(field.type)
                        val parameter = bind(handler.toDatabase(field, values[index])!!).removePrefix(":")
                        "${SqlIdentifier.quote(field.columnName)} = ${handler.bindExpression(field, parameter)}"
                    }.joinToString(" AND ", "(", ")")
            }
        }

    // a BigDecimal's equals counts its scale: the map key must not
    private fun key(value: Any?): Any? = if (value is BigDecimal) value.stripTrailingZeros() else value

    companion object {
        // names in any case and order, trimmed. anything else is a 400 on key
        fun of(
            definition: ObjectDefinition,
            names: List<String>,
            types: FieldTypeRegistry
        ): NaturalKey {
            val wanted = names.map { it.trim().lowercase() }
            if (wanted.isEmpty()) throw ValidationException("The key names no field", KEY, "names a unique field or a unique constraint")
            val fields =
                wanted.map { name ->
                    definition.fields.firstOrNull { it.name == name && types.handler(it.type).section == null }
                        ?: throw ValidationException("Unknown field '$name' in the key", KEY, "is not an attribute of '${definition.obj.name}'")
                }
            val unique =
                if (fields.size == 1) {
                    fields.single().unique
                } else {
                    definition.obj.uniqueConstraints.any { it.size == wanted.size && it.toSet() == wanted.toSet() }
                }
            if (!unique || wanted.toSet().size != wanted.size) {
                throw ValidationException(
                    "${wanted.joinToString(", ", "(", ")")} is no natural key of '${definition.obj.name}'",
                    KEY,
                    "names a field with unique: true or one of the object's uniqueConstraints"
                )
            }
            return NaturalKey(fields, types)
        }

        const val KEY = "key"
    }
}

// [value] as a read answers it: what the store would write, read back
internal fun normalized(
    field: CustomField,
    value: Any?,
    types: FieldTypeRegistry
): Any? {
    if (value == null) return null
    val handler = types.handler(field.type)
    return handler.fromDatabase(field, handler.toDatabase(field, value))
}

/**
 * True when [request] would change the record: a value it sends, in `attributes` or in an installed section, reads
 * other than the stored one. A key it leaves out is no change, as in a PATCH.
 */
internal fun RecordRow.changedBy(
    definition: ObjectDefinition,
    request: RecordRequest,
    sections: Map<String, Map<String, Any?>>,
    types: FieldTypeRegistry
): Boolean {
    val inAttributes =
        request.attributes.any { (name, value) ->
            val field = definition.fields.first { it.name == name && types.handler(it.type).section == null }
            !same(normalized(field, value, types), attributes[name])
        }
    if (inAttributes) return true
    return sections.any { (section, entries) ->
        entries.any { (name, value) ->
            val field = definition.fields.firstOrNull { it.name == name && types.handler(it.type).section == section } ?: return@any true
            !same(normalized(field, value, types), this.sections[section]?.get(name))
        }
    }
}

// 10 and 10.0 are one value; anything else as equals says
private fun same(
    a: Any?,
    b: Any?
): Boolean {
    if (a is Number && b is Number) return BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
    return a == b
}
