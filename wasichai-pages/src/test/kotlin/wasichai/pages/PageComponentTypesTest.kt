package wasichai.pages

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException

class PageComponentTypesTest {
    private val map =
        object : PageComponentProvider {
            override val type = ComponentType("MAP")
        }

    @Test
    fun `built-ins parse in any case`() {
        assertThat(PageComponentTypes(emptyList()).parse("history")).isEqualTo(ComponentType.HISTORY)
    }

    @Test
    fun `a module type is unknown until its module is installed`() {
        val error = assertThrows<ValidationException> { PageComponentTypes(emptyList()).parse("MAP") }
        assertThat(error.message).isEqualTo("Unknown component 'MAP'")
        assertThat(error.violations.single().field).isEqualTo("components")
        assertThat(error.violations.single().message)
            .isEqualTo("must be one of PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION")
    }

    @Test
    fun `an installed provider's type parses and finds its provider`() {
        val types = PageComponentTypes(listOf(map))
        assertThat(types.parse("map")).isEqualTo(ComponentType("MAP"))
        assertThat(types.provider(ComponentType("MAP"))).isSameAs(map)
        assertThat(types.provider(ComponentType.FORM)).isNull()
        assertThat(types.types.last()).isEqualTo(ComponentType("MAP"))
    }

    @Test
    fun `a provider cannot take a built-in's name`() {
        val history =
            object : PageComponentProvider {
                override val type = ComponentType.HISTORY
            }
        assertThatThrownBy { PageComponentTypes(listOf(history)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("page component type declared twice: HISTORY")
    }

    @Test
    fun `module components are placeable leaves`() {
        assertThat(ComponentType("MAP").container).isFalse()
        assertThat(ComponentType("MAP").placeable).isTrue()
        assertThat(ComponentType.DYNAMIC_FORM.container).isTrue()
        assertThat(ComponentType.REGION.placeable).isFalse()
    }

    @Test
    fun `a component type must be upper-case`() {
        assertThatThrownBy { ComponentType("Map") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("component type name must be upper-case: 'Map'")
    }
}
