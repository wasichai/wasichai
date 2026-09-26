package wasichai.forms

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// same url as before the split: core no longer knows forms exist, so forms answers it itself.
// only stored forms: the generated default lives behind /api/objects/{object}/forms
@RestController
@RequestMapping("/api/metadata/objects")
class FormMetadataController(
    private val forms: FormService
) {
    @GetMapping("/{object}/forms")
    suspend fun forms(
        @PathVariable("object") name: String
    ): List<FormResponse> = forms.forObject(name).map { it.toResponse() }
}
