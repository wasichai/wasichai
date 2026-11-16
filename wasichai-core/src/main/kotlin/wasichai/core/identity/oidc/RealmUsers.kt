package wasichai.core.identity.oidc

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.identity.RoleQueries
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// the app's row behind a realm person. email: the row's, after this sign-in's update.
data class RealmUser(
    val id: UUID,
    val organizationId: UUID,
    val email: String,
    val enabled: Boolean
)

// realm sign-in sql (oidc mode). open: unit tests fake it.
// tenant filter on every lookup: a realm person is only ever looked up in the token's organization.
open class RealmUsers(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val rolesCache: Duration,
    private val clock: Clock = Clock.systemUTC()
) {
    private class Entry(
        val roles: List<String>,
        val loadedAt: Instant
    )

    private class Found(
        val user: RealmUser,
        val displayName: String
    )

    private val cache = ConcurrentHashMap<UUID, Entry>()

    // same query as the local login's roles
    private val roleQueries = RoleQueries(db, schemas)

    // the realm's organization alias is the slug
    open suspend fun organizationIdBySlug(slug: String): UUID? =
        db
            .sql("SELECT id FROM ${schemas.metadata}.organizations WHERE slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitFirstOrNull()

    // by subject; else, first sign-in of an invited person: by verified email among unlinked rows, and link.
    // null: not invited. then the row follows the token's email (verified only) and name.
    open suspend fun resolve(
        organizationId: UUID,
        subject: String,
        email: String?,
        emailVerified: Boolean,
        displayName: String?
    ): RealmUser? {
        val found =
            bySubject(organizationId, subject)
                ?: (if (emailVerified && !email.isNullOrBlank()) link(organizationId, subject, email) else null)
                ?: return null
        var user = found.user
        if (emailVerified && !email.isNullOrBlank() && email != user.email && updateEmail(user, email)) {
            user = user.copy(email = email)
        }
        if (!displayName.isNullOrBlank() && displayName != found.displayName) updateDisplayName(user, displayName)
        return user
    }

    // cached per user for rolesCache; zero = every call reads the database
    open suspend fun roles(userId: UUID): List<String> {
        val entry = cache[userId]
        if (entry != null && Instant.now(clock).isBefore(entry.loadedAt.plus(rolesCache))) return entry.roles
        val roles = roleQueries.roleNamesOf(userId)
        remember(userId, Entry(roles, Instant.now(clock)))
        return roles
    }

    private suspend fun bySubject(
        organizationId: UUID,
        subject: String
    ): Found? =
        db
            .sql(
                "SELECT id, organization_id, email, display_name, enabled FROM ${schemas.metadata}.users " +
                    "WHERE organization_id = :organizationId AND external_subject = :subject"
            ).bind("organizationId", organizationId)
            .bind("subject", subject)
            .map { row, _ -> found(row) }
            .one()
            .awaitFirstOrNull()

    // two unlinked rows with the same address in another case: no guessing, not invited
    private suspend fun link(
        organizationId: UUID,
        subject: String,
        email: String
    ): Found? {
        val candidates =
            db
                .sql(
                    "SELECT id FROM ${schemas.metadata}.users " +
                        "WHERE organization_id = :organizationId AND external_subject IS NULL AND lower(email) = lower(:email)"
                ).bind("organizationId", organizationId)
                .bind("email", email)
                .map { row, _ -> Rows.uuid(row, "id") }
                .all()
                .asFlow()
                .toList()
        val id = candidates.singleOrNull() ?: return null
        // IS NULL again: a concurrent first sign-in may have linked it. then the subject finds it, or not ours.
        return db
            .sql(
                "UPDATE ${schemas.metadata}.users SET external_subject = :subject, updated_at = now() " +
                    "WHERE id = :id AND organization_id = :organizationId AND external_subject IS NULL " +
                    "RETURNING id, organization_id, email, display_name, enabled"
            ).bind("subject", subject)
            .bind("id", id)
            .bind("organizationId", organizationId)
            .map { row, _ -> found(row) }
            .one()
            .awaitFirstOrNull()
            ?: bySubject(organizationId, subject)
    }

    // skipped when another row of the organization already has that address (unique per organization)
    private suspend fun updateEmail(
        user: RealmUser,
        email: String
    ): Boolean =
        (
            db
                .sql(
                    """
                    UPDATE ${schemas.metadata}.users u SET email = :email, updated_at = now()
                    WHERE u.id = :id AND u.organization_id = :organizationId
                    AND NOT EXISTS (
                        SELECT 1 FROM ${schemas.metadata}.users o
                        WHERE o.organization_id = :organizationId AND o.email = :email AND o.id <> :id
                    )
                    """.trimIndent()
                ).bind("email", email)
                .bind("id", user.id)
                .bind("organizationId", user.organizationId)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull() ?: 0L
        ) > 0L

    private suspend fun updateDisplayName(
        user: RealmUser,
        displayName: String
    ) {
        db
            .sql(
                "UPDATE ${schemas.metadata}.users SET display_name = :displayName, updated_at = now() " +
                    "WHERE id = :id AND organization_id = :organizationId"
            ).bind("displayName", displayName)
            .bind("id", user.id)
            .bind("organizationId", user.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    private fun found(row: Row): Found =
        Found(
            RealmUser(Rows.uuid(row, "id"), Rows.uuid(row, "organization_id"), Rows.string(row, "email"), Rows.bool(row, "enabled")),
            Rows.string(row, "display_name")
        )

    // same shape as TokenRevocation: nothing kept with a zero ttl, expired entries swept once the map grows
    private fun remember(
        userId: UUID,
        entry: Entry
    ) {
        if (rolesCache.isZero) return
        if (cache.size >= SWEEP_AT) {
            val now = Instant.now(clock)
            cache.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(rolesCache)) }
        }
        cache[userId] = entry
    }

    private companion object {
        const val SWEEP_AT = 10_000
    }
}
