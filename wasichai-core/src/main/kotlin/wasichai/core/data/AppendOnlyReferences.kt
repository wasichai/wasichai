package wasichai.core.data

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
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
 */
class AppendOnlyReferences(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val relationships: RelationshipRepository
) {
    suspend fun rejectDelete(
        organizationId: UUID,
        definition: ObjectDefinition,
        recordId: UUID
    ) {
        val obj = definition.obj
        // an append-only object's own records are refused by its own rule first
        if (obj.appendOnly) return

        // relation columns elsewhere: every RELATION field aiming here, made by a relationship or by hand
        fields
            .findByRelationTarget(obj.id)
            .filter { it.type == FieldType.RELATION && it.objectId != obj.id }
            .forEach { field ->
                val owner = objects.findById(organizationId, field.objectId)?.takeIf { it.appendOnly } ?: return@forEach
                if (exists(schemas.dataTable(owner.physicalTable), SqlIdentifier.quote(field.columnName), organizationId, recordId)) {
                    throw ConflictException(
                        "Record $recordId is referenced by append-only '${owner.name}' (field '${field.name}'); it cannot be deleted"
                    )
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
                val column = if (fromSource) "source_id" else "target_id"
                if (exists(schemas.dataTable(relationship.joinTable!!), column, organizationId, recordId)) {
                    throw ConflictException(
                        "Record $recordId is linked to append-only '${other.name}' (relationship '${relationship.name}'); it cannot be deleted"
                    )
                }
            }
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
