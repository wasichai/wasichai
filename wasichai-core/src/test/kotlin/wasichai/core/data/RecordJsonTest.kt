package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class RecordJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `sections are flattened next to attributes, empty and null values included`() {
        val response = RecordResponse("1", null, null, mapOf("codigo" to "A"), null, mapOf("geometries" to mapOf("lote" to null), "extra" to emptyMap()))
        val tree = mapper.readTree(mapper.writeValueAsString(response))

        assertThat(tree.has("sections")).isFalse()
        assertThat(tree.get("geometries").has("lote")).isTrue()
        assertThat(tree.get("geometries").get("lote").isNull).isTrue()
        assertThat(tree.get("extra").size()).isEqualTo(0)
        assertThat(tree.has("state")).isTrue()
    }

    @Test
    fun `with no module a record has only the core keys`() {
        val tree = mapper.readTree(mapper.writeValueAsString(RecordResponse("1", null, null, emptyMap())))

        assertThat(tree.propertyNames().asSequence().toList()).containsExactlyInAnyOrder("id", "createdAt", "updatedAt", "attributes", "state")
    }

    @Test
    fun `a request keeps object-valued unknown properties as sections and drops the rest`() {
        val request = mapper.readValue("""{"attributes":{"codigo":"A"},"geometries":{"lote":null},"id":"x"}""", RecordRequest::class.java)

        assertThat(request.attributes).containsEntry("codigo", "A")
        assertThat(request.sections).containsOnlyKeys("geometries")
        assertThat(request.sections.getValue("geometries")).containsEntry("lote", null)
    }
}
