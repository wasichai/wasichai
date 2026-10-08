package wasichai.files

import org.springframework.core.io.buffer.DataBuffer
import reactor.core.publisher.Flux
import java.util.UUID

/**
 * Where the bytes of an uploaded file live (ADR-0061). The module writes a key once, reads it many
 * times, and deletes it when no record names the file any more. Keys are `<organization id>/<file id>`
 * ([keyOf]), never derived from what a client sent.
 *
 * Declare a bean of this type to bring your own store: the module's local and S3-compatible ones back
 * off (`@ConditionalOnMissingBean`).
 */
interface FileStore {
    /** Stores [content] under [key], replacing nothing: a key is new every time. */
    suspend fun put(
        key: String,
        content: ByteArray,
        contentType: String
    )

    /** The bytes under [key], streamed. Errors (a missing key included) surface on subscription. */
    fun open(key: String): Flux<DataBuffer>

    /** Removes [key]. A key that is not there is no error. */
    suspend fun delete(key: String)

    companion object {
        private val KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun keyOf(
            organizationId: UUID,
            fileId: UUID
        ): String = "$organizationId/$fileId"

        // a store refuses any other shape: no "..", no separator of its own, nothing a path could escape with
        fun requireKey(key: String): String {
            require(KEY.matches(key)) { "not a file key: $key" }
            return key
        }
    }
}
