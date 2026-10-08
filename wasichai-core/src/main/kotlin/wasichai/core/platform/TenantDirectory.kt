package wasichai.core.platform

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

/** An organization as background work sees it: its id and slug, nothing else (ADR-057). */
data class TenantRef(
    val id: UUID,
    val slug: String
)

/**
 * The tenants, for background work (ADR-057): a scheduled job, a startup hook, a queue consumer that
 * runs once per organization, usually inside `RecordService.asPlatform` or through
 * `RecordService.forEachOrganization`.
 *
 * Never reachable from a request: like `asPlatform` (ADR-039), a call made while serving one, with a
 * token or without, throws [IllegalStateException]. A tenant must not be able to enumerate the others,
 * and no controller takes this bean (an architecture test keeps it so).
 *
 * Every answer is a snapshot ordered by id, not a live view.
 */
interface TenantDirectory {
    // every organization
    suspend fun organizations(): List<TenantRef>

    // only organizations that define an object with this name, enabled or not: how an app finds "its" tenants
    suspend fun organizationsWithObject(objectName: String): List<TenantRef>
}

// the default: two reads over organizations and custom_objects. an app may replace it, and then owns the tripwire
class DatabaseTenantDirectory(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) : TenantDirectory {
    override suspend fun organizations(): List<TenantRef> {
        guard()
        return db
            .sql("SELECT id, slug FROM ${schemas.metadata}.organizations ORDER BY id")
            .map { row, _ -> TenantRef(Rows.uuid(row, "id"), Rows.string(row, "slug")) }
            .all()
            .asFlow()
            .toList()
    }

    override suspend fun organizationsWithObject(objectName: String): List<TenantRef> {
        guard()
        return db
            .sql(
                """
                SELECT o.id, o.slug FROM ${schemas.metadata}.organizations o
                WHERE EXISTS (SELECT 1 FROM ${schemas.metadata}.custom_objects c WHERE c.organization_id = o.id AND c.name = :name)
                ORDER BY o.id
                """.trimIndent()
            ).bind("name", objectName)
            .map { row, _ -> TenantRef(Rows.uuid(row, "id"), Rows.string(row, "slug")) }
            .all()
            .asFlow()
            .toList()
    }

    private suspend fun guard() = Background.require("TenantDirectory", "never lists the tenants")
}
