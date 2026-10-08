package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// ADR-045: modules find people through this port, never through core's tables. a service account has a
// disabled backing row (ADR-043), so "enabled" also means "a person": an account is nobody's recipient.
class UserDirectory(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    // key = the email trimmed and lower-cased. an unknown or disabled email is missing from the map
    suspend fun idsByEmail(
        organizationId: UUID,
        emails: Collection<String>
    ): Map<String, UUID> {
        val normalised = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (normalised.isEmpty()) return emptyMap()
        return db
            .sql(
                "SELECT id, lower(email) AS email FROM ${schemas.metadata}.users " +
                    "WHERE organization_id = :organizationId AND enabled AND lower(email) = ANY(:emails)"
            ).bind("organizationId", organizationId)
            .bind("emails", normalised.toTypedArray())
            .map { row, _ -> Rows.string(row, "email") to Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toMap()
    }

    // the enabled people of the tenant among ids
    suspend fun existing(
        organizationId: UUID,
        ids: Collection<UUID>
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return db
            .sql("SELECT id FROM ${schemas.metadata}.users WHERE organization_id = :organizationId AND enabled AND id = ANY(:ids)")
            .bind("organizationId", organizationId)
            .bind("ids", ids.distinct().toTypedArray())
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }

    // every enabled person of the tenant: what an "everyone" audience reaches when it must be named one by one
    suspend fun enabledIds(organizationId: UUID): Set<UUID> =
        db
            .sql("SELECT id FROM ${schemas.metadata}.users WHERE organization_id = :organizationId AND enabled")
            .bind("organizationId", organizationId)
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()

    // emails as stored, to show who an alert names. disabled users too: they are still who it named
    suspend fun emailsById(
        organizationId: UUID,
        ids: Collection<UUID>
    ): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return db
            .sql("SELECT id, email FROM ${schemas.metadata}.users WHERE organization_id = :organizationId AND id = ANY(:ids)")
            .bind("organizationId", organizationId)
            .bind("ids", ids.distinct().toTypedArray())
            .map { row, _ -> Rows.uuid(row, "id") to Rows.string(row, "email") }
            .all()
            .asFlow()
            .toList()
            .toMap()
    }
}
