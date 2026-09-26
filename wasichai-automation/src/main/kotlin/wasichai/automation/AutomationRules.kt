package wasichai.automation

import wasichai.core.data.RecordChange
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

// the workflow state is not a custom field, but conditions and templates read it like one
private const val STATE = "state"

// matching and templating. no expression language: operators over one field, {{field}} in values.
object AutomationRules {
    fun triggerMatches(
        trigger: AutomationTrigger,
        change: RecordChange
    ): Boolean {
        if (trigger.type.kind != change.kind) return false
        return when (trigger.type) {
            TriggerType.TRANSITION_APPLIED -> trigger.transition == null || trigger.transition == change.transition
            TriggerType.STATE_ENTERED -> trigger.state == null || trigger.state == change.state
            else -> true
        }
    }

    // null means every condition holds. otherwise the first one that did not, in words.
    fun unmetCondition(
        conditions: List<AutomationCondition>,
        change: RecordChange
    ): String? {
        val values = change.after ?: change.before.orEmpty()
        val previous = change.before
        return conditions
            .firstOrNull { !holds(it, values, previous, change.state) }
            ?.let { "condition not met: ${it.field} ${it.operator}${it.value?.let { v -> " $v" } ?: ""}" }
    }

    // {{field}} out of the record, plus a few platform values
    fun render(
        template: String,
        change: RecordChange
    ): String {
        val values = change.after ?: change.before.orEmpty()
        return PLACEHOLDER.replace(template) { match ->
            when (val key = match.groupValues[1].trim()) {
                STATE -> change.state.orEmpty()
                "id" -> change.recordId.toString()
                "now" -> OffsetDateTime.now().toString()
                "today" -> LocalDate.now().toString()
                "user" -> change.userId?.toString().orEmpty()
                else -> stringify(values[key])
            }
        }
    }

    // a url is validated before any record exists to fill its placeholders
    fun withoutPlaceholders(text: String): String = PLACEHOLDER.replace(text, "x")

    private fun holds(
        condition: AutomationCondition,
        values: Map<String, Any?>,
        previous: Map<String, Any?>?,
        state: String?
    ): Boolean {
        val actual = if (condition.field == STATE) state else values[condition.field]
        val expected = condition.value
        return when (condition.operator) {
            ConditionOperator.EQUALS -> same(actual, expected)
            ConditionOperator.NOT_EQUALS -> !same(actual, expected)
            ConditionOperator.GREATER_THAN -> compare(actual, expected) > 0
            ConditionOperator.LESS_THAN -> compare(actual, expected) < 0
            ConditionOperator.CONTAINS -> stringify(actual).contains(expected.orEmpty(), ignoreCase = true)
            ConditionOperator.IS_EMPTY -> stringify(actual).isBlank()
            ConditionOperator.IS_NOT_EMPTY -> stringify(actual).isNotBlank()
            // no before means nothing to compare against: a create never counts as a change
            ConditionOperator.CHANGED -> previous != null && stringify(previous[condition.field]) != stringify(actual)
        }
    }

    // numbers compare as numbers, everything else as text. iso dates sort right either way.
    private fun same(
        actual: Any?,
        expected: String?
    ): Boolean {
        val left = number(actual)
        val right = expected?.toBigDecimalOrNull()
        if (left != null && right != null) return left.compareTo(right) == 0
        return stringify(actual) == expected.orEmpty()
    }

    private fun compare(
        actual: Any?,
        expected: String?
    ): Int {
        val left = number(actual)
        val right = expected?.toBigDecimalOrNull()
        if (left != null && right != null) return left.compareTo(right)
        return stringify(actual).compareTo(expected.orEmpty())
    }

    private fun number(value: Any?): BigDecimal? =
        when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

    private fun stringify(value: Any?): String = value?.toString().orEmpty()

    private val PLACEHOLDER = Regex("\\{\\{([^}]+)}}")
}
