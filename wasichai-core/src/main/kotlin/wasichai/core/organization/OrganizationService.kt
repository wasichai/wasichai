package wasichai.core.organization

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

data class OrganizationResponse(
    val id: String,
    val name: String,
    val slug: String,
    val createdAt: Instant?
)

@Service
class OrganizationService(
    private val organizations: OrganizationRepository,
    private val objects: CustomObjectRepository,
    private val schema: ObjectSchemaManager,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun current(): Organization {
        val user = currentUser.require()
        return organizations.findById(user.organizationId)
            ?: throw NotFoundException("Organization does not exist")
    }

    @Transactional
    suspend fun updateCurrent(request: UpdateOrganizationRequest): Organization {
        currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val organization = current()
        return db
            .sql(
                """
                UPDATE ${schemas.metadata}.organizations SET name = :name, updated_at = now()
                WHERE id = :id
                RETURNING id, name, slug, created_at, updated_at
                """.trimIndent()
            ).bind("id", organization.id)
            .bind("name", request.name.trim())
            .map { row, _ ->
                Organization(
                    id = row.get("id", UUID::class.java)!!,
                    name = row.get("name", String::class.java)!!,
                    slug = row.get("slug", String::class.java)!!
                )
            }.one()
            .awaitSingle()
    }

    // provisioning: a tenant plus the administrator who can then configure it
    @Transactional
    suspend fun provision(request: CreateOrganizationRequest): Organization {
        currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val slug = request.slug.trim().lowercase()
        if (!SLUG.matches(slug)) {
            throw ValidationException("Invalid slug '$slug'", "slug", "must match ^[a-z][a-z0-9-]{1,48}$")
        }
        if (organizations.findBySlug(slug) != null) {
            throw ConflictException("Organization '$slug' already exists")
        }
        if (request.adminPassword.length < MIN_PASSWORD) {
            throw ValidationException("Password too short", "adminPassword", "must be at least $MIN_PASSWORD characters")
        }

        val organizationId = UUID.randomUUID()
        val roleId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        db
            .sql("INSERT INTO ${schemas.metadata}.organizations (id, name, slug) VALUES (:id, :name, :slug)")
            .bind("id", organizationId)
            .bind("name", request.name.trim())
            .bind("slug", slug)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        db
            .sql("INSERT INTO ${schemas.metadata}.roles (id, organization_id, name, label) VALUES (:id, :org, 'ADMIN', 'Administrator')")
            .bind("id", roleId)
            .bind("org", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.permissions (role_id, object_id, action)
                SELECT :roleId, NULL, action
                FROM unnest(ARRAY['READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION']) AS action
                """.trimIndent()
            ).bind("roleId", roleId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name)
                VALUES (:id, :org, :email, :hash, :displayName)
                """.trimIndent()
            ).bind("id", userId)
            .bind("org", organizationId)
            .bind("email", request.adminEmail.trim().lowercase())
            .bind("hash", passwordEncoder.encode(request.adminPassword) as String)
            .bind(
                "displayName",
                request.adminDisplayName
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "Administrator" }
            ).fetch()
            .rowsUpdated()
            .awaitSingle()

        db
            .sql("INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id) VALUES (:userId, :roleId)")
            .bind("userId", userId)
            .bind("roleId", roleId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

        return Organization(id = organizationId, name = request.name.trim(), slug = slug)
    }

    // metadata cascades, but business tables live outside those foreign keys: drop them first
    @Transactional
    suspend fun deleteCurrent() {
        val user = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        objects.findAll(user.organizationId).forEach { schema.dropTable(it) }
        joinTablesOf(user.organizationId).forEach { schema.dropJoinTable(it) }
        db
            .sql("DELETE FROM ${schemas.metadata}.organizations WHERE id = :id")
            .bind("id", user.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun joinTablesOf(organizationId: UUID): List<String> {
        val tables = mutableListOf<String>()
        db
            .sql("SELECT join_table FROM ${schemas.metadata}.relationships WHERE organization_id = :org AND join_table IS NOT NULL")
            .bind("org", organizationId)
            .map { row, _ -> row.get("join_table", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .let { tables.addAll(it) }
        return tables
    }

    companion object {
        private const val MIN_PASSWORD = 8
        private val SLUG = Regex("^[a-z][a-z0-9-]{1,48}$")
    }
}

fun Organization.toResponse(): OrganizationResponse = OrganizationResponse(id = id.toString(), name = name, slug = slug, createdAt = createdAt)
