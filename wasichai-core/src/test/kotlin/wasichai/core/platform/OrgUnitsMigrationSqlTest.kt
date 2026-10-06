package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// ADR-045: the unit tree and its members. the real schema check is the IT suite.
class OrgUnitsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/core/V9__org_units.sql")!!.readText()

    @Test
    fun `creates the unit tree and the membership, nothing else`() {
        val tables = Regex("CREATE TABLE \\$\\{metadataSchema}\\.([a-z_]+)").findAll(sql).map { it.groupValues[1] }.toList()

        assertThat(tables).containsExactly("org_units", "user_org_units")
        assertThat(sql).contains("CREATE INDEX org_units_parent_idx ON \${metadataSchema}.org_units (parent_id)")
        assertThat(sql).contains("CREATE INDEX user_org_units_unit_idx ON \${metadataSchema}.user_org_units (unit_id)")
    }

    @Test
    fun `every name is in the placeholder schema, never a literal one`() {
        Regex("(?:CREATE TABLE|REFERENCES|ON) ([a-z_\${}]+\\.[a-z_]+)").findAll(sql).forEach { match ->
            assertThat(match.groupValues[1]).startsWith("\${metadataSchema}.")
        }
        assertThat(sql).doesNotContain("\${dataSchema}").doesNotContainIgnoringCase("sapgis")
    }

    @Test
    fun `codes, labels and the parent are checked`() {
        assertThat(sql)
            .contains("CONSTRAINT org_units_code_unique UNIQUE (organization_id, code)")
            .contains("CONSTRAINT org_units_org_id_unique UNIQUE (organization_id, id)")
            .contains("CONSTRAINT org_units_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,48}\$')")
            .contains("CONSTRAINT org_units_label_valid CHECK (length(label) BETWEEN 1 AND 120)")
            .contains("CONSTRAINT org_units_not_own_parent CHECK (parent_id <> id)")
    }

    @Test
    fun `the parent key is composite, so a parent is always of the same tenant`() {
        val parent = Regex("CONSTRAINT org_units_parent_fkey FOREIGN KEY \\(organization_id, parent_id\\)\\s+REFERENCES (\\S+ \\([^)]*\\))").find(sql)

        assertThat(parent).isNotNull()
        assertThat(parent!!.groupValues[1]).isEqualTo("\${metadataSchema}.org_units (organization_id, id)")
        // no action: deleting the organization takes the whole tree; the admin route refuses a unit with children
        assertThat(sql.substring(parent.range.last + 1).substringBefore(";")).doesNotContain("ON DELETE")
    }

    @Test
    fun `membership goes with the user and with the unit`() {
        assertThat(sql)
            .contains("user_id uuid NOT NULL REFERENCES \${metadataSchema}.users (id) ON DELETE CASCADE")
            .contains("unit_id uuid NOT NULL REFERENCES \${metadataSchema}.org_units (id) ON DELETE CASCADE")
            .contains("PRIMARY KEY (user_id, unit_id)")
    }
}
