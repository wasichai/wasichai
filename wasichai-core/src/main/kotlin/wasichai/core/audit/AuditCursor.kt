package wasichai.core.audit

import wasichai.core.common.ValidationException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

// where an audit page resumes: the last row's occurred_at and id, the pair the list is ordered by, plus a hash of
// the filters it was read under, so a cursor only continues its own list. opaque: base64url of a few lines.
// built like RecordCursor (ADR-036). ADR-052.
internal data class AuditCursor(
    val filters: String,
    val occurredAt: Instant,
    val id: UUID
) {
    fun encode(): String = ENCODER.encodeToString(listOf(VERSION, filters, occurredAt.toString(), id.toString()).joinToString("\n").toByteArray())

    companion object {
        private const val VERSION = "1"
        private const val PARTS = 4
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        // [filters]: the hash of the list being read now. a cursor of another list is a 400, not a silent jump
        fun decode(
            raw: String,
            filters: String
        ): AuditCursor {
            val parts =
                runCatching { String(Base64.getUrlDecoder().decode(raw.trim())).split("\n") }
                    .getOrNull()
                    ?.takeIf { it.size == PARTS && it[0] == VERSION && it[1].isNotEmpty() }
                    ?: throw invalid()
            val occurredAt = runCatching { Instant.parse(parts[2]) }.getOrNull() ?: throw invalid()
            val id = runCatching { UUID.fromString(parts[3]) }.getOrNull() ?: throw invalid()
            if (parts[1] != filters) {
                throw ValidationException(
                    "The cursor belongs to another filter set",
                    "after",
                    "was returned for other filters; keep them, or start again without after"
                )
            }
            return AuditCursor(parts[1], occurredAt, id)
        }

        // a short hash of what shapes the list. each value length-prefixed: no two filter sets share a key
        fun filtersOf(values: List<Any?>): String {
            val key = values.joinToString("") { value -> (value?.toString() ?: "").let { "${it.length}:$it" } }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return ENCODER.encodeToString(digest.copyOf(12))
        }

        private fun invalid() = ValidationException("Invalid cursor", "after", "is not a cursor X-Next-Cursor returned")
    }
}
