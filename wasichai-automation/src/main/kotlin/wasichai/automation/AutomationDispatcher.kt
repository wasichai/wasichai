package wasichai.automation

import org.springframework.stereotype.Service
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.platform.ChangeOrigin
import java.util.UUID

// matching happens here, inside the caller's transaction: the snapshot is what the change was.
// running the actions happens later, off the request, so a slow webhook never delays a write.
@Service
class AutomationDispatcher(
    private val automations: AutomationRepository,
    private val runs: AutomationRunRepository,
    private val properties: AutomationProperties
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        val watching =
            automations
                .findEnabledByObject(change.organizationId, change.objectId)
                // an automation never answers its own writes. indirect loops die on depth.
                .filter { it.id != change.causedBy }
                .filter { AutomationRules.triggerMatches(it.definition.trigger, change) }
        if (watching.isEmpty()) return

        val payload = RunPayload(change.objectId, change.before, change.after, change.state, change.transition)
        // the request's id, or the run's own when one automation's write triggers the next (ADR-050)
        val correlationId = ChangeOrigin.correlationId()
        watching.forEach { automation ->
            // a skipped run is still a row: "why did nothing happen" must have an answer
            val skip =
                if (change.depth >= properties.depthCap) {
                    "automation chain reached depth ${properties.depthCap}"
                } else {
                    AutomationRules.unmetCondition(automation.definition.conditions, change)
                }
            runs.insert(
                AutomationRun(
                    id = UUID.randomUUID(),
                    organizationId = change.organizationId,
                    automationId = automation.id,
                    objectName = change.objectName,
                    recordId = change.recordId,
                    trigger = automation.definition.trigger.type,
                    status = if (skip == null) RunStatus.PENDING else RunStatus.SKIPPED,
                    depth = change.depth,
                    payload = payload,
                    error = skip,
                    userId = change.userId,
                    correlationId = correlationId
                )
            )
        }
    }
}

// what the actions read. the run carries its own change, so nothing is re-read to act on it.
fun AutomationRun.toChange(kind: wasichai.core.data.RecordChangeKind = trigger.kind): RecordChange =
    RecordChange(
        organizationId = organizationId,
        userId = userId,
        objectId = payload.objectId,
        objectName = objectName,
        recordId = recordId ?: UUID(0, 0),
        kind = kind,
        before = payload.before,
        after = payload.after,
        state = payload.state,
        transition = payload.transition,
        depth = depth,
        causedBy = automationId
    )
