package wasichai.documents

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

// an issued document, frozen. `snapshot` carries the template it was issued from, so the viewer
// draws it without ever reading the type again -- which is what makes an archived one still say
// what it said.
data class DocumentResponse(
    val id: String,
    val number: String,
    val year: Int,
    val sequence: Int,
    val status: String,
    val recordId: String,
    val objectName: String,
    val issuedAt: Instant?,
    val snapshot: DocumentSnapshot
)

fun Document.toResponse(): DocumentResponse =
    DocumentResponse(
        id = id.toString(),
        number = number,
        year = year,
        sequence = sequence,
        status = status.name,
        recordId = recordId.toString(),
        objectName = snapshot.objectName,
        issuedAt = issuedAt,
        snapshot = snapshot
    )

@RestController
@RequestMapping("/api")
class DocumentController(
    private val documents: DocumentService
) {
    // every document this record ever issued, newest first: the valid one and the archived ones
    @GetMapping("/objects/{object}/records/{id}/documents")
    suspend fun forRecord(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ): List<DocumentResponse> = documents.forRecord(objectName, id).map { it.toResponse() }

    @PostMapping("/objects/{object}/records/{id}/documents/{type}")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun issue(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @PathVariable("type") typeName: String
    ): DocumentResponse = documents.issueAsUser(objectName, id, typeName).toResponse()

    @GetMapping("/documents/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): DocumentResponse = documents.byId(id).toResponse()
}
