package wasichai.files

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import wasichai.core.common.WasichaiException
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

// a stored file whose bytes no longer match what was uploaded (ADR-074). no digest in the message: the audit row has them
class StoredFileDamagedException(
    val fileId: UUID
) : WasichaiException(HttpStatus.INTERNAL_SERVER_ERROR, "File $fileId failed its integrity check")

/**
 * The stored bytes, checked against the size and sha256 taken at upload while they stream (ADR-074). The last buffer
 * waits for the check: a damaged file never reaches its client whole, it ends short of its Content-Length, or as a 500
 * when nothing was sent yet. [onMismatch] gets the size read and the sha256 of it (null when the bytes ran past the
 * stored size and the read stopped there); it runs in the subscriber's Reactor context, before the error.
 */
internal class DigestCheck(
    private val expectedSize: Long,
    private val expectedSha256: String,
    private val fileId: UUID,
    private val onMismatch: suspend (actualSize: Long, actualSha256: String?) -> Unit
) {
    fun verify(content: Flux<DataBuffer>): Flux<DataBuffer> = Flux.defer { Run().flux(content) }

    // one per subscription: concatMap serializes the steps, so plain fields do
    private inner class Run {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        private var held: DataBuffer? = null

        fun flux(content: Flux<DataBuffer>): Flux<DataBuffer> =
            content
                .concatMap(::step)
                .concatWith(Mono.defer(::finish))
                .doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
                // cancelled or failed: the held buffer goes back to the pool
                .doFinally { release() }

        // hand on the buffer before this one: the newest always waits
        private fun step(buffer: DataBuffer): Mono<DataBuffer> {
            size += buffer.readableByteCount()
            if (size > expectedSize) {
                DataBufferUtils.release(buffer)
                return damaged(null)
            }
            buffer.readableByteBuffers().use { it.forEach(digest::update) }
            val previous = held
            held = buffer
            return Mono.justOrEmpty(previous)
        }

        private fun finish(): Mono<DataBuffer> {
            val actual = HexFormat.of().formatHex(digest.digest())
            if (size != expectedSize || actual != expectedSha256) return damaged(actual)
            val last = held
            held = null
            return Mono.justOrEmpty(last)
        }

        // what onMismatch fails with is logged, never what the client sees: the file is damaged either way
        private fun damaged(actualSha256: String?): Mono<DataBuffer> =
            mono { onMismatch(size, actualSha256) }
                .onErrorResume { e ->
                    log.error("Could not record the damage of stored file {}", fileId, e)
                    Mono.empty()
                }.then(Mono.error(StoredFileDamagedException(fileId)))

        private fun release() {
            held?.let(DataBufferUtils::release)
            held = null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DigestCheck::class.java)

        const val REPR_DIGEST = "Repr-Digest"

        // RFC 9530: the digest as a structured-field byte sequence, base64 between colons
        fun reprDigest(sha256Hex: String): String = "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)) + ":"
    }
}
