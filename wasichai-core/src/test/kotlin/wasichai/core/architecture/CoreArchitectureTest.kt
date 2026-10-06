package wasichai.core.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// core is a library other modules build on. it must never reach up into them, and its own
// packages must stay a DAG (the old package cycles are gone for good). ADR-0025.
class CoreArchitectureTest {
    private val root = File("src/main/kotlin/wasichai/core")

    // package -> the core packages it may use
    private val allowed: Map<String, Set<String>> =
        mapOf(
            "common" to emptySet(),
            "platform" to setOf("common"),
            "identity" to setOf("common", "platform"),
            "metadata" to setOf("common", "platform", "identity"),
            "audit" to setOf("common", "platform", "identity", "metadata"),
            "data" to setOf("common", "platform", "identity", "metadata", "audit"),
            "admin" to setOf("common", "platform", "identity", "metadata"),
            "organization" to setOf("common", "platform", "identity", "metadata"),
            "autoconfigure" to setOf("common", "platform", "identity", "metadata", "audit", "data", "admin", "organization")
        )

    private val coreReference = Regex("""\bwasichai\.core\.([a-z]+)\b""")
    private val moduleReference = Regex("""\bwasichai[.:-](views|forms|pages|workflow|automation|documents|gis|agent|notifications)\b""")
    private val geometryWords = Regex("""(?i)\b(postgis|geojson|geometry|geometries|bbox|wgs84)\b|\bST_[A-Za-z]+""")

    // SQL has no "wasichai." prefix to catch a module reference by, so a module table shows up
    // schema-qualified (app.documents) or as a definition (documents (...)). plain english
    // prose mentioning "documents" in a comment must not trip this.
    private val moduleTableReference =
        Regex(
            """\.(views|forms|pages|workflows|automations|documents|layers|notification\w*)\b""" +
                """|\b(views|forms|pages|workflows|automations|documents|layers|notification\w*)\s*\("""
        )

    private fun sources(): List<File> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun packageOf(file: File): String = file.relativeTo(root).path.substringBefore(File.separator)

    private fun offenders(pattern: Regex): List<String> =
        sources().flatMap { file ->
            file.readLines().mapIndexedNotNull {
                index,
                line
                ->
                if (pattern.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" else null
            }
        }

    private val resourcesRoot = File("src/main/resources")

    private fun resources(): List<File> =
        resourcesRoot
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .toList()

    private fun resourceOffenders(pattern: Regex): List<String> =
        resources().flatMap { file ->
            file.readLines().mapIndexedNotNull {
                index,
                line
                ->
                if (pattern.containsMatchIn(line)) "${file.relativeTo(resourcesRoot)}:${index + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `the scan finds the sources`() {
        assertThat(sources()).hasSizeGreaterThan(40)
    }

    @Test
    fun `core never names an optional module`() {
        assertThat(offenders(moduleReference)).isEmpty()
        assertThat(
            File("build.gradle.kts").readText()
        ).doesNotContainPattern(""":wasichai-(views|forms|pages|workflow|automation|documents|gis|agent|notifications)""")
    }

    @Test
    fun `core does not speak geometry`() {
        assertThat(offenders(geometryWords)).isEmpty()
    }

    @Test
    fun `every package sits in the dag and uses only the packages below it`() {
        val violations =
            sources().flatMap { file ->
                val own = packageOf(file)
                val permitted = allowed[own] ?: return@flatMap listOf("${file.relativeTo(root)}: package '$own' is not in the dag")
                file
                    .readLines()
                    .filterNot { it.startsWith("package ") }
                    .flatMap { line -> coreReference.findAll(line).map { it.groupValues[1] }.toList() }
                    .filter { it != own && it !in permitted }
                    .distinct()
                    .map { "${file.relativeTo(root)}: $own may not use wasichai.core.$it" }
            }
        assertThat(violations).isEmpty()
    }

    @Test
    fun `the scan finds the resources`() {
        assertThat(resources()).isNotEmpty()
    }

    @Test
    fun `core migrations never name an optional module table`() {
        assertThat(resourceOffenders(moduleTableReference)).isEmpty()
    }

    @Test
    fun `core migrations do not speak geometry`() {
        assertThat(resourceOffenders(geometryWords)).isEmpty()
    }
}
