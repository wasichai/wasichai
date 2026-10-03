package wasichai.core.audit

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.WasichaiSchemas
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
        var spec =
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
        spec = if (userId == null) spec.bindNull("userId", UUID::class.java) else spec.bind("userId", userId)
        spec = if (recordId == null) spec.bindNull("recordId", UUID::class.java) else spec.bind("recordId", recordId)
        spec = if (documentId == null) spec.bindNull("documentId", UUID::class.java) else spec.bind("documentId", documentId)
        spec = if (reason == null) spec.bindNull("reason", String::class.java) else spec.bind("reason", reason)
        spec = bindJson(spec, "before", before)
        spec = bindJson(spec, "after", after)
        spec.fetch().rowsUpdated().awaitSingle()
    }

    private fun bindJson(
        spec: DatabaseClient.GenericExecuteSpec,
        name: String,
        value: Any?
    ): DatabaseClient.GenericExecuteSpec =
        if (value == null) {
            spec.bindNull(name, String::class.java)
        } else {
            spec.bind(name, objectMapper.writeValueAsString(value))
        }
}
