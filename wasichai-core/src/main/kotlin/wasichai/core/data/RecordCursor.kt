package wasichai.core.data

import wasichai.core.common.ValidationException
import java.util.Base64
import java.util.UUID

// where a keyset read resumes: the last row's sort key, direction, sort value (postgres text of
// the column, null for a null) and id. opaque to the caller: base64url of a few lines. ADR-036.
internal data class RecordCursor(
    val sort: String,
    val descending: Boolean,
    val value: String?,
    val id: UUID
) {
    // the value goes last: it is the only part that may hold a line break
    fun encode(): String {
        val dir = if (descending) "d" else "a"
        val tail = if (value == null) NULL else PRESENT + value
        return ENCODER.encodeToString(listOf(VERSION, sort, dir, id.toString(), tail).joinToString("\n").toByteArray())
    }

    companion object {
        private const val VERSION = "1"
        private const val NULL = "-"
        private const val PRESENT = "+"
        private const val PARTS = 5
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun decode(raw: String): RecordCursor {
            val parts =
                runCatching { String(Base64.getUrlDecoder().decode(raw.trim())).split("\n", limit = PARTS) }
                    .getOrNull()
                    ?.takeIf { it.size == PARTS && it[0] == VERSION && it[2] in setOf("a", "d") && it[1].isNotEmpty() }
                    ?: throw invalid()
            val id = runCatching { UUID.fromString(parts[3]) }.getOrNull() ?: throw invalid()
            val value =
                when {
                    parts[4] == NULL -> null
                    parts[4].startsWith(PRESENT) -> parts[4].substring(PRESENT.length)
                    else -> throw invalid()
                }
            return RecordCursor(parts[1], parts[2] == "d", value, id)
        }

        private fun invalid() = ValidationException("Invalid cursor", "after", "is not a nextCursor a record list returned")
    }
}
