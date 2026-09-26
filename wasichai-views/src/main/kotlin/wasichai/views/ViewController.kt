package wasichai.views

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

// what the dynamic list screen renders itself from
data class ViewResponse(
    val id: String,
    val name: String,
    val label: String,
    val objectName: String,
    val isDefault: Boolean,
    val generated: Boolean,
    val definition: ViewDefinition
)

fun ResolvedView.toResponse(): ViewResponse =
    ViewResponse(
        id = view.id.toString(),
        name = view.name,
        label = view.label,
        objectName = objectName,
        isDefault = view.isDefault,
        generated = generated,
        definition = view.definition
    )

// views are named per object, so they hang off the object
@RestController
@RequestMapping("/api/objects")
class ViewController(
    private val views: ViewService
) {
    @GetMapping("/{object}/views")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<ViewResponse> = views.list(objectName).map { it.toResponse() }

    @PostMapping("/{object}/views")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: CreateViewRequest
    ): ViewResponse = views.create(objectName, request).toResponse()

    @GetMapping("/{object}/views/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): ViewResponse = views.byName(objectName, name).toResponse()

    @PutMapping("/{object}/views/{name}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: UpdateViewRequest
    ): ViewResponse = views.update(objectName, name, request).toResponse()

    @DeleteMapping("/{object}/views/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = views.delete(objectName, name)
}
