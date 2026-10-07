package wasichai.core.organization

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

data class Organization(
    val id: UUID,
    val name: String,
    val slug: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

// plain sql: a @Table annotation cannot follow a configurable schema
@Repository
class OrganizationRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun findById(id: UUID): Organization? =
        db
            .sql("SELECT id, name, slug, created_at, updated_at FROM ${schemas.metadata}.organizations WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> map(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun findBySlug(slug: String): Organization? =
        db
            .sql("SELECT id, name, slug, created_at, updated_at FROM ${schemas.metadata}.organizations WHERE slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> map(row) }
            .one()
            .awaitFirstOrNull()

    // every tenant, for background work that runs per organization. no REST list of organizations uses it
    suspend fun ids(): List<UUID> =
        db
            .sql("SELECT id FROM ${schemas.metadata}.organizations ORDER BY id")
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()

    private fun map(row: Row): Organization =
        Organization(
            id = Rows.uuid(row, "id"),
            name = Rows.string(row, "name"),
            slug = Rows.string(row, "slug"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            updatedAt = Rows.instantOrNull(row, "updated_at")
        )
}
