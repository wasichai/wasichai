package wasichai.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// a directory on this machine: <root>/<organization id>/<file id>. every replica must see the same
// directory (a shared volume), or uploads land on one node and reads miss on the others.
class LocalFileStore(
    root: Path
) : FileStore {
    private val root: Path = root.toAbsolutePath().normalize()

    override suspend fun put(
        key: String,
        content: ByteArray,
        contentType: String
    ) {
        val target = pathOf(key)
        withContext(Dispatchers.IO) {
            Files.createDirectories(target.parent)
            // written aside, then moved in: a reader never sees half a file
            val partial = Files.createTempFile(target.parent, ".upload-", ".part")
            try {
                Files.write(partial, content, StandardOpenOption.TRUNCATE_EXISTING)
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
    }

    override fun open(key: String): Flux<DataBuffer> = DataBufferUtils.read(pathOf(key), DefaultDataBufferFactory.sharedInstance, BUFFER)

    override suspend fun delete(key: String) {
        val target = pathOf(key)
        withContext(Dispatchers.IO) { Files.deleteIfExists(target) }
    }

    private fun pathOf(key: String): Path {
        val path = root.resolve(FileStore.requireKey(key)).normalize()
        check(path.startsWith(root)) { "file key escapes the store: $key" }
        return path
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
