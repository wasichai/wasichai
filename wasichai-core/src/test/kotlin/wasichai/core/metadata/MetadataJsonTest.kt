package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class MetadataJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun response(extensions: Map<String, Any?>) =
        FieldResponse(
            id = "1",
            name = "lote",
            label = "Lote",
            type = "TEXT",
            required = false,
            unique = false,
            defaultValue = null,
            description = null,
            position = 0,
            enumOptions = null,
            relationTarget = null,
            visible = true,
            editable = true,
            extensions = extensions
        )

    @Test
    fun `with no module the field json has exactly the core keys`() {
        val tree = mapper.readTree(mapper.writeValueAsString(response(emptyMap())))

        assertThat(tree.propertyNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id",
            "name",
            "label",
            "type",
            "required",
            "unique",
            "defaultValue",
            "description",
            "position",
            "enumOptions",
            "relationTarget",
            "visible",
            "editable"
        )
    }

    @Test
    fun `a declared time zone is written, and only then`() {
        val tree = mapper.readTree(mapper.writeValueAsString(response(emptyMap()).copy(type = "DATETIME", timeZone = "America/Lima")))

        assertThat(tree.get("timeZone").asString()).isEqualTo("America/Lima")
    }

    @Test
    fun `module properties are flattened, and a null one is still written`() {
        val tree = mapper.readTree(mapper.writeValueAsString(response(mapOf("geometry" to null, "other" to mapOf("srid" to 32718)))))

        assertThat(tree.has("extensions")).isFalse()
        assertThat(tree.has("geometry")).isTrue()
        assertThat(tree.get("geometry").isNull).isTrue()
        assertThat(tree.get("other").get("srid").asInt()).isEqualTo(32718)
    }

    @Test
    fun `request properties core does not know are kept for the field type`() {
        val request =
            mapper.readValue(
                """{"name":"lote","type":"GEOMETRY","geometryType":"POLYGON","srid":32718}""",
                FieldRequest::class.java
            )

        assertThat(request.name).isEqualTo("lote")
        assertThat(request.extensions).containsEntry("geometryType", "POLYGON").containsEntry("srid", 32718)
    }

    @Test
    fun `an unknown request key lands in extensions`() {
        val request = mapper.readValue("""{"name":"lote","type":"TEXT","srid":32718}""", FieldRequest::class.java)

        assertThat(request.extensions).containsExactly(
            org.assertj.core.api.Assertions
                .entry("srid", 32718)
        )
    }

    // extensions is a constructor property: copy() must carry it like any other field, not drop it
    @Test
    fun `copy() keeps extensions`() {
        val request = FieldRequest(name = "lote", type = "TEXT", extensions = mapOf("geometryType" to "POLYGON"))

        assertThat(request.copy(label = "Lote").extensions).isEqualTo(mapOf("geometryType" to "POLYGON"))
    }

    // same reason: a body property is invisible to the generated equals/hashCode
    @Test
    fun `equals takes extensions into account`() {
        val withExtension = FieldRequest(name = "lote", type = "TEXT", extensions = mapOf("geometryType" to "POLYGON"))
        val withoutExtension = FieldRequest(name = "lote", type = "TEXT")

        assertThat(withExtension).isNotEqualTo(withoutExtension)
    }
}
