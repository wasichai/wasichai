package wasichai.core.audit

import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import java.util.UUID

// admin changes go to the same audit_log as record changes, under their admin:* names (ADR-049).
// the actor's tenant, whatever the entity: provisioning another tenant is the provisioner's act.
class AuditLogAdminAudit(
    private val audit: AuditService
) : AdminAudit {
    override suspend fun record(
        actor: AuthenticatedUser,
        entity: AdminEntity,
        id: UUID,
        operation: AdminOperation,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ) {
        audit.record(
            organizationId = actor.organizationId,
            userId = actor.userId,
            objectName = entity.objectName,
            recordId = id,
            operation = AuditOperation.valueOf(operation.name),
            before = before,
            after = after
        )
    }
}
