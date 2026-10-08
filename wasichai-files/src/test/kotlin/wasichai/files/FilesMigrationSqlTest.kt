package wasichai.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FilesMigrationSqlTest {
    private val v1 = javaClass.getResource("/db/wasichai/files/V1__files.sql")!!.readText()
    private val types = javaClass.getResource("/db/wasichai/files/afterMigrate__file_types.sql")!!.readText()

    @Test
    fun `stored_files keeps no foreign key to what the cleanup must outlive`() {
        val table = v1.substringAfter("CREATE TABLE \${metadataSchema}.stored_files (").substringBefore(");")
        assertThat(table).contains("organization_id uuid NOT NULL,").contains("object_id       uuid NOT NULL,")
        // only created_by points elsewhere: a deleted tenant's or object's rows stay for the cleanup
        assertThat(Regex("REFERENCES").findAll(table).count()).isEqualTo(1)
        assertThat(table).contains("created_by      uuid REFERENCES \${metadataSchema}.users (id) ON DELETE SET NULL")
    }

    @Test
    fun `the field settings are custom_fields columns, and the descriptor a function of the row's organization`() {
        assertThat(v1)
            .contains("ADD COLUMN file_max_bytes     bigint,")
            .contains("ADD COLUMN file_content_types text;")
            .contains("CREATE FUNCTION \${metadataSchema}.stored_file_descriptor(file_id uuid, org uuid) RETURNS text")
            .contains("WHERE f.id = file_id AND f.organization_id = org")
    }

    // R4, composable: appended to the list as it stands, loud if core's check is missing
    @Test
    fun `FILE and IMAGE join whatever type list there is, on every start`() {
        assertThat(v1).doesNotContain("DROP CONSTRAINT")
        assertThat(types)
            .contains("c.conname = 'custom_fields_type_valid'")
            .contains("RAISE EXCEPTION 'custom_fields_type_valid is missing'")
            .contains("known := array_append(known, 'FILE')")
            .contains("known := array_append(known, 'IMAGE')")
            .doesNotContain("'GEOMETRY'")
    }
}
