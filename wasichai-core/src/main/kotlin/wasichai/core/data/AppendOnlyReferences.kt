package wasichai.core.data

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

/**
 * Deleting a record is a write on every append-only record that points at it (ADR-040): a RELATION
 * column is `ON DELETE SET NULL` and a join row `ON DELETE CASCADE`, so postgres would change them
 * with no guard and no history. RecordService refuses such a delete with 409 instead, for every
 * caller, before the store is touched.
 *
 * Check and delete are one transaction behind a row lock (ADR-044): a referencing insert takes
 * `FOR KEY SHARE` on the record for its FK, so it waits for the lock, or the lock waits for it and
 * the check then sees it. Objects nothing append-only can point at skip both.
 *
 * [transactions] is resolved on first use, as in ClusterLock: an app with no reference never needs one.
 */
class AppendOnlyReferences(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val relationships: RelationshipRepository,
    transactions: () -> TransactionalOperator
) {
    private val operator by lazy(transactions)

    /**
     * Runs [delete] unless an append-only record holds [recordId]; 409 if one does. With an append-only
     * referrer it runs lock -> check -> [delete] in one transaction, joining the caller's (ADR-038).
     */
    suspend fun <T> deleting(
        organizationId: UUID,
        definition: ObjectDefinition,
        recordId: UUID,
        delete: suspend () -> T
    ): T {
        val referrers = referrers(organizationId, definition)
        if (referrers.isEmpty()) return delete()
        return operator.executeAndAwait {
            lock(definition, organizationId, recordId)
            referrers.forEach { referrer ->
                if (exists(referrer.table, referrer.column, organizationId, recordId)) throw ConflictException(referrer.refusal(recordId))
            }
            delete()
        }
    }

    // a column or join table of an append-only object that can hold this object's ids
    private class Referrer(
        val table: String,
        val column: String,
        val refusal: (UUID) -> String
    )

    private suspend fun referrers(
        organizationId: UUID,
        definition: ObjectDefinition
    ): List<Referrer> {
        val obj = definition.obj
        // an append-only object's own records are refused by its own rule first
        if (obj.appendOnly) return emptyList()
        val found = mutableListOf<Referrer>()

        // relation columns elsewhere: every RELATION field aiming here, made by a relationship or by hand
        fields
            .findByRelationTarget(obj.id)
            .filter { it.type == FieldType.RELATION && it.objectId != obj.id }
            .forEach { field ->
                val owner = objects.findById(organizationId, field.objectId)?.takeIf { it.appendOnly } ?: return@forEach
                found +=
                    Referrer(schemas.dataTable(owner.physicalTable), SqlIdentifier.quote(field.columnName)) { recordId ->
                        "Record $recordId is referenced by append-only '${owner.name}' (field '${field.name}'); it cannot be deleted"
                    }
            }

        // join tables: a link is part of the append-only end's record
        relationships
            .findForObject(organizationId, obj.id)
            .filter { it.joinTable != null && it.sourceObjectId != it.targetObjectId }
            .forEach { relationship ->
                val fromSource = relationship.sourceObjectId == obj.id
                val otherId = if (fromSource) relationship.targetObjectId else relationship.sourceObjectId
                val other = objects.findById(organizationId, otherId)?.takeIf { it.appendOnly } ?: return@forEach
                found +=
                    Referrer(schemas.dataTable(relationship.joinTable!!), if (fromSource) "source_id" else "target_id") { recordId ->
                        "Record $recordId is linked to append-only '${other.name}' (relationship '${relationship.name}'); it cannot be deleted"
                    }
            }
        return found
    }

    // FOR UPDATE conflicts with the FOR KEY SHARE an FK check takes: no new reference slips in until we end
    private suspend fun lock(
        definition: ObjectDefinition,
        organizationId: UUID,
        recordId: UUID
    ) {
        db
            .sql("SELECT id FROM ${schemas.dataTable(definition.obj.physicalTable)} WHERE id = :recordId AND organization_id = :organizationId FOR UPDATE")
            .bind("recordId", recordId)
            .bind("organizationId", organizationId)
            .fetch()
            .all()
            .collectList()
            .awaitSingle()
    }

    private suspend fun exists(
        table: String,
        column: String,
        organizationId: UUID,
        recordId: UUID
    ): Boolean =
        db
            .sql("SELECT EXISTS (SELECT 1 FROM $table WHERE $column = :recordId AND organization_id = :organizationId) AS found")
            .bind("recordId", recordId)
            .bind("organizationId", organizationId)
            .map { row, _ -> row.get("found", Boolean::class.javaObjectType) == true }
            .one()
            .awaitSingle()
}
