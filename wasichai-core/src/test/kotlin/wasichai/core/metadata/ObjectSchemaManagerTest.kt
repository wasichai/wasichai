package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

class ObjectSchemaManagerTest {
    private val measure = FieldType("MEASURE")

    // a module type: its column type reads an attribute, and it asks for its own index
    private val measureHandler =
        object : FieldTypeHandler {
            override val type = measure

            override fun columnType(field: CustomField) = "numeric(12, ${field.attributes["scale"]})"

            override fun indexes(
                obj: CustomObject,
                table: String,
                field: CustomField
            ) = listOf(
                "CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "mix"))} " +
                    "ON $table (${SqlIdentifier.quote(field.columnName)})"
            )

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

    private fun manager(schemas: WasichaiSchemas = WasichaiSchemas("wasichai", "app_data")) =
        ObjectSchemaManager(mock(DatabaseClient::class.java), schemas, FieldTypeRegistry(listOf(measureHandler)))

    private val obj =
        CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)

    private fun field(
        name: String,
        type: FieldType,
        required: Boolean = false,
        unique: Boolean = false,
        enumOptions: List<String>? = null,
        relationTarget: UUID? = null,
        attributes: Map<String, Any?> = emptyMap(),
        indexed: Boolean = false
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = obj.id,
        name = name,
        label = name,
        type = type,
        columnName = name,
        required = required,
        unique = unique,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = enumOptions,
        relationTargetObjectId = relationTarget,
        attributes = attributes,
        visible = true,
        editable = true,
        indexed = indexed
    )

    @Test
    fun `a core field gets its postgres type and its constraints`() {
        assertThat(manager().columnDefinition(field("codigo", FieldType.TEXT, required = true, unique = true), emptyMap()))
            .isEqualTo("\"codigo\" text NOT NULL UNIQUE")
        assertThat(manager().columnDefinition(field("uso", FieldType.ENUM, enumOptions = listOf("A", "B'C")), emptyMap()))
            .isEqualTo("\"uso\" text CHECK (\"uso\" IN ('A', 'B''C'))")
    }

    @Test
    fun `a relation points at the target table in the configured data schema`() {
        val target = UUID.randomUUID()
        val ddl =
            manager(WasichaiSchemas("acme_meta", "acme_data")).columnDefinition(
                field("dueno", FieldType.RELATION, relationTarget = target),
                mapOf(target to "persona__1234abcd")
            )

        assertThat(ddl).isEqualTo("\"dueno\" uuid REFERENCES \"acme_data\".\"persona__1234abcd\" (id) ON DELETE SET NULL")
    }

    @Test
    fun `a module type brings its own column type and its own index`() {
        val area = field("area", measure, attributes = mapOf("scale" to 2))

        assertThat(manager().columnDefinition(area, emptyMap())).isEqualTo("\"area\" numeric(12, 2)")
        assertThat(manager().indexStatements(obj, area))
            .containsExactly("CREATE INDEX \"predio__1234abcd_area_mix\" ON \"app_data\".\"predio__1234abcd\" (\"area\")")
        assertThat(manager().indexStatements(obj, field("codigo", FieldType.TEXT))).isEmpty()
    }

    // issue 21: what the table should carry, as column lists. relations always, unique ones never twice.
    @Test
    fun `declared indexes are the indexed fields, the relations and the object's sets, each once`() {
        val definition =
            ObjectDefinition(
                obj.copy(indexes = listOf(listOf("anio", "predio"), listOf("anio"))),
                listOf(
                    field("anio", FieldType.INTEGER, indexed = true),
                    field("predio", FieldType.RELATION, relationTarget = UUID.randomUUID()),
                    field("titular", FieldType.RELATION, relationTarget = UUID.randomUUID(), unique = true),
                    field("codigo", FieldType.TEXT, unique = true, indexed = true),
                    field("notas", FieldType.TEXT)
                )
            )

        assertThat(manager().declaredIndexes(definition))
            .containsExactlyInAnyOrder(listOf("anio"), listOf("predio"), listOf("anio", "predio"))
    }

    @Test
    fun `a declared index is created if missing and dropped by its derived name`() {
        val name = SqlIdentifier.fieldSetName(obj.physicalTable, listOf("anio", "predio"), "ix")

        assertThat(manager().createIndexStatement(obj, listOf("anio", "predio")))
            .isEqualTo("CREATE INDEX IF NOT EXISTS \"$name\" ON \"app_data\".\"predio__1234abcd\" (\"anio\", \"predio\")")
        assertThat(manager().dropIndexStatement(obj, listOf("anio", "predio")))
            .isEqualTo("DROP INDEX IF EXISTS \"app_data\".\"$name\"")
    }

    @Test
    fun `only what changed between two definitions is created or dropped`() {
        val fields = listOf(field("anio", FieldType.INTEGER, indexed = true), field("predio", FieldType.TEXT))
        val before = ObjectDefinition(obj.copy(indexes = listOf(listOf("anio", "predio"))), fields)
        val after = ObjectDefinition(obj.copy(indexes = listOf(listOf("predio", "anio"))), fields)

        assertThat(manager().indexChanges(before, after))
            .containsExactly(
                manager().dropIndexStatement(obj, listOf("anio", "predio")),
                manager().createIndexStatement(obj, listOf("predio", "anio"))
            )
        assertThat(manager().indexChanges(after, after)).isEmpty()
    }
}
