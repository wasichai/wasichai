package wasichai.core.metadata

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.ValidationException
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

enum class RelationshipType {
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    MANY_TO_MANY;

    // which side carries the foreign key column
    val fkOnSource: Boolean
        get() = this == MANY_TO_ONE || this == ONE_TO_ONE

    val fkOnTarget: Boolean
        get() = this == ONE_TO_MANY

    val usesJoinTable: Boolean
        get() = this == MANY_TO_MANY

    companion object {
        fun parse(raw: String): RelationshipType =
            entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ValidationException(
                    "Unknown relationship type '$raw'",
                    "type",
                    "must be one of ${entries.joinToString(", ") { it.name }}"
                )
    }
}

data class Relationship(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val label: String,
    val inverseLabel: String?,
    val type: RelationshipType,
    val sourceObjectId: UUID,
    val targetObjectId: UUID,
    val relationFieldId: UUID?,
    val joinTable: String?
)

private const val RELATIONSHIP_COLUMNS =
    "id, organization_id, name, label, inverse_label, type, source_object_id, target_object_id, " +
        "relation_field_id, join_table"

@Repository
class RelationshipRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(relationship: Relationship): Relationship =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.relationships
                    (id, organization_id, name, label, inverse_label, type, source_object_id,
                     target_object_id, relation_field_id, join_table)
                VALUES (:id, :organizationId, :name, :label, :inverseLabel, :type, :sourceObjectId,
                        :targetObjectId, :relationFieldId, :joinTable)
                RETURNING $RELATIONSHIP_COLUMNS
                """.trimIndent()
            ).bind("id", relationship.id)
            .bind("organizationId", relationship.organizationId)
            .bind("name", relationship.name)
            .bind("label", relationship.label)
            .bindNullable("inverseLabel", relationship.inverseLabel)
            .bind("type", relationship.type.name)
            .bind("sourceObjectId", relationship.sourceObjectId)
            .bind("targetObjectId", relationship.targetObjectId)
            .bindNullable("relationFieldId", relationship.relationFieldId)
            .bindNullable("joinTable", relationship.joinTable)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findAll(organizationId: UUID): List<Relationship> =
        db
            .sql("SELECT $RELATIONSHIP_COLUMNS FROM ${schemas.metadata}.relationships WHERE organization_id = :org ORDER BY label")
            .bind("org", organizationId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): Relationship? =
        db
            .sql("SELECT $RELATIONSHIP_COLUMNS FROM ${schemas.metadata}.relationships WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    // only the labels. what backs a relationship never moves: see RelationshipService.update.
    suspend fun update(relationship: Relationship): Relationship =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.relationships
                SET label = :label, inverse_label = :inverseLabel
                WHERE id = :id
                RETURNING $RELATIONSHIP_COLUMNS
                """.trimIndent()
            ).bind("id", relationship.id)
            .bind("label", relationship.label)
            .bindNullable("inverseLabel", relationship.inverseLabel)
            .map(::map)
            .one()
            .awaitSingle()

    // both directions: a relationship shows up on the detail page of either object
    suspend fun findForObject(
        organizationId: UUID,
        objectId: UUID
    ): List<Relationship> =
        db
            .sql(
                """
                SELECT $RELATIONSHIP_COLUMNS FROM ${schemas.metadata}.relationships
                WHERE organization_id = :org AND (source_object_id = :objectId OR target_object_id = :objectId)
                ORDER BY label
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.relationships WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun tableExists(table: String): Boolean =
        db
            .sql("SELECT 1 FROM information_schema.tables WHERE table_schema = :schema AND table_name = :table")
            .bind("schema", schemas.data)
            .bind("table", table)
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): Relationship =
        Relationship(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            inverseLabel = Rows.stringOrNull(row, "inverse_label"),
            type = RelationshipType.valueOf(Rows.string(row, "type")),
            sourceObjectId = Rows.uuid(row, "source_object_id"),
            targetObjectId = Rows.uuid(row, "target_object_id"),
            relationFieldId = Rows.uuidOrNull(row, "relation_field_id"),
            joinTable = Rows.stringOrNull(row, "join_table")
        )
}
