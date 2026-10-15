package wasichai.core.platform

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

// every module keeps its migrations under db/wasichai/<name> (ModuleMigration): one pattern puts
// them all in a native image, modules core does not know included (ADR-068)
class WasichaiMigrationsRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?
    ) {
        hints.resources().registerPattern("$LOCATION/**")
    }

    companion object {
        const val LOCATION = "db/wasichai"
    }
}
