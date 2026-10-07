package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.Audience
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.Notifications
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

// /api/notifications: manual notifications by hand (spec B, REST, Administration)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsAdminApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var notifications: Notifications

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }
    private val json = JsonMapper.builder().build()

    @Test
    fun `an admin creates a manual notification`() {
        val created =
            call(
                "POST",
                "/api/notifications",
                tenant.admin,
                mapOf(
                    "kind" to "info",
                    "title" to "  Ordenanza 006-2026  ",
                    "body" to "Nueva tasa de arbitrios",
                    "link" to mapOf("type" to "URL", "url" to "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf"),
                    "audience" to listOf(mapOf("type" to "ALL"))
                ),
                HttpStatus.CREATED
            )

        assertThat(created.get("kind").asString()).isEqualTo("INFO")
        assertThat(created.get("title").asString()).isEqualTo("Ordenanza 006-2026")
        assertThat(created.get("source").asString()).isEqualTo("manual")
        assertThat(created.get("key").isNull).isTrue()
        assertThat(created.get("link").get("url").asString()).isEqualTo("https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf")
        assertThat(created.get("audience").toList().map { it.get("type").asString() }).containsExactly("ALL")
        assertThat(created.get("readCount").asLong()).isZero()
        assertThat(created.get("resolvedAt").isNull).isTrue()
        val id = UUID.fromString(created.get("id").asString())
        assertThat(column(id, "created_by", UUID::class.java)).isEqualTo(tenant.adminId)
    }

    @Test
    fun `the admin view describes the audience, a user with its email`() {
        val role = tenant.createRole()
        val unit = tenant.createUnit()
        val ana = tenant.createUser()
        val beto = tenant.createUser()
        val obj = tenant.createObject()
        val record = tenant.createRecord(obj, mapOf("codigo" to "A-1"))
        val id =
            create(
                audience =
                    listOf(
                        mapOf("type" to "USER", "value" to ana.id.toString()),
                        mapOf("type" to "EMAIL", "value" to " " + beto.email.uppercase() + " "),
                        mapOf("type" to "ROLE", "value" to role.lowercase()),
                        mapOf("type" to "UNIT", "value" to unit.code.lowercase())
                    ),
                link = mapOf("type" to "RECORD", "object" to obj, "recordId" to record.toString(), "tab" to "details")
            )

        val view = call("GET", "/api/notifications/$id", tenant.admin, null, HttpStatus.OK)

        val audience = view.get("audience").toList().map { Triple(it.get("type").asString(), it.get("value").asString(), it.get("email")?.asString()) }
        assertThat(audience).containsExactlyInAnyOrder(
            Triple("USER", ana.id.toString(), ana.email),
            Triple("USER", beto.id.toString(), beto.email),
            Triple("ROLE", role, null),
            Triple("UNIT", unit.code, null)
        )
        assertThat(view.get("link").get("type").asString()).isEqualTo("RECORD")
        assertThat(view.get("link").get("object").asString()).isEqualTo(obj)
        assertThat(view.get("link").get("tab").asString()).isEqualTo("DETAILS")
    }

    @Test
    fun `the list filters by source, kind, status and unit`() {
        val source = "it.list." + UUID.randomUUID().toString().take(8)
        val now = Instant.now()
        val open = publish(source, "open", NotificationKind.WARNING)
        val scheduled = publish(source, "scheduled", NotificationKind.INFO, publishAt = now.plus(2, ChronoUnit.DAYS))
        val ended = publish(source, "ended", NotificationKind.INFO)
        runBlocking { notifications.resolve(tenant.organizationId, source, "ended") }

        assertThat(ids("source=$source")).containsExactlyInAnyOrder(open, scheduled, ended)
        assertThat(ids("source=$source&kind=warning")).containsExactly(open)
        assertThat(ids("source=$source&status=OPEN")).containsExactly(open)
        assertThat(ids("source=$source&status=scheduled")).containsExactly(scheduled)
        assertThat(ids("source=$source&status=Ended")).containsExactly(ended)

        val unit = tenant.createUnit()
        val forUnit = create(audience = listOf(mapOf("type" to "UNIT", "value" to unit.code)))
        create()
        assertThat(ids("unit=${unit.code}")).containsExactly(forUnit)
        assertThat(ids("source=manual&unit=${unit.code.lowercase()}")).containsExactly(forUnit)

        // paging: core's rules
        val page = call("GET", "/api/notifications?source=$source&size=2&page=1", tenant.admin, null, HttpStatus.OK)
        assertThat(page.get("size").asInt()).isEqualTo(2)
        assertThat(page.get("page").asInt()).isEqualTo(1)
        assertThat(page.get("totalElements").asLong()).isEqualTo(3)
        assertThat(page.get("content").size()).isEqualTo(1)
    }

    @Test
    fun `a bad filter is a 400`() {
        assertThat(errorFields(call("GET", "/api/notifications?status=later", tenant.admin, null, HttpStatus.BAD_REQUEST))).containsExactly("status")
        assertThat(errorFields(call("GET", "/api/notifications?kind=NEWS", tenant.admin, null, HttpStatus.BAD_REQUEST))).containsExactly("kind")
        assertThat(errorFields(call("GET", "/api/notifications?unit=NO_SUCH_UNIT", tenant.admin, null, HttpStatus.BAD_REQUEST))).containsExactly("unit")
    }

    @Test
    fun `readCount counts the people who read it`() {
        val id = create()
        val ana = tenant.createUser()
        val beto = tenant.createUser()
        receipt(id, ana.id, read = true)
        // dismissed without reading: not a read
        receipt(id, beto.id, read = false)

        assertThat(call("GET", "/api/notifications/$id", tenant.admin, null, HttpStatus.OK).get("readCount").asLong()).isEqualTo(1)
    }

    @Test
    fun `PUT replaces it, and a kind change resets who read it`() {
        val id = create(title = "Antes")
        val ana = tenant.createUser()
        receipt(id, ana.id, read = true)

        val sameKind = call("PUT", "/api/notifications/$id", tenant.admin, body(title = "Después", kind = "INFO"), HttpStatus.OK)
        assertThat(sameKind.get("title").asString()).isEqualTo("Después")
        assertThat(sameKind.get("readCount").asLong()).isEqualTo(1)

        val otherKind = call("PUT", "/api/notifications/$id", tenant.admin, body(title = "Después", kind = "WARNING"), HttpStatus.OK)
        assertThat(otherKind.get("kind").asString()).isEqualTo("WARNING")
        assertThat(otherKind.get("readCount").asLong()).isZero()
        assertThat(otherKind.get("source").asString()).isEqualTo("manual")
    }

    @Test
    fun `DELETE removes it`() {
        val id = create()

        call("DELETE", "/api/notifications/$id", tenant.admin, null, HttpStatus.NO_CONTENT)

        call("GET", "/api/notifications/$id", tenant.admin, null, HttpStatus.NOT_FOUND)
        call("DELETE", "/api/notifications/$id", tenant.admin, null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `without MANAGE_ORGANIZATION it is a 403`() {
        val role = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ")))
        val user = tenant.createUser(roles = listOf(role))
        val id = create()

        call("GET", "/api/notifications", user.token, null, HttpStatus.FORBIDDEN)
        call("GET", "/api/notifications/$id", user.token, null, HttpStatus.FORBIDDEN)
        call("POST", "/api/notifications", user.token, body(), HttpStatus.FORBIDDEN)
        call("PUT", "/api/notifications/$id", user.token, body(), HttpStatus.FORBIDDEN)
        call("DELETE", "/api/notifications/$id", user.token, null, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `another tenant's notification is a 404`() {
        val other = NotificationsTenant.provision(client, db, schemas)
        val theirs = UUID.fromString(call("POST", "/api/notifications", other.admin, body(), HttpStatus.CREATED).get("id").asString())

        call("GET", "/api/notifications/$theirs", tenant.admin, null, HttpStatus.NOT_FOUND)
        call("PUT", "/api/notifications/$theirs", tenant.admin, body(), HttpStatus.NOT_FOUND)
        call("DELETE", "/api/notifications/$theirs", tenant.admin, null, HttpStatus.NOT_FOUND)
        assertThat(ids("source=manual")).doesNotContain(theirs)
        // still there for its own tenant
        call("GET", "/api/notifications/$theirs", other.admin, null, HttpStatus.OK)
    }

    @Test
    fun `an unknown recipient is a 400 naming audience at its index`() {
        val problem =
            call(
                "POST",
                "/api/notifications",
                tenant.admin,
                body(
                    audience =
                        listOf(
                            mapOf("type" to "ALL"),
                            mapOf("type" to "USER", "value" to UUID.randomUUID().toString()),
                            mapOf("type" to "EMAIL", "value" to "nobody@${tenant.slug}.local"),
                            mapOf("type" to "ROLE", "value" to "NO_SUCH_ROLE"),
                            mapOf("type" to "UNIT", "value" to "NO_SUCH_UNIT")
                        )
                ),
                HttpStatus.BAD_REQUEST
            )

        assertThat(errorFields(problem)).containsExactly("audience[1]", "audience[2]", "audience[3]", "audience[4]")
    }

    @Test
    fun `a malformed body is a 400 naming its fields`() {
        fun fields(body: Map<String, Any?>) = errorFields(call("POST", "/api/notifications", tenant.admin, body, HttpStatus.BAD_REQUEST))

        assertThat(fields(body(link = mapOf("type" to "RECORD", "object" to "no_such_object", "recordId" to UUID.randomUUID().toString()))))
            .containsExactly("link.object")
        assertThat(fields(body(link = mapOf("type" to "MAILTO", "url" to "x")))).containsExactly("link.type")
        assertThat(fields(body(audience = listOf(mapOf("type" to "GROUP", "value" to "X"))))).containsExactly("audience[0]")
        assertThat(fields(body(audience = emptyList()))).containsExactly("audience")
        assertThat(fields(body(kind = "NEWS"))).containsExactly("kind")
        // strict: never cut over REST
        assertThat(fields(body(title = "x".repeat(201)))).containsExactly("title")
        val now = Instant.now()
        assertThat(fields(body(publishAt = now.plus(2, ChronoUnit.DAYS), expiresAt = now.plus(1, ChronoUnit.DAYS)))).containsExactly("expiresAt")
    }

    @Test
    fun `a notification of a source is read, never changed, over REST`() {
        val source = "it.owned." + UUID.randomUUID().toString().take(8)
        val id = publish(source, "k1", NotificationKind.ACTION)

        val view = call("GET", "/api/notifications/$id", tenant.admin, null, HttpStatus.OK)
        assertThat(view.get("source").asString()).isEqualTo(source)
        assertThat(view.get("key").asString()).isEqualTo("k1")

        val put = call("PUT", "/api/notifications/$id", tenant.admin, body(), HttpStatus.CONFLICT)
        assertThat(put.get("detail").asString()).isEqualTo("Notification is owned by its source")
        call("DELETE", "/api/notifications/$id", tenant.admin, null, HttpStatus.CONFLICT)
        assertThat(column(id, "title", String::class.java)).isEqualTo("k1")
    }

    @Test
    fun `PUT without publishAt keeps a scheduled publication and checks the expiry against it`() {
        val now = Instant.now()
        val publishAt = now.plus(3, ChronoUnit.DAYS)
        val id = create(publishAt = publishAt)

        val problem = call("PUT", "/api/notifications/$id", tenant.admin, body(expiresAt = now.plus(1, ChronoUnit.DAYS)), HttpStatus.BAD_REQUEST)
        assertThat(errorFields(problem)).containsExactly("expiresAt")

        val kept = call("PUT", "/api/notifications/$id", tenant.admin, body(title = "Más tarde", expiresAt = now.plus(5, ChronoUnit.DAYS)), HttpStatus.OK)
        assertThat(Instant.parse(kept.get("publishAt").asString())).isEqualTo(publishAt.truncatedTo(ChronoUnit.MICROS))
    }

    // ---- helpers ----

    private fun body(
        kind: String = "INFO",
        title: String = "Aviso " + UUID.randomUUID().toString().take(6),
        audience: List<Map<String, Any?>> = listOf(mapOf("type" to "ALL")),
        link: Map<String, Any?>? = null,
        publishAt: Instant? = null,
        expiresAt: Instant? = null
    ): Map<String, Any?> =
        mapOf(
            "kind" to kind,
            "title" to title,
            "audience" to audience,
            "link" to link,
            "publishAt" to publishAt?.toString(),
            "expiresAt" to expiresAt?.toString()
        )

    private fun create(
        title: String = "Aviso " + UUID.randomUUID().toString().take(6),
        audience: List<Map<String, Any?>> = listOf(mapOf("type" to "ALL")),
        link: Map<String, Any?>? = null,
        publishAt: Instant? = null
    ): UUID =
        UUID.fromString(
            call("POST", "/api/notifications", tenant.admin, body(title = title, audience = audience, link = link, publishAt = publishAt), HttpStatus.CREATED)
                .get("id")
                .asString()
        )

    // from code, as an app would: the title is the key, so a test can tell rows apart
    private fun publish(
        source: String,
        key: String,
        kind: NotificationKind,
        publishAt: Instant? = null
    ): UUID =
        runBlocking {
            notifications.publish(
                tenant.organizationId,
                source,
                NotificationDraft(kind = kind, title = key, audience = listOf(Audience.All), key = key, publishAt = publishAt)
            )!!
        }

    private fun ids(query: String): List<UUID> =
        call("GET", "/api/notifications?$query&size=200", tenant.admin, null, HttpStatus.OK)
            .get("content")
            .toList()
            .map { UUID.fromString(it.get("id").asString()) }

    private fun errorFields(problem: JsonNode): List<String> = problem.get("errors").toList().map { it.get("field").asString() }

    private fun receipt(
        notificationId: UUID,
        userId: UUID,
        read: Boolean
    ) = runBlocking {
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.notification_receipts (notification_id, user_id, read_at, dismissed_at) " +
                    "VALUES (:n, :u, CAST(:readAt AS timestamptz), CAST(:dismissedAt AS timestamptz))"
            ).bind("n", notificationId)
            .bind("u", userId)
            .let { if (read) it.bind("readAt", now()) else it.bindNull("readAt", OffsetDateTime::class.java) }
            .let { if (read) it.bindNull("dismissedAt", OffsetDateTime::class.java) else it.bind("dismissedAt", now()) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private fun <T : Any> column(
        id: UUID,
        name: String,
        type: Class<T>
    ): T? =
        runBlocking {
            db
                .sql("SELECT $name AS v FROM ${schemas.metadata}.notifications WHERE id = :id")
                .bind("id", id)
                .map { row, _ -> java.util.Optional.ofNullable(row.get("v", type)) }
                .one()
                .awaitSingle()
                .orElse(null)
        }

    private fun call(
        method: String,
        uri: String,
        token: String,
        body: Any?,
        expected: HttpStatus
    ): JsonNode {
        val request = client.method(HttpMethod.valueOf(method)).uri(uri).header(HttpHeaders.AUTHORIZATION, token)
        val result = (if (body == null) request else request.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
        if (result.status.value() != expected.value()) {
            throw AssertionError("$method $uri answered ${result.status}, expected $expected\nresponse body: ${result.responseBody}")
        }
        return json.readTree(result.responseBody ?: "null")
    }
}
