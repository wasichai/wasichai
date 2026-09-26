package wasichai.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/workflow/V1__workflow.sql")!!.readText()

    @Test
    fun `one workflow per object, names unique per organization`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.workflows (")
            .contains("CONSTRAINT workflows_one_per_object UNIQUE (object_id)")
            .contains("CONSTRAINT workflows_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CREATE INDEX workflows_org_idx ON \${metadataSchema}.workflows (organization_id);")
            .doesNotContainIgnoringCase("sapgis")
    }
}
