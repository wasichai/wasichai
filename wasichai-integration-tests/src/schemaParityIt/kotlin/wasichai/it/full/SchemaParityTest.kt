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

    // differences accepted on purpose: "<catalog line>" to "<reason, ADR or ruling>". empty until the
    // controller approves one.
    private val knownDeviations: Map<String, String> = emptyMap()

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
