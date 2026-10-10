package wasichai.core.audit

import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition

/**
 * What a caller reads of an audited state: the app's read masks (ADR-065). Audit sits below data in core's DAG,
 * so data answers this (`RecordReadMasks`) and audit only asks.
 */
interface AuditStateMask {
    // false: no mask rewrites this object for this caller, so its states stand as stored
    fun appliesTo(
        caller: AuthenticatedUser?,
        definition: ObjectDefinition
    ): Boolean

    // [state], one before or after, as [caller] reads it
    suspend fun state(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
        state: Map<String, Any?>
    ): Map<String, Any?>
}
