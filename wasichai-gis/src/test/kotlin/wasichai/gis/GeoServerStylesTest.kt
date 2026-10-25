package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeoServerStylesTest {
    private val categories =
        StyleClasses("uso", StyleType.CATEGORIES, listOf(StyleClass("vivienda", "#8dd3c7", value = "vivienda"), StyleClass("a<b", "#ffffb3", value = "a<b")))
    private val quantiles =
        StyleClasses(
            "arancel",
            StyleType.QUANTILES,
            listOf(StyleClass("0 – 10", "#ffffcc", min = 0.0, max = 10.0), StyleClass("10 – 20", "#800026", min = 10.0, max = 20.0))
        )

    @Test
    fun `one rule per category, an else rule for the rest`() {
        val sld = GeoServerStyles.sld("predio__00000000__geom", GeometryType.POLYGON, categories)
        assertThat(sld)
            .startsWith("<?xml")
            .contains("<NamedLayer><Name>predio__00000000__geom</Name>")
            .contains("<ogc:PropertyIsEqualTo><ogc:PropertyName>uso</ogc:PropertyName><ogc:Literal>vivienda</ogc:Literal></ogc:PropertyIsEqualTo>")
            .contains("<ogc:Literal>a&lt;b</ogc:Literal>")
            .contains("<ElseFilter/>")
            .contains("""<CssParameter name="fill">#8dd3c7</CssParameter>""")
            .contains("""<CssParameter name="fill">#9e9e9e</CssParameter>""")
            .doesNotContain("a<b")
    }

    @Test
    fun `quantiles are half open, the last one closed`() {
        val sld = GeoServerStyles.sld("t__geom", GeometryType.POLYGON, quantiles)
        assertThat(sld)
            .contains("<ogc:PropertyIsGreaterThanOrEqualTo><ogc:PropertyName>arancel</ogc:PropertyName><ogc:Literal>0.0</ogc:Literal>")
            .contains("<ogc:PropertyIsLessThan><ogc:PropertyName>arancel</ogc:PropertyName><ogc:Literal>10.0</ogc:Literal></ogc:PropertyIsLessThan>")
            .contains("<ogc:PropertyIsLessThanOrEqualTo><ogc:PropertyName>arancel</ogc:PropertyName><ogc:Literal>20.0</ogc:Literal>")
    }

    @Test
    fun `the symbolizer follows the geometry`() {
        assertThat(GeoServerStyles.sld("t", GeometryType.POLYGON, categories)).contains("<PolygonSymbolizer>").doesNotContain("<LineSymbolizer>")
        assertThat(GeoServerStyles.sld("t", GeometryType.MULTILINESTRING, categories)).contains("<LineSymbolizer>").doesNotContain("<PolygonSymbolizer>")
        assertThat(GeoServerStyles.sld("t", GeometryType.POINT, categories)).contains("<PointSymbolizer>").contains("<WellKnownName>circle</WellKnownName>")
    }

    @Test
    fun `a dropped style falls back to geoserver's built-in for the geometry kind`() {
        assertThat(GeoServerStyles.generic(GeometryType.MULTIPOINT)).isEqualTo("point")
        assertThat(GeoServerStyles.generic(GeometryType.LINESTRING)).isEqualTo("line")
        assertThat(GeoServerStyles.generic(GeometryType.POLYGON)).isEqualTo("polygon")
    }

    @Test
    fun `outlines are a third darker`() {
        assertThat(GeoServerStyles.darker("#ffffff")).isEqualTo("#aaaaaa")
        assertThat(GeoServerStyles.darker("#000000")).isEqualTo("#000000")
    }

    @Test
    fun `a layer's default style is workspace local or one of geoserver's globals`() {
        assertThat(GeoServerPayloads.defaultStyle("wasichai", "t__geom", local = true))
            .isEqualTo(mapOf("layer" to mapOf("defaultStyle" to mapOf("name" to "t__geom", "workspace" to "wasichai"))))
        assertThat(GeoServerPayloads.defaultStyle("wasichai", "polygon", local = false))
            .isEqualTo(mapOf("layer" to mapOf("defaultStyle" to mapOf("name" to "polygon"))))
    }
}
