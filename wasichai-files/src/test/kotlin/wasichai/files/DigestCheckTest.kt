package wasichai.files

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// issue 144: what a download streams is checked against the upload's size and sha256 (ADR-074)
class DigestCheckTest {
    private val bytes = ByteArray(10_000) { (it % 251).toByte() }
    private val fileId = UUID.randomUUID()
    private val mismatches = mutableListOf<Pair<Long, String?>>()

    private fun check(
        size: Long = bytes.size.toLong(),
        sha256: String = sha256(bytes)
    ) = DigestCheck(size, sha256, fileId) { actualSize, actualSha256 -> mismatches += actualSize to actualSha256 }

    @Test
    fun `intact bytes pass through whole, in order, and nothing is recorded`(): Unit =
        runBlocking {
            assertThat(join(check().verify(chunks(bytes, 1024)))).isEqualTo(bytes)
            assertThat(mismatches).isEmpty()
        }

    @Test
    fun `a byte changed in the store fails the stream, and the last buffer is never sent`() {
        val altered = bytes.copyOf().also { it[5000] = 0x7f }
        val sent = mutableListOf<ByteArray>()
        assertThatThrownBy {
            runBlocking {
                check()
                    .verify(chunks(altered, 1024))
                    .doOnNext { sent += read(it) }
                    .collectList()
                    .awaitSingle()
            }
        }.isInstanceOf(StoredFileDamagedException::class.java)
        // everything but the held buffer went out: the client gets less than Content-Length
        assertThat(sent.sumOf { it.size }).isLessThan(altered.size)
        assertThat(mismatches).containsExactly(altered.size.toLong() to sha256(altered))
    }

    @Test
    fun `bytes past the stored size stop the read there, with no digest`() {
        val longer = bytes + ByteArray(2048)
        assertThatThrownBy { runBlocking { join(check().verify(chunks(longer, 1024))) } }
            .isInstanceOf(StoredFileDamagedException::class.java)
        assertThat(mismatches).containsExactly(bytes.size.toLong() + 1024 - bytes.size % 1024 to null)
    }

    @Test
    fun `fewer bytes than stored fail, with what was read`() {
        val shorter = bytes.copyOf(4000)
        assertThatThrownBy { runBlocking { join(check().verify(chunks(shorter, 1024))) } }
            .isInstanceOf(StoredFileDamagedException::class.java)
        assertThat(mismatches).containsExactly(4000L to sha256(shorter))
    }

    @Test
    fun `a single buffer file sends nothing before the check`() {
        val altered = bytes.copyOf().also { it[0] = 0x7f }
        val sent = mutableListOf<ByteArray>()
        assertThatThrownBy {
            runBlocking {
                check()
                    .verify(chunks(altered, altered.size))
                    .doOnNext { sent += read(it) }
                    .collectList()
                    .awaitSingle()
            }
        }.isInstanceOf(StoredFileDamagedException::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a failure to record the damage still fails the stream`() {
        val failing = DigestCheck(bytes.size.toLong(), sha256(bytes), fileId) { _, _ -> error("database down") }
        assertThatThrownBy { runBlocking { join(failing.verify(chunks(bytes.copyOf(10), 1024))) } }
            .isInstanceOf(StoredFileDamagedException::class.java)
    }

    @Test
    fun `Repr-Digest carries the sha256 as base64 between colons`() {
        // the sha256 of the empty content, well known
        assertThat(DigestCheck.reprDigest(sha256(ByteArray(0)))).isEqualTo("sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:")
    }

    private fun chunks(
        content: ByteArray,
        size: Int
    ): Flux<DataBuffer> = Flux.fromIterable(content.asList().chunked(size)).map { DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()) }

    private suspend fun join(content: Flux<DataBuffer>): ByteArray = read(DataBufferUtils.join(content).awaitSingle())

    private fun read(buffer: DataBuffer): ByteArray {
        val out = ByteArray(buffer.readableByteCount())
        buffer.read(out)
        DataBufferUtils.release(buffer)
        return out
    }

    private fun sha256(content: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
}
