package wasichai.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/documents/V1__documents.sql")!!.readText()

    @Test
    fun `types, counters and issued documents`() {
        assertThat(sql)
            .contains("CREATE TABLE \${metadataSchema}.document_types (")
            .contains("CONSTRAINT document_types_prefix_unique_per_org UNIQUE (organization_id, prefix)")
            .contains("CREATE TABLE \${metadataSchema}.document_counters (")
            .contains("CREATE TABLE \${metadataSchema}.documents (")
            .contains("document_type_id uuid NOT NULL REFERENCES \${metadataSchema}.document_types (id) ON DELETE RESTRICT")
            .contains("CREATE UNIQUE INDEX documents_one_valid_per_record")
            .doesNotContainIgnoringCase("sapgis")
    }

    // core created audit_log.document_id without its FK and without ISSUE (P1 R10): this module owns both
    @Test
    fun `the audit log learns ISSUE and points at the document`() {
        assertThat(sql)
            .contains("ALTER TABLE \${metadataSchema}.audit_log DROP CONSTRAINT audit_log_operation_valid;")
            .contains("CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'ISSUE'))")
            .contains(
                "ADD CONSTRAINT audit_log_document_id_fkey FOREIGN KEY (document_id) REFERENCES \${metadataSchema}.documents (id) ON DELETE SET NULL"
            )
        // loud failure if core's constraint or column is not what we expect
        assertThat(sql).doesNotContain("IF EXISTS").doesNotContain("ADD COLUMN")
    }
}
