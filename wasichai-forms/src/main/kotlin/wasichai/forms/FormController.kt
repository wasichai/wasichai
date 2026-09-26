package wasichai.forms

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// what the dynamic record form renders itself from
data class FormResponse(
    val id: String,
    val name: String,
    val label: String,
    val objectName: String,
    val generated: Boolean,
    val definition: FormDefinition
)

fun ResolvedForm.toResponse(): FormResponse =
    FormResponse(
        id = form.id.toString(),
        name = form.name,
        label = form.label,
        objectName = objectName,
        generated = generated,
        definition = form.definition
    )

// forms are named per object, so they hang off the object
@RestController
@RequestMapping("/api/objects")
class FormController(
    private val forms: FormService
) {
    @GetMapping("/{object}/forms")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<FormResponse> = forms.list(objectName).map { it.toResponse() }

    @PostMapping("/{object}/forms")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: CreateFormRequest
    ): FormResponse = forms.create(objectName, request).toResponse()

    @GetMapping("/{object}/forms/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): FormResponse = forms.byName(objectName, name).toResponse()

    @PutMapping("/{object}/forms/{name}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: UpdateFormRequest
    ): FormResponse = forms.update(objectName, name, request).toResponse()

    @DeleteMapping("/{object}/forms/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = forms.delete(objectName, name)
}
