package wasichai.core.identity

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val organizationId: UUID,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

data class Role(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val label: String,
    val ownRecordsOnly: Boolean = false,
    val createdAt: Instant? = null
)

// what login needs. plain sql: a @Table annotation cannot follow a configurable schema.
@Repository
class UserRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun findByEmail(email: String): User? =
        db
            .sql(
                "SELECT id, organization_id, email, password_hash, display_name, enabled, created_at, updated_at " +
                    "FROM ${schemas.metadata}.users WHERE email = :email"
            ).bind("email", email)
            .map { row, _ ->
                User(
                    id = Rows.uuid(row, "id"),
                    organizationId = Rows.uuid(row, "organization_id"),
                    email = Rows.string(row, "email"),
                    passwordHash = Rows.string(row, "password_hash"),
                    displayName = Rows.string(row, "display_name"),
                    enabled = Rows.bool(row, "enabled"),
                    createdAt = Rows.instantOrNull(row, "created_at"),
                    updatedAt = Rows.instantOrNull(row, "updated_at")
                )
            }.one()
            .awaitFirstOrNull()
}
