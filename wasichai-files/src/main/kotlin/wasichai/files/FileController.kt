package wasichai.files

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordETag
import wasichai.core.data.RecordResponse
import wasichai.core.data.withETag
import java.nio.charset.StandardCharsets
import java.util.UUID

// the file routes of FILE and IMAGE fields (ADR-0061). the multipart part is named "file".
@RestController
@RequestMapping("/api/objects/{object}")
class FileController(
    private val files: FileService
) {
    // replaces the field's file: a PATCH of that field, answered like one (the record, its ETag)
    @PostMapping("/records/{id}/files/{field}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replace(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable field: String,
        @RequestPart("file") file: FilePart,
        @RequestHeader(ChangeReason.HEADER, required = false) reason: String?,
        @RequestHeader(RecordETag.IF_MATCH, required = false) ifMatch: String?,
        response: ServerHttpResponse
    ): RecordResponse {
        // the headers' shape first, as the record routes do: refused before a byte is stored
        val expected = RecordETag.parseIfMatch(ifMatch)
        val reasonText = ChangeReason.fromHeader(reason)
        return files.replace(objectName, id, field, file.toUpload(), reasonText, expected).withETag(response)
    }

    // an upload for a record still to be created: send the answer's id as the field's value
    @PostMapping("/files/{field}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun stage(
        @PathVariable("object") objectName: String,
        @PathVariable field: String,
        @RequestPart("file") file: FilePart
    ): Map<String, Any?> = files.stageAsRead(objectName, field, file.toUpload())

    // the bytes. an IMAGE is shown inline; anything else is a download the browser never renders
    @GetMapping("/records/{id}/files/{field}")
    suspend fun download(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable field: String
    ): ResponseEntity<Flux<DataBuffer>> {
        val download = files.open(objectName, id, field)
        val file = download.file
        val inline = download.fieldType == IMAGE && file.contentType.startsWith("image/")
        val disposition =
            (if (inline) ContentDisposition.inline() else ContentDisposition.attachment())
                .filename(download.fileName, StandardCharsets.UTF_8)
                .build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .contentLength(file.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header(NOSNIFF_HEADER, "nosniff")
            .body(download.content)
    }

    private fun FilePart.toUpload(): FileUpload = FileUpload(filename(), headers().contentType?.toString(), content())

    private companion object {
        const val NOSNIFF_HEADER = "X-Content-Type-Options"
    }
}
