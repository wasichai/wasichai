package wasichai.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

// the definition is one jsonb column: rows written before per-language labels must still read
class WorkflowDefinitionJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `a definition stored before labels reads with none`() {
        val stored =
            """
            {"states":[{"name":"draft","label":"Borrador","type":"INITIAL"},{"name":"done","label":"Hecho","type":"FINAL"}],
             "transitions":[{"name":"finish","label":"Terminar","from":"draft","to":"done","roles":[]}]}
            """.trimIndent()

        val definition = mapper.readValue(stored, WorkflowDefinition::class.java)

        assertThat(definition.states.map { it.labels }).containsOnly(emptyMap())
        assertThat(definition.transitions.single().labels).isEmpty()
    }

    @Test
    fun `labels go round the column untouched`() {
        val definition =
            WorkflowDefinition(
                states = listOf(WorkflowState("draft", "Borrador", StateType.INITIAL, labels = mapOf("es" to "Borrador", "en" to "Draft"))),
                transitions = listOf(WorkflowTransition("finish", "Terminar", "draft", "draft", labels = mapOf("en" to "Finish")))
            )

        val read = mapper.readValue(mapper.writeValueAsString(definition), WorkflowDefinition::class.java)

        assertThat(read).isEqualTo(definition)
    }
}
