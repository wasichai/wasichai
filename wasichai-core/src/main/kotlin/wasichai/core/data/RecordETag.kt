package wasichai.core.data

import wasichai.core.common.FieldViolation
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * A record's version on the wire (ADR-051): the strong ETag `"<updatedAt>"`, updatedAt exactly as the
 * record json writes it (ISO-8601, UTC). So a list item, which carries updatedAt and no header, gives
 * its ETag too. Every write moves updated_at to the statement's `clock_timestamp()`: two writes never
 * share one, inside one transaction either.
 */
object RecordETag {
    const val IF_MATCH = "If-Match"

    fun of(updatedAt: Instant): String = "\"$updatedAt\""

    /**
     * The versions an `If-Match` header accepts, by strong comparison (RFC 9110 13.1.1). null: no header,
     * or `*`, so no precondition: today's write. A weak tag, or a quoted value that is no instant, matches
     * nothing; the list may end up empty, and then the write is stale. Malformed: 400 on the header.
     */
    fun parseIfMatch(raw: String?): List<Instant>? {
        val value = raw?.trim() ?: return null
        if (value == "*") return null
        val accepted = mutableListOf<Instant>()
        var tags = 0
        var i = 0
        while (i < value.length) {
            if (value[i] == ',' || value[i] == ' ' || value[i] == '\t') {
                i++
                continue
            }
            val weak = value.startsWith("W/", i)
            val open = if (weak) i + 2 else i
            if (value.getOrNull(open) != '"') throw malformed()
            val close = value.indexOf('"', open + 1)
            if (close < 0) throw malformed()
            val opaque = value.substring(open + 1, close)
            // etagc: visible ascii but the quote, or obs-text. a space or control char is no tag
            if (opaque.any { it.code < 0x21 || it.code == 0x7F }) throw malformed()
            tags++
            // strong comparison: a weak tag never matches
            if (!weak) instantOrNull(opaque)?.let { accepted += it }
            i = close + 1
            while (i < value.length && (value[i] == ' ' || value[i] == '\t')) i++
            if (i < value.length && value[i] != ',') throw malformed()
        }
        if (tags == 0) throw malformed()
        return accepted
    }

    // the answer to a write whose If-Match no longer holds. said only of a record the caller can read.
    fun stale(id: UUID): PreconditionFailedException =
        PreconditionFailedException(
            "Record $id changed since you read it",
            listOf(FieldViolation(IF_MATCH, "does not match the record's current ETag; read it again"))
        )

    private fun instantOrNull(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun malformed() = ValidationException("Malformed $IF_MATCH header", IF_MATCH, "send * or the quoted ETag a read returned")
}
