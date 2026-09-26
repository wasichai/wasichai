package wasichai.forms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FormsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/forms/V1__forms.sql")!!.readText()

    @Test
    fun `forms are tenant scoped and unique by name per object`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.forms (")
            .contains("organization_id uuid NOT NULL REFERENCES \${metadataSchema}.organizations (id) ON DELETE CASCADE")
            .contains("CONSTRAINT forms_name_unique_per_object UNIQUE (object_id, name)")
            .doesNotContainIgnoringCase("sapgis")
            .doesNotContain("app_data")
    }
}
