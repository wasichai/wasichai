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
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.PasswordPolicy
import wasichai.core.identity.UserInfo
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

// every change leaves one admin audit entry in the caller's tenant (ADR-049). audit_log has no foreign key to
// organizations, so a deleted tenant's entries stay.
@Service
class OrganizationService(
    private val organizations: OrganizationRepository,
    private val objects: CustomObjectRepository,
    private val schema: ObjectSchemaManager,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val audit: AdminAudit,
    private val passwordPolicy: PasswordPolicy
) {
    suspend fun current(): Organization {
        val user = currentUser.require()
        return organizations.findById(user.organizationId)
            ?: throw NotFoundException("Organization does not exist")
    }

    @Transactional
    suspend fun updateCurrent(request: UpdateOrganizationRequest): Organization {
        val actor = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
        val organization = current()
        val updated =
            db
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
        audit.record(actor, AdminEntity.ORGANIZATION, organization.id, AdminOperation.UPDATE, snapshot(organization), snapshot(updated))
        return updated
    }

    // provisioning: a tenant plus the administrator who can then configure it.
    // MANAGE_TENANTS: MANAGE_ORGANIZATION as ever, unless wasichai.organizations.separate-provisioning (ADR-055)
    @Transactional
    suspend fun provision(request: CreateOrganizationRequest): Organization {
        val actor = currentUser.requireWithPermission(Actions.MANAGE_TENANTS)
        val slug = request.slug.trim().lowercase()
        if (!SLUG.matches(slug)) {
            throw ValidationException("Invalid slug '$slug'", "slug", "must match ^[a-z][a-z0-9-]{1,48}$")
        }
        if (organizations.findBySlug(slug) != null) {
            throw ConflictException("Organization '$slug' already exists")
        }
        val organizationId = UUID.randomUUID()
        val adminEmail = request.adminEmail.trim().lowercase()
        // the request's own field name, as it always was
        PasswordPolicy.enforce(passwordPolicy, request.adminPassword, UserInfo(adminEmail, request.adminDisplayName?.trim(), organizationId), "adminPassword")

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

        // every action but MANAGE_TENANTS: the new tenant's administrator never creates or deletes tenants (ADR-055)
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

        val created = Organization(id = organizationId, name = request.name.trim(), slug = slug)
        // the provisioner's act, in the provisioner's trail. the administrator by address, never the password
        audit.record(
            actor,
            AdminEntity.ORGANIZATION,
            organizationId,
            AdminOperation.CREATE,
            null,
            snapshot(created) + ("adminEmail" to request.adminEmail.trim().lowercase())
        )
        return created
    }

    // metadata cascades, but business tables live outside those foreign keys: drop them first.
    // MANAGE_TENANTS, as provisioning (ADR-055)
    @Transactional
    suspend fun deleteCurrent() {
        val user = currentUser.requireWithPermission(Actions.MANAGE_TENANTS)
        // null when already gone: deleting nothing stays a 204, and leaves no entry
        val organization = organizations.findById(user.organizationId)
        objects.findAll(user.organizationId).forEach { schema.dropTable(it) }
        joinTablesOf(user.organizationId).forEach { schema.dropJoinTable(it) }
        db
            .sql("DELETE FROM ${schemas.metadata}.organizations WHERE id = :id")
            .bind("id", user.organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        organization?.let { audit.record(user, AdminEntity.ORGANIZATION, it.id, AdminOperation.DELETE, snapshot(it), null) }
    }

    private fun snapshot(organization: Organization): Map<String, Any?> = linkedMapOf("name" to organization.name, "slug" to organization.slug)

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
        private val SLUG = Regex("^[a-z][a-z0-9-]{1,48}$")
    }
}

fun Organization.toResponse(): OrganizationResponse = OrganizationResponse(id = id.toString(), name = name, slug = slug, createdAt = createdAt)
