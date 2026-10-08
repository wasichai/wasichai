package wasichai.automation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.WorkflowStates
import wasichai.core.metadata.FieldDefaults
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.ChangeOrigin

// runs claimed work. no user sits behind this, so nothing here asks CurrentUser anything: an
// automation acts as the platform, inside the organization that owns the record. ADR-016.
@Service
class AutomationRunner(
    private val automations: AutomationRepository,
    private val runs: AutomationRunRepository,
    private val metadata: MetadataService,
    private val store: RecordStore,
    private val workflows: WorkflowStates,
    private val audit: AuditService,
    private val dispatcher: AutomationDispatcher,
    private val webhooks: WebhookSender,
    private val documents: DocumentIssuer,
    private val guards: RecordWriteGuards,
    private val types: FieldTypeRegistry,
    private val notifier: AutomationNotifier = NoAutomationNotifier()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // returns how many runs were taken, so a caller can drain until it comes back empty
    suspend fun drainOnce(batch: Int): Int {
        val claimed = runs.claim(batch)
        claimed.forEach { execute(it) }
        return claimed.size
    }

    private suspend fun execute(run: AutomationRun) {
        val steps = mutableListOf<RunStep>()
        try {
            val automation =
                automations.findById(run.organizationId, run.automationId)
                    ?: throw NotFoundException("Automation ${run.automationId} no longer exists")
            // every write below says automation:<name> and carries the triggering request's id (ADR-050),
            // the document an action issues and the runs it queues included
            ChangeOrigin.within(ChangeOrigin.automation(automation.name), run.correlationId) {
                automation.definition.actions.forEachIndexed { index, action -> steps += perform(automation, run, action, index) }
            }
            runs.finish(run.id, RunStatus.SUCCEEDED, steps, null)
        } catch (e: Exception) {
            // a failed automation is a logged failure, never a failed user request: the write
            // that triggered it committed long ago.
            log.warn("Automation run {} failed: {}", run.id, e.message)
            runs.finish(run.id, RunStatus.FAILED, steps, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun perform(
        automation: Automation,
        run: AutomationRun,
        action: AutomationAction,
        index: Int
    ): RunStep =
        when (action.type) {
            ActionType.UPDATE_FIELD -> updateField(automation, run, action)
            ActionType.CREATE_RECORD -> createRecord(automation, run, action)
            ActionType.WEBHOOK -> webhook(automation, run, action)
            ActionType.GENERATE_DOCUMENT -> generateDocument(run, action)
            ActionType.NOTIFY -> notify(automation, run, action, index)
        }

    // the condition judged the snapshot; the write lands on the row as it is now. reusing the
    // snapshot as the update body would blank whatever changed in between.
    private suspend fun updateField(
        automation: Automation,
        run: AutomationRun,
        action: AutomationAction
    ): RunStep {
        val recordId = run.recordId ?: throw ValidationException("Run has no record", "recordId", "is required")
        val definition = metadata.loadDefinitionById(run.organizationId, run.payload.objectId)
        val field =
            definition.fields.firstOrNull { it.name == action.field }
                ?: throw NotFoundException("Object '${definition.obj.name}' has no field '${action.field}'")
        val workflow = workflows.stateOf(run.organizationId, definition.obj.id)
        val current =
            store.findById(definition, run.organizationId, recordId, null, workflow.attached)
                ?: throw NotFoundException("Record $recordId does not exist")

        val value = AutomationRules.render(action.value.orEmpty(), run.toChange())
        val attributes = current.attributes + (field.name to value)
        // the platform writes (ADR-016), held to appendOnly and every guard like anyone (ADR-040).
        // a refusal fails the run, never the write that triggered it.
        guards.beforeWrite(
            definition,
            RecordWrite(
                organizationId = run.organizationId,
                userId = null,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = recordId,
                kind = RecordChangeKind.UPDATED,
                before = current.attributes,
                attributes = attributes,
                reason = automation.changeReason()
            )
        )
        val updated =
            store.update(
                definition,
                run.organizationId,
                // rows carry created_by/updated_by: the triggering user is the honest answer, null when
                // the platform wrote the record that triggered it (ADR-039). the audit row says platform.
                run.userId,
                recordId,
                attributes,
                // an action writes a field, so every geometry is left exactly as it was
                emptyMap(),
                workflow.attached
            )
        audit.record(
            organizationId = run.organizationId,
            userId = null,
            objectName = definition.obj.name,
            recordId = recordId,
            operation = AuditOperation.UPDATE,
            before = current.attributes,
            after = updated.attributes,
            reason = automation.changeReason()
        )
        // the write is a change like any other, one step deeper and marked with its author
        dispatcher.recordChanged(
            RecordChange(
                organizationId = run.organizationId,
                userId = run.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = recordId,
                kind = RecordChangeKind.UPDATED,
                before = current.attributes,
                after = updated.attributes,
                state = updated.state,
                depth = run.depth + 1,
                causedBy = automation.id
            )
        )
        return RunStep(ActionType.UPDATE_FIELD, "${definition.obj.name}.${field.name} = '$value' on $recordId")
    }

    private suspend fun createRecord(
        automation: Automation,
        run: AutomationRun,
        action: AutomationAction
    ): RunStep {
        val target =
            metadata.loadDefinition(
                run.organizationId,
                action.targetObject ?: throw ValidationException("Action has no target object", "actions", "targetObject is required")
            )
        val change = run.toChange()
        val rendered = action.values.mapValues { (_, template) -> AutomationRules.render(template, change) }
        // what the rule leaves out takes the field's default, as any create does (issue 60)
        val (writable, attributes) = FieldDefaults.applied(target, rendered, types)
        val workflow = workflows.stateOf(run.organizationId, target.obj.id)
        guards.beforeWrite(
            target,
            RecordWrite(
                organizationId = run.organizationId,
                userId = null,
                objectId = target.obj.id,
                objectName = target.obj.name,
                recordId = null,
                kind = RecordChangeKind.CREATED,
                attributes = attributes,
                reason = automation.changeReason()
            )
        )
        val created =
            store.insert(writable, run.organizationId, run.userId, attributes, emptyMap(), workflow)
        audit.record(
            organizationId = run.organizationId,
            userId = null,
            objectName = target.obj.name,
            recordId = created.id,
            operation = AuditOperation.CREATE,
            after = created.attributes,
            reason = automation.changeReason()
        )
        dispatcher.recordChanged(
            RecordChange(
                organizationId = run.organizationId,
                userId = run.userId,
                objectId = target.obj.id,
                objectName = target.obj.name,
                recordId = created.id,
                kind = RecordChangeKind.CREATED,
                after = created.attributes,
                state = created.state,
                depth = run.depth + 1,
                causedBy = automation.id
            )
        )
        return RunStep(ActionType.CREATE_RECORD, "created ${target.obj.name} ${created.id}")
    }

    private suspend fun webhook(
        automation: Automation,
        run: AutomationRun,
        action: AutomationAction
    ): RunStep {
        val url = AutomationRules.render(action.url.orEmpty(), run.toChange())
        val body =
            mapOf(
                "automation" to automation.name,
                "trigger" to run.trigger.name,
                "object" to run.objectName,
                "recordId" to run.recordId?.toString(),
                "state" to run.payload.state,
                "transition" to run.payload.transition,
                "record" to (run.payload.after ?: run.payload.before)
            )
        return RunStep(ActionType.WEBHOOK, webhooks.post(url, body))
    }

    // to, title and body take {{field}} like the other actions; the notifications module resolves who that is
    private suspend fun notify(
        automation: Automation,
        run: AutomationRun,
        action: AutomationAction,
        index: Int
    ): RunStep {
        val change = run.toChange()
        val detail =
            notifier.notify(
                NotifyRequest(
                    organizationId = run.organizationId,
                    automation = automation.name,
                    objectName = run.objectName,
                    recordId = run.recordId,
                    actionIndex = index,
                    to = AutomationRules.render(action.to.orEmpty(), change),
                    kind = action.kind ?: "INFO",
                    title = AutomationRules.render(action.title.orEmpty(), change),
                    body = action.body?.let { AutomationRules.render(it, change) }
                )
            )
        return RunStep(ActionType.NOTIFY, detail)
    }

    // issuedBy stays null: the platform issues it, same as the audit row for every other action
    // here (ADR-016 -- no user sits behind a queued run). run.userId exists for depth tracking,
    // not for putting a name on what the automation does.
    private suspend fun generateDocument(
        run: AutomationRun,
        action: AutomationAction
    ): RunStep {
        val recordId = run.recordId ?: throw ValidationException("Run has no record", "recordId", "is required")
        val typeName = action.documentType ?: throw ValidationException("Action has no document type", "documentType", "is required")
        val number = documents.issue(run.organizationId, run.objectName, recordId, typeName)
        return RunStep(ActionType.GENERATE_DOCUMENT, "issued $number on $recordId")
    }
}

// no user to ask why: the automation is the reason, so its writes pass requiresReason and its audit rows
// say which one wrote (ADR-041)
private fun Automation.changeReason(): String = "automation '$name'"
