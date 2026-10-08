package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.util.UUID

// issue 60: a default is parsed by its type's handler, checked when the field is made, applied on create to a key left out
class FieldDefaultsTest {
    private val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)

    private val measure = FieldType("MEASURE")

    private val sectionHandler =
        object : FieldTypeHandler {
            override val type = measure
            override val section = "measures"

            override fun columnType(field: CustomField) = "numeric"

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

    private val types = FieldTypeRegistry(listOf(sectionHandler))

    private fun field(
        name: String,
        type: FieldType,
        defaultValue: String? = null,
        required: Boolean = false,
        enumOptions: List<String>? = null,
        editable: Boolean = true
    ) = CustomField(
        UUID.randomUUID(),
        obj.id,
        name,
        name,
        type,
        name,
        required,
        false,
        defaultValue,
        null,
        0,
        enumOptions,
        null,
        emptyMap(),
        true,
        editable
    )

    private fun refused(
        property: String,
        reason: String,
        block: () -> Unit
    ) {
        assertThatThrownBy(block)
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ e ->
                val violation = (e as ValidationException).violations.single()
                assertThat(violation.field).isEqualTo(property)
                assertThat(violation.message).isEqualTo(reason)
            })
    }

    @Test
    fun `a default the type cannot parse is a 400 on defaultValue`() {
        refused("defaultValue", "'abc' is not an integer") { FieldDefaults.checked(field("cantidad", FieldType.INTEGER, "abc"), types) }
        refused("defaultValue", "'si' is not a boolean") { FieldDefaults.checked(field("activo", FieldType.BOOLEAN, "si"), types) }
        refused("defaultValue", "'ayer' is not an ISO date") { FieldDefaults.checked(field("fecha", FieldType.DATE, "ayer"), types) }
        refused("defaultValue", "'x' is not an email") { FieldDefaults.checked(field("correo", FieldType.EMAIL, "x"), types) }
        refused("defaultValue", "'x' is not a UUID") { FieldDefaults.checked(field("titular", FieldType.RELATION, "x"), types) }
    }

    @Test
    fun `an enum default must be one of the options, and the property is the caller's`() {
        val estado = field("estado", FieldType.ENUM, "CERRADO", enumOptions = listOf("ABIERTO", "EN_CURSO"))
        refused("defaultValue", "'CERRADO' must be one of ABIERTO, EN_CURSO") { FieldDefaults.checked(estado, types) }
        refused("enumOptions", "'CERRADO' must be one of ABIERTO, EN_CURSO") { FieldDefaults.checked(estado, types, "enumOptions") }
        assertThat(FieldDefaults.checked(estado.copy(defaultValue = "ABIERTO"), types)).isEqualTo("ABIERTO")
    }

    @Test
    fun `a valid default is kept as sent, a blank one is none`() {
        assertThat(FieldDefaults.checked(field("cantidad", FieldType.INTEGER, "5"), types)).isEqualTo("5")
        assertThat(FieldDefaults.checked(field("nombre", FieldType.TEXT, " con espacios "), types)).isEqualTo(" con espacios ")
        assertThat(FieldDefaults.checked(field("cantidad", FieldType.INTEGER, "  "), types)).isNull()
        assertThat(FieldDefaults.checked(field("cantidad", FieldType.INTEGER, null), types)).isNull()
        // required plays no part in checking the default itself
        assertThat(FieldDefaults.checked(field("cantidad", FieldType.INTEGER, "7", required = true), types)).isEqualTo("7")
    }

    @Test
    fun `a section type takes no default`() {
        refused("defaultValue", "a MEASURE field takes no default") { FieldDefaults.checked(field("area", measure, "5"), types) }
        assertThat(FieldDefaults.appliesTo(field("area", measure, "5"), types)).isFalse()
    }

    @Test
    fun `a key left out takes the default, in the shape the api carries it`() {
        val definition =
            ObjectDefinition(
                obj,
                listOf(
                    field("cantidad", FieldType.INTEGER, "5"),
                    field("monto", FieldType.DECIMAL, "12.50"),
                    field("activo", FieldType.BOOLEAN, "true"),
                    field("fecha", FieldType.DATE, "2026-01-31"),
                    field("estado", FieldType.ENUM, "ABIERTO", enumOptions = listOf("ABIERTO")),
                    field("nota", FieldType.TEXT)
                )
            )

        val (_, attributes) = FieldDefaults.applied(definition, emptyMap(), types)

        assertThat(attributes).containsExactlyInAnyOrderEntriesOf(
            mapOf("cantidad" to 5L, "monto" to BigDecimal("12.50"), "activo" to true, "fecha" to "2026-01-31", "estado" to "ABIERTO")
        )
    }

    @Test
    fun `a key sent wins, null included`() {
        val definition = ObjectDefinition(obj, listOf(field("cantidad", FieldType.INTEGER, "5"), field("unidad", FieldType.TEXT, "m2")))

        val (target, attributes) = FieldDefaults.applied(definition, mapOf("cantidad" to 9, "unidad" to null), types)

        assertThat(attributes).isEqualTo(mapOf("cantidad" to 9, "unidad" to null))
        assertThat(target).isSameAs(definition)
    }

    @Test
    fun `a defaulted field is written even where it was locked, the others stay as they were`() {
        val locked = field("estado", FieldType.TEXT, "NUEVO", editable = false)
        val other = field("nota", FieldType.TEXT, editable = false)
        val definition = ObjectDefinition(obj, listOf(locked, other))

        val (target, attributes) = FieldDefaults.applied(definition, emptyMap(), types)

        assertThat(attributes).isEqualTo(mapOf("estado" to "NUEVO"))
        assertThat(target.fields.single { it.name == "estado" }.editable).isTrue()
        assertThat(target.fields.single { it.name == "nota" }.editable).isFalse()
    }

    @Test
    fun `a stored default that no longer parses is a 400 on the field, not a silent null`() {
        val definition = ObjectDefinition(obj, listOf(field("cantidad", FieldType.INTEGER, "abc")))

        refused("cantidad", "its defaultValue 'abc' is not an integer") { FieldDefaults.applied(definition, emptyMap(), types) }
    }

    @Test
    fun `a blank stored default and a section field get nothing`() {
        val definition = ObjectDefinition(obj, listOf(field("nota", FieldType.TEXT, ""), field("area", measure, "5")))

        val (_, attributes) = FieldDefaults.applied(definition, emptyMap(), types)

        assertThat(attributes).isEmpty()
    }
}
