package wasichai.pages

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

class PageGenerationTest {
    private val definition =
        ObjectDefinition(
            CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__00000000", null, null),
            emptyList()
        )

    private fun provider(
        name: String,
        generated: GeneratedComponent?
    ) = object : PageComponentProvider {
        override val type = ComponentType(name)

        override suspend fun generated(definition: ObjectDefinition) = generated
    }

    private val related = listOf(PageComponent(type = ComponentType.RELATED_LIST, title = "Lotes", relationship = "predio_lotes"))

    @Test
    fun `core only - details, related, history`() =
        runTest {
            val tabs = generatedTabs(definition, emptyList(), related)
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "RELATED", "HISTORY")
            assertThat(tabs[0].children.map { it.type }).containsExactly(ComponentType.FORM)
            assertThat(tabs[2].children.map { it.type }).containsExactly(ComponentType.HISTORY)
        }

    // the original's layout with gis and workflow: WORKFLOW beside the form, MAP in its own tab before related
    @Test
    fun `module components land where they always did`() =
        runTest {
            val workflow = provider("WORKFLOW", GeneratedComponent(PageComponent(type = ComponentType("WORKFLOW"))))
            val map = provider("MAP", GeneratedComponent(PageComponent(type = ComponentType("MAP"), title = "Predio"), tab = "MAP"))
            val tabs = generatedTabs(definition, listOf(workflow, map), related)
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "MAP", "RELATED", "HISTORY")
            assertThat(tabs[0].children.map { it.type }).containsExactly(ComponentType.FORM, ComponentType("WORKFLOW"))
            assertThat(tabs[1].children.single().title).isEqualTo("Predio")
        }

    @Test
    fun `a provider with nothing for this object adds nothing, and no relationship means no related tab`() =
        runTest {
            val tabs = generatedTabs(definition, listOf(provider("MAP", null)), emptyList())
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "HISTORY")
        }

    // D33: a link names a generated tab by its title
    @Test
    fun `generated tabs are keyed by their titles`() =
        runTest {
            val tabs = generatedTabs(definition, emptyList(), related)
            assertThat(tabs.map { it.key }).containsExactly("DETAILS", "RELATED", "HISTORY")
        }

    @Test
    fun `a module tab is keyed by its title`() =
        runTest {
            val map = provider("MAP", GeneratedComponent(PageComponent(type = ComponentType("MAP")), tab = "MAP"))
            val tabs = generatedTabs(definition, listOf(map), related)
            assertThat(tabs.map { it.key }).containsExactly("DETAILS", "MAP", "RELATED", "HISTORY")
            // the component inside is no tab: it carries none
            assertThat(tabs[1].children.single().key).isNull()
        }

    // two modules naming the same tab: both tabs stay, only the first is linkable, so the page still validates
    @Test
    fun `a module tab repeating a key already taken keeps its tab but no key`() =
        runTest {
            val first = provider("MAP", GeneratedComponent(PageComponent(type = ComponentType("MAP")), tab = "MAP"))
            val second = provider("PLANO", GeneratedComponent(PageComponent(type = ComponentType("PLANO")), tab = "MAP"))
            val history = provider("AUDIT", GeneratedComponent(PageComponent(type = ComponentType("AUDIT")), tab = "HISTORY"))
            val tabs = generatedTabs(definition, listOf(first, second, history), emptyList())
            assertThat(tabs.map { it.title }).containsExactly("DETAILS", "MAP", "MAP", "HISTORY", "HISTORY")
            assertThat(tabs.map { it.key }).containsExactly("DETAILS", "MAP", null, null, "HISTORY")
        }

    @Test
    fun `a module tab must be a valid key`() {
        assertThrows<IllegalArgumentException> { GeneratedComponent(PageComponent(type = ComponentType("MAP")), tab = "Mapa del predio") }
    }
}
