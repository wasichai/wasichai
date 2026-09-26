package wasichai.core.data

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageResponse
import wasichai.core.metadata.RelatedSideResponse
import wasichai.core.metadata.RelationshipMapper
import wasichai.core.metadata.RelationshipService
import java.util.UUID

data class LinkRequest(
    val otherId: String
)

@RestController
@RequestMapping("/api/objects/{object}")
class RelatedRecordController(
    private val relationships: RelationshipService,
    private val related: RelatedRecordService,
    private val mapper: RelationshipMapper,
    private val queries: RecordQueryParser
) {
    @GetMapping("/relationships")
    suspend fun relationshipsOf(
        @PathVariable("object") objectName: String
    ): List<RelatedSideResponse> = relationships.forObject(objectName).map(mapper::toResponse)

    @GetMapping("/records/{id}/related/{relationship}")
    suspend fun related(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @RequestParam params: Map<String, String>
    ): PageResponse<RecordResponse> {
        val (_, page) = related.relatedRecords(objectName, id, relationship, queries.parse(params))
        return PageResponse(
            content = page.content.map { it.toResponse() },
            page = page.page,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }

    @PostMapping("/records/{id}/related/{relationship}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun link(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @RequestBody request: LinkRequest
    ) = related.link(objectName, id, relationship, UUID.fromString(request.otherId))

    @DeleteMapping("/records/{id}/related/{relationship}/{otherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unlink(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable relationship: String,
        @PathVariable otherId: UUID
    ) = related.unlink(objectName, id, relationship, otherId)
}
