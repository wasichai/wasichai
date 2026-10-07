package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationsMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/notifications/V1__notifications.sql")!!.readText()

    @Test
    fun `the five tables, all in the metadata schema`() {
        listOf("notifications", "notification_targets", "notification_receipts", "notification_rules", "notification_source_runs").forEach {
            assertThat(sql).contains("CREATE TABLE \${metadataSchema}.$it (")
        }
    }

    @Test
    fun `a notification has a kind, a window and one row per source key`() {
        assertThat(sql)
            .contains("CONSTRAINT notifications_kind_valid CHECK (kind IN ('INFO', 'WARNING', 'ACTION'))")
            .contains("CONSTRAINT notifications_window_valid CHECK (expires_at IS NULL OR expires_at > publish_at)")
            .contains("CONSTRAINT notifications_source_key_unique UNIQUE (organization_id, source, source_key)")
            .contains("link_object_id  uuid REFERENCES \${metadataSchema}.custom_objects (id) ON DELETE SET NULL,")
            .contains("CREATE INDEX notifications_open_idx ON \${metadataSchema}.notifications (organization_id, publish_at DESC)")
    }

    @Test
    fun `a target names exactly what its type needs`() {
        assertThat(sql)
            .contains("CONSTRAINT notification_targets_type_valid CHECK (type IN ('ALL', 'USER', 'ROLE', 'UNIT'))")
            .contains("((type = 'USER') = (user_id IS NOT NULL))")
            .contains("AND ((type = 'ROLE') = (role_name IS NOT NULL))")
            .contains("AND ((type = 'UNIT') = (unit_id IS NOT NULL))")
            .contains("unit_id         uuid REFERENCES \${metadataSchema}.org_units (id) ON DELETE CASCADE,")
    }

    @Test
    fun `one receipt per person and notification`() {
        assertThat(sql)
            .contains("PRIMARY KEY (notification_id, user_id)")
            .contains("CREATE INDEX notification_receipts_user_idx ON \${metadataSchema}.notification_receipts (user_id);")
    }

    @Test
    fun `rule names are identifiers, unique per organization`() {
        assertThat(sql)
            .contains("CONSTRAINT notification_rules_name_unique UNIQUE (organization_id, name)")
            .contains("CONSTRAINT notification_rules_name_valid CHECK (name ~ '^[a-z][a-z0-9_]{1,48}\$')")
            .contains("source      text PRIMARY KEY,")
    }

    @Test
    fun `only the metadata schema placeholder, never a literal schema`() {
        assertThat(sql)
            .doesNotContain("\${dataSchema}")
            .doesNotContainIgnoringCase("sapgis")
            .doesNotContain("wasichai.")
            .doesNotContain("app_data")
        // every table reference goes through the placeholder
        assertThat(Regex("""(?:TABLE|REFERENCES|ON)\s+([\w${'$'}{}]+)\.""").findAll(sql).map { it.groupValues[1] }.toSet())
            .containsExactly("\${metadataSchema}")
    }
}
