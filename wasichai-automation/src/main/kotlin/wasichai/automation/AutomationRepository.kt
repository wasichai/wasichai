package wasichai.automation

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val COLUMNS =
    "id, organization_id, object_id, name, label, enabled, definition::text AS definition, created_at, updated_at"

@Repository
class AutomationRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(automation: Automation): Automation =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.automations (id, organization_id, object_id, name, label, enabled, definition)
                VALUES (:id, :organizationId, :objectId, :name, :label, :enabled, CAST(:definition AS jsonb))
                RETURNING $COLUMNS
                """.trimIndent()
            ).bindAll(automation)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(automation: Automation): Automation =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.automations
                SET name = :name, label = :label, enabled = :enabled,
                    definition = CAST(:definition AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $COLUMNS
                """.trimIndent()
            ).bindCommon(automation)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findAll(organizationId: UUID): List<Automation> =
        db
            .sql("SELECT $COLUMNS FROM ${schemas.metadata}.automations WHERE organization_id = :org ORDER BY name")
            .bind("org", organizationId)
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findByObject(
        organizationId: UUID,
        objectId: UUID
    ): List<Automation> =
        db
            .sql("SELECT $COLUMNS FROM ${schemas.metadata}.automations WHERE organization_id = :org AND object_id = :objectId ORDER BY name")
            .bind("org", organizationId)
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()

    // the hot path: every record change asks for the automations watching that object
    suspend fun findEnabledByObject(
        organizationId: UUID,
        objectId: UUID
    ): List<Automation> =
        db
            .sql(
                """
                SELECT $COLUMNS FROM ${schemas.metadata}.automations
                WHERE organization_id = :org AND object_id = :objectId AND enabled = true
                ORDER BY name
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findById(
        organizationId: UUID,
        id: UUID
    ): Automation? =
        db
            .sql("SELECT $COLUMNS FROM ${schemas.metadata}.automations WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): Automation? =
        db
            .sql("SELECT $COLUMNS FROM ${schemas.metadata}.automations WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.automations WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun DatabaseClient.GenericExecuteSpec.bindAll(automation: Automation) = bindCommon(automation).bind("objectId", automation.objectId)

    // r2dbc rejects a parameter the statement never mentions, so object_id stays out of UPDATE
    private fun DatabaseClient.GenericExecuteSpec.bindCommon(automation: Automation) =
        bind("id", automation.id)
            .bind("organizationId", automation.organizationId)
            .bind("name", automation.name)
            .bind("label", automation.label)
            .bind("enabled", automation.enabled)
            .bind("definition", objectMapper.writeValueAsString(automation.definition))

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): Automation =
        Automation(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            enabled = Rows.bool(row, "enabled"),
            definition = objectMapper.readValue(Rows.string(row, "definition"), AutomationDefinition::class.java)
        )
}
