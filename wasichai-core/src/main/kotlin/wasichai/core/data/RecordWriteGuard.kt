package wasichai.core.data

import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

/**
 * A record write about to happen (ADR-040). Guards judge this, before the store does anything.
 *
 * - CREATED: no [recordId] yet, [attributes] is what the caller sends.
 * - UPDATED: [before] is the stored row, [attributes] what the caller sends. A link or unlink is an
 *   UPDATE of both records: [attributes] is then `rel:<relationship>` and the other id, or null.
 * - DELETED: [before] is the stored row, [attributes] null.
 * - TRANSITIONED: [before] is the stored row, [transition] its name, [attributes] null.
 *
 * Attributes only: section payloads stay out, as they do for [RecordChange] (ADR-0019).
 */
data class RecordWrite(
    val organizationId: UUID,
    // null = the platform (ADR-039) or an automation (ADR-016)
    val userId: UUID?,
    val objectId: UUID,
    val objectName: String,
    val recordId: UUID?,
    val kind: RecordChangeKind,
    val before: Map<String, Any?>? = null,
    val attributes: Map<String, Any?>? = null,
    val transition: String? = null
)

/**
 * SPI: told of every record write BEFORE the store write, for every caller and route (ADR-040). A
 * throw aborts it: nothing stored, nothing audited, no listener. Throw a [wasichai.core.common.WasichaiException]
 * for a clean status (ValidationException 400, ConflictException 409...). Collected from every bean,
 * in @Order.
 *
 * It runs in the caller's transaction when there is one (ADR-038), so a read here sees what the
 * caller already wrote. It does not lock anything for you.
 */
interface RecordWriteGuard {
    suspend fun beforeWrite(change: RecordWrite)
}

/**
 * What every write path calls right before the store write: RecordService, related-record links,
 * workflow transitions, automation actions. A module that writes through [RecordStore] itself calls
 * it too; the store port stays raw on purpose (ADR-040).
 */
class RecordWriteGuards(
    private val guards: List<RecordWriteGuard>
) {
    // appendOnly first: no guard is asked about a write that can never happen
    suspend fun beforeWrite(
        definition: ObjectDefinition,
        change: RecordWrite
    ) {
        if (definition.obj.appendOnly && change.kind != RecordChangeKind.CREATED) {
            throw ConflictException("Object '${definition.obj.name}' is append-only: its records are never changed or deleted")
        }
        guards.forEach { it.beforeWrite(change) }
    }
}

// apiOnly: the generic record api is a second door around the app's own rules (ADR-040). in-process callers pass.
internal fun rejectApiOnly(definition: ObjectDefinition) {
    if (definition.obj.apiOnly) {
        throw ForbiddenException("Object '${definition.obj.name}' is written only by the application, not through the record API")
    }
}
