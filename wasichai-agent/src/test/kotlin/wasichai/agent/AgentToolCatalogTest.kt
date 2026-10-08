package wasichai.agent

import com.embabel.agent.api.tool.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.coroutines.EmptyCoroutineContext

// What the model is actually handed. Embabel derives the tool definitions by reflecting over the
// toolbox, so this reads them back the same way it does and holds them to the contract: nine
// read-only tools, every one described, every argument named and required where it matters.
// No model, no platform, no Spring: just the declarations.
class AgentToolCatalogTest {
    private val definitions: List<Tool.Definition> =
        Tool
            .fromInstance(
                AgentToolbox(
                    tools = mock(AgentTools::class.java),
                    run = AgentRun("does not matter", EmptyCoroutineContext)
                )
            ).map { it.definition }

    private fun definitionOf(name: String): Tool.Definition = definitions.first { it.name == name }

    private fun argumentsOf(name: String): List<Tool.Parameter> = definitionOf(name).inputSchema.parameters

    private fun requiredOf(name: String): List<String> = argumentsOf(name).filter { it.required }.map { it.name }

    @Test
    fun `exposes exactly the declared tools`() {
        assertThat(definitions.map { it.name }).containsExactlyInAnyOrderElementsOf(AgentToolCatalog.names)
        assertThat(definitions).hasSize(9)
    }

    @Test
    fun `every tool is described`() {
        definitions.forEach { definition ->
            assertThat(definition.description)
                .describedAs("description of ${definition.name}")
                .isNotBlank()
        }
    }

    @Test
    fun `every argument is described`() {
        definitions.forEach { definition ->
            definition.inputSchema.parameters.forEach { parameter ->
                assertThat(parameter.description)
                    .describedAs("${definition.name}.${parameter.name}")
                    .isNotBlank()
            }
        }
    }

    @Test
    fun `no tool can change data`() {
        val writing = definitions.map { it.name }.filter { name -> WRITE_WORDS.any { name.contains(it) } }
        assertThat(writing).isEmpty()
    }

    @Test
    fun `tools that name an object require it`() {
        AgentToolCatalog.names
            .filter { it != AgentToolCatalog.LIST_OBJECTS }
            .forEach { name ->
                assertThat(requiredOf(name)).describedAs("required of $name").contains("object")
            }
        assertThat(argumentsOf(AgentToolCatalog.LIST_OBJECTS)).isEmpty()
    }

    @Test
    fun `record tools require the record id`() {
        listOf(
            AgentToolCatalog.GET_RECORD,
            AgentToolCatalog.RELATED_RECORDS,
            AgentToolCatalog.RECORD_HISTORY,
            AgentToolCatalog.AVAILABLE_TRANSITIONS
        ).forEach { name ->
            assertThat(requiredOf(name)).describedAs(name).contains("id")
        }
    }

    @Test
    fun `query_records declares its filters and says the limit is capped`() {
        assertThat(argumentsOf(AgentToolCatalog.QUERY_RECORDS).map { it.name })
            .containsExactlyInAnyOrder("object", "search", "filters", "sort", "direction", "limit", "bbox")
        // everything but the object is optional, or the model cannot ask a plain question
        assertThat(requiredOf(AgentToolCatalog.QUERY_RECORDS)).containsExactly("object")
        assertThat(definitionOf(AgentToolCatalog.QUERY_RECORDS).description).contains(MAX_TOOL_LIMIT.toString())
    }

    // #61: a self-relationship can be walked both ways; the direction is optional, forward by default
    @Test
    fun `related_records takes an optional direction`() {
        assertThat(argumentsOf(AgentToolCatalog.RELATED_RECORDS).map { it.name })
            .containsExactlyInAnyOrder("object", "id", "relationship", "limit", "direction")
        assertThat(requiredOf(AgentToolCatalog.RELATED_RECORDS)).containsExactlyInAnyOrder("object", "id", "relationship")
        assertThat(argumentsOf(AgentToolCatalog.RELATED_RECORDS).first { it.name == "direction" }.description).contains("inverse")
    }

    @Test
    fun `the tools that page tell the model about the cap`() {
        listOf(
            AgentToolCatalog.QUERY_RECORDS,
            AgentToolCatalog.RELATED_RECORDS,
            AgentToolCatalog.RECORD_HISTORY
        ).forEach { name ->
            assertThat(definitionOf(name).description).describedAs(name).contains(MAX_TOOL_LIMIT.toString())
            assertThat(argumentsOf(name).first { it.name == "limit" }.description).contains(MAX_TOOL_LIMIT.toString())
        }
    }

    @Test
    fun `the json schema handed to the model is well formed`() {
        definitions.forEach { definition ->
            assertThat(definition.inputSchema.toJsonSchema())
                .describedAs("schema of ${definition.name}")
                .contains("object")
        }
    }

    companion object {
        private val WRITE_WORDS = listOf("create", "update", "delete", "insert", "write", "apply", "move", "set_")
    }
}
