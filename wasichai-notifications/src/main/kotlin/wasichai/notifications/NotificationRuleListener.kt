package wasichai.notifications

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectRemovalListener
import java.time.Clock

/**
 * Keeps a rule's notification of one record in step with the record (spec C): a renewed licence stops saying
 * "vence" at once, not at the next run. The changed record is judged by `change.after`, the evaluator the run
 * uses; `DELETED` resolves. An unreadable value is logged and skipped; a SQL error propagates (the writer's
 * transaction is aborted anyway). Last in line: after automation.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
class NotificationRuleListener(
    private val rules: NotificationRuleRepository,
    private val metadata: MetadataService,
    private val runner: RuleNotifications,
    private val writer: NotificationWriter,
    private val clock: Clock
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        // one indexed read per write; most objects have no rule
        val enabled = rules.enabledByObject(change.organizationId, change.objectId)
        if (enabled.isEmpty()) return
        val organizationId = change.organizationId
        val key = change.recordId.toString()
        if (change.kind == RecordChangeKind.DELETED) {
            enabled.forEach { writer.resolve(organizationId, Sources.rule(it.rule.name), key) }
            return
        }
        val after = change.after ?: return
        val definition = metadata.loadDefinitionById(organizationId, change.objectId)
        val now = clock.instant()
        enabled.forEach { stored ->
            val source = Sources.rule(stored.rule.name)
            val prepared =
                try {
                    runner.prepareOne(organizationId, stored.rule, definition, change.recordId, after, now)
                } catch (e: IllegalArgumentException) {
                    skipped(change, stored, e)
                    return@forEach
                } catch (e: IllegalStateException) {
                    skipped(change, stored, e)
                    return@forEach
                }
            if (prepared == null) writer.resolve(organizationId, source, key) else writer.publish(organizationId, source, prepared, null)
        }
    }

    private fun skipped(
        change: RecordChange,
        stored: StoredRule,
        e: RuntimeException
    ) {
        log.warn(
            "organization {}: notification rule '{}' skipped record {} of {}: {}",
            change.organizationId,
            stored.rule.name,
            change.recordId,
            change.objectName,
            e.message
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NotificationRuleListener::class.java)
    }
}

// deleting an object cascades its rules: what they published is resolved first, in the same transaction, or it would
// stay open with nobody left to resolve it. a bean of its own: MetadataService asks for removal listeners when it is
// built, and the record listener needs MetadataService.
class NotificationRuleCleanup(
    private val rules: NotificationRuleRepository,
    private val writer: NotificationWriter
) : ObjectRemovalListener {
    override suspend fun objectRemoved(obj: CustomObject) {
        rules.listByObject(obj.organizationId, obj.id).forEach { writer.resolveAll(obj.organizationId, Sources.rule(it.rule.name)) }
    }
}
