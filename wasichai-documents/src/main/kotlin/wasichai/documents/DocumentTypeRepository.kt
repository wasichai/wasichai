package wasichai.documents

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
private const val TYPE_COLUMNS =
    "id, organization_id, object_id, name, label, prefix, template::text AS template, created_at, updated_at"

@Repository
class DocumentTypeRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(type: DocumentType): DocumentType =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.document_types
                    (id, organization_id, object_id, name, label, prefix, template)
                VALUES (:id, :organizationId, :objectId, :name, :label, :prefix, CAST(:template AS jsonb))
                RETURNING $TYPE_COLUMNS
                """.trimIndent()
            ).bind("id", type.id)
            .bind("organizationId", type.organizationId)
            .bind("objectId", type.objectId)
            .bind("name", type.name)
            .bind("label", type.label)
            .bind("prefix", type.prefix)
            .bind("template", objectMapper.writeValueAsString(type.template))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(type: DocumentType): DocumentType =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.document_types
                SET label = :label, prefix = :prefix, template = CAST(:template AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $TYPE_COLUMNS
                """.trimIndent()
            ).bind("id", type.id)
            .bind("organizationId", type.organizationId)
            .bind("label", type.label)
            .bind("prefix", type.prefix)
            .bind("template", objectMapper.writeValueAsString(type.template))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findByObject(objectId: UUID): List<DocumentType> =
        db
            .sql("SELECT $TYPE_COLUMNS FROM ${schemas.metadata}.document_types WHERE object_id = :objectId ORDER BY label")
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByName(
        objectId: UUID,
        name: String
    ): DocumentType? =
        db
            .sql("SELECT $TYPE_COLUMNS FROM ${schemas.metadata}.document_types WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findByPrefix(
        organizationId: UUID,
        prefix: String
    ): DocumentType? =
        db
            .sql("SELECT $TYPE_COLUMNS FROM ${schemas.metadata}.document_types WHERE organization_id = :organizationId AND prefix = :prefix")
            .bind("organizationId", organizationId)
            .bind("prefix", prefix)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.document_types WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): DocumentType =
        DocumentType(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            prefix = Rows.string(row, "prefix"),
            template = objectMapper.readValue(Rows.string(row, "template"), TemplateNode::class.java)
        )
}
