package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationRuleFieldUsageTest {
    private fun rule(
        name: String,
        field: String = "vence",
        conditions: List<RuleCondition> = emptyList(),
        title: String = "Vence",
        body: String? = null
    ) = NotificationRuleDefinition(
        name = name,
        label = name,
        field = field,
        stages = listOf(RuleStage(0, NotificationKind.ACTION)),
        untilDays = 0,
        conditions = conditions,
        audience = listOf(AudienceJson("ALL")),
        title = title,
        body = body
    )

    @Test
    fun `the date field is in use`() {
        assertThat(NotificationRuleFieldUsage.users(listOf(rule("por_vencer")), "vence")).containsExactly("notification rule 'por_vencer'")
    }

    @Test
    fun `a condition field is in use`() {
        val rules = listOf(rule("vigentes", conditions = listOf(RuleCondition("estado", ConditionOp.EQ, "VIGENTE"))), rule("otra"))
        assertThat(NotificationRuleFieldUsage.users(rules, "estado")).containsExactly("notification rule 'vigentes'")
    }

    @Test
    fun `a placeholder in the title or the body is in use`() {
        val rules = listOf(rule("en_titulo", title = "La licencia {{ numero }} vence"), rule("en_cuerpo", body = "Titular: {{titular}}"))
        assertThat(NotificationRuleFieldUsage.users(rules, "numero")).containsExactly("notification rule 'en_titulo'")
        assertThat(NotificationRuleFieldUsage.users(rules, "titular")).containsExactly("notification rule 'en_cuerpo'")
    }

    @Test
    fun `a built-in placeholder reads no field of its name`() {
        val rules = listOf(rule("builtins", title = "{{date}} {{days}} {{object}}"))
        listOf("date", "days", "object").forEach { assertThat(NotificationRuleFieldUsage.users(rules, it)).isEmpty() }
    }

    @Test
    fun `a field no rule reads is free`() {
        assertThat(NotificationRuleFieldUsage.users(listOf(rule("por_vencer", title = "{{codigo}}")), "monto")).isEmpty()
    }
}
