package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// the rebaseline must stay core-only and schema-agnostic. the real schema check is the IT suite.
class CoreMigrationSqlTest {
    private fun sql(path: String): String = javaClass.getResource(path)!!.readText()

    private val core = sql("/db/wasichai/core/V1__core.sql")
    private val seed = sql("/db/wasichai/core-seed/V1__seed_dev.sql")

    @Test
    fun `every table is created in the placeholder schema, never a literal one`() {
        assertThat(core).doesNotContainIgnoringCase("sapgis")
        Regex("CREATE TABLE ([^ ]+)").findAll(core).forEach { match ->
            assertThat(match.groupValues[1]).startsWith("\${metadataSchema}.")
        }
        assertThat(core).contains("CREATE SCHEMA IF NOT EXISTS \${dataSchema};")
    }

    @Test
    fun `every extension is created in public, never the schema flyway's search_path would pick`() {
        val extensions = Regex("CREATE EXTENSION IF NOT EXISTS (\\w+)[^;]*;").findAll(core).toList()

        assertThat(extensions).isNotEmpty()
        extensions.forEach { match -> assertThat(match.value).contains("WITH SCHEMA public") }
    }

    @Test
    fun `core creates exactly the core tables and nothing a module owns`() {
        val tables = Regex("CREATE TABLE \\$\\{metadataSchema}\\.([a-z_]+)").findAll(core).map { it.groupValues[1] }.toList()

        assertThat(tables).containsExactly(
            "organizations",
            "users",
            "roles",
            "user_roles",
            "custom_objects",
            "custom_fields",
            "relationships",
            "permissions",
            "field_permissions",
            "audit_log"
        )
        assertThat(core).doesNotContainIgnoringCase("postgis")
        assertThat(core).doesNotContain("GEOMETRY", "geometry_type", "'ISSUE'")
    }

    // ADR-050: additive, nullable, so rows written before it read back without either
    @Test
    fun `audit rows gain a correlation id and a source, both nullable, the id indexed per tenant`() {
        val origin = sql("/db/wasichai/core/V10__audit_origin.sql")

        assertThat(origin)
            .contains("ADD COLUMN correlation_id text,")
            .contains("ADD COLUMN source text;")
            .contains("CHECK (correlation_id ~ '^[A-Za-z0-9._-]{1,64}\$')")
            .contains("CHECK (source ~ '^[A-Za-z0-9._:-]{1,64}\$')")
            .contains("CREATE INDEX audit_log_correlation_idx ON \${metadataSchema}.audit_log (organization_id, correlation_id);")
            .doesNotContain("NOT NULL")
    }

    // ADR-052: one index for a user's rows of a period, in list order. nothing else changes
    @Test
    fun `the audit log gains a per-tenant user and time index, and only that`() {
        val index = sql("/db/wasichai/core/V12__audit_user_index.sql")

        assertThat(index).contains(
            "CREATE INDEX audit_log_user_time_idx ON \${metadataSchema}.audit_log (organization_id, user_id, occurred_at DESC);"
        )
        assertThat(index.lines().filterNot { it.startsWith("--") || it.isBlank() }).hasSize(1)
    }

    @Test
    fun `the dev seed names the wasichai admin and every admin action`() {
        assertThat(seed).contains("admin@wasichai.local").contains("MANAGE_ORGANIZATION").doesNotContainIgnoringCase("sapgis")
    }

    @Test
    fun `the seeded hash really is the password admin`() {
        val hash = Regex("'(\\$2[aby]\\$10\\$[./A-Za-z0-9]{53})'").find(seed)!!.groupValues[1]

        assertThat(hash).startsWith("\$2a\$")
        assertThat(
            org.springframework.security.crypto.bcrypt
                .BCryptPasswordEncoder()
                .matches("admin", hash)
        ).isTrue()
    }
}
