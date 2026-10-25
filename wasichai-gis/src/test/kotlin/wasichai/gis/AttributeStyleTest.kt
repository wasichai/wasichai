package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.metadata.FieldType
import wasichai.gis.GisFixtures.refused

class AttributeStyleTest {
    private val definition =
        GisFixtures.definition(
            GisFixtures.geometry("geom"),
            GisFixtures.typed("uso", FieldType.ENUM, listOf("vivienda", "comercio")),
            GisFixtures.typed("arancel", FieldType.DECIMAL),
            GisFixtures.typed("inscrito", FieldType.DATE)
        )

    @Test
    fun `a style names a field the object has`() {
        refused("Unknown style field 'nope'", "style.by") { AttributeStyle("nope", StyleType.CATEGORIES).validate(definition) }
        assertThat(AttributeStyle("uso", StyleType.CATEGORIES).validate(definition).name).isEqualTo("uso")
    }

    @Test
    fun `quantiles need a number, categories a discrete value`() {
        refused("Field 'uso' cannot be split in quantiles", "style.by") { AttributeStyle("uso", StyleType.QUANTILES).validate(definition) }
        refused("Field 'arancel' cannot be styled by category", "style.by") { AttributeStyle("arancel", StyleType.CATEGORIES).validate(definition) }
        refused("Field 'geom' cannot be styled by category", "style.by") { AttributeStyle("geom", StyleType.CATEGORIES).validate(definition) }
        refused("Field 'inscrito' cannot be split in quantiles", "style.by") { AttributeStyle("inscrito", StyleType.QUANTILES).validate(definition) }
    }

    @Test
    fun `class count and palette are checked`() {
        refused("Invalid class count 12", "style.classes") { AttributeStyle("arancel", StyleType.QUANTILES, classes = 12).validate(definition) }
        refused("Invalid colour 'red'", "style.palette") { AttributeStyle("uso", StyleType.CATEGORIES, palette = listOf("red")).validate(definition) }
        refused("Empty palette", "style.palette") { AttributeStyle("uso", StyleType.CATEGORIES, palette = emptyList()).validate(definition) }
    }

    @Test
    fun `the type travels lowercase and parses any case`() {
        assertThat(StyleType.QUANTILES.wire()).isEqualTo("quantiles")
        assertThat(StyleType.parse("Categories")).isEqualTo(StyleType.CATEGORIES)
        refused("Unknown style type 'cuantiles'", "style.type") { StyleType.parse("cuantiles") }
    }

    @Test
    fun `categories cycle the palette`() {
        val classes = StyleClassifier.categories(listOf("a", "b", "c"), listOf("#000000", "#ffffff"))
        assertThat(classes.map { it.color }).containsExactly("#000000", "#ffffff", "#000000")
        assertThat(classes.map { it.value }).containsExactly("a", "b", "c")
    }

    @Test
    fun `quantile breaks become ranges, equal edges collapse`() {
        val classes = StyleClassifier.quantiles(listOf(0.0, 10.0, 10.0, 25.5, 40.0), null)
        assertThat(classes.map { it.min to it.max }).containsExactly(0.0 to 10.0, 10.0 to 25.5, 25.5 to 40.0)
        assertThat(classes.map { it.label }).containsExactly("0 – 10", "10 – 25.50", "25.50 – 40")
        assertThat(classes.map { it.color }).containsExactly("#ffffcc", "#fd8d3c", "#800026")
        assertThat(StyleClassifier.quantiles(listOf(7.0, 7.0), null)).hasSize(1)
        assertThat(StyleClassifier.quantiles(emptyList(), null)).isEmpty()
    }

    @Test
    fun `percentile fractions run from 0 to 1`() {
        assertThat(StyleClassifier.fractions(4)).containsExactly(0.0, 0.25, 0.5, 0.75, 1.0)
    }
}
