package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GisMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/gis/V1__gis.sql")!!.readText()

    @Test
    fun `postgis lives in public, shared by every app in the database`() {
        assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;")
    }

    @Test
    fun `the geometry columns and checks the original had on custom_fields`() {
        assertThat(sql)
            .contains("ADD COLUMN geometry_type text,")
            .contains("ADD COLUMN srid          integer,")
            .contains("ADD COLUMN dimension     integer;")
            .contains("ADD CONSTRAINT custom_fields_geometry_has_type CHECK (")
            .contains("ADD CONSTRAINT custom_fields_geometry_type_valid CHECK (geometry_type IS NULL OR geometry_type IN (")
            .contains("ADD CONSTRAINT custom_fields_dimension_valid CHECK (dimension IS NULL OR dimension IN (2, 3));")
    }

    // P1 R4: the type list is re-added under the same name with GEOMETRY appended
    @Test
    fun `GEOMETRY joins the type check, loud if core's check is missing`() {
        assertThat(sql)
            .contains("ALTER TABLE \${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;")
            .contains("'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'GEOMETRY'")
            .doesNotContain("IF EXISTS")
            .doesNotContainIgnoringCase("sapgis")
    }

    @Test
    fun `map views are per object and per tenant, gone with either`() {
        val v2 = javaClass.getResource("/db/wasichai/gis/V2__map_views.sql")!!.readText()
        assertThat(v2)
            .contains("CREATE TABLE \${metadataSchema}.map_views (")
            .contains("REFERENCES \${metadataSchema}.custom_objects (id) ON DELETE CASCADE")
            .contains("REFERENCES \${metadataSchema}.organizations (id) ON DELETE CASCADE")
            .contains("style           jsonb,")
            .contains("UNIQUE (object_id, name)")
    }
}
