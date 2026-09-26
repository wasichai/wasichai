package wasichai.core.audit

import java.math.BigDecimal

// one field that changed, as the history UI shows it
data class FieldChange(
    val field: String,
    val before: Any?,
    val after: Any?
)

// diffing lives here, not in SQL: the states are json and the comparison is value by value.
object AuditDiff {
    fun changes(
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ): List<FieldChange> {
        if (before == null || after == null) return emptyList()
        val names = LinkedHashSet(before.keys) + after.keys
        return names
            .filterNot { same(before[it], after[it]) }
            .map { FieldChange(it, before[it], after[it]) }
    }

    // json numbers: 10 and 10.0 are the same value, different classes
    private fun same(
        a: Any?,
        b: Any?
    ): Boolean {
        if (a is Number && b is Number) return decimal(a).compareTo(decimal(b)) == 0
        return a == b
    }

    private fun decimal(value: Number): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is Double, is Float -> BigDecimal.valueOf(value.toDouble())
            else -> BigDecimal.valueOf(value.toLong())
        }
}
