package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import wasichai.core.audit.AuditService
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.time.Instant
import java.util.UUID

// issue 48 (ADR-048): every RecordService read carries the app's read scope, next to the owner filter.
// GIS features and the agent's record tools read through list, rows and get, so they carry it too.
class RecordServiceReadScopeTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val projectId = ObjectDefinitionFixtures.field("project_id", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, projectId))
    private val organizationId = definition.obj.organizationId
    private val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val scope = RecordCriterion { _, bind -> "project_id = ${bind("A")}" }
    private val bbox = RecordCriterion { _, _ -> "true" }
    private val asked = mutableListOf<AuthenticatedUser>()

    private val projectA =
        object : RecordReadScope {
            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ): RecordCriterion {
                asked += caller
                return scope
            }
        }

    private val queries = mutableListOf<RecordQuery>()
    private val lookups = mutableListOf<List<RecordCriterion>>()
    private val writes = mutableListOf<String>()

    // what findById answers. null: missing, or out of scope
    private var stored: RecordRow? = null

    private val store =
        object : RecordStore {
            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow {
                writes += "insert"
                return RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), attributes)
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow {
                writes += "update"
                return RecordRow(id, Instant.now(), Instant.now(), attributes)
            }

            override suspend fun transitionState(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                id: UUID,
                from: String?,
                to: String
            ): RecordRow? = null

            override suspend fun delete(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID
            ): Boolean {
                writes += "delete"
                return true
            }

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow? {
                lookups += criteria
                return stored?.copy(id = id)
            }

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> {
                queries += query
                return PageResponse.of(emptyList(), 0, 25, 0)
            }
        }

    private suspend fun service(
        caller: AuthenticatedUser = person,
        vararg scopes: RecordReadScope
    ): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(caller).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(FieldAccess.FULL).`when`(access).fieldAccess(caller, definition.obj.id)
        return RecordService(
            metadata,
            store,
            mock(AuditService::class.java),
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            emptyList(),
            RecordWriteGuards(emptyList(), RelationTargetsFixtures.none()),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopes(scopes.toList(), mock(RecordStore::class.java)),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none()
        )
    }

    private val query = RecordQuery(page = PageRequest.of(0, 25), criteria = listOf(bbox))

    // the condition a read was handed, as sql
    private fun RecordCriterion.sql(): String = condition(definition) { ":$it" }

    @Test
    fun `with no scope declared every read is the one it was`() =
        runTest {
            stored = RecordRow(UUID.randomUUID(), null, null, emptyMap())
            val records = service()

            records.list("predio", query)
            records.rows("predio", query)
            records.get("predio", UUID.randomUUID())
            records.update("predio", UUID.randomUUID(), RecordRequest(mapOf("codigo" to "X")))
            records.delete("predio", UUID.randomUUID())

            assertThat(queries.map { it.criteria }).containsOnly(listOf(bbox))
            assertThat(lookups).allSatisfy { assertThat(it).isEmpty() }
        }

    @Test
    fun `a list, its count and rows carry the scope after the module criteria`() =
        runTest {
            val records = service(person, projectA)

            records.list("predio", query)
            records.rows("predio", query)

            assertThat(queries).hasSize(2).allSatisfy { read ->
                assertThat(read.criteria).hasSize(2)
                assertThat(read.criteria.first()).isSameAs(bbox)
                assertThat(read.criteria.last().sql()).isEqualTo("project_id = :A")
                assertThat(read.count).isTrue()
            }
        }

    @Test
    fun `a record out of scope is a 404 on get`() =
        runTest {
            stored = null
            val id = UUID.randomUUID()

            val ex = runCatching { service(person, projectA).get("predio", id) }.exceptionOrNull()

            assertThat(ex).isInstanceOf(NotFoundException::class.java).hasMessage("Record $id does not exist")
            assertThat(lookups.single().map { it.sql() }).containsExactly("project_id = :A")
        }

    @Test
    fun `a record out of scope is a 404 on update and delete, and nothing is written`() =
        runTest {
            stored = null
            val records = service(person, projectA)

            val update = runCatching { records.update("predio", UUID.randomUUID(), RecordRequest(mapOf("codigo" to "X"))) }.exceptionOrNull()
            val delete = runCatching { records.delete("predio", UUID.randomUUID()) }.exceptionOrNull()

            assertThat(update).isInstanceOf(NotFoundException::class.java)
            assertThat(delete).isInstanceOf(NotFoundException::class.java)
            assertThat(lookups).hasSize(2).allSatisfy { assertThat(it.map { c -> c.sql() }).containsExactly("project_id = :A") }
            assertThat(writes).isEmpty()
        }

    @Test
    fun `a record in scope reads, updates and deletes as before`() =
        runTest {
            stored = RecordRow(UUID.randomUUID(), null, null, mapOf("codigo" to "A-1"))
            val records = service(person, projectA)

            records.get("predio", UUID.randomUUID())
            records.update("predio", UUID.randomUUID(), RecordRequest(mapOf("codigo" to "A-2")))
            records.delete("predio", UUID.randomUUID())

            assertThat(writes).containsExactly("update", "delete")
        }

    @Test
    fun `ADMIN is never asked`() =
        runTest {
            val admin = person.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE))

            service(admin, projectA).list("predio", query)

            assertThat(asked).isEmpty()
            assertThat(queries.single().criteria).containsExactly(bbox)
        }

    @Test
    fun `the platform is never asked`() =
        runTest {
            val records = service(person, projectA)

            records.asPlatform(organizationId) { records.list("predio", query) }

            assertThat(asked).isEmpty()
            assertThat(queries.single().criteria).containsExactly(bbox)
        }

    @Test
    fun `a service account is asked like a person`() =
        runTest {
            val account = person.copy(email = "", serviceAccount = "erp")

            service(account, projectA).list("predio", query)

            assertThat(asked).containsExactly(account)
        }
}
