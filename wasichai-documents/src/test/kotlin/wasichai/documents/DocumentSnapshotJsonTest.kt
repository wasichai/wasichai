package wasichai.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

// the snapshot is read back the moment it is inserted, with the kotlin module's strict null checks on (its default).
// an empty cell in a related row must come back as null, not fail the issue (#112).
class DocumentSnapshotJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `a related row with an empty field round-trips with the null kept`() {
        val snapshot =
            DocumentSnapshot(
                template = TemplateNode(type = "doc"),
                values = mapOf("codigo" to "A-1", "nota" to null),
                platform = emptyMap(),
                related =
                    mapOf(
                        "b_a" to
                            RelatedSnapshot(
                                label = "B",
                                columns = listOf(SnapshotColumn("codigo", "Codigo"), SnapshotColumn("nota", "Nota")),
                                rows = listOf(mapOf("codigo" to "B-1", "nota" to null))
                            )
                    ),
                objectName = "a",
                objectLabel = "A",
                number = "S-2026-001",
                issuedAt = Instant.parse("2026-10-15T10:00:00Z")
            )

        val read = mapper.readValue(mapper.writeValueAsString(snapshot), DocumentSnapshot::class.java)

        assertThat(read.related.getValue("b_a").rows).containsExactly(mapOf("codigo" to "B-1", "nota" to null))
        assertThat(read).isEqualTo(snapshot)
    }
}
