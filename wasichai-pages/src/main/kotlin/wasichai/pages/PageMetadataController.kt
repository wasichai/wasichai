package wasichai.pages

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows pages exist, so pages answers it itself.
// only stored pages: the generated default lives behind /api/objects/{object}/pages/{kind}
@RestController
@RequestMapping("/api/metadata/objects")
class PageMetadataController(
    private val pages: PageService
) {
    @GetMapping("/{object}/pages")
    suspend fun pages(
        @PathVariable("object") name: String
    ): List<PageResponse> = pages.forObject(name).map { it.toResponse() }
}
