package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import wasichai.core.common.Actions
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 147 (ADR-075): a read scope's subquery over another object reads it as the caller would
class RecordSubqueriesTest {
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId

    private val version =
        ObjectDefinition(
            ObjectDefinitionFixtures.obj.copy(id = UUID.randomUUID(), name = "version_curso", physicalTable = "version_curso__1111aaaa"),
            listOf(ObjectDefinitionFixtures.field("estado", FieldType.TEXT).copy(columnName = "estado_col"))
        )
    private val unit =
        ObjectDefinition(
            ObjectDefinitionFixtures.obj.copy(id = UUID.randomUUID(), name = "unidad", physicalTable = "unidad__2222bbbb"),
            listOf(ObjectDefinitionFixtures.field("version_curso", FieldType.TEXT).copy(columnName = "version_col"))
        )
    private val lesson =
        ObjectDefinition(
            ObjectDefinitionFixtures.obj.copy(id = UUID.randomUUID(), name = "leccion", physicalTable = "leccion__3333cccc"),
            listOf(ObjectDefinitionFixtures.field("unidad", FieldType.TEXT).copy(columnName = "unidad_col"))
        )

    private val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("ALUMNO"))

    private val metadata = mock(MetadataService::class.java)
    private val currentUser = mock(CurrentUser::class.java)
    private val access = mock(AccessPolicy::class.java)
    private var scopes: List<RecordReadScope> = emptyList()

    private val subqueries =
        RecordSubqueries(
            metadata,
            WasichaiSchemas("wasichai", "app_data"),
            currentUser,
            access,
            FieldTypeRegistry(emptyList())
        ) { RecordReadScopes(scopes, mock(RecordStore::class.java)) }

    private suspend fun given(
        definition: ObjectDefinition,
        readable: Boolean = true
    ) {
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, definition.obj.name)
        doReturn(readable).`when`(currentUser).hasPermission(person, Actions.READ, definition.obj.id)
    }

    // the sql with every bound value written as :<value>, and the values in bind order
    private fun RecordSubquery.rendered(): Pair<String, List<Any>> {
        val values = mutableListOf<Any>()
        val sql =
            sql { value ->
                values += value
                ":c${values.size - 1}"
            }
        return sql to values
    }

    @Test
    fun `a subquery reads the other object's table and columns from metadata, in the caller's organization`() =
        runTest {
            given(version)
            given(unit)

            val units = subqueries.select(person, "unidad", where = mapOf("version_curso" to subqueries.select(person, "version_curso")))
            val (sql, values) = units.rendered()

            assertThat(sql).isEqualTo(
                "SELECT id FROM \"app_data\".\"unidad__2222bbbb\" WHERE organization_id = :c0 AND \"version_col\" IN " +
                    "(SELECT id FROM \"app_data\".\"version_curso__1111aaaa\" WHERE organization_id = :c1)"
            )
            assertThat(values).containsExactly(organizationId, organizationId)
        }

    @Test
    fun `a field is selected by name, and a scalar value is bound through its type`() =
        runTest {
            given(unit)

            val (sql, values) = subqueries.select(person, "unidad", select = "version_curso", where = mapOf("version_curso" to "V1")).rendered()

            assertThat(sql).startsWith("SELECT \"version_col\" FROM").endsWith("AND \"version_col\" = :c1")
            assertThat(values).containsExactly(organizationId, "V1")
        }

    @Test
    fun `null is IS NULL and an id is a uuid`() =
        runTest {
            given(unit)
            val id = UUID.randomUUID()

            val (sql, values) = subqueries.select(person, "unidad", where = mapOf("version_curso" to null, "id" to id.toString())).rendered()

            assertThat(sql).endsWith("AND \"version_col\" IS NULL AND id = :c1")
            assertThat(values).containsExactly(organizationId, id)
        }

    @Test
    fun `no READ on the other object, no row, and its scopes are never asked`() =
        runTest {
            given(unit, readable = false)
            var asked = false
            scopes =
                listOf(
                    object : RecordReadScope {
                        override suspend fun criterion(
                            caller: AuthenticatedUser,
                            definition: ObjectDefinition
                        ): RecordCriterion? {
                            asked = true
                            return null
                        }
                    }
                )

            val (sql, values) = subqueries.select(person, "unidad").rendered()

            assertThat(sql).isEqualTo("SELECT id FROM \"app_data\".\"unidad__2222bbbb\" WHERE false")
            assertThat(values).isEmpty()
            assertThat(asked).isFalse()
        }

    @Test
    fun `own_records_only keeps the subquery to the caller's own records`() =
        runTest {
            given(unit)
            doReturn(person.userId).`when`(access).ownerFilter(person)

            val (sql, values) = subqueries.select(person, "unidad").rendered()

            assertThat(sql).endsWith("WHERE organization_id = :c0 AND created_by = :c1")
            assertThat(values).containsExactly(organizationId, person.userId)
        }

    @Test
    fun `the other object's own read scopes apply, each in parens`() =
        runTest {
            given(version)
            scopes =
                listOf(
                    object : RecordReadScope {
                        override suspend fun criterion(
                            caller: AuthenticatedUser,
                            definition: ObjectDefinition
                        ) = RecordCriterion { handed, bind -> "${handed.column("estado")} = ${bind("PUBLICADO")} OR true" }
                    }
                )

            val (sql, values) = subqueries.select(person, "version_curso").rendered()

            assertThat(sql).endsWith("WHERE organization_id = :c0 AND (\"estado_col\" = :c1 OR true)")
            assertThat(values).containsExactly(organizationId, "PUBLICADO")
        }

    @Test
    fun `a criterion puts a field of the object read in the subquery`() =
        runTest {
            given(unit)

            val criterion = subqueries.select(person, "unidad").criterion("unidad")
            val sql = criterion.condition(lesson) { ":v" }

            assertThat(sql).isEqualTo("\"unidad_col\" IN (SELECT id FROM \"app_data\".\"unidad__2222bbbb\" WHERE organization_id = :v)")
        }

    @Test
    fun `scopes that reach an object again through subqueries are a cycle`() =
        runTest {
            given(unit)
            given(lesson)
            scopes =
                listOf(
                    object : RecordReadScope {
                        override suspend fun criterion(
                            caller: AuthenticatedUser,
                            definition: ObjectDefinition
                        ): RecordCriterion =
                            when (definition.obj.name) {
                                "leccion" -> subqueries.select(caller, "unidad").criterion("unidad")
                                else -> subqueries.select(caller, "leccion", select = "unidad").criterion("id")
                            }
                    }
                )

            assertThat(runCatching { subqueries.select(person, "unidad") }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("unidad -> leccion -> unidad")
        }

    @Test
    fun `an unknown field is refused before any read`() =
        runTest {
            given(unit)

            assertThat(runCatching { subqueries.select(person, "unidad", select = "nope") }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("'unidad' has no field 'nope'")
            assertThat(runCatching { subqueries.select(person, "unidad", where = mapOf("nope" to 1)) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
}
