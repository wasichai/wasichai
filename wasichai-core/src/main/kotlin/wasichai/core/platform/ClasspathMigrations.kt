package wasichai.core.platform

import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import java.io.InputStreamReader
import java.io.Reader

// flyway scans jars and directories; a native image has neither. spring's resolver can list an
// image's resources, so in an image a module's migrations are listed through it (ADR-068).
// the files themselves must be in the image: WasichaiMigrationsRuntimeHints puts them there.
class ClasspathMigrations(
    location: String,
    private val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
) : ResourceProvider {
    private val root = location.removePrefix("classpath:").trim('/')

    // flyway asks once per prefix (V, R__, afterMigrate…): list the location once
    private val all: List<LoadableResource> by lazy {
        resolver
            .getResources("classpath*:$root/**/*")
            .filter { it.isReadable && !it.filename.isNullOrEmpty() }
            .map { Migration(root, relativePath(it), it) }
            .distinctBy { it.relativePath }
            .sorted()
    }

    override fun getResource(name: String): LoadableResource? = all.firstOrNull { it.relativePath == name }

    override fun getResources(
        prefix: String,
        suffixes: Array<String>
    ): Collection<LoadableResource> = all.filter { file -> file.filename.startsWith(prefix) && suffixes.any { file.filename.endsWith(it) } }

    // the path below the location, as flyway's own scan names it
    private fun relativePath(resource: Resource): String {
        val url = resource.url.toString()
        val at = url.lastIndexOf("$root/")
        return if (at < 0) resource.filename!! else url.substring(at + root.length + 1)
    }

    private class Migration(
        private val root: String,
        private val relative: String,
        private val resource: Resource
    ) : LoadableResource() {
        // flyway's default encoding; wasichai's migrations are utf-8
        override fun read(): Reader = InputStreamReader(resource.inputStream, Charsets.UTF_8)

        override fun getAbsolutePath(): String = "$root/$relative"

        override fun getAbsolutePathOnDisk(): String = absolutePath

        override fun getFilename(): String = relative.substringAfterLast('/')

        override fun getRelativePath(): String = relative
    }
}
