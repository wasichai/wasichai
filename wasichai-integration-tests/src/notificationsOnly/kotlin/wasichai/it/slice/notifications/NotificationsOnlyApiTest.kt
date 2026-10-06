package wasichai.it.slice.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest

// core + notifications on plain postgres. the module's routes come in later tasks: here, it boots,
// it migrates, and core still answers. the inherited checks cover every other module's 404s.
class NotificationsOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("notifications")

    @Test
    fun `the module's tables exist, next to core's units they point at`() {
        val tables =
            runBlocking {
                db
                    .sql(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'wasichai' " +
                            "AND (table_name LIKE 'notification%' OR table_name LIKE '%org\\_units')"
                    ).map { row, _ -> row.get("table_name", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        assertThat(tables).containsExactlyInAnyOrder(
            "notifications",
            "notification_targets",
            "notification_receipts",
            "notification_rules",
            "notification_source_runs",
            "org_units",
            "user_org_units"
        )
    }

    @Test
    fun `core answers as without the module`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.email")
            .isEqualTo(ADMIN_EMAIL)
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "N-1")))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `notifications needs no postgis`() {
        val postgis =
            runBlocking {
                db
                    .sql("SELECT count(*) AS n FROM pg_extension WHERE extname = 'postgis'")
                    .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                    .one()
                    .awaitFirstOrNull()
            }
        assertThat(postgis).isEqualTo(0L)
    }
}
