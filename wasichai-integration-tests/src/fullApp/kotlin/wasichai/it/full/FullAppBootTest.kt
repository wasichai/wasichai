package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import wasichai.it.support.SliceSmokeTest
import wasichai.test.WasichaiTestDatabase
import java.sql.DriverManager

// the full app on postgis: every module installed (so the matrix has nothing absent and every
// migration ran), every module answers, and this jvm holds the suite lock. same properties as
// FullAppIntegrationTest: this one reuses the slice checks, so it cannot extend that class.
@FullAppProperties
class FullAppBootTest : SliceSmokeTest() {
    override val installed = setOf("views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent", "notifications")

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `postgis and pgcrypto live in public`() {
        val schemas =
            runBlocking {
                db
                    .sql(
                        "SELECT e.extname, n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace " +
                            "WHERE e.extname IN ('postgis', 'pgcrypto')"
                    ).map { row, _ -> row.get("extname", String::class.java)!! to row.get("nspname", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
                    .toMap()
            }
        assertThat(schemas).containsEntry("postgis", "public").containsEntry("pgcrypto", "public")
    }

    @Test
    fun `every module answers its list route`() {
        listOf(
            "/api/objects/$objectName/views",
            "/api/objects/$objectName/forms",
            "/api/pages",
            "/api/metadata/page-templates",
            "/api/objects/$objectName/automations",
            "/api/automation-runs",
            "/api/objects/$objectName/document-types",
            "/api/gis/layers",
            "/api/gis/services",
            "/api/agent/status"
        ).forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
        }
        // workflow has no list route: its system column is how it shows
        client
            .get()
            .uri("/api/metadata/system-fields")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == 'workflow_state')].scope")
            .isEqualTo("WORKFLOW")
    }

    @Test
    fun `the suite lock is held for as long as this jvm runs`() {
        assumeTrue(!System.getenv("WASICHAI_TEST_DB_HOST").isNullOrBlank(), "testcontainers: every jvm has a database of its own, no lock")
        val url =
            "jdbc:postgresql://${environment.getProperty("wasichai.database.host")}:${environment.getProperty("wasichai.database.port")}/" +
                environment.getProperty("wasichai.database.name")
        DriverManager
            .getConnection(url, environment.getProperty("wasichai.database.username"), environment.getProperty("wasichai.database.password"))
            .use { other ->
                other.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('${WasichaiTestDatabase.SUITE_LOCK}'))").use { rows ->
                        rows.next()
                        assertThat(rows.getBoolean(1)).describedAs("a second session took the suite lock").isFalse()
                    }
                }
            }
    }
}
