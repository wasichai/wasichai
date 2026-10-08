package wasichai.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

// the spec's module graph, checked on the sources: pages may use forms; every other link between
// modules is an optional adapter, allowed only in the files that exist for it (M2).
class ModuleBoundariesTest {
    private val modules = listOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent", "notifications", "files")
    private val reference = Regex("""\bwasichai\.(views|forms|pages|workflow|automation|documents|gis|agent|notifications|files)\.""")

    // module -> (other module -> files allowed to name it)
    private val allowed: Map<String, Map<String, Set<String>>> =
        mapOf(
            "pages" to mapOf("forms" to setOf("*")),
            "documents" to mapOf("automation" to setOf("DocumentIssuerAdapter.kt", "WasichaiDocumentsAutomationAutoConfiguration.kt")),
            "notifications" to mapOf("automation" to setOf("AutomationNotifierAdapter.kt", "WasichaiNotificationsAutomationAutoConfiguration.kt")),
            "agent" to mapOf("workflow" to setOf("WorkflowRecordTransitions.kt", "WasichaiAgentWorkflowAutoConfiguration.kt")),
            "gis" to mapOf("pages" to setOf("MapPageComponent.kt", "WasichaiGisPagesAutoConfiguration.kt")),
            "workflow" to mapOf("pages" to setOf("WorkflowPageComponent.kt", "WasichaiWorkflowPagesAutoConfiguration.kt"))
        )

    @Test
    fun `no module reaches into another outside its declared adapters`() {
        val offenders =
            modules.flatMap { module ->
                File("../wasichai-$module/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                    reference.findAll(file.readText()).map { it.groupValues[1] }.filter { it != module }.distinct().mapNotNull { other ->
                        val files = allowed[module]?.get(other).orEmpty()
                        if ("*" in files || file.name in files) null else "wasichai-$module/${file.name} -> wasichai.$other"
                    }
                }
            }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `core never names a module`() {
        val core = File("../wasichai-core/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        assertThat(core.filter { reference.containsMatchIn(it.readText()) }.map { it.name }.toList()).isEmpty()
    }
}
