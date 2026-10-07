package wasichai.core.audit

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.Actions
import wasichai.core.data.ObjectDefinitionFixtures
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 49 (ADR-049): the admin trail is MANAGE_ORGANIZATION's, whole, and nobody else's
class AuditQueryServiceAdminTest {
    private val organizationId = UUID.randomUUID()

    // no ADMIN role: holds MANAGE_ORGANIZATION through a role of its own, and a narrowing read scope
    private val manager = AuthenticatedUser(UUID.randomUUID(), organizationId, "gestor@example.com", listOf("GESTOR"))
    private val reader = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val db = mock(DatabaseClient::class.java)
    private val metadata = mock(MetadataService::class.java)
    private val currentUser = mock(CurrentUser::class.java)
    private val asked = mutableListOf<String>()

    private val scope =
        object : AuditRecordScope {
            override fun appliesTo(caller: AuthenticatedUser) = true

            override suspend fun readable(
                caller: AuthenticatedUser,
                definition: ObjectDefinition,
                ids: Collection<UUID>
            ): Set<UUID> {
                asked += definition.obj.name
                return emptySet()
            }
        }

    private fun service() =
        AuditQueryService(
            db,
            JsonMapper.builder().build(),
            currentUser,
            metadata,
            mock(AccessPolicy::class.java),
            WasichaiSchemas("wasichai", "app_data"),
            scope
        )

    private fun row(
        objectName: String,
        operation: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ) = AuditRow(UUID.randomUUID(), "admin@example.com", objectName, UUID.randomUUID(), operation, null, before, after, null, null, null)

    @Test
    fun `asked for admin entries without MANAGE_ORGANIZATION, the answer is empty, not a 403, and nothing is read`() =
        runTest {
            doReturn(reader).`when`(currentUser).require()
            doReturn(false).`when`(currentUser).hasPermission(reader, Actions.MANAGE_ORGANIZATION)

            val entries = service().list(AdminEntity.ROLE.objectName, null, null, null)

            assertThat(entries).isEmpty()
            // not even READ is asked for: the answer must not depend on it
            verify(currentUser, never()).requirePermission(reader, Actions.READ)
            verifyNoInteractions(db)
        }

    @Test
    fun `a read scope never drops an admin entry and is never asked about one`() =
        runTest {
            val rows = listOf(row(AdminEntity.ROLE.objectName, "UPDATE", mapOf("label" to "A"), mapOf("label" to "B")))

            val kept = service().inScope(manager, rows)

            assertThat(kept).isEqualTo(rows)
            assertThat(asked).isEmpty()
        }

    @Test
    fun `field permissions do not blank an admin entry, and its create and delete list every key`() =
        runTest {
            val rows =
                listOf(
                    row(AdminEntity.PERMISSION.objectName, "UPDATE", mapOf("role" to "R", "*.READ" to true), mapOf("role" to "R", "*.DELETE" to true)),
                    row(AdminEntity.ROLE.objectName, "CREATE", null, mapOf("name" to "R", "label" to "Role")),
                    row(AdminEntity.ROLE.objectName, "DELETE", mapOf("name" to "R", "label" to "Role"), null)
                )

            val entries = service().toEntries(manager, rows)

            assertThat(entries[0].changes).containsExactly(FieldChange("*.READ", true, null), FieldChange("*.DELETE", null, true))
            assertThat(entries[1].changes).containsExactly(FieldChange("name", null, "R"), FieldChange("label", null, "Role"))
            assertThat(entries[2].changes).containsExactly(FieldChange("name", "R", null), FieldChange("label", "Role", null))
            // no object named admin:* is ever looked up: the entry is shown whole
            verify(metadata, never()).loadDefinition(organizationId, AdminEntity.ROLE.objectName)
            verify(metadata, never()).loadDefinition(organizationId, AdminEntity.PERMISSION.objectName)
        }

    @Test
    fun `a record's create still carries no changes`() =
        runTest {
            val obra = ObjectDefinitionFixtures.empty()
            doReturn(obra).`when`(metadata).loadDefinition(obra.obj.organizationId, obra.obj.name)
            val admin = AuthenticatedUser(UUID.randomUUID(), obra.obj.organizationId, "a@example.com", listOf(AuthenticatedUser.ADMIN_ROLE))

            val entries = service().toEntries(admin, listOf(row(obra.obj.name, "CREATE", null, mapOf("codigo" to "X"))))

            assertThat(entries.single().changes).isEmpty()
        }

    @Test
    fun `admin names are exactly the reserved prefix, which no object name can carry`() {
        assertThat(AdminEntity.entries.map { it.objectName }).allMatch { AdminEntity.isAdmin(it) }
        assertThat(AdminEntity.entries.map { it.objectName }).noneMatch { Regex("^[a-z][a-z0-9_]{0,48}$").matches(it) }
        assertThat(AdminEntity.isAdmin("admin")).isFalse()
        assertThat(AdminEntity.isAdmin("administrador")).isFalse()
        assertThat(AdminEntity.isAdmin(null)).isFalse()
    }
}
