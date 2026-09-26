package wasichai.views

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
private const val VIEW_COLUMNS =
    "id, organization_id, object_id, name, label, definition::text AS definition, is_default, created_at, updated_at"

@Repository
class ViewRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(view: View): View =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.views
                    (id, organization_id, object_id, name, label, definition, is_default)
                VALUES (:id, :organizationId, :objectId, :name, :label, CAST(:definition AS jsonb), :isDefault)
                RETURNING $VIEW_COLUMNS
                """.trimIndent()
            ).bind("id", view.id)
            .bind("organizationId", view.organizationId)
            .bind("objectId", view.objectId)
            .bind("name", view.name)
            .bind("label", view.label)
            .bind("definition", objectMapper.writeValueAsString(view.definition))
            .bind("isDefault", view.isDefault)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(view: View): View =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.views
                SET label = :label, definition = CAST(:definition AS jsonb), is_default = :isDefault, updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $VIEW_COLUMNS
                """.trimIndent()
            ).bind("id", view.id)
            .bind("organizationId", view.organizationId)
            .bind("label", view.label)
            .bind("definition", objectMapper.writeValueAsString(view.definition))
            .bind("isDefault", view.isDefault)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findByObject(objectId: UUID): List<View> =
        db
            .sql("SELECT $VIEW_COLUMNS FROM ${schemas.metadata}.views WHERE object_id = :objectId ORDER BY is_default DESC, label")
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByName(
        objectId: UUID,
        name: String
    ): View? =
        db
            .sql("SELECT $VIEW_COLUMNS FROM ${schemas.metadata}.views WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findDefault(objectId: UUID): View? =
        db
            .sql("SELECT $VIEW_COLUMNS FROM ${schemas.metadata}.views WHERE object_id = :objectId AND is_default")
            .bind("objectId", objectId)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    // partial unique index allows one default per object. drop the old flag before raising a new one.
    suspend fun clearDefault(
        objectId: UUID,
        except: UUID
    ) {
        db
            .sql("UPDATE ${schemas.metadata}.views SET is_default = false, updated_at = now() WHERE object_id = :objectId AND is_default AND id <> :except")
            .bind("objectId", objectId)
            .bind("except", except)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.views WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): View =
        View(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            isDefault = Rows.bool(row, "is_default"),
            definition = objectMapper.readValue(Rows.string(row, "definition"), ViewDefinition::class.java)
        )
}
