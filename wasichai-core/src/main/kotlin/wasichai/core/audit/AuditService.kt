package wasichai.core.audit

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

// ISSUE is written by a module; the audit_log CHECK accepts it once that module's migration ran (R10)
enum class AuditOperation { CREATE, UPDATE, DELETE, ISSUE }

@Service
class AuditService(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun record(
        organizationId: UUID,
        userId: UUID?,
        objectName: String,
        recordId: UUID?,
        operation: AuditOperation,
        before: Any? = null,
        after: Any? = null,
        documentId: UUID? = null,
        // why, as the writer said it (ADR-041). normalized by the caller: see ChangeReason
        reason: String? = null
    ) {
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.audit_log
                    (organization_id, user_id, object_name, record_id, operation, before_state, after_state, document_id, reason)
                VALUES (:organizationId, :userId, :objectName, :recordId, :operation,
                        CAST(:before AS jsonb), CAST(:after AS jsonb), :documentId, :reason)
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("objectName", objectName)
            .bind("operation", operation.name)
            .bindNullable("userId", userId)
            .bindNullable("recordId", recordId)
            .bindNullable("documentId", documentId)
            .bindNullable("reason", reason)
            .bindNullable("before", before?.let { objectMapper.writeValueAsString(it) })
            .bindNullable("after", after?.let { objectMapper.writeValueAsString(it) })
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
