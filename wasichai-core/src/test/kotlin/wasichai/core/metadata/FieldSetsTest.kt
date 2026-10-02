package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.util.UUID

class FieldSetsTest {
    private val shape = FieldType("SHAPE")

    // a module type that refuses filter and sort, like a geometry
    private val shapeHandler =
        object : FieldTypeHandler {
            override val type = shape

            override fun columnType(field: CustomField) = "bytea"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = ByteArray::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun rejectFilterOrSort(field: CustomField) = ValidationException("no", field.name, "is a shape")
        }

    private val types = FieldTypeRegistry(listOf(shapeHandler))

    private fun field(
        name: String,
        type: FieldType = FieldType.TEXT
    ) = CustomField(UUID.randomUUID(), UUID.randomUUID(), name, name, type, name, false, false, null, null, 0, null, null, emptyMap(), true, true)

    private val fields =
        listOf(field("anio", FieldType.INTEGER), field("predio", FieldType.RELATION), field("notas", FieldType.LONG_TEXT), field("lote", shape))

    @Test
    fun `sets are trimmed, lower cased and kept in order, and a repeated set counts once`() {
        val sets = FieldSets.normalize("indexes", listOf(listOf(" Anio", "PREDIO "), listOf("predio"), listOf("anio", "predio")), fields, types)

        assertThat(sets).containsExactly(listOf("anio", "predio"), listOf("predio"))
    }

    @Test
    fun `column order is part of the set`() {
        assertThat(FieldSets.normalize("indexes", listOf(listOf("anio", "predio"), listOf("predio", "anio")), fields, types)).hasSize(2)
    }

    @Test
    fun `an empty set, an unknown field and a field named twice are refused naming the property`() {
        listOf(
            listOf(emptyList()),
            listOf(listOf("anio", "nope")),
            listOf(listOf("anio", "anio"))
        ).forEach { sets ->
            assertThatThrownBy { FieldSets.normalize("indexes", sets, fields, types) }
                .describedAs(sets.toString())
                .isInstanceOf(ValidationException::class.java)
                .extracting { (it as ValidationException).violations.single().field }
                .isEqualTo("indexes")
        }
    }

    @Test
    fun `a field its type keeps out of filters, and a long text, cannot be indexed`() {
        assertThatThrownBy { FieldSets.normalize("indexes", listOf(listOf("anio", "lote")), fields, types) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("lote")
        assertThatThrownBy { FieldSets.normalize("indexes", listOf(listOf("notas")), fields, types) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("notas")
        assertThatThrownBy { FieldSets.requireIndexable(fields.last(), "indexed", types) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `more fields than postgres allows in one index is refused`() {
        val many = (1..33).map { field("f$it") }
        assertThatThrownBy { FieldSets.normalize("indexes", listOf(many.map { it.name }), many, types) }
            .isInstanceOf(ValidationException::class.java)
    }

    // a field cannot be dropped from under either list, and the refusal names which list holds it
    @Test
    fun `the set that blocks a delete names its kind and its list`() {
        val obj =
            CustomObject(UUID.randomUUID(), UUID.randomUUID(), "o", "O", "Os", null, true, "o__1", null, null)
                .copy(indexes = listOf(listOf("anio", "predio")), uniqueConstraints = listOf(listOf("predio", "notas")))

        assertThat(FieldSets.blocking("anio", obj).toString()).isEqualTo("index (anio, predio)")
        assertThat(FieldSets.blocking("anio", obj)!!.property).isEqualTo("indexes")
        assertThat(FieldSets.blocking("notas", obj).toString()).isEqualTo("unique constraint (predio, notas)")
        assertThat(FieldSets.blocking("notas", obj)!!.property).isEqualTo("uniqueConstraints")
        assertThat(FieldSets.blocking("lote", obj)).isNull()
    }

    @Test
    fun `the sets a field takes part in`() {
        val sets = listOf(listOf("anio", "predio"), listOf("predio"), listOf("notas"))

        assertThat(FieldSets.containing("predio", sets)).containsExactly(listOf("anio", "predio"), listOf("predio"))
        assertThat(FieldSets.containing("lote", sets)).isEmpty()
    }
}
