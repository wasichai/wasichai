package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class NotificationRulesTest {
    private val lima = ZoneId.of("America/Lima")
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-06")
    private val recordId = UUID.fromString("7c1e0000-0000-0000-0000-0000000000aa")
    private val objectId = UUID.fromString("7c1e0000-0000-0000-0000-000000000001")

    private fun field(
        name: String,
        type: FieldType,
        column: String = "c_$name",
        enumOptions: List<String>? = null
    ) = CustomField(
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        objectId = objectId,
        name = name,
        label = name,
        type = type,
        columnName = column,
        required = false,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = enumOptions,
        relationTargetObjectId = null,
        visible = true,
        editable = true
    )

    private val definition =
        ObjectDefinition(
            CustomObject(objectId, UUID.randomUUID(), "tasa", "Tasa", "Tasas", null, true, "tasa__abcd1234", null, null),
            listOf(
                field("codigo", FieldType.TEXT),
                field("vigencia_hasta", FieldType.DATE, column = "vigencia_col"),
                field("vence_en", FieldType.DATETIME),
                field("estado", FieldType.ENUM, enumOptions = listOf("VIGENTE", "ANULADA")),
                field("reemplazo", FieldType.TEXT),
                field("monto", FieldType.DECIMAL),
                field("cuotas", FieldType.INTEGER),
                field("geom", FieldType("GEOMETRY"))
            )
        )

    private val rule =
        NotificationRuleDefinition(
            name = "tasa_por_vencer",
            label = "Tasas por vencer",
            field = "vigencia_hasta",
            stages = listOf(RuleStage(-15, NotificationKind.WARNING), RuleStage(0, NotificationKind.ACTION)),
            untilDays = 3,
            conditions = listOf(RuleCondition("estado", ConditionOp.EQ, "VIGENTE"), RuleCondition("reemplazo", ConditionOp.EMPTY)),
            audience = listOf(AudienceJson("ROLE", "TESORERIA")),
            title = "La tasa {{codigo}} vence el {{date}}",
            body = "Quedan {{days}} días.",
            tab = "VIGENCIA"
        )

    private val datetimeRule = rule.copy(field = "vence_en", conditions = emptyList())

    private fun violations(r: NotificationRuleDefinition) = NotificationRules.validate(r, definition).violations.map { it.field }

    private fun sql(criterion: RecordCriterion): Pair<String, List<Any>> {
        val binds = mutableListOf<Any>()
        val text =
            criterion.condition(definition) { value ->
                binds += value
                ":c${binds.size - 1}"
            }
        return text to binds
    }

    private fun evaluate(
        values: Map<String, Any?>,
        r: NotificationRuleDefinition = rule,
        zone: ZoneId = lima,
        day: LocalDate = today
    ) = NotificationRules.evaluate(r, definition, recordId, values, day, zone, "dd/MM/yyyy")

    private fun tasa(
        date: Any?,
        estado: String? = "VIGENTE",
        reemplazo: String? = null,
        codigo: String? = "T-1"
    ) = mapOf("vigencia_hasta" to date, "estado" to estado, "reemplazo" to reemplazo, "codigo" to codigo)

    // ---- json ----

    @Test
    fun `the spec's example reads and writes back unchanged`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val json =
            """
            {
              "name": "tasa_por_vencer", "label": "Tasas por vencer", "enabled": true,
              "field": "vigencia_hasta",
              "stages": [{"fromDays": -15, "kind": "WARNING"}, {"fromDays": 0, "kind": "ACTION"}],
              "untilDays": 3,
              "conditions": [{"field": "estado", "op": "EQ", "value": "VIGENTE"}, {"field": "reemplazo", "op": "EMPTY"}],
              "audience": [{"type": "ROLE", "value": "TESORERIA"}],
              "title": "La tasa {{codigo}} vence el {{date}}",
              "body": "Quedan {{days}} días.",
              "tab": "VIGENCIA"
            }
            """.trimIndent()
        val read = mapper.readValue<NotificationRuleDefinition>(json)
        assertThat(read).isEqualTo(rule)
        assertThat(mapper.readTree(mapper.writeValueAsString(read))).isEqualTo(mapper.readTree(json))
    }

    @Test
    fun `defaults fill what a body leaves out`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val read =
            mapper.readValue<NotificationRuleDefinition>(
                """{"name":"r1","label":"R","field":"vigencia_hasta","stages":[{"fromDays":0,"kind":"INFO"}],"untilDays":0,
                   "audience":[{"type":"ALL"}],"title":"t"}"""
            )
        assertThat(read.enabled).isTrue()
        assertThat(read.conditions).isEmpty()
        assertThat(read.body).isNull()
        assertThat(mapper.writeValueAsString(read)).doesNotContain("body", "tab")
    }

    // ---- window ----

    @Test
    fun `the window runs from today minus untilDays to today minus the earliest stage`() {
        assertThat(NotificationRules.window(rule, today)).isEqualTo(DateWindow(LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-21")))
    }

    @Test
    fun `a DATE window is a BETWEEN on the quoted column with bound dates`() {
        val (text, binds) = sql(NotificationRules.windowCriterion(rule, today, lima))
        assertThat(text).isEqualTo("\"vigencia_col\" BETWEEN :c0 AND :c1")
        assertThat(binds).containsExactly(LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-21"))
    }

    @Test
    fun `a DATETIME window runs from the zone's start of the first day to the start of the day after the last`() {
        val (text, binds) = sql(NotificationRules.windowCriterion(datetimeRule, today, lima))
        assertThat(text).isEqualTo("\"c_vence_en\" >= :c0 AND \"c_vence_en\" < :c1")
        assertThat(binds.map { (it as OffsetDateTime).toInstant() })
            .containsExactly(Instant.parse("2026-10-03T05:00:00Z"), Instant.parse("2026-10-22T05:00:00Z"))

        val (_, utcBinds) = sql(NotificationRules.windowCriterion(datetimeRule, today, utc))
        assertThat(utcBinds.map { (it as OffsetDateTime).toInstant() })
            .containsExactly(Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"))
    }

    @Test
    fun `a DATETIME counts its date in the zone`() {
        // 22:00 in Lima on the 21st, already the 22nd in UTC
        val late = "2026-10-22T03:00:00Z"
        val inLima = evaluate(mapOf("vence_en" to late, "codigo" to "T-1"), datetimeRule, lima)
        assertThat(inLima?.kind).isEqualTo(NotificationKind.WARNING)
        assertThat(evaluate(mapOf("vence_en" to late), datetimeRule, utc)).isNull()
    }

    // ---- stages ----

    @Test
    fun `the kind is the stage with the largest fromDays not after the offset`() {
        val kinds = listOf(-16L, -15L, -1L, 0L, 3L, 4L).map { NotificationRules.stageFor(rule, it) }
        assertThat(kinds).containsExactly(
            null,
            NotificationKind.WARNING,
            NotificationKind.WARNING,
            NotificationKind.ACTION,
            NotificationKind.ACTION,
            null
        )
    }

    @Test
    fun `stage order in the definition does not matter`() {
        val reversed = rule.copy(stages = rule.stages.reversed())
        assertThat(NotificationRules.stageFor(reversed, -1)).isEqualTo(NotificationKind.WARNING)
        assertThat(NotificationRules.stageFor(reversed, 1)).isEqualTo(NotificationKind.ACTION)
    }

    // ---- due ----

    @Test
    fun `a DATE is due at the start of the next day in the zone, a DATETIME at its instant`() {
        assertThat(NotificationRules.dueAt(FieldType.DATE, LocalDate.parse("2026-10-06"), lima)).isEqualTo(Instant.parse("2026-10-07T05:00:00Z"))
        assertThat(NotificationRules.dueAt(FieldType.DATE, "2026-10-06", utc)).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"))
        assertThat(NotificationRules.dueAt(FieldType.DATETIME, "2026-10-06T15:30:00Z", lima)).isEqualTo(Instant.parse("2026-10-06T15:30:00Z"))
        assertThat(NotificationRules.dueAt(FieldType.DATETIME, OffsetDateTime.parse("2026-10-06T10:30:00-05:00"), utc))
            .isEqualTo(Instant.parse("2026-10-06T15:30:00Z"))
    }

    // ---- evaluate ----

    @Test
    fun `a record in the window gives a draft`() {
        val draft = evaluate(tasa("2026-10-16"))!!
        assertThat(draft.kind).isEqualTo(NotificationKind.WARNING)
        assertThat(draft.key).isEqualTo(recordId.toString())
        assertThat(draft.link).isEqualTo(NotificationLink.Record("tasa", recordId, "VIGENCIA"))
        assertThat(draft.audience).containsExactly(Audience.Role("TESORERIA"))
        assertThat(draft.dueAt).isEqualTo(Instant.parse("2026-10-17T05:00:00Z"))
        assertThat(draft.title).isEqualTo("La tasa T-1 vence el 16/10/2026")
        assertThat(draft.body).isEqualTo("Quedan 10 días.")
    }

    @Test
    fun `days go negative once the date has passed`() {
        val draft = evaluate(tasa(LocalDate.parse("2026-10-04")))!!
        assertThat(draft.kind).isEqualTo(NotificationKind.ACTION)
        assertThat(draft.body).isEqualTo("Quedan -2 días.")
    }

    @Test
    fun `a record out of the window or without a date gives nothing`() {
        assertThat(evaluate(tasa("2026-10-22"))).isNull()
        assertThat(evaluate(tasa("2026-10-02"))).isNull()
        assertThat(evaluate(tasa(null))).isNull()
        assertThat(evaluate(mapOf("estado" to "VIGENTE"))).isNull()
    }

    @Test
    fun `a date that is not a date is an error the caller logs`() {
        assertThatThrownBy { evaluate(tasa("mañana")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `conditions EQ, EMPTY and NOT_EMPTY are checked against the values`() {
        assertThat(evaluate(tasa("2026-10-06", estado = "ANULADA"))).isNull()
        assertThat(evaluate(tasa("2026-10-06", estado = null))).isNull()
        assertThat(evaluate(tasa("2026-10-06", reemplazo = "T-2"))).isNull()
        assertThat(evaluate(tasa("2026-10-06", reemplazo = "  "))).isNotNull()

        val notEmpty = rule.copy(conditions = listOf(RuleCondition("reemplazo", ConditionOp.NOT_EMPTY)))
        assertThat(evaluate(tasa("2026-10-06", reemplazo = "T-2"), notEmpty)).isNotNull()
        assertThat(evaluate(tasa("2026-10-06", reemplazo = null), notEmpty)).isNull()
    }

    @Test
    fun `EQ compares values, not their spelling`() {
        val decimal = rule.copy(conditions = listOf(RuleCondition("monto", ConditionOp.EQ, "10.5")))
        assertThat(evaluate(tasa("2026-10-06") + ("monto" to BigDecimal("10.50")), decimal)).isNotNull()
        assertThat(evaluate(tasa("2026-10-06") + ("monto" to BigDecimal("10.51")), decimal)).isNull()

        val integer = rule.copy(conditions = listOf(RuleCondition("cuotas", ConditionOp.EQ, "3")))
        assertThat(evaluate(tasa("2026-10-06") + ("cuotas" to 3L), integer)).isNotNull()
        assertThat(evaluate(tasa("2026-10-06") + ("cuotas" to 3), integer)).isNotNull()
    }

    // ---- templates ----

    @Test
    fun `templates print DATE and DATETIME with the pattern, the object label, and null as empty`() {
        val r =
            datetimeRule.copy(
                title = "{{object}} {{codigo}}: {{vence_en}} / {{date}} / {{vigencia_hasta}}",
                body = "[{{reemplazo}}] {{ monto }}"
            )
        val draft =
            evaluate(
                mapOf(
                    "vence_en" to "2026-10-07T03:15:00Z",
                    "vigencia_hasta" to "2026-12-31",
                    "codigo" to "T-1",
                    "reemplazo" to null,
                    "monto" to BigDecimal("12.50")
                ),
                r
            )!!
        assertThat(draft.title).isEqualTo("Tasa T-1: 06/10/2026 22:15 / 06/10/2026 / 31/12/2026")
        assertThat(draft.body).isEqualTo("[] 12.50")
        assertThat(draft.dueAt).isEqualTo(Instant.parse("2026-10-07T03:15:00Z"))
    }

    @Test
    fun `a long title is cut at 200 characters with an ellipsis`() {
        val r = rule.copy(title = "{{codigo}}")
        val draft = evaluate(tasa("2026-10-06", codigo = "x".repeat(300)), r)!!
        assertThat(draft.title).hasSize(200).endsWith("x…")
    }

    @Test
    fun `a title that renders blank falls back to the rule label, a blank body to none`() {
        val r = rule.copy(title = "{{codigo}}", body = "{{reemplazo}}")
        val draft = evaluate(tasa("2026-10-06", codigo = null), r)!!
        assertThat(draft.title).isEqualTo("Tasas por vencer")
        assertThat(draft.body).isNull()
    }

    // ---- criteria and filters ----

    @Test
    fun `EQ conditions become field filters, EMPTY and NOT_EMPTY become criteria`() {
        val r = rule.copy(conditions = rule.conditions + RuleCondition("cuotas", ConditionOp.NOT_EMPTY))
        assertThat(NotificationRules.filters(r)).isEqualTo(mapOf("estado" to "VIGENTE"))
        val criteria = NotificationRules.conditionCriteria(r).map { sql(it) }
        assertThat(criteria.map { it.first }).containsExactly(
            "\"c_reemplazo\" IS NULL OR btrim(\"c_reemplazo\") = ''",
            "\"c_cuotas\" IS NOT NULL"
        )
        assertThat(criteria.flatMap { it.second }).isEmpty()
    }

    @Test
    fun `the record query reads the window, earliest first, without a count`() {
        val query = NotificationRules.recordQuery(rule, today, lima, 101)
        assertThat(query.page.page).isZero()
        assertThat(query.page.size).isEqualTo(101)
        assertThat(query.sort).isEqualTo("vigencia_hasta")
        assertThat(query.descending).isFalse()
        assertThat(query.count).isFalse()
        assertThat(query.filters).isEqualTo(mapOf("estado" to "VIGENTE"))
        assertThat(query.criteria.map { sql(it).first }).containsExactly(
            "\"vigencia_col\" BETWEEN :c0 AND :c1",
            "\"c_reemplazo\" IS NULL OR btrim(\"c_reemplazo\") = ''"
        )
    }

    // ---- validation ----

    @Test
    fun `the spec's rule passes and comes back normalised`() {
        val check =
            NotificationRules.validate(
                rule.copy(
                    label = "  Tasas por vencer ",
                    stages = rule.stages.reversed(),
                    audience = listOf(AudienceJson("ROLE", " tesoreria ")),
                    tab = " vigencia "
                ),
                definition
            )
        assertThat(check.violations).isEmpty()
        assertThat(check.rule).isEqualTo(rule)
        assertThat(check.orThrow()).isEqualTo(rule)
    }

    @Test
    fun `name and label have a format`() {
        assertThat(violations(rule.copy(name = "Tasa"))).containsExactly("name")
        assertThat(violations(rule.copy(name = "t"))).containsExactly("name")
        assertThat(violations(rule.copy(name = "a" + "b".repeat(49)))).containsExactly("name")
        assertThat(violations(rule.copy(label = " "))).containsExactly("label")
        assertThat(violations(rule.copy(label = "x".repeat(121)))).containsExactly("label")
    }

    @Test
    fun `the field is a DATE or DATETIME of the object`() {
        assertThat(violations(rule.copy(field = "nope"))).containsExactly("field")
        assertThat(violations(rule.copy(field = "codigo"))).containsExactly("field")
        assertThat(violations(datetimeRule)).isEmpty()
    }

    @Test
    fun `stages are one to five distinct offsets within a year`() {
        assertThat(violations(rule.copy(stages = emptyList()))).containsExactly("stages")
        assertThat(violations(rule.copy(stages = (-5..0).map { RuleStage(it, NotificationKind.INFO) }))).containsExactly("stages")
        assertThat(violations(rule.copy(stages = listOf(RuleStage(-366, NotificationKind.INFO))))).containsExactly("stages[0].fromDays")
        assertThat(violations(rule.copy(stages = listOf(RuleStage(-1, NotificationKind.INFO), RuleStage(-1, NotificationKind.ACTION)))))
            .containsExactly("stages[1].fromDays")
    }

    @Test
    fun `untilDays is within a year and reaches every stage`() {
        assertThat(violations(rule.copy(untilDays = 366))).containsExactly("untilDays")
        assertThat(violations(rule.copy(untilDays = -1))).containsExactly("stages[1].fromDays")
        assertThat(violations(rule.copy(untilDays = -16))).containsExactly("stages[0].fromDays", "stages[1].fromDays", "untilDays")
    }

    @Test
    fun `conditions name fields of the object, and EQ values pass the field's codec`() {
        fun with(vararg c: RuleCondition) = violations(rule.copy(conditions = c.toList()))
        assertThat(with(RuleCondition("nope", ConditionOp.EMPTY))).containsExactly("conditions[0].field")
        assertThat(with(RuleCondition("geom", ConditionOp.EMPTY))).containsExactly("conditions[0].field")
        assertThat(with(RuleCondition("estado", ConditionOp.EQ, "OTRO"))).containsExactly("conditions[0].value")
        assertThat(with(RuleCondition("cuotas", ConditionOp.EQ, "tres"))).containsExactly("conditions[0].value")
        assertThat(with(RuleCondition("estado", ConditionOp.EQ))).containsExactly("conditions[0].value")
        assertThat(with(RuleCondition("reemplazo", ConditionOp.EMPTY, "x"))).containsExactly("conditions[0].value")
        assertThat(with(RuleCondition("estado", ConditionOp.EQ, "VIGENTE"), RuleCondition("estado", ConditionOp.EQ, "ANULADA")))
            .containsExactly("conditions[1].field")
        assertThat(with(RuleCondition("cuotas", ConditionOp.EQ, "3"), RuleCondition("vigencia_hasta", ConditionOp.EQ, "2026-10-06"))).isEmpty()
    }

    @Test
    fun `the audience is required and well formed`() {
        assertThat(violations(rule.copy(audience = emptyList()))).containsExactly("audience")
        assertThat(violations(rule.copy(audience = listOf(AudienceJson("ALL"), AudienceJson("USER", "x"))))).containsExactly("audience[1]")
        assertThat(violations(rule.copy(audience = listOf(AudienceJson("ROLE", "no válido"))))).containsExactly("audience[0]")
    }

    @Test
    fun `title, body and tab have their limits`() {
        assertThat(violations(rule.copy(title = " "))).containsExactly("title")
        assertThat(violations(rule.copy(title = "x".repeat(201)))).containsExactly("title")
        assertThat(violations(rule.copy(body = "x".repeat(4001)))).containsExactly("body")
        assertThat(violations(rule.copy(tab = "no-tab"))).containsExactly("tab")
    }

    @Test
    fun `an unknown placeholder is refused`() {
        assertThat(violations(rule.copy(title = "{{codgo}} vence"))).containsExactly("title")
        assertThat(violations(rule.copy(body = "{{geom}}"))).containsExactly("body")
        assertThat(violations(rule.copy(body = "{{}}"))).containsExactly("body")
        assertThat(violations(rule.copy(title = "{{ object }} {{days}} {{date}} {{vence_en}}"))).isEmpty()
    }

    @Test
    fun `orThrow names every problem`() {
        assertThatThrownBy { NotificationRules.validate(rule.copy(name = "X", field = "nope"), definition).orThrow() }
            .isInstanceOf(ValidationException::class.java)
            .extracting { (it as ValidationException).violations.map { v -> v.field } }
            .isEqualTo(listOf("name", "field"))
    }
}
