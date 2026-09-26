package wasichai.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PagesMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/pages/V1__pages.sql")!!.readText()

    @Test
    fun `pages are tenant scoped, one per object and kind, on a template`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.pages (")
            .contains("object_id       uuid REFERENCES \${metadataSchema}.custom_objects (id) ON DELETE CASCADE,")
            .contains("template        text NOT NULL DEFAULT 'one-region',")
            .contains("CONSTRAINT pages_name_unique_per_org UNIQUE (organization_id, name)")
            .contains("CONSTRAINT pages_kind_valid CHECK (kind IN ('RECORD_DETAIL'))")
            .contains("CONSTRAINT pages_kind_unique_per_object UNIQUE (object_id, kind)")
            .doesNotContain("layout")
            .doesNotContainIgnoringCase("sapgis")
    }
}
