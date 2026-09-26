package wasichai.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.data.RecordRow
import java.util.UUID

class DocumentTemplateValuesTest {
    private fun row(sections: Map<String, Map<String, Any?>>) =
        RecordRow(id = UUID.randomUUID(), createdAt = null, updatedAt = null, attributes = mapOf("codigo" to "A-1"), sections = sections)

    @Test
    fun `a flat record freezes its attributes`() {
        assertThat(row(emptyMap()).templateValues()).containsExactly(entry("codigo", "A-1"))
    }

    @Test
    fun `section fields sit next to the attributes, by field name, null included`() {
        val point = mapOf("type" to "Point", "coordinates" to listOf(1.0, 2.0))
        val values = row(mapOf("geometries" to mapOf("lote" to point, "acceso" to null))).templateValues()
        assertThat(values)
            .containsEntry("codigo", "A-1")
            .containsEntry("lote", point)
            .containsEntry("acceso", null)
            .hasSize(3)
    }

    private fun entry(
        key: String,
        value: Any?
    ) = org.assertj.core.api.Assertions
        .entry(key, value)
}
