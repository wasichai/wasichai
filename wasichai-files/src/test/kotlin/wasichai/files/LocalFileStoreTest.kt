package wasichai.files

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.buffer.DataBufferUtils
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID

class LocalFileStoreTest {
    @TempDir
    lateinit var root: Path

    private val organization = UUID.randomUUID()

    @Test
    fun `put, open and delete a key under the organization's directory`(): Unit =
        runBlocking {
            val store = LocalFileStore(root)
            val key = FileStore.keyOf(organization, UUID.randomUUID())
            store.put(key, FileFixtures.PDF, "application/pdf")

            assertThat(root.resolve(key)).exists().hasBinaryContent(FileFixtures.PDF)
            // nothing half-written is left next to it
            assertThat(Files.list(root.resolve(organization.toString())).use { it.count() }).isEqualTo(1)
            assertThat(read(store, key)).isEqualTo(FileFixtures.PDF)

            store.delete(key)
            assertThat(root.resolve(key)).doesNotExist()
            // a second delete is no error
            store.delete(key)
        }

    @Test
    fun `a missing key fails on read`() {
        val store = LocalFileStore(root)
        assertThatThrownBy { runBlocking { read(store, FileStore.keyOf(organization, UUID.randomUUID())) } }
            .isInstanceOf(NoSuchFileException::class.java)
    }

    @Test
    fun `a key that is not organization-slash-id never reaches the disk`() {
        val store = LocalFileStore(root)
        listOf("../etc/passwd", "$organization/../../x", "$organization/acta.pdf", "/abs/$organization", "$organization\\${UUID.randomUUID()}")
            .forEach { key ->
                assertThatThrownBy { runBlocking { store.put(key, byteArrayOf(1), "application/octet-stream") } }
                    .isInstanceOf(IllegalArgumentException::class.java)
                assertThatThrownBy { store.open(key) }.isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    private suspend fun read(
        store: FileStore,
        key: String
    ): ByteArray {
        val joined = DataBufferUtils.join(store.open(key)).awaitSingle()
        return ByteArray(joined.readableByteCount()).also {
            joined.read(it)
            DataBufferUtils.release(joined)
        }
    }
}
