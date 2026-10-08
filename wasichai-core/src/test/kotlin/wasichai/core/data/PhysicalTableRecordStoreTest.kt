package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
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
import java.time.Instant
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

    // issue 48 (ADR-048): a read by id with no owner and no scope is the query it always was
    @Test
    fun `a read by id with no owner and no criteria is unchanged`() {
        val id = UUID.randomUUID()
        val organizationId = UUID.randomUUID()

        val (where, bindings) = store().byIdClause(ObjectDefinitionFixtures.empty(), organizationId, id, null, emptyList())

        assertThat(where).isEqualTo("id = :id AND organization_id = :organizationId")
        assertThat(bindings).containsExactly(entry("id", id), entry("organizationId", organizationId))
    }

    @Test
    fun `a read by id puts the owner filter right behind the tenant, as before`() {
        val owner = UUID.randomUUID()

        val (where, bindings) = store().byIdClause(ObjectDefinitionFixtures.empty(), UUID.randomUUID(), UUID.randomUUID(), owner, emptyList())

        assertThat(where).isEqualTo("id = :id AND organization_id = :organizationId AND created_by = :createdBy")
        assertThat(bindings).containsEntry("createdBy", owner)
    }

    // an app's OR cannot widen the tenant or the owner filter: it ANDs in as one term
    @Test
    fun `a read scope on a read by id is parenthesized behind the tenant and the owner`() {
        val scope = RecordCriterion { _, bind -> "project_id = ${bind("A")} OR project_id = ${bind("B")}" }

        val (where, bindings) =
            store().byIdClause(ObjectDefinitionFixtures.empty(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), listOf(scope))

        assertThat(where).isEqualTo(
            "id = :id AND organization_id = :organizationId AND created_by = :createdBy AND (project_id = :c3 OR project_id = :c4)"
        )
        assertThat(bindings).containsEntry("c3", "A").containsEntry("c4", "B")
    }

    @Test
    fun `a read scope on a list joins after the module criteria, each in its own parens`() {
        val bbox = RecordCriterion { _, bind -> "x = ${bind("v")}" }
        val scope = RecordCriterion { _, bind -> "project_id = ${bind("A")} OR project_id = ${bind("B")}" }

        val (where, _) =
            store().whereClause(
                ObjectDefinitionFixtures.empty(),
                UUID.randomUUID(),
                RecordQuery(page = PageRequest.of(0, 10), createdBy = UUID.randomUUID(), criteria = listOf(bbox, scope))
            )

        assertThat(where).isEqualTo(
            "organization_id = :organizationId AND created_by = :createdBy AND (x = :c2) AND (project_id = :c3 OR project_id = :c4)"
        )
    }

    @Test
    fun `a blank criterion on a read by id is rejected too`() {
        assertThatThrownBy {
            store().byIdClause(ObjectDefinitionFixtures.empty(), UUID.randomUUID(), UUID.randomUUID(), null, listOf(RecordCriterion { _, _ -> " " }))
        }.isInstanceOf(IllegalStateException::class.java)
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

    // issue 21: a keyset read resumes after (sort value, id), the same pair the ORDER BY ends on
    @Test
    fun `a cursor round-trips, even a value with line breaks or none at all`() {
        val id = UUID.randomUUID()
        listOf("P-1", "two\nlines", "", null).forEach { value ->
            val cursor = RecordCursor("codigo", true, value, id)
            assertThat(RecordCursor.decode(cursor.encode())).isEqualTo(cursor)
        }
        assertThat(RecordCursor("codigo", false, "x", id).encode()).doesNotContain("codigo", "\n", "=", "+", "/")
    }

    @Test
    fun `a cursor that is not one is a 400 on after`() {
        listOf(
            "nope",
            "",
            "!!!",
            java.util.Base64
                .getUrlEncoder()
                .encodeToString("2\nid\na\nx\n-".toByteArray())
        ).forEach { raw ->
            assertThatThrownBy { RecordCursor.decode(raw) }
                .describedAs(raw)
                .isInstanceOf(ValidationException::class.java)
                .extracting { (it as ValidationException).violations.single().field }
                .isEqualTo("after")
        }
    }

    @Test
    fun `a not-null sort key resumes with a row comparison in the sort direction`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))
        val id = UUID.randomUUID()
        val query = RecordQuery(page = PageRequest.of(0, 10))

        val (asc, bindings) = store().keysetCondition(definition, query, RecordCursor("created_at", false, "2026-10-02 10:00:00+00", id))
        assertThat(asc).isEqualTo("(created_at, id) > (CAST(:afterValue AS timestamptz), :afterId)")
        assertThat(bindings).containsEntry("afterValue", "2026-10-02 10:00:00+00").containsEntry("afterId", id)

        val (desc, _) = store().keysetCondition(definition, query.copy(descending = true), RecordCursor("created_at", true, "x", id))
        assertThat(desc).isEqualTo("(created_at, id) < (CAST(:afterValue AS timestamptz), :afterId)")

        val (byId, idBindings) = store().keysetCondition(definition, query.copy(sort = "id"), RecordCursor("id", false, id.toString(), id))
        assertThat(byId).isEqualTo("id > :afterId")
        assertThat(idBindings).containsOnlyKeys("afterId")
    }

    // nulls sort last ascending and first descending, so the cursor has to step over them the same way
    @Test
    fun `a nullable sort key resumes around its nulls`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))
        val id = UUID.randomUUID()
        val asc = RecordQuery(page = PageRequest.of(0, 10), sort = "codigo")
        val desc = asc.copy(descending = true)
        val c = "\"codigo\""
        val v = "CAST(:afterValue AS text)"

        assertThat(store().keysetCondition(definition, asc, RecordCursor("codigo", false, "P", id)).first)
            .isEqualTo("($c > $v OR ($c = $v AND id > :afterId) OR $c IS NULL)")
        assertThat(store().keysetCondition(definition, asc, RecordCursor("codigo", false, null, id)).first)
            .isEqualTo("($c IS NULL AND id > :afterId)")
        assertThat(store().keysetCondition(definition, desc, RecordCursor("codigo", true, "P", id)).first)
            .isEqualTo("($c < $v OR ($c = $v AND id < :afterId))")
        assertThat(store().keysetCondition(definition, desc, RecordCursor("codigo", true, null, id)).first)
            .isEqualTo("(($c IS NULL AND id < :afterId) OR $c IS NOT NULL)")
        assertThat(store().keysetCondition(definition, desc, RecordCursor("codigo", true, null, id)).second).containsOnlyKeys("afterId")
    }

    @Test
    fun `a cursor from another sort, or after combined with a page, is a 400`() {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))
        val cursor = RecordCursor("created_at", false, "x", UUID.randomUUID())

        assertThatThrownBy { store().keysetCondition(definition, RecordQuery(page = PageRequest.of(0, 10), sort = "codigo"), cursor) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { store().keysetCondition(definition, RecordQuery(page = PageRequest.of(0, 10), descending = true), cursor) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                store().query(definition, UUID.randomUUID(), RecordQuery(page = PageRequest.of(1, 10), after = cursor.encode()))
            }
        }.isInstanceOf(ValidationException::class.java)
            .extracting { (it as ValidationException).violations.single().field }
            .isEqualTo("after")
    }

    // review round 1: a cursor value postgres cannot cast back is the caller's 400, any other failure stays ours
    @Test
    fun `only a postgres data exception counts as a bad cursor value`() {
        val cast = object : io.r2dbc.spi.R2dbcException("invalid input syntax for type bigint", "22P02") {}
        val grammar = object : io.r2dbc.spi.R2dbcException("syntax error", "42601") {}

        assertThat(store().isDataException(RuntimeException("wrapped", cast))).isTrue()
        assertThat(store().isDataException(grammar)).isFalse()
        assertThat(store().isDataException(IllegalStateException("no sqlstate"))).isFalse()
    }

    // ADR-051: the version compare rides in the write's own WHERE, on updated_at in epoch micros
    @Test
    fun `a write with no precondition adds nothing to its where, one with a precondition compares updated_at`() {
        assertThat(store().versionMatch(null)).isEmpty()
        assertThat(store().versionMatch(arrayOf(1L)))
            .isEqualTo(" AND CAST(EXTRACT(EPOCH FROM updated_at) * 1000000 AS bigint) = ANY(:expected)")
    }

    @Test
    fun `versions are epoch micros, and an instant no updated_at can hold matches nothing`() {
        val read = Instant.parse("2026-10-07T10:15:30.123456Z")

        assertThat(store().versions(listOf(read, read))).containsExactly(1_791_368_130_123_456L)
        assertThat(store().versions(listOf(Instant.parse("1969-12-31T23:59:59.5Z")))).containsExactly(-500_000L)
        // finer than postgres keeps, or past a long: never stored, so no statement at all
        assertThat(store().versions(listOf(Instant.parse("2026-10-07T10:15:30.123456789Z")))).isNull()
        assertThat(store().versions(listOf(Instant.ofEpochSecond(Instant.MAX.epochSecond)))).isNull()
        assertThat(store().versions(emptyList())).isNull()
    }
}
