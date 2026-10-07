package wasichai.core.audit

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.NotFoundException
import wasichai.core.data.ObjectDefinitionFixtures
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 48 (ADR-048): the audit log shows a scoped caller only the entries of records they read
class AuditQueryServiceScopeTest {
    private val obra = ObjectDefinitionFixtures.empty()
    private val persona = ObjectDefinition(obra.obj.copy(id = UUID.randomUUID(), name = "persona"), emptyList())
    private val organizationId = obra.obj.organizationId
    private val user = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val inScope = UUID.randomUUID()
    private val outOfScope = UUID.randomUUID()
    private val deleted = UUID.randomUUID()
    private val anyPersona = UUID.randomUUID()

    private val asked = mutableListOf<Pair<String, Collection<UUID>>>()

    // scoped on obra (only [inScope] exists in scope); persona carries no scope for this caller
    private fun scope(applies: Boolean = true) =
        object : AuditRecordScope {
            override fun appliesTo(caller: AuthenticatedUser) = applies

            override suspend fun readable(
                caller: AuthenticatedUser,
                definition: ObjectDefinition,
                ids: Collection<UUID>
            ): Set<UUID>? {
                asked += definition.obj.name to ids
                return if (definition.obj.name == obra.obj.name) setOf(inScope) else null
            }
        }

    private suspend fun service(scope: AuditRecordScope): AuditQueryService {
        val metadata = mock(MetadataService::class.java)
        val currentUser = mock(CurrentUser::class.java)
        doReturn(obra).`when`(metadata).loadDefinition(organizationId, obra.obj.name)
        doReturn(persona).`when`(metadata).loadDefinition(organizationId, "persona")
        doThrow(NotFoundException("Object 'gone' does not exist")).`when`(metadata).loadDefinition(organizationId, "gone")
        doReturn(user).`when`(currentUser).require()
        return AuditQueryService(
            mock(DatabaseClient::class.java),
            JsonMapper.builder().build(),
            currentUser,
            metadata,
            mock(AccessPolicy::class.java),
            WasichaiSchemas("wasichai", "app_data"),
            scope
        )
    }

    private fun row(
        objectName: String,
        recordId: UUID?
    ) = AuditRow(UUID.randomUUID(), "ana@example.com", objectName, recordId, "UPDATE", null, null, null, null, null, null)

    private val rows =
        listOf(
            row(obra.obj.name, inScope),
            row(obra.obj.name, outOfScope),
            row(obra.obj.name, deleted),
            row("persona", anyPersona),
            row("gone", UUID.randomUUID()),
            row(obra.obj.name, null)
        )

    @Test
    fun `with no scope for the caller every entry stands and nothing is asked`() =
        runTest {
            val kept = service(scope(applies = false)).inScope(user, rows)

            assertThat(kept).isSameAs(rows)
            assertThat(asked).isEmpty()
        }

    @Test
    fun `out-of-scope and deleted records leave, unscoped objects and record-less entries stay`() =
        runTest {
            val kept = service(scope()).inScope(user, rows)

            assertThat(kept.map { it.recordId }).containsExactly(inScope, anyPersona, null)
        }

    // a gone object cannot be asked about: with a scope declared, its entries go (fail closed)
    @Test
    fun `entries of an object that is gone leave too`() =
        runTest {
            val kept = service(scope()).inScope(user, rows)

            assertThat(kept.map { it.objectName }).doesNotContain("gone")
        }

    @Test
    fun `one ask per object, every id of it at once`() =
        runTest {
            service(scope()).inScope(user, rows)

            assertThat(asked.map { it.first }).containsExactlyInAnyOrder(obra.obj.name, "persona")
            assertThat(asked.first { it.first == obra.obj.name }.second).containsExactlyInAnyOrder(inScope, outOfScope, deleted)
        }

    @Test
    fun `the history of a record out of scope is a 404, as GET on it`() =
        runTest {
            val ex = runCatching { service(scope()).history(obra.obj.name, outOfScope, null) }.exceptionOrNull()

            assertThat(ex).isInstanceOf(NotFoundException::class.java).hasMessage("Record $outOfScope does not exist")
        }
}
