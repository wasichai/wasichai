package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// ADR-060's migration: one delivery per notification, person and channel; preferences per person and channel
class DeliveriesMigrationSqlTest {
    private val sql = javaClass.getResource("/db/wasichai/notifications/V2__deliveries.sql")!!.readText()

    @Test
    fun `the two tables, in the metadata schema`() {
        listOf("notification_deliveries", "notification_preferences").forEach {
            assertThat(sql).contains("CREATE TABLE \${metadataSchema}.$it (")
        }
    }

    @Test
    fun `a delivery has a status, one row per news and person, and goes with its notification`() {
        assertThat(sql)
            .contains("CONSTRAINT notification_deliveries_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))")
            .contains("CONSTRAINT notification_deliveries_unique UNIQUE (notification_id, user_id, channel)")
            .contains("notification_id uuid NOT NULL REFERENCES \${metadataSchema}.notifications (id) ON DELETE CASCADE,")
            .contains("user_id         uuid NOT NULL REFERENCES \${metadataSchema}.users (id) ON DELETE CASCADE,")
            .contains("WHERE status = 'PENDING';")
    }

    @Test
    fun `a preference holds known kinds only`() {
        assertThat(sql)
            .contains("PRIMARY KEY (user_id, channel)")
            .contains("CHECK (kinds <@ ARRAY['INFO', 'WARNING', 'ACTION']::text[])")
    }

    @Test
    fun `only the metadata schema placeholder, never a literal schema`() {
        assertThat(sql).doesNotContain("\${dataSchema}").doesNotContain("wasichai.").doesNotContain("app_data")
        assertThat(Regex("""(?:TABLE|REFERENCES|ON)\s+([\w${'$'}{}]+)\.""").findAll(sql).map { it.groupValues[1] }.toSet())
            .containsExactly("\${metadataSchema}")
    }
}
