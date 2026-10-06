package wasichai.automation

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

private const val COLUMNS =
    "id, organization_id, automation_id, object_name, record_id, trigger_type, status, depth, " +
        "payload::text AS payload, steps::text AS steps, error, attempts, user_id, " +
        "created_at, started_at, finished_at"

@Repository
class AutomationRunRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(run: AutomationRun): AutomationRun {
        var spec =
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.automation_runs
                        (id, organization_id, automation_id, object_name, record_id, trigger_type, status, depth,
                         payload, steps, error, user_id, finished_at)
                    VALUES (:id, :org, :automationId, :objectName, :recordId, :triggerType, :status, :depth,
                            CAST(:payload AS jsonb), CAST(:steps AS jsonb), :error, :userId, :finishedAt)
                    RETURNING $COLUMNS
                    """.trimIndent()
                ).bind("id", run.id)
                .bind("org", run.organizationId)
                .bind("automationId", run.automationId)
                .bind("objectName", run.objectName)
                .bind("triggerType", run.trigger.name)
                .bind("status", run.status.name)
                .bind("depth", run.depth)
                .bind("payload", objectMapper.writeValueAsString(run.payload))
        spec = spec.bindNullable("recordId", run.recordId, UUID::class.java)
        spec = spec.bindNullable("userId", run.userId, UUID::class.java)
        spec = spec.bindNullable("error", run.error, String::class.java)
        spec =
            spec.bindNullable(
                "steps",
                if (run.steps.isEmpty()) null else objectMapper.writeValueAsString(run.steps),
                String::class.java
            )
        // a run that never gets queued (skipped) is already over
        spec =
            if (run.status == RunStatus.PENDING) {
                spec.bindNull("finishedAt", java.time.OffsetDateTime::class.java)
            } else {
                spec.bind("finishedAt", java.time.OffsetDateTime.now())
            }
        return spec.map(::map).one().awaitSingle()
    }

    // SKIP LOCKED so two instances can drain the same queue without stepping on each other
    suspend fun claim(limit: Int): List<AutomationRun> =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.automation_runs
                SET status = 'RUNNING', started_at = now(), attempts = attempts + 1
                WHERE id IN (
                    SELECT id FROM ${schemas.metadata}.automation_runs
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING $COLUMNS
                """.trimIndent()
            ).bind("limit", limit)
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()

    suspend fun finish(
        id: UUID,
        status: RunStatus,
        steps: List<RunStep>,
        error: String?
    ) {
        var spec =
            db
                .sql(
                    """
                    UPDATE ${schemas.metadata}.automation_runs
                    SET status = :status, steps = CAST(:steps AS jsonb), error = :error, finished_at = now()
                    WHERE id = :id
                    """.trimIndent()
                ).bind("id", id)
                .bind("status", status.name)
                .bind("steps", objectMapper.writeValueAsString(steps))
        spec = spec.bindNullable("error", error?.take(2_000), String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun list(
        organizationId: UUID,
        automationId: UUID?,
        limit: Int
    ): List<AutomationRun> {
        val filter = if (automationId == null) "" else " AND r.automation_id = :automationId"
        var spec =
            db
                .sql(
                    """
                    SELECT ${COLUMNS.split(", ").joinToString(", ") { "r.$it" }}, a.name AS automation_name
                    FROM ${schemas.metadata}.automation_runs r
                    JOIN ${schemas.metadata}.automations a ON a.id = r.automation_id
                    WHERE r.organization_id = :org$filter
                    ORDER BY r.created_at DESC
                    LIMIT :limit
                    """.trimIndent()
                ).bind("org", organizationId)
                .bind("limit", limit)
        if (automationId != null) spec = spec.bind("automationId", automationId)
        return spec
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()
    }

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): AutomationRun =
        AutomationRun(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            automationId = Rows.uuid(row, "automation_id"),
            objectName = Rows.string(row, "object_name"),
            recordId = Rows.uuidOrNull(row, "record_id"),
            trigger = TriggerType.valueOf(Rows.string(row, "trigger_type")),
            status = RunStatus.valueOf(Rows.string(row, "status")),
            depth = Rows.int(row, "depth"),
            payload = objectMapper.readValue(Rows.string(row, "payload"), RunPayload::class.java),
            steps =
                Rows.stringOrNull(row, "steps")?.let {
                    objectMapper.readValue(it, object : TypeReference<List<RunStep>>() {})
                } ?: emptyList(),
            error = Rows.stringOrNull(row, "error"),
            attempts = Rows.int(row, "attempts"),
            userId = Rows.uuidOrNull(row, "user_id"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            startedAt = Rows.instantOrNull(row, "started_at"),
            finishedAt = Rows.instantOrNull(row, "finished_at"),
            // only the log query joins the definition in
            automationName =
                if (metadata.columnMetadatas.any { it.name.equals("automation_name", ignoreCase = true) }) {
                    Rows.stringOrNull(row, "automation_name")
                } else {
                    null
                }
        )
}
