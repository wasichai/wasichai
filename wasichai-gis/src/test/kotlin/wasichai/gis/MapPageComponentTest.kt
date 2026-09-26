package wasichai.gis

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.gis.GisFixtures.definition
import wasichai.gis.GisFixtures.geometry
import wasichai.gis.GisFixtures.refused
import wasichai.gis.GisFixtures.text
import wasichai.pages.ComponentType
import wasichai.pages.PageComponentRequest

class MapPageComponentTest {
    private val map = MapPageComponent()
    private val spatial = definition(text("codigo"), geometry("lote"), geometry("acceso", "POINT"))

    @Test
    fun `a map needs a geometry to draw`() {
        refused("MAP on a non-spatial object", "components") { map.check(PageComponentRequest(type = "MAP"), definition(text("codigo"))) }
    }

    // naming none draws them all, which is what a one-shape object wants
    @Test
    fun `a map may name one geometry, and only one the object has`() {
        map.check(PageComponentRequest(type = "MAP"), spatial)
        map.check(PageComponentRequest(type = "MAP", geometry = " acceso "), spatial)
        refused("Unknown geometry 'x'", "components") { map.check(PageComponentRequest(type = "MAP", geometry = "x"), spatial) }
    }

    @Test
    fun `a generated page gets a map tab only when the object is spatial`() =
        runTest {
            assertThat(map.generated(definition(text("codigo")))).isNull()
            val generated = map.generated(spatial)!!
            assertThat(generated.tab).isEqualTo("MAP")
            assertThat(generated.component.type).isEqualTo(ComponentType("MAP"))
            assertThat(generated.component.title).isEqualTo("Predio")
        }
}
