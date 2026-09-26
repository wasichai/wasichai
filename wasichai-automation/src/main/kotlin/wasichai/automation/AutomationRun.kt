package wasichai.automation

import java.time.Instant
import java.util.UUID

// the record as it was when the change landed. everything the actions need, so the runner
// never has to re-read a row that may have changed since.
data class RunPayload(
    val objectId: UUID,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
    val state: String? = null,
    val transition: String? = null
)

data class AutomationRun(
    val id: UUID,
    val organizationId: UUID,
    val automationId: UUID,
    val objectName: String,
    val recordId: UUID?,
    val trigger: TriggerType,
    val status: RunStatus,
    val depth: Int,
    val payload: RunPayload,
    val steps: List<RunStep> = emptyList(),
    val error: String? = null,
    val attempts: Int = 0,
    val userId: UUID? = null,
    val createdAt: Instant? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    // filled by the log query only. not a column of the run.
    val automationName: String? = null
)
