package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.sql.DriverManager

// the metadata schema wasichai's migrations build is the one the original's V1-V14 built, fact by
// fact (catalog.sql). the expected side comes from the original's own migrations
// (generate-expected.sh). a line on one side only is a schema difference.
class SchemaParityTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    // differences accepted on purpose: "<catalog line>" to "<reason, ADR or ruling>". today only the
    // user_preferences table (ADR-031 D18), one entry per catalog line. a new one needs an ADR-031 entry.
    private val knownDeviations: Map<String, String> =
        mapOf(
            "column user_preferences.locale #3 text" to "ADR-031 D18: per-user theme and locale",
            "column user_preferences.theme #2 text NOT NULL DEFAULT 'system'::text" to
                "ADR-031 D18: per-user theme and locale",
            "column user_preferences.updated_at #4 timestamp with time zone NOT NULL DEFAULT now()" to
                "ADR-031 D18: per-user theme and locale",
            "column user_preferences.user_id #1 uuid NOT NULL" to "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_pkey PRIMARY KEY (user_id)" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_theme_not_null NOT NULL theme" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_updated_at_not_null NOT NULL updated_at" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES META.users(id) ON DELETE CASCADE" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_user_id_not_null NOT NULL user_id" to
                "ADR-031 D18: per-user theme and locale",
            "index user_preferences.user_preferences_pkey CREATE UNIQUE INDEX user_preferences_pkey ON META.user_preferences USING btree (user_id)" to
                "ADR-031 D18: per-user theme and locale",
            "table user_preferences" to "ADR-031 D18: per-user theme and locale"
        )

    @Test
    fun `the fixture is the original's whole schema`() {
        assertThat(expected().filter { it.startsWith("table ") }).contains(
            "table audit_log",
            "table automation_runs",
            "table automations",
            "table custom_fields",
            "table custom_objects",
            "table document_counters",
            "table document_types",
            "table documents",
            "table field_permissions",
            "table forms",
            "table organizations",
            "table pages",
            "table permissions",
            "table relationships",
            "table roles",
            "table user_roles",
            "table users",
            "table views",
            "table workflows"
        )
    }

    @Test
    fun `the metadata schema is the original's final schema`() {
        // the context is up, so every module migrated
        val actual = catalogOf("wasichai")
        val expected = expected()
        assertThat((expected - actual - knownDeviations.keys).sorted()).describedAs("in the original, not in wasichai").isEmpty()
        assertThat((actual - expected - knownDeviations.keys).sorted()).describedAs("in wasichai, not in the original").isEmpty()
    }

    private fun expected(): Set<String> = resource("legacy-final.catalog").lines().filter { it.isNotBlank() }.toSet()

    private fun resource(name: String): String = javaClass.getResource("/schema-parity/$name")!!.readText()

    private fun catalogOf(schema: String): Set<String> {
        require(Regex("[a-z_][a-z0-9_]*").matches(schema)) { "not a schema name: $schema" }
        val sql = resource("catalog.sql").replace("__SCHEMA__", schema)
        val url =
            "jdbc:postgresql://${environment.getProperty("wasichai.database.host")}:${environment.getProperty("wasichai.database.port")}/" +
                environment.getProperty("wasichai.database.name")
        return DriverManager
            .getConnection(url, environment.getProperty("wasichai.database.username"), environment.getProperty("wasichai.database.password"))
            .use { connection ->
                connection.createStatement().use { statement ->
                    // the connection's own search_path would start with "$user" = wasichai and hide the prefix
                    statement.execute("SET search_path TO public")
                    statement.executeQuery(sql).use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
    }
}
