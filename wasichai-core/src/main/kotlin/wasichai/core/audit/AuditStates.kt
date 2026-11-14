package wasichai.core.audit

import wasichai.core.metadata.AuditMode
import wasichai.core.metadata.ObjectDefinition

/**
 * What a record write of an object stores in the audit log, as its metadata says (issue 164, ADR-079): the object's
 * [AuditMode], and its fields with `audited: false`, whose values stay out of both states. A state that left one out
 * names it under [OMITTED], a key no field can have (field names start with a letter), so history can say a value
 * was there without showing it.
 */
internal object AuditStates {
    const val OMITTED = "_omitted"

    // the states of one entry; both null under OPERATION
    data class Entry(
        val before: Map<String, Any?>?,
        val after: Map<String, Any?>?
    )

    // null: no entry. [fieldStates] false: the states are no record fields (a link, a transition), so only the mode applies
    fun of(
        definition: ObjectDefinition,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
        fieldStates: Boolean = true
    ): Entry? =
        when (definition.obj.auditMode) {
            AuditMode.NONE -> null
            AuditMode.OPERATION -> Entry(null, null)
            AuditMode.FULL -> if (fieldStates) Entry(omitted(definition, before), omitted(definition, after)) else Entry(before, after)
        }

    // the names a stored state left out, in field order
    @Suppress("UNCHECKED_CAST")
    fun omittedIn(state: Map<String, Any?>?): List<String> = (state?.get(OMITTED) as? List<*>)?.filterIsInstance<String>().orEmpty()

    // a state without its marker: what the diff compares
    fun withoutMarker(state: Map<String, Any?>?): Map<String, Any?>? = state?.let { if (OMITTED in it) it - OMITTED else it }

    private fun omitted(
        definition: ObjectDefinition,
        state: Map<String, Any?>?
    ): Map<String, Any?>? {
        if (state == null) return null
        val names = definition.fields.filter { !it.audited && it.name in state }.map { it.name }
        if (names.isEmpty()) return state
        return state.filterKeys { it !in names } + (OMITTED to names)
    }
}
