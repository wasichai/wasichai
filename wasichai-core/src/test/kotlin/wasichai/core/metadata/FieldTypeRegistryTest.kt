package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

class FieldTypeRegistryTest {
    private val shape = FieldType("SHAPE")

    // a stand-in for a module type: own section, own attribute column, a property on every field
    private val shapeHandler =
        object : FieldTypeHandler {
            override val type = shape
            override val attributeColumns = mapOf("shape_kind" to String::class.java)
            override val section = "shapes"

            override fun columnType(field: CustomField) = "text"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun fieldProperties(field: CustomField) = mapOf("shape" to if (field.type == shape) field.attributes["shape_kind"] else null)

            override fun objectProperties(definition: ObjectDefinition) = mapOf("shape" to definition.fields.firstOrNull { it.type == shape }?.name)
        }

    private fun field(
        type: FieldType,
        attributes: Map<String, Any?> = emptyMap()
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = UUID.randomUUID(),
        name = "campo",
        label = "Campo",
        type = type,
        columnName = "campo",
        required = false,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = null,
        relationTargetObjectId = null,
        attributes = attributes,
        visible = true,
        editable = true
    )

    @Test
    fun `core alone parses its twelve types, any case, and lists them in order when it cannot`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.parse("text")).isEqualTo(FieldType.TEXT)
        assertThatThrownBy { registry.parse("GEOMETRY") }
            .isInstanceOfSatisfying(ValidationException::class.java) { ex ->
                assertThat(ex.violations.single().message)
                    .isEqualTo("must be one of TEXT, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, ENUM, EMAIL, URL, UUID, RELATION")
            }
    }

    @Test
    fun `a module type is parsed and listed after the core ones`() {
        val registry = FieldTypeRegistry(listOf(shapeHandler))

        assertThat(registry.parse("shape")).isEqualTo(shape)
        assertThat(registry.types.last()).isEqualTo(shape)
        assertThat(registry.types).hasSize(13)
    }

    @Test
    fun `a type declared twice fails at boot`() {
        assertThatThrownBy { FieldTypeRegistry(listOf(shapeHandler, shapeHandler)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SHAPE")
    }

    @Test
    fun `a stored field whose module is gone answers a conflict that names the type`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.isInstalled(FieldType("GEOMETRY"))).isFalse()
        assertThatThrownBy { registry.handler(FieldType("GEOMETRY")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("GEOMETRY")
    }

    @Test
    fun `sections, attribute columns and json properties come from every installed handler`() {
        val registry = FieldTypeRegistry(listOf(shapeHandler))

        assertThat(registry.sections).containsExactly("shapes")
        assertThat(registry.sectionOwner("shapes")).isSameAs(shapeHandler)
        assertThat(registry.attributeColumns).containsOnlyKeys("shape_kind")
        // a text field still gets the module's key, with null: this is how gis keeps "geometry": null
        assertThat(registry.fieldProperties(field(FieldType.TEXT))).containsExactly(
            org.assertj.core.api.Assertions
                .entry("shape", null)
        )
        assertThat(registry.fieldProperties(field(shape, mapOf("shape_kind" to "round")))).containsEntry("shape", "round")
    }

    @Test
    fun `core alone adds no section, no attribute column and no json property`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThat(registry.sections).isEmpty()
        assertThat(registry.attributeColumns).isEmpty()
        assertThat(registry.fieldProperties(field(FieldType.TEXT))).isEmpty()
        assertThat(registry.handler(FieldType.TEXT).textLike).isTrue()
        assertThat(registry.handler(FieldType.INTEGER).columnType(field(FieldType.INTEGER))).isEqualTo("bigint")
    }

    @Test
    fun `asking for a section nobody owns fails clearly instead of throwing NoSuchElementException`() {
        val registry = FieldTypeRegistry(emptyList())

        assertThatThrownBy { registry.sectionOwner("nowhere") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("nowhere")
    }

    @Test
    fun `two handlers claiming the same section fail at boot`() {
        // same section as shapeHandler, different type: only the section clashes
        val secondShape =
            object : FieldTypeHandler by shapeHandler {
                override val type = FieldType("SHAPE_TWO")
            }

        assertThatThrownBy { FieldTypeRegistry(listOf(shapeHandler, secondShape)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shapes")
    }

    @Test
    fun `the same attribute column declared with a different type fails at boot`() {
        // same column name as shapeHandler, disagreeing on its java type; no section of its own
        val conflictingColumn =
            object : FieldTypeHandler by shapeHandler {
                override val type = FieldType("SHAPE_INT")
                override val attributeColumns = mapOf("shape_kind" to Int::class.java)
                override val section: String? = null
            }

        assertThatThrownBy { FieldTypeRegistry(listOf(shapeHandler, conflictingColumn)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shape_kind")
    }

    @Test
    fun `a section named after a core record json key fails at boot`() {
        val stateSection =
            object : FieldTypeHandler by shapeHandler {
                override val type = FieldType("STATE_LIKE")
                override val attributeColumns = emptyMap<String, Class<*>>()

                // collides with RecordResponse's own "state" key
                override val section = "state"
            }

        assertThatThrownBy { FieldTypeRegistry(listOf(stateSection)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("state")
    }

    @Test
    fun `a field property key that shadows a core key fails at boot`() {
        val shadowing =
            object : FieldTypeHandler by shapeHandler {
                override val type = FieldType("BAD")
                override val attributeColumns = emptyMap<String, Class<*>>()
                override val section: String? = null

                override fun fieldProperties(field: CustomField) = mapOf("type" to "nope")
            }

        assertThatThrownBy { FieldTypeRegistry(listOf(shadowing)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("type")
    }

    @Test
    fun `the core object keys are exactly what the object responses carry`() {
        val properties =
            listOf(ObjectResponse::class, ObjectDefinitionResponse::class)
                .flatMap { response -> response.primaryConstructor!!.parameters.map { it.name!! } }
                .toSet() - "extensions"

        assertThat(FieldTypeRegistry.CORE_OBJECT_KEYS).isEqualTo(properties)
    }
}
