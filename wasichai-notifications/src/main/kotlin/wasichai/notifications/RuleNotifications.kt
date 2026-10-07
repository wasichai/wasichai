package wasichai.notifications

import org.slf4j.LoggerFactory
import wasichai.core.data.RecordStore
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One date rule in one organization (spec C). The run reads the window's records outside any transaction,
 * then reconciles once: the only transactional step. The record listener shares [prepareOne], so a write
 * and a run agree on every record.
 *
 * Rows come from the [RecordStore] port with the whole definition: what `RecordService.asPlatform { rows }`
 * reads, minus the platform marker. POST, PUT and `run` run a rule inside a request, where `asPlatform` throws.
 */
class RuleNotifications(
    private val store: RecordStore,
    private val preparer: NotificationPreparer,
    private val writer: NotificationWriter,
    // the zone days are counted in. the auto-configuration settles it once (property, app clock, system)
    val zone: ZoneId,
    private val datePattern: String,
    // records per rule and organization
    private val cap: Int
) {
    suspend fun run(
        organizationId: UUID,
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition,
        now: Instant
    ): ReconcileResult {
        val source = Sources.rule(rule.name)
        // saving checks the field, and core refuses to delete one a rule reads: a stale definition is a bug. once, not per record
        if (!NotificationRules.readsDateField(rule, definition)) {
            notADateField(organizationId, rule, definition)
            return ReconcileResult(0, 0, 0, 0)
        }
        val today = today(now)
        // one more than the cap tells "full" from "more than allowed"
        val found = store.query(definition, organizationId, NotificationRules.recordQuery(rule, today, zone, cap + 1)).content
        if (found.size > cap) {
            log.warn(
                "organization {}: notification rule '{}' has more than {} records in its window; the earliest {} are notified",
                organizationId,
                rule.name,
                cap,
                cap
            )
        }
        val prepared =
            found.take(cap).mapNotNull { row ->
                val draft = evaluate(organizationId, rule, definition, row.id, row.attributes, today) ?: return@mapNotNull null
                preparer.prepare(organizationId, source, draft, now, strict = false, allowReservedSource = true)
            }
        // now is when the read began: a notification written after it saw newer records, and stays as it is
        return writer.reconcile(organizationId, source, prepared, readStart = now)
    }

    // one record, lenient: null when it is out of the window, fails a condition, or reaches nobody.
    // a value the evaluator cannot read is an IllegalArgumentException or IllegalStateException
    suspend fun prepareOne(
        organizationId: UUID,
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition,
        recordId: UUID,
        values: Map<String, Any?>,
        now: Instant
    ): PreparedNotification? {
        val draft = NotificationRules.evaluate(rule, definition, recordId, values, today(now), zone, datePattern) ?: return null
        return preparer.prepare(organizationId, Sources.rule(rule.name), draft, now, strict = false, allowReservedSource = true)
    }

    private fun today(now: Instant): LocalDate = LocalDate.ofInstant(now, zone)

    internal fun notADateField(
        organizationId: UUID,
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition
    ) {
        log.warn(
            "organization {}: notification rule '{}' skipped: '{}' is not a DATE or DATETIME field of '{}'",
            organizationId,
            rule.name,
            rule.field,
            definition.obj.name
        )
    }

    // one unreadable record never stops the others: logged, left out (so a notification of it resolves)
    private fun evaluate(
        organizationId: UUID,
        rule: NotificationRuleDefinition,
        definition: ObjectDefinition,
        recordId: UUID,
        values: Map<String, Any?>,
        today: LocalDate
    ): NotificationDraft? =
        try {
            NotificationRules.evaluate(rule, definition, recordId, values, today, zone, datePattern)
        } catch (e: IllegalArgumentException) {
            log.warn("organization {}: notification rule '{}' skipped record {}: {}", organizationId, rule.name, recordId, e.message)
            null
        }

    private companion object {
        private val log = LoggerFactory.getLogger(RuleNotifications::class.java)
    }
}

// the loop's `rules` item: every enabled rule of the organization, each object's definition read once.
// one failing rule is logged; the others still run.
internal class RulesWork(
    // the organization's enabled rules
    private val enabled: suspend (UUID) -> List<StoredRule>,
    // (organization, object id) -> the object's definition
    private val definitionOf: suspend (UUID, UUID) -> ObjectDefinition,
    private val runRule: suspend (UUID, NotificationRuleDefinition, ObjectDefinition, Instant) -> ReconcileResult,
    override val interval: Duration
) : LoopWork {
    constructor(
        rules: NotificationRuleRepository,
        metadata: MetadataService,
        runner: RuleNotifications,
        interval: Duration
    ) : this(rules::enabledAll, metadata::loadDefinitionById, runner::run, interval)

    override val key: String = KEY

    init {
        require(!interval.isNegative && !interval.isZero) { "wasichai.notifications.rule-interval must be positive, was $interval" }
    }

    override suspend fun run(
        organizationId: UUID,
        now: Instant
    ) {
        enabled(organizationId).groupBy { it.objectId }.forEach { (objectId, stored) ->
            // read once per object, inside each rule's try: an object that does not load fails its own rules, not the rest
            var definition: ObjectDefinition? = null
            stored.forEach { rule ->
                logged({
                    val loaded = definition ?: definitionOf(organizationId, objectId).also { definition = it }
                    val result = runRule(organizationId, rule.rule, loaded, now)
                    if (result.changed) log.debug("notification rule {} organization {}: {}", rule.rule.name, organizationId, result)
                }) { e -> log.warn("Notification rule '{}' failed for organization {}: {}", rule.rule.name, organizationId, e.message, e) }
            }
        }
    }

    override fun toString(): String = "notification rules"

    companion object {
        const val KEY = Sources.RULES

        private val log = LoggerFactory.getLogger(RulesWork::class.java)
    }
}
