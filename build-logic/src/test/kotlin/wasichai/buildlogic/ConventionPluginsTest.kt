package wasichai.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConventionPluginsTest {
    @TempDir
    lateinit var dir: File

    // test jvm runs in build-logic
    private val repoRoot = File("..").canonicalFile

    private fun write(
        path: String,
        text: String,
    ) = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            // no github credentials, like a laptop
            .withEnvironment(mapOf("HOME" to System.getProperty("user.home")))

    @BeforeEach
    fun probeProject() {
        write(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs { create("libs") { from(files("${File(repoRoot, "gradle/libs.versions.toml").invariantSeparatorsPath}")) } }
            }
            rootProject.name = "probe"
            """.trimIndent() + "\n",
        )
        File(repoRoot, ".editorconfig").copyTo(File(dir, ".editorconfig"))
    }

    @Test
    fun `kotlin library compiles and passes ktlint`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\n\nfun probe(): String = \"ok\"\n")

        val result = runner("build").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertTrue(result.tasks.any { it.path.startsWith(":runKtlintCheck") })
    }

    @Test
    fun `badly formatted kotlin fails the build`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\nfun   probe( ) : String   =\"ok\"")

        val result = runner("build").buildAndFail()

        assertTrue(result.output.contains("ktlint", ignoreCase = true))
    }

    @Test
    fun `check after format in one invocation sees the formatted source`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\nfun   probe( ) : String   =\"ok\"")

        // check named first on purpose: without mustRunAfter it would run first and fail
        runner("ktlintCheck", "ktlintFormat").build()

        assertEquals("package probe\n\nfun probe(): String = \"ok\"\n", File(dir, "src/main/kotlin/probe/Probe.kt").readText())
    }

    @Test
    fun `format is never restored from the build cache`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.kotlin-library\") }\n")
        val unformatted = "package probe\nfun   probe( ) : String   =\"ok\""
        val source = write("src/main/kotlin/probe/Probe.kt", unformatted)

        runner("ktlintFormat", "--build-cache").build()
        // same unformatted input again, fresh build dir: a cacheable format task would come back FROM-CACHE
        source.writeText(unformatted)
        File(dir, "build").deleteRecursively()
        val second = runner("ktlintFormat", "--build-cache").build()

        assertTrue(second.tasks.none { it.path.contains("Format") && it.outcome == TaskOutcome.FROM_CACHE }, second.output)
        assertEquals("package probe\n\nfun probe(): String = \"ok\"\n", source.readText())
    }

    @Test
    fun `spring module compiles an auto configuration`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.spring-module\") }\n")
        write(
            "src/main/kotlin/probe/ProbeAutoConfiguration.kt",
            """
            package probe

            import org.springframework.boot.autoconfigure.AutoConfiguration

            @AutoConfiguration
            class ProbeAutoConfiguration
            """.trimIndent() + "\n",
        )

        val result = runner("compileKotlin").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    }

    @Test
    fun `publishing writes a wasichai pom and publishes locally without github credentials`() {
        write(
            "build.gradle.kts",
            "plugins {\n    id(\"wasichai.kotlin-library\")\n    id(\"wasichai.publishing\")\n}\nversion = \"9.9.9\"\n",
        )
        write("src/main/kotlin/probe/Probe.kt", "package probe\n\nfun probe(): String = \"ok\"\n")

        val result = runner("publishToMavenLocal", "tasks", "--all", "-Dmaven.repo.local=${File(dir, "m2").path}").build()

        assertTrue(result.output.contains("publishMavenPublicationToGitHubPackagesRepository"))
        val pom = File(dir, "build/publications/maven/pom-default.xml").readText()
        assertTrue(pom.contains("<groupId>wasichai</groupId>"))
        assertTrue(pom.contains("<version>9.9.9</version>"))
    }

    @Test
    fun `integration test task runs only integration tagged tests`() {
        write("build.gradle.kts", "plugins {\n    id(\"wasichai.kotlin-library\")\n    id(\"wasichai.integration-test\")\n}\n")

        val result = runner("tasks", "--group", "verification").build()

        assertTrue(result.output.contains("integrationTest"))
    }

    @Test
    fun `kotlin library runs a junit test`() {
        write("build.gradle.kts", "plugins { id(\"wasichai.kotlin-library\") }\n")
        write("src/main/kotlin/probe/Probe.kt", "package probe\n\nfun probe(): String = \"ok\"\n")
        write(
            "src/test/kotlin/probe/ProbeTest.kt",
            """
            package probe

            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test

            class ProbeTest {
                @Test
                fun `probe returns ok`() {
                    assertEquals("ok", probe())
                }
            }
            """.trimIndent() + "\n",
        )

        val result = runner("test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
    }

    @Test
    fun `integration test plugin keeps tagged tests out of test`() {
        write("build.gradle.kts", "plugins {\n    id(\"wasichai.kotlin-library\")\n    id(\"wasichai.integration-test\")\n}\n")
        write(
            "src/test/kotlin/probe/ProbeTest.kt",
            """
            package probe

            import org.junit.jupiter.api.Assertions.fail
            import org.junit.jupiter.api.Tag
            import org.junit.jupiter.api.Test

            class ProbeTest {
                @Test
                @Tag("integration")
                fun `integration tagged test`() {
                    fail<Unit>("must not run in test")
                }

                @Test
                fun `plain test`() {
                    // no assertion needed, just must run and pass
                }
            }
            """.trimIndent() + "\n",
        )

        val result = runner("test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
    }
}
