package wasichai.core.audit

import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

/**
 * Which audited records a caller may read below READ on the object: the app's read scope (ADR-048). Audit
 * sits below data in core's DAG, so data answers this (`RecordReadScopes`) and audit only asks.
 */
interface AuditRecordScope {
    // false: no scope can narrow this caller (none declared, ADMIN), so nothing is asked or loaded
    fun appliesTo(caller: AuthenticatedUser): Boolean

    // null: the caller has no scope on this object, every entry stands, a deleted record's too.
    // otherwise the ids of [ids] that still exist and are in the caller's scope.
    suspend fun readable(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        ids: Collection<UUID>
    ): Set<UUID>?
}
