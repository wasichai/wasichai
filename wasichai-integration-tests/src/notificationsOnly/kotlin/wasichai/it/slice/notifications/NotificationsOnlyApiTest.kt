package wasichai.it.slice.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import wasichai.it.support.SliceSmokeTest

// core + notifications on plain postgres: it boots, it migrates, core still answers, and the admin, the inbox
// and the rules work without pages. the inherited checks cover every other module's 404s.
// the loop is off, as in the full app: nothing here needs it
@TestPropertySource(properties = ["wasichai.notifications.tick=0s"])
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

    // a RECORD link's tab is a format check, never a page lookup: pages is absent and it is still accepted
    @Test
    fun `an admin publishes with a record link and a tab, and a member reads it`() {
        val recordId = json("POST", "/api/objects/$objectName/records", admin, mapOf("attributes" to mapOf("codigo" to "N-2")), 201)["id"] as String
        val created =
            json(
                "POST",
                "/api/notifications",
                admin,
                mapOf(
                    "kind" to "WARNING",
                    "title" to "Revisar el predio",
                    "link" to mapOf("type" to "RECORD", "object" to objectName, "recordId" to recordId, "tab" to "vigencia"),
                    "audience" to listOf(mapOf("type" to "ALL"))
                ),
                201
            )
        val id = created["id"] as String
        assertThat(created["link"]).isEqualTo(mapOf("type" to "RECORD", "object" to objectName, "recordId" to recordId, "tab" to "VIGENCIA"))
        assertThat(json("GET", "/api/notifications/$id", admin, null, 200)["title"]).isEqualTo("Revisar el predio")
        assertThat(contentIds(json("GET", "/api/notifications?source=manual&size=100", admin, null, 200))).contains(id)

        val member = memberWithoutGrants()
        val inbox = json("GET", "/api/auth/me/notifications?size=100", member, null, 200)["content"] as List<*>
        val item = inbox.map { it as Map<*, *> }.single { it["id"] == id }
        assertThat(item["kind"]).isEqualTo("WARNING")
        assertThat(item["read"]).isEqualTo(false)
        // no READ on the object: the record link is dropped for this reader
        assertThat(item["link"]).isNull()
        val kinds = json("GET", "/api/auth/me/notifications/summary", member, null, 200)["kinds"] as Map<*, *>
        assertThat(((kinds["WARNING"] as Map<*, *>)["unread"] as Number).toInt()).isGreaterThanOrEqualTo(1)
        client
            .post()
            .uri("/api/auth/me/notifications/$id/read")
            .header(HttpHeaders.AUTHORIZATION, member)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `the rules answer with none`() {
        listOf("/api/notification-rules", "/api/objects/$objectName/notification-rules").forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBodyList(Any::class.java)
                .hasSize(0)
        }
    }

    private fun contentIds(page: Map<String, Any?>): List<Any?> = (page["content"] as List<*>).map { (it as Map<*, *>)["id"] }

    private fun json(
        method: String,
        uri: String,
        token: String,
        body: Any?,
        status: Int
    ): Map<String, Any?> {
        val spec =
            client
                .method(HttpMethod.valueOf(method))
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
        val request = if (body == null) spec else spec.bodyValue(body)
        return request
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(object : ParameterizedTypeReference<Map<String, Any?>>() {})
            .returnResult()
            .responseBody!!
    }
}
