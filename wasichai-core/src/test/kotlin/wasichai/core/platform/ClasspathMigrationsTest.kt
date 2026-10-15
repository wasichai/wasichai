package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class ClasspathMigrationsTest {
    private val core = ClasspathMigrations(ModuleMigration.CORE.location)

    @Test
    fun `lists a location's migrations by prefix and suffix, as flyway asks for them`() {
        val versioned = core.getResources("V", arrayOf(".sql")).map { it.filename }

        assertThat(versioned).contains("V1__core.sql", "V18__field_time_zone.sql").allMatch { it.startsWith("V") }
        assertThat(core.getResources("R__", arrayOf(".sql")).map { it.filename }).containsExactly("R__audit_purge_role.sql")
        assertThat(core.getResources("V", arrayOf(".java"))).isEmpty()
    }

    @Test
    fun `reads a migration and finds it by its path below the location`() {
        val v1 = core.getResource("V1__core.sql")!!

        assertThat(v1.read().use { it.readText() }).isNotBlank()
        assertThat(v1.absolutePath).isEqualTo("db/wasichai/core/V1__core.sql")
        assertThat(core.getResource("V0__missing.sql")).isNull()
    }

    @Test
    fun `an image gets the provider, the jvm keeps flyway's own scan`() {
        val migrations = WasichaiMigrations(WasichaiDatabaseProperties(), WasichaiSchemas("m", "d"), listOf(ModuleMigration.CORE))

        assertThat(migrations.flyway(ModuleMigration.CORE, native = true).configuration.resourceProvider)
            .isInstanceOf(ClasspathMigrations::class.java)
        assertThat(migrations.flyway(ModuleMigration.CORE, native = false).configuration.resourceProvider).isNull()
    }

    @Test
    fun `every module's migrations go in the image`() {
        val hints = RuntimeHints()
        WasichaiMigrationsRuntimeHints().registerHints(hints, javaClass.classLoader)

        assertThat(RuntimeHintsPredicates.resource().forResource("db/wasichai/core/V1__core.sql")).accepts(hints)
        assertThat(RuntimeHintsPredicates.resource().forResource("db/wasichai/gis/V1__gis.sql")).accepts(hints)
    }
}
