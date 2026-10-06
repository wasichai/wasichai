package wasichai.notifications

import com.fasterxml.jackson.annotation.JsonInclude
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldValueCodec
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// ---- a date rule (spec C). the REST body and the jsonb `definition` column hold this whole shape.
// name, label and enabled also live in columns (unique name, cheap filter); the columns win on read. ----

@JsonInclude(JsonInclude.Include.NON_NULL)
data class NotificationRuleDefinition(
    val name: String,
    val label: String,
    val enabled: Boolean = true,
    // a DATE or DATETIME field of the object
    val field: String,
    val stages: List<RuleStage>,
    val untilDays: Int,
    val conditions: List<RuleCondition> = emptyList(),
    val audience: List<AudienceJson>,
    val title: String,
    val body: String? = null,
    val tab: String? = null
)

// from `fromDays` days after the date (negative: before it) the notification has this kind
data class RuleStage(
    val fromDays: Int,
    val kind: NotificationKind
)

enum class ConditionOp { EQ, EMPTY, NOT_EMPTY }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RuleCondition(
    val field: String,
    val op: ConditionOp,
    // EQ only
    val value: String? = null
)

// record dates in [from, to] are in the window, both ends included
data class DateWindow(
    val from: LocalDate,
    val to: LocalDate
)

data class RuleCheck(
    // normalised; null when anything is wrong
    val rule: NotificationRuleDefinition?,
    val violations: List<FieldViolation>
) {
    fun orThrow(): NotificationRuleDefinition = rule ?: throw ValidationException(MESSAGE, violations)

    companion object {
        const val MESSAGE = "Invalid notification rule"
    }
}

// pure: no database, no beans. the scheduled run and the record listener share it, so both agree.
object NotificationRules {
    const val LABEL_MAX = 120
    const val STAGES_MAX = 5
    const val CONDITIONS_MAX = 10
    const val DAYS_MAX = 365

    private val NAME = Regex("^[a-z][a-z0-9_]{1,48}$")
    private val PLACEHOLDER = Regex("""\{\{([^{}]*)}}""")

    private const val DAYS = "days"
    private const val DATE = "date"
    private const val OBJECT = "object"

    // these win over a field of the same name
    private val BUILT_INS = setOf(DAYS, DATE, OBJECT)

    private val DATE_TYPES = setOf(FieldType.DATE, FieldType.DATETIME)

    // core types only: their values travel in the record's attributes and FieldValueCodec reads them.
    // a module type (geometry, files) lives in a section the evaluator never sees.
    private val CORE_TYPES =
        setOf(
            FieldType.TEXT,
            FieldType.LONG_TEXT,
            FieldType.INTEGER,
            FieldType.DECIMAL,
            FieldType.BOOLEAN,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.ENUM,
            FieldType.EMAIL,
            FieldType.URL,
            FieldType.UUID,
            FieldType.RELATION
        )

    // text columns: blank counts as empty, as it does in evaluate
    private val TEXT_TYPES = setOf(FieldType.TEXT, FieldType.LONG_TEXT, FieldType.ENUM, FieldType.EMAIL, FieldType.URL)

    // ---- validation (on save). the audience's existence needs the database: the caller checks it. ----

    fun validate(
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition
    ): RuleCheck {
        val violations = mutableListOf<FieldViolation>()
        val fields = definition.fields.associateBy { it.name }

        if (!NAME.matches(rule.name)) violations += FieldViolation("name", "must match ${NAME.pattern}")
        val label = rule.label.trim()
        if (label.charCount() !in 1..LABEL_MAX) violations += FieldViolation("label", "must be 1 to $LABEL_MAX characters")

        val dateField = fields[rule.field]
        if (dateField == null || dateField.type !in DATE_TYPES) {
            violations += FieldViolation("field", "must be a DATE or DATETIME field of '${definition.obj.name}'")
        }

        checkDays(rule, violations)
        checkConditions(rule.conditions, fields, definition, violations)
        val audience = checkAudience(rule.audience, violations)

        val title = rule.title.trim()
        if (title.charCount() !in 1..NotificationValidation.TITLE_MAX) {
            violations += FieldViolation("title", "must be 1 to ${NotificationValidation.TITLE_MAX} characters")
        }
        checkPlaceholders("title", title, fields, violations)
        val body = rule.body?.takeIf { it.isNotBlank() }
        if (body != null && body.charCount() > NotificationValidation.BODY_MAX) {
            violations += FieldViolation("body", "must be at most ${NotificationValidation.BODY_MAX} characters")
        }
        body?.let { checkPlaceholders("body", it, fields, violations) }

        // shared tab format; it names its violation link.tab
        val tabViolations = mutableListOf<FieldViolation>()
        val tab = NotificationValidation.normalizeTab(rule.tab, tabViolations)
        violations += tabViolations.map { it.copy(field = "tab") }

        if (violations.isNotEmpty()) return RuleCheck(null, violations)
        val normalized =
            rule.copy(
                label = label,
                stages = rule.stages.sortedBy { it.fromDays },
                audience = audience.map { it.toJson() },
                title = title,
                body = body,
                tab = tab
            )
        return RuleCheck(normalized, emptyList())
    }

    private fun checkDays(
        rule: NotificationRuleDefinition,
        violations: MutableList<FieldViolation>
    ) {
        val range = -DAYS_MAX..DAYS_MAX
        if (rule.stages.size !in 1..STAGES_MAX) {
            violations += FieldViolation("stages", "must hold 1 to $STAGES_MAX stages")
        } else {
            val seen = mutableSetOf<Int>()
            rule.stages.forEachIndexed { i, stage ->
                val path = "stages[$i].fromDays"
                when {
                    stage.fromDays !in range -> violations += FieldViolation(path, "must be between -$DAYS_MAX and $DAYS_MAX")
                    !seen.add(stage.fromDays) -> violations += FieldViolation(path, "must differ from the other stages")
                    // a stage past the window's end is never reached
                    stage.fromDays > rule.untilDays -> violations += FieldViolation(path, "must be at most untilDays")
                }
            }
        }
        if (rule.untilDays !in range) {
            violations += FieldViolation("untilDays", "must be between -$DAYS_MAX and $DAYS_MAX")
        } else if (rule.stages.isNotEmpty() && rule.untilDays < rule.stages.minOf { it.fromDays }) {
            violations += FieldViolation("untilDays", "must be at least the earliest stage's fromDays")
        }
    }

    private fun checkConditions(
        conditions: List<RuleCondition>,
        fields: Map<String, CustomField>,
        definition: ObjectDefinition,
        violations: MutableList<FieldViolation>
    ) {
        if (conditions.size > CONDITIONS_MAX) {
            violations += FieldViolation("conditions", "at most $CONDITIONS_MAX conditions")
            return
        }
        val equalities = mutableSetOf<String>()
        conditions.forEachIndexed { i, condition ->
            val field = fields[condition.field]?.takeIf { it.type in CORE_TYPES }
            if (field == null) {
                violations += FieldViolation("conditions[$i].field", "must be a field of '${definition.obj.name}' with a core type")
                return@forEachIndexed
            }
            val valuePath = "conditions[$i].value"
            when (condition.op) {
                ConditionOp.EQ -> {
                    // filters are a map: two EQ on one field could never both hold anyway
                    if (!equalities.add(field.name)) {
                        violations += FieldViolation("conditions[$i].field", "has an EQ condition already")
                    } else if (condition.value == null) {
                        violations += FieldViolation(valuePath, "is required for EQ")
                    } else {
                        // the same codec the record list applies to a filter
                        runCatching { FieldValueCodec.toDatabase(field.copy(required = false), condition.value) }
                            .onFailure { e ->
                                val reason = (e as? ValidationException)?.violations?.firstOrNull()?.message ?: "is not a valid value"
                                violations += FieldViolation(valuePath, reason)
                            }
                    }
                }
                ConditionOp.EMPTY, ConditionOp.NOT_EMPTY ->
                    if (condition.value != null) violations += FieldViolation(valuePath, "must be absent for ${condition.op}")
            }
        }
    }

    // syntax and format only (strict REST shape, then the shared normalisation)
    private fun checkAudience(
        audience: List<AudienceJson>,
        violations: MutableList<FieldViolation>
    ): List<Audience> {
        val before = violations.size
        val parsed = audience.mapIndexedNotNull { i, json -> json.toAudience("audience[$i]", violations) }
        if (violations.size > before) return emptyList()
        return NotificationValidation.normalizeAudience(parsed, violations)
    }

    private fun checkPlaceholders(
        path: String,
        template: String,
        fields: Map<String, CustomField>,
        violations: MutableList<FieldViolation>
    ) {
        val unknown =
            PLACEHOLDER
                .findAll(template)
                .map { it.groupValues[1].trim() }
                .filter { it !in BUILT_INS && fields[it]?.type !in CORE_TYPES }
                .distinct()
                .toList()
        if (unknown.isNotEmpty()) {
            violations += FieldViolation(path, "unknown placeholder ${unknown.joinToString { "{{$it}}" }}")
        }
    }

    // every field the rule reads: its date, its conditions, its placeholders. a built-in wins over a field of its name.
    fun fieldsRead(rule: NotificationRuleDefinition): Set<String> =
        buildSet {
            add(rule.field)
            rule.conditions.forEach { add(it.field) }
            listOfNotNull(rule.title, rule.body).forEach { template ->
                PLACEHOLDER.findAll(template).map { it.groupValues[1].trim() }.filterTo(this) { it !in BUILT_INS }
            }
        }

    // ---- window and stage. offset = today - the record's date, in days. ----

    fun window(
        rule: NotificationRuleDefinition,
        today: LocalDate
    ): DateWindow = DateWindow(today.minusDays(rule.untilDays.toLong()), today.minusDays(rule.stages.minOf { it.fromDays }.toLong()))

    // the latest stage already started; null outside the window
    fun stageFor(
        rule: NotificationRuleDefinition,
        offset: Long
    ): NotificationKind? {
        if (offset > rule.untilDays) return null
        return rule.stages
            .filter { it.fromDays <= offset }
            .maxByOrNull { it.fromDays }
            ?.kind
    }

    // DATE: the day still counts, due at the start of the next. DATETIME: at the instant.
    fun dueAt(
        type: FieldType,
        value: Any,
        zone: ZoneId
    ): Instant =
        when (type) {
            FieldType.DATE -> localDate(value).plusDays(1).atStartOfDay(zone).toInstant()
            FieldType.DATETIME -> instant(value)
            else -> throw IllegalArgumentException("a rule's date field is DATE or DATETIME, not $type")
        }

    // ---- the query of a run: every record in the window that meets the conditions ----

    // the window on the quoted column, bounds bound. DATETIME: the zone's days, as instants.
    fun windowCriterion(
        rule: NotificationRuleDefinition,
        today: LocalDate,
        zone: ZoneId
    ): RecordCriterion {
        val window = window(rule, today)
        return RecordCriterion { definition, bind ->
            val field = dateField(rule, definition)
            val column = SqlIdentifier.quote(field.columnName)
            if (field.type == FieldType.DATE) {
                "$column BETWEEN ${bind(window.from)} AND ${bind(window.to)}"
            } else {
                val from =
                    window.from
                        .atStartOfDay(zone)
                        .toOffsetDateTime()
                        .withOffsetSameInstant(ZoneOffset.UTC)
                val to =
                    window.to
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .toOffsetDateTime()
                        .withOffsetSameInstant(ZoneOffset.UTC)
                "$column >= ${bind(from)} AND $column < ${bind(to)}"
            }
        }
    }

    // EQ conditions: RecordQuery.filters, by field name. the store parses each with the field's codec.
    fun filters(rule: NotificationRuleDefinition): Map<String, String> =
        rule.conditions.filter { it.op == ConditionOp.EQ }.associate { it.field to requireNotNull(it.value) { "an EQ condition has a value" } }

    // EMPTY / NOT_EMPTY: one criterion each. no values, so nothing to bind.
    fun conditionCriteria(rule: NotificationRuleDefinition): List<RecordCriterion> =
        rule.conditions
            .filter { it.op != ConditionOp.EQ }
            .map { condition ->
                RecordCriterion { definition, _ ->
                    val field =
                        definition.fields.firstOrNull { it.name == condition.field }
                            ?: throw IllegalStateException("rule reads '${condition.field}', which '${definition.obj.name}' no longer has")
                    val column = SqlIdentifier.quote(field.columnName)
                    val text = field.type in TEXT_TYPES
                    when {
                        condition.op == ConditionOp.EMPTY && text -> "$column IS NULL OR btrim($column) = ''"
                        condition.op == ConditionOp.EMPTY -> "$column IS NULL"
                        text -> "$column IS NOT NULL AND btrim($column) <> ''"
                        else -> "$column IS NOT NULL"
                    }
                }
            }

    // earliest dates first. limit: the caller's cap (plus one to notice more).
    fun recordQuery(
        rule: NotificationRuleDefinition,
        today: LocalDate,
        zone: ZoneId,
        limit: Int
    ): RecordQuery =
        RecordQuery(
            page = PageRequest(0, limit),
            sort = rule.field,
            filters = filters(rule),
            criteria = listOf(windowCriterion(rule, today, zone)) + conditionCriteria(rule),
            count = false
        )

    // ---- one record ----

    // values: a record's attributes (RecordRow.attributes or RecordChange.after), by field name.
    // null: out of the window, no date, or a condition fails. a malformed date throws IllegalArgumentException.
    fun evaluate(
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition,
        recordId: UUID,
        values: Map<String, Any?>,
        today: LocalDate,
        zone: ZoneId,
        datePattern: String
    ): NotificationDraft? {
        val field = dateField(rule, definition)
        val raw = values[field.name] ?: return null
        val date = dateIn(field.type, raw, zone)
        val kind = stageFor(rule, ChronoUnit.DAYS.between(date, today)) ?: return null
        if (!rule.conditions.all { holds(it, definition, values) }) return null

        val dates = DateTimeFormatter.ofPattern(datePattern)
        val times = DateTimeFormatter.ofPattern("$datePattern HH:mm")
        val fields = definition.fields.associateBy { it.name }

        fun render(template: String): String =
            PLACEHOLDER.replace(template) { match ->
                when (val name = match.groupValues[1].trim()) {
                    DAYS -> ChronoUnit.DAYS.between(today, date).toString()
                    DATE -> dates.format(date)
                    OBJECT -> definition.obj.label
                    else -> fields[name]?.let { printValue(it, values[name], zone, dates, times) }.orEmpty()
                }
            }

        val title = render(rule.title).trim().ifEmpty { rule.label }
        val body = rule.body?.let { render(it).trim() }?.takeIf { it.isNotEmpty() }
        return NotificationDraft(
            kind = kind,
            title = title.cut(NotificationValidation.TITLE_MAX),
            audience = rule.audience.toAudiences(),
            body = body?.cut(NotificationValidation.BODY_MAX),
            link = NotificationLink.Record(definition.obj.name, recordId, rule.tab),
            key = recordId.toString(),
            dueAt = dueAt(field.type, raw, zone)
        )
    }

    private fun dateField(
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition
    ): CustomField =
        definition.fields.firstOrNull { it.name == rule.field && it.type in DATE_TYPES }
            ?: throw IllegalStateException("rule '${rule.name}' reads '${rule.field}', not a date field of '${definition.obj.name}'")

    private fun holds(
        condition: RuleCondition,
        definition: ObjectDefinition,
        values: Map<String, Any?>
    ): Boolean {
        val value = values[condition.field]
        val empty = value == null || (value is String && value.isBlank())
        return when (condition.op) {
            ConditionOp.EMPTY -> empty
            ConditionOp.NOT_EMPTY -> !empty
            ConditionOp.EQ -> {
                if (empty) return false
                val field =
                    definition.fields.firstOrNull { it.name == condition.field }
                        ?: throw IllegalStateException("rule reads '${condition.field}', which '${definition.obj.name}' no longer has")
                val expected = parse(field, condition.value) ?: return false
                val actual = parse(field, value) ?: return false
                same(expected, actual)
            }
        }
    }

    // both sides through the codec, so "10.5" meets 10.50 and "3" meets 3L. unreadable: no match.
    private fun parse(
        field: CustomField,
        value: Any?
    ): Any? {
        val plain = if (value is Instant) value.toString() else FieldValueCodec.fromDatabase(value)
        return runCatching { FieldValueCodec.toDatabase(field.copy(required = false), plain) }.getOrNull()
    }

    private fun same(
        a: Any,
        b: Any
    ): Boolean =
        when {
            a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
            a is OffsetDateTime && b is OffsetDateTime -> a.isEqual(b)
            else -> a == b
        }

    private fun printValue(
        field: CustomField,
        value: Any?,
        zone: ZoneId,
        dates: DateTimeFormatter,
        times: DateTimeFormatter
    ): String {
        if (value == null) return ""
        // a value that does not read as its type prints as it came
        return runCatching {
            when (field.type) {
                FieldType.DATE -> dates.format(localDate(value))
                FieldType.DATETIME -> times.format(instant(value).atZone(zone))
                else -> if (value is BigDecimal) value.toPlainString() else value.toString()
            }
        }.getOrElse { value.toString() }
    }

    private fun dateIn(
        type: FieldType,
        value: Any,
        zone: ZoneId
    ): LocalDate = if (type == FieldType.DATE) localDate(value) else instant(value).atZone(zone).toLocalDate()

    private fun localDate(value: Any): LocalDate =
        when (value) {
            is LocalDate -> value
            is String -> parseOrFail(value) { LocalDate.parse(it) }
            else -> throw IllegalArgumentException("not a date: ${value::class.simpleName}")
        }

    private fun instant(value: Any): Instant =
        when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is String -> parseOrFail(value) { text -> runCatching { OffsetDateTime.parse(text).toInstant() }.getOrElse { Instant.parse(text) } }
            else -> throw IllegalArgumentException("not a date-time: ${value::class.simpleName}")
        }

    private fun <T> parseOrFail(
        text: String,
        parse: (String) -> T
    ): T = runCatching { parse(text.trim()) }.getOrElse { throw IllegalArgumentException("not an ISO date or date-time: '$text'", it) }

    // code points, like NotificationValidation and postgres
    private fun String.charCount(): Int = codePointCount(0, length)

    private fun String.cut(max: Int): String {
        if (charCount() <= max) return this
        return substring(0, offsetByCodePoints(0, max - 1)) + "…"
    }
}
