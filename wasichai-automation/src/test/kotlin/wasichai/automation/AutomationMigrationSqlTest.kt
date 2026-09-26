package wasichai.automation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AutomationMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/automation/V1__automation.sql")!!.readText()

    @Test
    fun `automations and their runs, with the drain's partial index`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.automations (")
            .contains("CONSTRAINT automations_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CREATE TABLE \${metadataSchema}.automation_runs (")
            .contains("user_id uuid REFERENCES \${metadataSchema}.users (id) ON DELETE SET NULL")
            .contains("CREATE INDEX automation_runs_pending_idx ON \${metadataSchema}.automation_runs (created_at) WHERE status = 'PENDING';")
            .doesNotContainIgnoringCase("sapgis")
    }
}
