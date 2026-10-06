package wasichai.pages

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.util.UUID

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val PAGE_COLUMNS =
    "id, organization_id, object_id, name, label, kind, template, definition::text AS definition, " +
        "created_at, updated_at"

@Repository
class PageRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(page: Page): Page =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.pages
                    (id, organization_id, object_id, name, label, kind, template, definition)
                VALUES (:id, :organizationId, :objectId, :name, :label, :kind, :template, CAST(:definition AS jsonb))
                RETURNING $PAGE_COLUMNS
                """.trimIndent()
            ).bind("id", page.id)
            .bind("organizationId", page.organizationId)
            .bindNullable("objectId", page.objectId)
            .bind("name", page.name)
            .bind("label", page.label)
            .bind("kind", page.kind.name)
            .bind("template", page.template.value)
            .bind("definition", objectMapper.writeValueAsString(page.definition))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(page: Page): Page =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.pages
                SET label = :label, template = :template, definition = CAST(:definition AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $PAGE_COLUMNS
                """.trimIndent()
            ).bind("id", page.id)
            .bind("organizationId", page.organizationId)
            .bind("label", page.label)
            .bind("template", page.template.value)
            .bind("definition", objectMapper.writeValueAsString(page.definition))
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): Page? =
        db
            .sql("SELECT $PAGE_COLUMNS FROM ${schemas.metadata}.pages WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findAll(organizationId: UUID): List<Page> =
        db
            .sql("SELECT $PAGE_COLUMNS FROM ${schemas.metadata}.pages WHERE organization_id = :org ORDER BY label")
            .bind("org", organizationId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByObjectAndKind(
        objectId: UUID,
        kind: PageKind
    ): Page? =
        db
            .sql("SELECT $PAGE_COLUMNS FROM ${schemas.metadata}.pages WHERE object_id = :objectId AND kind = :kind")
            .bind("objectId", objectId)
            .bind("kind", kind.name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun findByObject(objectId: UUID): List<Page> =
        db
            .sql("SELECT $PAGE_COLUMNS FROM ${schemas.metadata}.pages WHERE object_id = :objectId ORDER BY label")
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM ${schemas.metadata}.pages WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun map(
        row: Row,
        metadata: io.r2dbc.spi.RowMetadata
    ): Page =
        Page(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuidOrNull(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            kind = PageKind.valueOf(Rows.string(row, "kind")),
            template = PageTemplate.parse(Rows.string(row, "template")),
            definition = objectMapper.readValue(Rows.string(row, "definition"), PageDefinition::class.java)
        )
}
