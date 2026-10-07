package wasichai.core.audit

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 49 (ADR-049): an admin change is an audit_log row like a record's, under its admin:* name, in the actor's tenant
class AuditLogAdminAuditTest {
    private class Captured(
        val organizationId: UUID,
        val userId: UUID?,
        val objectName: String,
        val recordId: UUID?,
        val operation: AuditOperation,
        val before: Any?,
        val after: Any?
    )

    private val written = mutableListOf<Captured>()

    private val audit =
        object : AuditService(mock(DatabaseClient::class.java), JsonMapper.builder().build(), WasichaiSchemas("wasichai", "app_data")) {
            override suspend fun record(
                organizationId: UUID,
                userId: UUID?,
                objectName: String,
                recordId: UUID?,
                operation: AuditOperation,
                before: Any?,
                after: Any?,
                documentId: UUID?,
                reason: String?
            ) {
                written += Captured(organizationId, userId, objectName, recordId, operation, before, after)
            }
        }

    @Test
    fun `the actor, the entity id and both states go to the audit log under the admin name`() =
        runTest {
            val actor = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "admin@example.com", listOf(AuthenticatedUser.ADMIN_ROLE))
            val roleId = UUID.randomUUID()

            AuditLogAdminAudit(audit).record(actor, AdminEntity.PERMISSION, roleId, AdminOperation.UPDATE, mapOf("*.READ" to true), mapOf())

            val row = written.single()
            assertThat(row.organizationId).isEqualTo(actor.organizationId)
            assertThat(row.userId).isEqualTo(actor.userId)
            assertThat(row.objectName).isEqualTo("admin:permission")
            assertThat(row.recordId).isEqualTo(roleId)
            assertThat(row.operation).isEqualTo(AuditOperation.UPDATE)
            assertThat(row.before).isEqualTo(mapOf("*.READ" to true))
            assertThat(row.after).isEqualTo(emptyMap<String, Any?>())
        }

    // the audit_log CHECK accepts CREATE, UPDATE and DELETE from core: every admin operation is one of them
    @Test
    fun `every admin operation is a record operation the CHECK already accepts`() {
        assertThat(AdminOperation.entries.map { AuditOperation.valueOf(it.name) })
            .containsExactly(AuditOperation.CREATE, AuditOperation.UPDATE, AuditOperation.DELETE)
    }
}
