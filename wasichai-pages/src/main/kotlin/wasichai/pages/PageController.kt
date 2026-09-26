package wasichai.pages

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

// what the dynamic UI renders a detail page from.
data class PageResponse(
    val id: String,
    val name: String,
    val label: String,
    val objectName: String?,
    val kind: String,
    // the whole template, not just its name: the renderer needs the spans to lay the page out, and
    // a name alone would cost it a second request and a loading state on every record it draws.
    // it also means the canvas and the renderer lay out from the same object, which is the cheapest
    // defence there is against the two drifting apart.
    val template: PageTemplateResponse,
    val generated: Boolean,
    val definition: PageDefinition
)

fun ResolvedPage.toResponse(): PageResponse =
    PageResponse(
        id = page.id.toString(),
        name = page.name,
        label = page.label,
        objectName = objectName,
        kind = page.kind.name,
        template = page.template.toResponse(),
        generated = generated,
        definition = page.definition
    )

// explicit dtos, not the enum: PageTemplate carries @get:JsonValue, so serialising it directly
// would emit the bare name and the rows would vanish.
data class PageTemplateRegionResponse(
    val name: String,
    val span: Int
)

data class PageTemplateRowResponse(
    val regions: List<PageTemplateRegionResponse>
)

data class PageTemplateResponse(
    val name: String,
    val columns: Int,
    val rows: List<PageTemplateRowResponse>
)

fun PageTemplate.toResponse(): PageTemplateResponse =
    PageTemplateResponse(
        name = value,
        columns = PageTemplate.COLUMNS,
        rows = rows.map { row -> PageTemplateRowResponse(row.regions.map { PageTemplateRegionResponse(it.name.name, it.span) }) }
    )

@RestController
@RequestMapping("/api/pages")
class PageController(
    private val pages: PageService
) {
    @GetMapping
    suspend fun list(): List<PageResponse> = pages.list().map { it.toResponse() }

    @GetMapping("/{name}")
    suspend fun get(
        @PathVariable name: String
    ): PageResponse = pages.byName(name).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: CreatePageRequest
    ): PageResponse = pages.create(request).toResponse()

    @PutMapping("/{name}")
    suspend fun update(
        @PathVariable name: String,
        @RequestBody request: UpdatePageRequest
    ): PageResponse = pages.update(name, request).toResponse()

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable name: String
    ) = pages.delete(name)
}

// the page to render for one object, stored or generated
@RestController
@RequestMapping("/api/objects")
class ObjectPageController(
    private val pages: PageService
) {
    @GetMapping("/{object}/pages/{kind}")
    suspend fun resolve(
        @PathVariable("object") objectName: String,
        @PathVariable("kind") kind: String
    ): PageResponse = pages.resolve(objectName, parsePageKind(kind)).toResponse()
}

// static and tenant-free: the catalogue is code, the same nine for everybody. it sits under
// /api/metadata beside system-fields because it is the same kind of thing -- a fixed list the UI
// needs to build a form. not under /api/pages, where GET /api/pages/{name} would shadow it.
@RestController
@RequestMapping("/api/metadata/page-templates")
class PageTemplateController {
    @GetMapping
    fun templates(): List<PageTemplateResponse> = PageTemplate.entries.map { it.toResponse() }
}
