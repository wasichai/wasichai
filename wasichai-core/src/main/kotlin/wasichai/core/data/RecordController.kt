package wasichai.core.data

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ): RecordResponse = records.get(objectName, id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable("object") objectName: String,
        @RequestBody request: RecordRequest
    ): RecordResponse = records.create(objectName, request)

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestBody request: RecordRequest
    ): RecordResponse = records.update(objectName, id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID
    ) = records.delete(objectName, id)
}
