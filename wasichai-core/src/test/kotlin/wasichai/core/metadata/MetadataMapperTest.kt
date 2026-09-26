package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class MetadataMapperTest {
    private val tag = FieldType("TAG")

    private val tagHandler =
        object : FieldTypeHandler {
            override val type = tag

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

            override fun fieldProperties(field: CustomField) = mapOf("tag" to if (field.type == tag) field.attributes["color"] else null)

            override fun objectProperties(definition: ObjectDefinition) = mapOf("tag" to definition.fields.firstOrNull { it.type == tag }?.name)
        }

    private val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1", null, null)

    private fun field(
        name: String,
        type: FieldType,
        attributes: Map<String, Any?> = emptyMap()
    ) = CustomField(UUID.randomUUID(), obj.id, name, name, type, name, false, false, null, null, 0, null, null, attributes, true, true)

    @Test
    fun `without modules the responses carry no extension`() {
        val mapper = MetadataMapper(mock(CustomObjectRepository::class.java), FieldTypeRegistry(emptyList()))
        val definition = ObjectDefinition(obj, listOf(field("codigo", FieldType.TEXT)))

        assertThat(mapper.toObjectResponse(definition).extensions).isEmpty()
        assertThat(mapper.toFieldResponse(definition.fields.single(), null).extensions).isEmpty()
    }

    @Test
    fun `an installed type adds its keys to every field and to the object`() {
        val mapper = MetadataMapper(mock(CustomObjectRepository::class.java), FieldTypeRegistry(listOf(tagHandler)))
        val codigo = field("codigo", FieldType.TEXT)
        val marca = field("marca", tag, mapOf("color" to "red"))
        val definition = ObjectDefinition(obj, listOf(codigo, marca))

        assertThat(mapper.toObjectResponse(definition).extensions).containsEntry("tag", "marca")
        assertThat(mapper.toFieldResponse(codigo, null).extensions).containsEntry("tag", null)
        assertThat(mapper.toFieldResponse(marca, null).extensions).containsEntry("tag", "red")
        assertThat(mapper.toFieldResponse(marca, null).type).isEqualTo("TAG")
    }
}
