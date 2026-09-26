package wasichai.automation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import java.math.BigDecimal
import java.util.UUID

// no database, no spring: operators and templates are pure functions and deserve to stay that way.
class AutomationRulesTest {
    private val objectId = UUID.randomUUID()

    private fun change(
        kind: RecordChangeKind = RecordChangeKind.UPDATED,
        before: Map<String, Any?>? = mapOf("area" to BigDecimal("500"), "uso" to "residencial"),
        after: Map<String, Any?>? = mapOf("area" to BigDecimal("1500"), "uso" to "comercial"),
        state: String? = null,
        transition: String? = null
    ) = RecordChange(
        organizationId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        objectId = objectId,
        objectName = "predio",
        recordId = UUID.randomUUID(),
        kind = kind,
        before = before,
        after = after,
        state = state,
        transition = transition
    )

    @Test
    fun `a trigger only fires on its own kind of change`() {
        val onCreate = AutomationTrigger(TriggerType.RECORD_CREATED)
        assertThat(AutomationRules.triggerMatches(onCreate, change(kind = RecordChangeKind.CREATED))).isTrue()
        assertThat(AutomationRules.triggerMatches(onCreate, change(kind = RecordChangeKind.UPDATED))).isFalse()
    }

    @Test
    fun `a named transition fires only for that transition, an unnamed one for any`() {
        val transitioned = change(kind = RecordChangeKind.TRANSITIONED, state = "aprobado", transition = "approve")
        assertThat(
            AutomationRules.triggerMatches(AutomationTrigger(TriggerType.TRANSITION_APPLIED, transition = "approve"), transitioned)
        ).isTrue()
        assertThat(
            AutomationRules.triggerMatches(AutomationTrigger(TriggerType.TRANSITION_APPLIED, transition = "reject"), transitioned)
        ).isFalse()
        assertThat(AutomationRules.triggerMatches(AutomationTrigger(TriggerType.TRANSITION_APPLIED), transitioned)).isTrue()
        assertThat(
            AutomationRules.triggerMatches(AutomationTrigger(TriggerType.STATE_ENTERED, state = "aprobado"), transitioned)
        ).isTrue()
        assertThat(
            AutomationRules.triggerMatches(AutomationTrigger(TriggerType.STATE_ENTERED, state = "rechazado"), transitioned)
        ).isFalse()
    }

    @Test
    fun `numbers compare as numbers, not as text`() {
        // "1500" < "900" as text. the spec's own question depends on this being wrong.
        val greater = listOf(AutomationCondition("area", ConditionOperator.GREATER_THAN, "900"))
        assertThat(AutomationRules.unmetCondition(greater, change())).isNull()
        val lesser = listOf(AutomationCondition("area", ConditionOperator.LESS_THAN, "900"))
        assertThat(AutomationRules.unmetCondition(lesser, change())).isNotNull()
    }

    @Test
    fun `equality, emptiness and containment read the values after the change`() {
        assertThat(AutomationRules.unmetCondition(listOf(AutomationCondition("uso", ConditionOperator.EQUALS, "comercial")), change())).isNull()
        assertThat(
            AutomationRules.unmetCondition(listOf(AutomationCondition("uso", ConditionOperator.EQUALS, "residencial")), change())
        ).isNotNull()
        assertThat(AutomationRules.unmetCondition(listOf(AutomationCondition("uso", ConditionOperator.CONTAINS, "COMER")), change())).isNull()
        assertThat(AutomationRules.unmetCondition(listOf(AutomationCondition("uso", ConditionOperator.IS_NOT_EMPTY)), change())).isNull()
        assertThat(
            AutomationRules.unmetCondition(listOf(AutomationCondition("falta", ConditionOperator.IS_EMPTY)), change())
        ).isNull()
    }

    @Test
    fun `CHANGED needs a before, so a create never counts as a change`() {
        val changed = listOf(AutomationCondition("uso", ConditionOperator.CHANGED))
        assertThat(AutomationRules.unmetCondition(changed, change())).isNull()
        assertThat(AutomationRules.unmetCondition(changed, change(before = null, kind = RecordChangeKind.CREATED))).isNotNull()
        // same value on both sides is not a change either
        assertThat(
            AutomationRules.unmetCondition(changed, change(before = mapOf("uso" to "comercial")))
        ).isNotNull()
    }

    @Test
    fun `the first unmet condition is the one reported`() {
        val conditions =
            listOf(
                AutomationCondition("uso", ConditionOperator.EQUALS, "comercial"),
                AutomationCondition("area", ConditionOperator.LESS_THAN, "100")
            )
        assertThat(AutomationRules.unmetCondition(conditions, change())).contains("area", "LESS_THAN", "100")
    }

    @Test
    fun `templates read the record and the platform, and unknown keys render empty`() {
        val c = change(state = "aprobado")
        assertThat(AutomationRules.render("uso {{uso}} de {{area}} m2", c)).isEqualTo("uso comercial de 1500 m2")
        assertThat(AutomationRules.render("{{id}}", c)).isEqualTo(c.recordId.toString())
        assertThat(AutomationRules.render("{{state}}", c)).isEqualTo("aprobado")
        assertThat(AutomationRules.render("[{{nada}}]", c)).isEqualTo("[]")
    }

    @Test
    fun `a url keeps its shape once its placeholders are gone`() {
        assertThat(AutomationRules.withoutPlaceholders("https://hooks.example.com/{{id}}/done"))
            .isEqualTo("https://hooks.example.com/x/done")
    }
}
