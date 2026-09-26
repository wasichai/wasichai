package wasichai.documents

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

// what the template editor loads and saves
data class DocumentTypeResponse(
    val id: String,
    val name: String,
    val label: String,
    val prefix: String,
    val objectName: String,
    val template: TemplateNode
)

fun ResolvedDocumentType.toResponse(): DocumentTypeResponse =
    DocumentTypeResponse(
        id = type.id.toString(),
        name = type.name,
        label = type.label,
        prefix = type.prefix,
        objectName = objectName,
        template = type.template
    )

// document types are named per object, so they hang off the object, like forms and views
@RestController
@RequestMapping("/api/objects")
class DocumentTypeController(
    private val types: DocumentTypeService
) {
    @GetMapping("/{object}/document-types")
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<DocumentTypeResponse> = types.list(objectName).map { it.toResponse() }

    @GetMapping("/{object}/document-types/{name}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ): DocumentTypeResponse = types.byName(objectName, name).toResponse()

    @PostMapping("/{object}/document-types")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: CreateDocumentTypeRequest
    ): DocumentTypeResponse = types.create(objectName, request).toResponse()

    @PutMapping("/{object}/document-types/{name}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable name: String,
        @RequestBody request: UpdateDocumentTypeRequest
    ): DocumentTypeResponse = types.update(objectName, name, request).toResponse()

    @DeleteMapping("/{object}/document-types/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable name: String
    ) = types.delete(objectName, name)
}
