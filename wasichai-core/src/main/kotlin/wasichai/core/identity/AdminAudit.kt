package wasichai.core.identity

import java.util.UUID

// what the admin trail calls each kind of entity: an audit_log object_name an object can never have (object names
// match ^[a-z][a-z0-9_]{0,48}$, no ':'), so admin entries never mix with an object's (ADR-049)
enum class AdminEntity(
    val objectName: String
) {
    USER("admin:user"),
    ROLE("admin:role"),
    PERMISSION("admin:permission"),
    SERVICE_ACCOUNT("admin:service-account"),
    ORG_UNIT("admin:org-unit"),
    OBJECT("admin:object"),
    FIELD("admin:field"),
    RELATIONSHIP("admin:relationship"),
    ORGANIZATION("admin:organization");

    companion object {
        const val PREFIX = "admin:"

        fun isAdmin(objectName: String?): Boolean = objectName != null && objectName.startsWith(PREFIX)
    }
}

enum class AdminOperation { CREATE, UPDATE, DELETE }

/**
 * A change to users, roles, permissions, service accounts, units, the model or the tenant, written to the audit log
 * inside the caller's transaction (ADR-049). `identity` sits below every package that administers, so they all call
 * it; `audit` implements it. A snapshot never holds a secret: the caller builds it from what it may show.
 */
interface AdminAudit {
    suspend fun record(
        actor: AuthenticatedUser,
        entity: AdminEntity,
        id: UUID,
        operation: AdminOperation,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    )
}
