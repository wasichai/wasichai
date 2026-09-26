package wasichai.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class PageComponentJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    // the wire and jsonb shape the enum had: a bare name
    @Test
    fun `a component type is written as its bare name`() {
        val json = mapper.writeValueAsString(PageComponent(type = ComponentType.TABS, children = listOf(PageComponent(type = ComponentType("MAP")))))
        assertThat(json).contains("\"type\":\"TABS\"").contains("\"type\":\"MAP\"")
    }

    // a page stored while gis/workflow were installed must still load after they are removed
    @Test
    fun `a stored page naming a module type still reads back`() {
        val stored = """{"page":{"type":"PAGE","children":[{"type":"WORKFLOW"},{"type":"map"}]}}"""
        val definition = mapper.readValue(stored, PageDefinition::class.java)
        assertThat(definition.page.type).isEqualTo(ComponentType.PAGE)
        assertThat(definition.page.children.map { it.type }).containsExactly(ComponentType("WORKFLOW"), ComponentType("MAP"))
    }
}
