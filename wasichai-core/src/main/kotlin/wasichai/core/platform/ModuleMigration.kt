package wasichai.core.platform

// one module's flyway scripts. each module keeps its own history table, so a module can join an
// app later without touching anyone else's history. ADR-0026.
data class ModuleMigration(
    val name: String,
    val location: String,
    val order: Int
) {
    init {
        require(NAME.matches(name)) { "module migration name '$name' must match ${NAME.pattern}" }
    }

    val historyTable: String get() = "flyway_history_$name"

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]{0,40}$")

        const val CORE_ORDER = 0
        const val CORE_SEED_ORDER = 10

        // modules start here and space themselves out in dependency order. equal orders fall back
        // to name order (WasichaiMigrations.plan), which a dependency cannot rely on: a module whose
        // migration depends on another module's tables must use a strictly higher order, e.g.
        // MODULE_ORDER + 1, not just a different name.
        const val MODULE_ORDER = 100

        val CORE = ModuleMigration("core", "classpath:db/wasichai/core", CORE_ORDER)

        // opt-in: wasichai.seed.dev=true
        val CORE_SEED = ModuleMigration("core_seed", "classpath:db/wasichai/core-seed", CORE_SEED_ORDER)
    }
}
