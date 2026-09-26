package wasichai.forms

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val FORM_COLUMNS =
    "id, organization_id, object_id, name, label, definition::text AS definition, created_at, updated_at"

@Repository
class FormRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(form: Form): Form =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.forms
                    (id, organization_id, object_id, name, label, definition)
                VALUES (:id, :organizationId, :objectId, :name, :label, CAST(:definition AS jsonb))
                RETURNING $FORM_COLUMNS
                """.trimIndent()
            ).bind("id", form.id)
            .bind("organizationId", form.organizationId)
            .bind("objectId", form.objectId)
            .bind("name", form.name)
            .bind("label", form.label)
            .bind("definition", objectMapper.writeValueAsString(form.definition))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(form: Form): Form =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.forms
                SET label = :label, definition = CAST(:definition AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $FORM_COLUMNS
                """.trimIndent()
            ).bind("id", form.id)
            .bind("organizationId", form.organizationId)
            .bind("label", form.label)
            .bind("definition", objectMapper.writeValueAsString(form.definition))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findByObject(objectId: UUID): List<Form> =
        db
            .sql("SELECT $FORM_COLUMNS FROM ${schemas.metadata}.forms WHERE object_id = :objectId ORDER BY label")
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByName(
        objectId: UUID,
        name: String
    ): Form? =
        db
            .sql("SELECT $FORM_COLUMNS FROM ${schemas.metadata}.forms WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.forms WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): Form =
        Form(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            definition = objectMapper.readValue(Rows.string(row, "definition"), FormDefinition::class.java)
        )
}
