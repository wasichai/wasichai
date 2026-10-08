package wasichai.core.audit

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

@RestController
class AuditController(
    private val audit: AuditQueryService
) {
    // still a json array; the next page's cursor, when one follows, is the X-Next-Cursor header (ADR-052)
    @GetMapping("/api/audit")
    suspend fun list(
        @RequestParam(required = false) objectName: String?,
        @RequestParam(required = false) recordId: UUID?,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) limit: Int?,
        // one request's rows, or one source's (ADR-050)
        @RequestParam(required = false) correlationId: String?,
        @RequestParam(required = false) source: String?,
        // a period, who made the change, where the page starts (ADR-052). strings: a bad one is a 400 naming it
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) serviceAccount: String?,
        @RequestParam(required = false) after: String?
    ): ResponseEntity<List<AuditEntry>> {
        val filter = AuditFilter(correlationId, source, instant("from", from), instant("to", to), uuid("userId", userId), serviceAccount)
        return answer(audit.page(objectName, recordId, operation, limit, filter, after))
    }

    // history of one record. lives in audit, the path belongs to the record it describes.
    @GetMapping("/api/objects/{object}/records/{id}/history")
    suspend fun history(
        @PathVariable("object") objectName: String,
        @PathVariable id: UUID,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) after: String?
    ): ResponseEntity<List<AuditEntry>> {
        val filter = AuditFilter(from = instant("from", from), to = instant("to", to), userId = uuid("userId", userId))
        return answer(audit.historyPage(objectName, id, limit, filter, after))
    }

    private fun answer(page: AuditPage): ResponseEntity<List<AuditEntry>> {
        val ok = ResponseEntity.ok()
        page.nextCursor?.let { ok.header(AuditPage.NEXT_CURSOR_HEADER, it) }
        return ok.body(page.entries)
    }

    internal companion object {
        // ISO-8601, Z or an offset. blank is no filter. an unencoded + of the offset arrives as a space: put it back
        fun instant(
            name: String,
            raw: String?
        ): Instant? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Instant.parse(value.replace(' ', '+')) }.getOrNull()
                ?: throw ValidationException("Invalid $name", name, "is not an ISO-8601 instant, like 2026-10-01T00:00:00Z")
        }

        // UUID.fromString takes 1-2-3-4-5 too: only the canonical form is an id
        fun uuid(
            name: String,
            raw: String?
        ): UUID? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase() }
                ?: throw ValidationException("Invalid $name", name, "is not a UUID")
        }
    }
}
