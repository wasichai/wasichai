package wasichai.it.support

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.test.WasichaiIntegrationTest

/**
 * The module matrix: an app with core and [installed] on its classpath, nothing else. Every route of
 * every other module is absent -- a 404 for anyone holding a valid token, never a 403 -- and only the
 * installed modules' migrations ran. Each slice adds its own positive tests.
 */
abstract class SliceSmokeTest : WasichaiIntegrationTest() {
    @Autowired
    protected lateinit var db: DatabaseClient

    /** The modules on this app's classpath besides core. Pages brings forms. */
    protected abstract val installed: Set<String>

    protected lateinit var admin: String
    protected lateinit var objectName: String

    @BeforeEach
    fun createFlatObject() {
        admin = bearer()
        objectName = uniqueName("slice")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to objectName, "label" to "Slice", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `every route of a module that is not installed answers 404 to an administrator`() {
        assertThat(notFoundMisses(admin)).isEmpty()
    }

    @Test
    fun `a member with no grants gets 404 from an absent module too, never 403`() {
        assertThat(notFoundMisses(memberWithoutGrants())).isEmpty()
    }

    @Test
    fun `only core's and the installed modules' migrations ran`() {
        val histories =
            runBlocking {
                db
                    .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'wasichai' AND table_name LIKE 'flyway\\_history\\_%'")
                    .map { row, _ -> row.get("table_name", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        // agent has no tables
        val expected = listOf("flyway_history_core", "flyway_history_core_seed") + (installed - "agent").map { "flyway_history_$it" }
        assertThat(histories).containsExactlyInAnyOrderElementsOf(expected)
    }

    // "<route> -> <status>" for every absent route that did not answer 404
    private fun notFoundMisses(token: String): List<String> =
        ModuleRoutes.absentFrom(installed).mapNotNull { route ->
            val status = statusOf(route, token)
            if (status == 404) null else "$route -> $status"
        }

    protected fun statusOf(
        route: String,
        token: String
    ): Int {
        val (method, uri) = ModuleRoutes.probe(route, objectName)
        return client
            .method(method)
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectBody()
            .returnResult()
            .status
            .value()
    }

    // a signed-in user holding a role with no grant at all
    protected fun memberWithoutGrants(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Nadie", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
