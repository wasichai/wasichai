package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 48 (ADR-048): who is asked, what the criteria see, and how they join a read
class RecordReadScopesTest {
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val projectId = ObjectDefinitionFixtures.field("project_id", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, projectId))

    private val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))
    private val admin = AuthenticatedUser(UUID.randomUUID(), organizationId, "admin@example.com", listOf(AuthenticatedUser.ADMIN_ROLE))

    // a service account is never ADMIN, whatever its roles say (ADR-043)
    private val account = AuthenticatedUser(UUID.randomUUID(), organizationId, "", listOf(AuthenticatedUser.ADMIN_ROLE), serviceAccount = "erp")

    private val asked = mutableListOf<AuthenticatedUser>()
    private val seen = mutableListOf<ObjectDefinition>()

    // the app's rule: project A only, and the definition it was handed noted
    private val projectA =
        object : RecordReadScope {
            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ): RecordCriterion {
                asked += caller
                return RecordCriterion { handed, bind ->
                    seen += handed
                    "\"project_id\" = ${bind("A")}"
                }
            }
        }

    private val noRestriction =
        object : RecordReadScope {
            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ): RecordCriterion? = null
        }

    private val nothing =
        object : RecordReadScope {
            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ) = RecordCriterion { _, _ -> "false" }
        }

    private val queries = mutableListOf<Pair<ObjectDefinition, RecordQuery>>()
    private var found: List<UUID> = emptyList()

    private val store =
        object : RecordStore by mock(RecordStore::class.java) {
            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> {
                queries += definition to query
                return PageResponse.of(found.map { RecordRow(it, null, null, emptyMap()) }, 0, query.page.size, null)
            }
        }

    private fun scopes(vararg scopes: RecordReadScope) = RecordReadScopes(scopes.toList(), store)

    private fun whereOf(criteria: List<RecordCriterion>): Pair<String, Map<String, Any>> =
        PhysicalTableRecordStore(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data"), FieldTypeRegistry(emptyList()))
            .whereClause(definition, organizationId, RecordQuery(page = PageRequest.of(0, 10), criteria = criteria))

    @Test
    fun `with no scope declared nothing is asked and every read stays as it was`() =
        runTest {
            val none = scopes()

            assertThat(none.appliesTo(person)).isFalse()
            assertThat(none.criteria(person, definition)).isEmpty()
            assertThat(none.readable(person, definition, listOf(UUID.randomUUID()))).isNull()
            assertThat(queries).isEmpty()
        }

    @Test
    fun `a person and a service account are asked`() =
        runTest {
            val scoped = scopes(projectA)

            assertThat(scoped.criteria(person, definition)).hasSize(1)
            assertThat(scoped.criteria(account, definition)).hasSize(1)
            assertThat(asked).containsExactly(person, account)
        }

    @Test
    fun `ADMIN and the platform are never asked`() =
        runTest {
            val scoped = scopes(projectA)

            assertThat(scoped.appliesTo(admin)).isFalse()
            assertThat(scoped.criteria(admin, definition)).isEmpty()
            assertThat(scoped.criteria(null, definition)).isEmpty()
            assertThat(scoped.readable(admin, definition, listOf(UUID.randomUUID()))).isNull()
            assertThat(asked).isEmpty()
            assertThat(queries).isEmpty()
        }

    @Test
    fun `a scope that answers null leaves the caller unrestricted on that object`() =
        runTest {
            val open = scopes(noRestriction)

            assertThat(open.criteria(person, definition)).isEmpty()
            assertThat(open.readable(person, definition, listOf(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `several scopes all apply, in order, each in its own parens`() =
        runTest {
            val both = scopes(projectA, noRestriction, nothing)

            val (where, bindings) = whereOf(both.criteria(person, definition))

            assertThat(where).isEqualTo("organization_id = :organizationId AND (\"project_id\" = :c1) AND (false)")
            assertThat(bindings).containsEntry("c1", "A")
        }

    // a criterion that matches nothing: the read finds nothing and counts 0
    @Test
    fun `an empty scope joins as a term that matches nothing`() =
        runTest {
            val (where, _) = whereOf(scopes(nothing).criteria(person, definition))

            assertThat(where).isEqualTo("organization_id = :organizationId AND (false)")
        }

    // the store reads with the caller's projection; the scope may name a field that projection hides
    @Test
    fun `the condition is always handed the full definition`() =
        runTest {
            val narrowed = definition.copy(fields = listOf(codigo))
            val criterion = scopes(projectA).criteria(person, definition).single()

            criterion.condition(narrowed) { ":v" }

            assertThat(seen).containsExactly(definition)
        }

    @Test
    fun `readable asks the store for the ids in scope, selecting no field`() =
        runTest {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            found = listOf(a)

            val readable = scopes(projectA).readable(person, definition, listOf(a, b, a))

            assertThat(readable).containsExactly(a)
            val (read, query) = queries.single()
            assertThat(read.fields).isEmpty()
            assertThat(query.ids).containsExactly(a, b)
            assertThat(query.count).isFalse()
            assertThat(query.page.size).isEqualTo(2)
            assertThat(query.criteria).hasSize(1)
            assertThat(query.createdBy).isNull()
        }

    @Test
    fun `readable of no id reads nothing`() =
        runTest {
            assertThat(scopes(projectA).readable(person, definition, emptyList())).isEmpty()
            assertThat(queries).isEmpty()
        }
}
