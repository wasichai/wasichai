package wasichai.views

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows views exist, so views answers it itself.
// only stored views: the generated default lives behind /api/objects/{object}/views
@RestController
@RequestMapping("/api/metadata/objects")
class ViewMetadataController(
    private val views: ViewService
) {
    @GetMapping("/{object}/views")
    suspend fun views(
        @PathVariable("object") name: String
    ): List<ViewResponse> = views.forObject(name).map { it.toResponse() }
}
