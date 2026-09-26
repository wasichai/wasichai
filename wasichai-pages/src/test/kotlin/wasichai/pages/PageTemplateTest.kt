package wasichai.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageTemplateTest {
    @Test
    fun `every row fills the grid`() {
        PageTemplate.entries.forEach { template ->
            template.rows.forEach { row ->
                assertThat(row.regions.sumOf { it.span })
                    .describedAs("${template.value} has a row that does not fill ${PageTemplate.COLUMNS} columns")
                    .isEqualTo(PageTemplate.COLUMNS)
            }
        }
    }

    @Test
    fun `no template names the same region twice`() {
        PageTemplate.entries.forEach { template ->
            assertThat(template.regionNames).describedAs(template.value).doesNotHaveDuplicates()
        }
    }

    // the main region is what makes a template change cheap: the admin's principal content never
    // has to move, so only a header or a sidebar can ever be orphaned.
    @Test
    fun `every template declares a main region`() {
        PageTemplate.entries.forEach { template ->
            assertThat(template.regionNames).describedAs(template.value).contains(PageRegion.MAIN)
        }
    }

    @Test
    fun `the catalogue holds the ten standards`() {
        assertThat(PageTemplate.entries.map { it.value })
            .containsExactly(
                "one-region",
                "two-regions",
                "three-regions",
                "header-and-one-region",
                "header-and-two-regions",
                "header-and-three-regions",
                "header-and-left-sidebar",
                "header-and-right-sidebar",
                "main-and-left-sidebar",
                "main-and-right-sidebar"
            )
    }

    @Test
    fun `a blank name is the one-region template`() {
        assertThat(PageTemplate.parse(null)).isEqualTo(PageTemplate.ONE_REGION)
        assertThat(PageTemplate.parse("  ")).isEqualTo(PageTemplate.ONE_REGION)
    }

    @Test
    fun `an unknown name is refused`() {
        assertThat(runCatching { PageTemplate.parse("four-regions") }.exceptionOrNull())
            .isInstanceOf(wasichai.core.common.ValidationException::class.java)
    }
}
