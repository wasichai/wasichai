package wasichai.gis

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

// a map someone saved: one object, one of its geometries, an optional colouring
data class MapView(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val geometryName: String,
    val style: AttributeStyle?
)

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val MAP_VIEW_COLUMNS = "id, organization_id, object_id, name, label, geometry_name, style::text AS style"

@Repository
class MapViewRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(view: MapView): MapView =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.map_views (id, organization_id, object_id, name, label, geometry_name, style)
                VALUES (:id, :organizationId, :objectId, :name, :label, :geometryName, CAST(:style AS jsonb))
                RETURNING $MAP_VIEW_COLUMNS
                """.trimIndent()
            ).bind("id", view.id)
            .bind("organizationId", view.organizationId)
            .bind("objectId", view.objectId)
            .bind("name", view.name)
            .bind("label", view.label)
            .bind("geometryName", view.geometryName)
            .bindStyle(view.style)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun update(view: MapView): MapView =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.map_views
                SET label = :label, geometry_name = :geometryName, style = CAST(:style AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $MAP_VIEW_COLUMNS
                """.trimIndent()
            ).bind("id", view.id)
            .bind("organizationId", view.organizationId)
            .bind("label", view.label)
            .bind("geometryName", view.geometryName)
            .bindStyle(view.style)
            .map(::map)
            .one()
            .awaitSingle()

    suspend fun findAll(organizationId: UUID): List<MapView> =
        db
            .sql("SELECT $MAP_VIEW_COLUMNS FROM ${schemas.metadata}.map_views WHERE organization_id = :organizationId ORDER BY label")
            .bind("organizationId", organizationId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByObject(
        organizationId: UUID,
        objectId: UUID
    ): List<MapView> =
        db
            .sql(
                "SELECT $MAP_VIEW_COLUMNS FROM ${schemas.metadata}.map_views " +
                    "WHERE organization_id = :organizationId AND object_id = :objectId ORDER BY label"
            ).bind("organizationId", organizationId)
            .bind("objectId", objectId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findByName(
        organizationId: UUID,
        objectId: UUID,
        name: String
    ): MapView? =
        db
            .sql(
                "SELECT $MAP_VIEW_COLUMNS FROM ${schemas.metadata}.map_views " +
                    "WHERE organization_id = :organizationId AND object_id = :objectId AND name = :name"
            ).bind("organizationId", organizationId)
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ) {
        db
            .sql("DELETE FROM ${schemas.metadata}.map_views WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun DatabaseClient.GenericExecuteSpec.bindStyle(style: AttributeStyle?): DatabaseClient.GenericExecuteSpec =
        if (style == null) bindNull("style", String::class.java) else bind("style", objectMapper.writeValueAsString(style))

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): MapView =
        MapView(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            geometryName = Rows.string(row, "geometry_name"),
            style = Rows.stringOrNull(row, "style")?.let { objectMapper.readValue(it, AttributeStyle::class.java) }
        )
}
