package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

class PhysicalTableRecordStoreTest {
    private val measure = FieldType("MEASURE")

    private val measureHandler =
        object : FieldTypeHandler {
            override val type = measure
            override val section = "measures"

            override fun columnType(field: CustomField) = "numeric"

            override fun select(
                field: CustomField,
                column: String
            ) = "CAST($column AS text) AS ${SqlIdentifier.quote(readName(field))}"

            override fun readName(field: CustomField) = field.columnName + "__txt"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    private fun store(vararg extra: FieldTypeHandler) =
        PhysicalTableRecordStore(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data"), FieldTypeRegistry(extra.toList()))

    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)

    @Test
    fun `core columns are selected plainly and the state only when asked`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))

        assertThat(store().selectList(definition, false)).isEqualTo("id, created_at, updated_at, \"codigo\"")
        assertThat(store().selectList(definition, true)).isEqualTo("id, created_at, updated_at, \"codigo\", \"workflow_state\"")
    }

    @Test
    fun `a module type is selected through its own expression`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))

        assertThat(store(measureHandler).selectList(definition, false))
            .isEqualTo("id, created_at, updated_at, \"codigo\", CAST(\"area\" AS text) AS \"area__txt\"")
    }

    // issue 20: rows of one transaction share created_at, so OFFSET paging needs a unique last key
    @Test
    fun `every order ends with id in the direction of the primary sort`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))
        val page = PageRequest.of(0, 10)

        assertThat(store().orderBy(definition, RecordQuery(page = page))).isEqualTo("ORDER BY created_at ASC, id ASC")
        assertThat(store().orderBy(definition, RecordQuery(page = page, sort = "codigo", descending = true)))
            .isEqualTo("ORDER BY \"codigo\" DESC, id DESC")
        assertThat(store().orderBy(definition, RecordQuery(page = page, sort = "updated_at")))
            .isEqualTo("ORDER BY updated_at ASC, id ASC")
    }

    @Test
    fun `sorting by id does not repeat id`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))

        assertThat(store().orderBy(definition, RecordQuery(page = PageRequest.of(0, 10), sort = "id", descending = true)))
            .isEqualTo("ORDER BY id DESC")
    }

    // fix round 1, finding 1: a contributed criterion must not be able to widen the WHERE past
    // organization_id by returning an unparenthesized OR.
    @Test
    fun `a module criterion is parenthesized, so an OR inside it cannot escape the tenancy guard`() {
        val criterion = RecordCriterion { _, bind -> "x = ${bind("v1")} OR y = ${bind("v2")}" }

        val (where, bindings) =
            store().whereClause(
                ObjectDefinitionFixtures.empty(),
                UUID.randomUUID(),
                RecordQuery(page = PageRequest.of(0, 10), criteria = listOf(criterion))
            )

        assertThat(where).isEqualTo("organization_id = :organizationId AND (x = :c1 OR y = :c2)")
        assertThat(bindings).containsEntry("c1", "v1").containsEntry("c2", "v2")
    }

    @Test
    fun `a blank criterion condition is rejected instead of joining an empty pair of parens`() {
        val blank = RecordCriterion { _, _ -> "   " }

        assertThatThrownBy {
            store().whereClause(
                ObjectDefinitionFixtures.empty(),
                UUID.randomUUID(),
                RecordQuery(page = PageRequest.of(0, 10), criteria = listOf(blank))
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    // fix round 1, finding 3: sectionValues is the piece that decides clear/leave-alone/reject.
    @Test
    fun `sectionValues clears a column the caller sends as null`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))
        val area = definition.fields.single { it.name == "area" }

        val values = store(measureHandler).sectionValues(definition, mapOf("measures" to mapOf("area" to null)))

        assertThat(values).containsKey(area)
        assertThat(values[area]).isNull()
    }

    @Test
    fun `sectionValues leaves a section key the caller never mentioned untouched`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))
        val area = definition.fields.single { it.name == "area" }

        val values = store(measureHandler).sectionValues(definition, mapOf("measures" to emptyMap()))

        assertThat(values).doesNotContainKey(area)
    }

    @Test
    fun `sectionValues rejects a key naming no field of that section with the handler's unknown-key message`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))

        assertThatThrownBy { store(measureHandler).sectionValues(definition, mapOf("measures" to mapOf("bogus" to 1))) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("bogus")
    }

    @Test
    fun `bindValues null-binds a cleared column under the field's java type, and binds a present one`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, ObjectDefinitionFixtures.field("area", measure)))
        val codigoField = definition.fields.single { it.name == "codigo" }
        val areaField = definition.fields.single { it.name == "area" }
        val spec = mock(DatabaseClient.GenericExecuteSpec::class.java, Answers.RETURNS_SELF)

        store(measureHandler).bindValues(spec, "s", linkedMapOf(codigoField to null, areaField to "12.5"))

        verify(spec).bindNull("s0", String::class.java)
        verify(spec).bind("s1", "12.5")
    }
}
