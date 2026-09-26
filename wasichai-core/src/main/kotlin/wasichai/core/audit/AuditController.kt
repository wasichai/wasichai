package wasichai.core.audit

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AuditController(
    private val audit: AuditQueryService
) {
    @GetMapping("/api/audit")
    suspend fun list(
        @RequestParam(required = false) objectName: String?,
        @RequestParam(required = false) recordId: UUID?,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) limit: Int?
    ): List<AuditEntry> = audit.list(objectName, recordId, operation, limit)

    // history of one record. lives in audit, the path belongs to the record it describes.
    @GetMapping("/api/objects/{object}/records/{id}/history")
    suspend fun history(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestParam(required = false) limit: Int?
    ): List<AuditEntry> = audit.history(objectName, id, limit)
}
