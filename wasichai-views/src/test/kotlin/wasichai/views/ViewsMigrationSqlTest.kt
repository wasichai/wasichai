package wasichai.views

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ViewsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/views/V1__views.sql")!!.readText()

    @Test
    fun `views are tenant scoped with at most one default per object`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.views (")
            .contains("organization_id uuid NOT NULL REFERENCES \${metadataSchema}.organizations (id) ON DELETE CASCADE")
            .contains("is_default      boolean NOT NULL DEFAULT false")
            .contains("CONSTRAINT views_name_unique_per_object UNIQUE (object_id, name)")
            .contains("CREATE UNIQUE INDEX views_one_default_per_object ON \${metadataSchema}.views (object_id) WHERE is_default;")
            .doesNotContainIgnoringCase("sapgis")
            .doesNotContain("app_data")
    }
}
