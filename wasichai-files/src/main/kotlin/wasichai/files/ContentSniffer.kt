package wasichai.files

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// the content type of an upload, from its bytes. the header the client sent is trusted only where the
// bytes cannot tell (a zip-based office file, plain text), and only when they agree with it. anything
// else is application/octet-stream. magic numbers only: no parser ever runs on an upload.
object ContentSniffer {
    const val OCTET_STREAM = "application/octet-stream"

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    // a zip says nothing about what it packs: these are believed when the bytes are a zip
    private val ZIP_BASED =
        setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
        )

    // text has no magic number: believed when the bytes are utf-8 with no NUL
    private val TEXT = setOf("text/plain", "text/csv")

    fun detect(
        bytes: ByteArray,
        declared: String?
    ): String {
        val claimed = declared?.substringBefore(';')?.trim()?.lowercase()
        return when {
            bytes.startsWith(PNG) -> "image/png"
            bytes.startsWith(JPEG) -> "image/jpeg"
            bytes.startsWith(GIF87) || bytes.startsWith(GIF89) -> "image/gif"
            bytes.startsWith(RIFF) && bytes.startsWith(WEBP, 8) -> "image/webp"
            bytes.startsWith(PDF) -> "application/pdf"
            bytes.startsWith(ZIP) -> if (claimed in ZIP_BASED) claimed!! else "application/zip"
            claimed in TEXT && isText(bytes) -> claimed!!
            else -> OCTET_STREAM
        }
    }

    // exact, or a "type/*" entry. case-insensitive, as media types are.
    fun allowed(
        contentType: String,
        allowList: List<String>?
    ): Boolean {
        if (allowList == null) return true
        val type = contentType.lowercase()
        return allowList.any { entry ->
            val wanted = entry.trim().lowercase()
            wanted == type || (wanted.endsWith("/*") && type.startsWith(wanted.dropLast(1)))
        }
    }

    private fun isText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun ByteArray.startsWith(
        prefix: ByteArray,
        offset: Int = 0
    ): Boolean {
        if (size < offset + prefix.size) return false
        return prefix.indices.all { this[offset + it] == prefix[it] }
    }
}
