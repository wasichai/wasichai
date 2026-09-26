package wasichai.pages

import com.fasterxml.jackson.annotation.JsonValue
import wasichai.core.common.ValidationException

// a region's name is a key, not a word: the backend has no language, so the client translates it.
// the vocabulary is shared across templates on purpose -- a region both templates name keeps its
// contents when the template changes, so only what genuinely disappears needs a decision.
enum class PageRegion {
    HEADER,
    MAIN,
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun parse(raw: String?): PageRegion =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
                ?: throw ValidationException(
                    "Unknown region '${raw.orEmpty()}'",
                    "page",
                    "must be one of ${entries.joinToString(", ")}"
                )
    }
}

// span is out of PageTemplate.COLUMNS. twelve divides by 2, 3 and 4, so halves, thirds and a 2:1
// sidebar are all whole numbers, and it drops straight into a css grid or a flex-grow.
data class TemplateRegion(
    val name: PageRegion,
    val span: Int
)

data class TemplateRow(
    val regions: List<TemplateRegion>
)

// the catalogue is code, not a table. one description serves three readers: the validator, the
// renderer, and the preview the client draws without any shipped artwork.
enum class PageTemplate(
    @get:JsonValue val value: String,
    val rows: List<TemplateRow>
) {
    ONE_REGION("one-region", rows(row(PageRegion.MAIN to 12))),
    TWO_REGIONS("two-regions", rows(row(PageRegion.MAIN to 6, PageRegion.RIGHT to 6))),
    THREE_REGIONS("three-regions", rows(row(PageRegion.LEFT to 4, PageRegion.MAIN to 4, PageRegion.RIGHT to 4))),
    HEADER_AND_ONE_REGION("header-and-one-region", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 12))),
    HEADER_AND_TWO_REGIONS("header-and-two-regions", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 6, PageRegion.RIGHT to 6))),
    HEADER_AND_THREE_REGIONS(
        "header-and-three-regions",
        rows(row(PageRegion.HEADER to 12), row(PageRegion.LEFT to 4, PageRegion.MAIN to 4, PageRegion.RIGHT to 4))
    ),
    HEADER_AND_LEFT_SIDEBAR("header-and-left-sidebar", rows(row(PageRegion.HEADER to 12), row(PageRegion.LEFT to 4, PageRegion.MAIN to 8))),
    HEADER_AND_RIGHT_SIDEBAR("header-and-right-sidebar", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 8, PageRegion.RIGHT to 4))),
    MAIN_AND_LEFT_SIDEBAR("main-and-left-sidebar", rows(row(PageRegion.LEFT to 4, PageRegion.MAIN to 8))),
    MAIN_AND_RIGHT_SIDEBAR("main-and-right-sidebar", rows(row(PageRegion.MAIN to 8, PageRegion.RIGHT to 4)));

    // reading order, row by row, left to right. this is the order the PAGE node's children take.
    val regions: List<TemplateRegion> get() = rows.flatMap { it.regions }
    val regionNames: List<PageRegion> get() = regions.map { it.name }

    companion object {
        const val COLUMNS = 12

        fun parse(raw: String?): PageTemplate {
            if (raw.isNullOrBlank()) return ONE_REGION
            return entries.firstOrNull { it.value.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown template '$raw'",
                    "template",
                    "must be one of ${entries.joinToString(", ") { it.value }}"
                )
        }
    }
}

private fun row(vararg regions: Pair<PageRegion, Int>): TemplateRow = TemplateRow(regions.map { TemplateRegion(it.first, it.second) })

private fun rows(vararg rows: TemplateRow): List<TemplateRow> = rows.toList()
