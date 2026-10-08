package wasichai.core.data

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageResponse
import java.util.UUID

@RestController
@RequestMapping("/api/objects/{object}/records")
class RecordController(
    private val records: RecordService,
    private val queries: RecordQueryParser
) {
    @GetMapping
    suspend fun list(
        @PathVariable("object") objectName: String,
        @RequestParam params: Map<String, String>
    ): PageResponse<RecordResponse> = records.list(objectName, queries.parse(params))

    // every single-record answer carries the record's version as its ETag; If-Match sends it back (ADR-051)
    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        response: ServerHttpResponse
    ): RecordResponse = records.get(objectName, id).withETag(response)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: RecordRequest,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        response: ServerHttpResponse
    ): RecordResponse = records.create(objectName, request, ChangeReason.fromHeader(reason), viaApi = true).withETag(response)

    /**
     * The same create, at most once per `Idempotency-Key` of the caller (ADR-058). The answer is the stored
     * one, the first time too, so a replay is the same bytes; a replay says `Idempotent-Replayed: true`.
     */
    @PostMapping(headers = [IdempotencyKeys.HEADER])
    suspend fun createOnce(
        @PathVariable("object") objectName: String,
        @RequestBody request: RecordRequest,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        // not required: an empty header is a 400 on it, not a missing one
        @RequestHeader(IdempotencyKeys.HEADER, required = false) idempotencyKey: String?
    ): ResponseEntity<ByteArray> {
        val reasonText = ChangeReason.fromHeader(reason)
        val created = records.createOnce(objectName, request, reasonText, viaApi = true, idempotencyKey = idempotencyKey.orEmpty())
        val answer = ResponseEntity.status(created.status).contentType(MediaType.APPLICATION_JSON)
        created.record.updatedAt?.let { answer.header(HttpHeaders.ETAG, RecordETag.of(it)) }
        if (created.replayed) answer.header(IdempotencyKeys.REPLAYED, "true")
        return answer.body(created.body.toByteArray(Charsets.UTF_8))
    }

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestBody request: RecordRequest,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        @RequestHeader(RecordETag.IF_MATCH, required = false) ifMatch: String?,
        response: ServerHttpResponse
    ): RecordResponse {
        // the header is read before the reason: a malformed one is the request's shape, refused before anything
        val expected = RecordETag.parseIfMatch(ifMatch)
        return records.update(objectName, id, request, ChangeReason.fromHeader(reason), viaApi = true, expectedUpdatedAt = expected).withETag(response)
    }

    // JSON merge on attributes: only the keys sent are written (ADR-051)
    @PatchMapping("/{id}")
    suspend fun patch(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestBody request: RecordRequest,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        @RequestHeader(RecordETag.IF_MATCH, required = false) ifMatch: String?,
        response: ServerHttpResponse
    ): RecordResponse {
        val expected = RecordETag.parseIfMatch(ifMatch)
        return records.patch(objectName, id, request, ChangeReason.fromHeader(reason), viaApi = true, expectedUpdatedAt = expected).withETag(response)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        @RequestHeader(RecordETag.IF_MATCH, required = false) ifMatch: String?
    ) {
        val expected = RecordETag.parseIfMatch(ifMatch)
        records.delete(objectName, id, ChangeReason.fromHeader(reason), viaApi = true, expectedUpdatedAt = expected)
    }
}

// the record's ETag on the answer. a store that wrote no updated_at gives none rather than a wrong one
fun RecordResponse.withETag(response: ServerHttpResponse): RecordResponse {
    updatedAt?.let { response.headers.set(HttpHeaders.ETAG, RecordETag.of(it)) }
    return this
}
