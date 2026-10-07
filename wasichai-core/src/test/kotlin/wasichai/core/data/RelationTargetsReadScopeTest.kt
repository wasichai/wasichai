package wasichai.core.data

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Flux
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID
import java.util.function.BiFunction

// issue 48 (ADR-048): a RELATION value may only name a record in the writer's read scope. folded into
// D30's one read per target object, after the role and owner rules.
class RelationTargetsReadScopeTest {
    private val schemas = WasichaiSchemas("wasichai", "app_data")
    private val target = ObjectDefinitionFixtures.obj
    private val organizationId = target.organizationId
    private val projectId = ObjectDefinitionFixtures.field("project_id", FieldType.TEXT)
    private val customer = ObjectDefinitionFixtures.field("customer", FieldType.RELATION).copy(relationTargetObjectId = target.id)
    private val receipt = ObjectDefinition(target.copy(id = UUID.randomUUID(), name = "receipt"), listOf(customer))
    private val member = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val handed = mutableListOf<ObjectDefinition>()

    private val projectA =
        object : RecordReadScope {
            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ): RecordCriterion {
                handed += definition
                return RecordCriterion { _, bind -> "project_id = ${bind("A")} OR project_id = ${bind("B")}" }
            }
        }

    private val spec = mock(DatabaseClient.GenericExecuteSpec::class.java, Answers.RETURNS_SELF)
    private val db = mock(DatabaseClient::class.java)
    private val objects = mock(CustomObjectRepository::class.java)
    private val fields = mock(CustomFieldRepository::class.java)

    // the database finds nothing: every value is out of scope
    private suspend fun targets(vararg scopes: RecordReadScope): RelationTargets {
        val rows = mock(RowsFetchSpec::class.java)
        doReturn(Flux.empty<UUID>()).`when`(rows).all()
        doReturn(rows).`when`(spec).map(any<BiFunction<Row, RowMetadata, UUID>>())
        doReturn(spec).`when`(db).sql(anyString())
        doReturn(target).`when`(objects).findById(organizationId, target.id)
        doReturn(listOf(projectId)).`when`(fields).findByObject(target.id)
        return RelationTargets(db, schemas, objects, fields, RecordReadScopes(scopes.toList(), mock(RecordStore::class.java)))
    }

    private fun sql(): String {
        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(db).sql(captor.capture())
        return captor.value
    }

    @Test
    fun `with no scope the query is D30's`() {
        val relationTargets = RelationTargets(db, schemas, objects, fields, RecordReadScopesFixtures.none())

        assertThat(relationTargets.sql(target, false, emptyList()))
            .isEqualTo("SELECT id FROM ${schemas.dataTable(target.physicalTable)} WHERE organization_id = :organizationId AND id = ANY(:ids)")
        assertThat(relationTargets.sql(target, true, emptyList()))
            .endsWith("AND (created_by = :userId OR NOT COALESCE((${AccessPolicy.ownRecordsOnlyQuery(schemas)}), false))")
    }

    @Test
    fun `a scoped reader's criterion joins last, in parens, with its values bound`() =
        runTest {
            val relationTargets = targets(projectA)
            val unseen = UUID.randomUUID()

            val ex = runCatching { relationTargets.rejectMissing(organizationId, receipt, mapOf("customer" to unseen), reader = member) }.exceptionOrNull()

            assertThat(ex).isInstanceOf(ValidationException::class.java)
            assertThat(sql()).endsWith("false)) AND (project_id = :c0 OR project_id = :c1)")
            verify(spec).bind("c0", "A")
            verify(spec).bind("c1", "B")
            // the target's own definition, fields and all
            assertThat(handed).containsExactly(ObjectDefinition(target, listOf(projectId)))
        }

    @Test
    fun `ADMIN and the platform are not asked, and read as before`() =
        runTest {
            val relationTargets = targets(projectA)
            val admin = member.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE))

            runCatching { relationTargets.rejectMissing(organizationId, receipt, mapOf("customer" to UUID.randomUUID()), reader = admin) }

            assertThat(handed).isEmpty()
            assertThat(sql()).isEqualTo(relationTargets.sql(target, false, emptyList()))
            verify(fields, never()).findByObject(target.id)
        }

    @Test
    fun `a reader with no scope declared reads with D30's rules only`() =
        runTest {
            val relationTargets = targets()

            runCatching { relationTargets.rejectMissing(organizationId, receipt, mapOf("customer" to UUID.randomUUID()), reader = member) }

            assertThat(sql()).isEqualTo(relationTargets.sql(target, true, emptyList()))
            verify(fields, never()).findByObject(target.id)
        }
}
