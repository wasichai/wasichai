package wasichai.core.data

import wasichai.core.common.ValidationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Why a record changed, as its writer says it (ADR-041). Stored on that write's audit row. Trimmed;
 * blank is no reason; at most [MAX_LENGTH] characters. An object with `requiresReason` refuses a
 * write without one (400 on `reason`).
 */
object ChangeReason {
    // what the record write routes read it from: the body is the record, sections included
    const val HEADER = "X-Change-Reason"

    // one observation, not a document. counted in characters (code points).
    const val MAX_LENGTH = 500

    fun normalize(raw: String?): String? {
        val reason = raw?.trim()?.ifEmpty { null } ?: return null
        if (reason.codePointCount(0, reason.length) > MAX_LENGTH) {
            throw ValidationException("Change reason is too long", "reason", "at most $MAX_LENGTH characters")
        }
        // postgres text cannot hold NUL, and the audit row is written after the record: refuse here, before
        // anything lands. tab and line breaks are text, a multi-line observation is fine.
        if (reason.any { Character.isISOControl(it) && it !in TEXT_CONTROLS }) {
            throw ValidationException("Change reason has a control character", "reason", "no control characters except tab and line breaks")
        }
        return reason
    }

    private val TEXT_CONTROLS = setOf('\t', '\n', '\r')

    /**
     * A header value: as is, or the RFC 8187 form `UTF-8''<percent-encoded>`. A header carries
     * ISO-8859-1 at best, and a browser refuses anything else, so a reason in any other script
     * comes encoded. A plain value is never percent-decoded: "10% off" stays what it says.
     */
    fun fromHeader(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (!value.startsWith(UTF8_PREFIX, ignoreCase = true)) return normalize(value)
        return normalize(percentDecode(value.substring(UTF8_PREFIX.length)))
    }

    private const val UTF8_PREFIX = "UTF-8''"

    private fun percentDecode(encoded: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%') {
                // exactly two ascii hex digits: toIntOrNull takes a sign ("%+1"), Character.digit any script's digits
                val high = hexDigit(encoded.getOrNull(i + 1))
                val low = hexDigit(encoded.getOrNull(i + 2))
                if (high < 0 || low < 0) throw malformed()
                bytes.write(high * 16 + low)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
                i++
            }
        }
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            throw malformed()
        }
    }

    private fun hexDigit(c: Char?): Int =
        when {
            c == null -> -1
            c in '0'..'9' -> c - '0'
            c in 'a'..'f' -> c - 'a' + 10
            c in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

    private fun malformed() = ValidationException("Change reason is not valid UTF-8''", "reason", "percent-encode it as UTF-8 (RFC 8187)")
}
