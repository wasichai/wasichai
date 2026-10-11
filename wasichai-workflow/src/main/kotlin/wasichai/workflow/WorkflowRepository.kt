package wasichai.workflow

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import wasichai.core.metadata.MetadataChanges
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val WORKFLOW_COLUMNS =
    "id, organization_id, object_id, name, label, enabled, definition::text AS definition, created_at, updated_at"

@Repository
class WorkflowRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas,
    // where an object's workflow is kept between requests (ADR-067); uncached by default, as before
    private val changes: MetadataChanges = MetadataChanges.uncached()
) {
    private data class WorkflowOfObject(
        val objectId: UUID
    )

    suspend fun insert(workflow: Workflow): Workflow = changes.setupChanging(workflow.organizationId) { insertRow(workflow) }

    suspend fun update(workflow: Workflow): Workflow = changes.setupChanging(workflow.organizationId) { updateRow(workflow) }

    private suspend fun insertRow(workflow: Workflow): Workflow =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.workflows (id, organization_id, object_id, name, label, enabled, definition)
                VALUES (:id, :organizationId, :objectId, :name, :label, :enabled, CAST(:definition AS jsonb))
                RETURNING $WORKFLOW_COLUMNS
                """.trimIndent()
            ).bind("id", workflow.id)
            .bind("organizationId", workflow.organizationId)
            .bind("objectId", workflow.objectId)
            .bind("name", workflow.name)
            .bind("label", workflow.label)
            .bind("enabled", workflow.enabled)
            .bind("definition", objectMapper.writeValueAsString(workflow.definition))
            .map(::map)
            .one()
            .awaitSingle()

    private suspend fun updateRow(workflow: Workflow): Workflow =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.workflows
                SET name = :name, label = :label, enabled = :enabled,
                    definition = CAST(:definition AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $WORKFLOW_COLUMNS
                """.trimIndent()
            ).bind("id", workflow.id)
            .bind("organizationId", workflow.organizationId)
            .bind("name", workflow.name)
            .bind("label", workflow.label)
            .bind("enabled", workflow.enabled)
            .bind("definition", objectMapper.writeValueAsString(workflow.definition))
            .map(::map)
            .one()
            .awaitSingle()

    // the object's workflow as the record paths read it: kept like its definition, dropped by any workflow or model
    // write (ADR-067). admin writes read findByObject, from the database
    suspend fun forObject(
        organizationId: UUID,
        objectId: UUID
    ): Workflow? = changes.cached(organizationId, WorkflowOfObject(objectId)) { findByObject(organizationId, objectId) }

    suspend fun findByObject(
        organizationId: UUID,
        objectId: UUID
    ): Workflow? =
        db
            .sql("SELECT $WORKFLOW_COLUMNS FROM ${schemas.metadata}.workflows WHERE organization_id = :org AND object_id = :objectId")
            .bind("org", organizationId)
            .bind("objectId", objectId)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): Workflow? =
        db
            .sql("SELECT $WORKFLOW_COLUMNS FROM ${schemas.metadata}.workflows WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ) {
        changes.setupChanging(organizationId) {
            db
                .sql("DELETE FROM ${schemas.metadata}.workflows WHERE id = :id AND organization_id = :org")
                .bind("id", id)
                .bind("org", organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun map(
        row: Row,
        metadata: io.r2dbc.spi.RowMetadata
    ): Workflow =
        Workflow(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            enabled = Rows.bool(row, "enabled"),
            definition = objectMapper.readValue(Rows.string(row, "definition"), WorkflowDefinition::class.java)
        )
}
