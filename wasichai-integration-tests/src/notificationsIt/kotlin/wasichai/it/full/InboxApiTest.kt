package wasichai.it.full

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.Audience
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.NotificationLink
import wasichai.notifications.NotificationPreparer
import wasichai.notifications.NotificationWriter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// my notifications: /api/auth/me/notifications. each test makes its own users, so counts are its own;
// ALL goes to a second tenant, where it cannot reach this one's users.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboxApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var preparer: NotificationPreparer

    @Autowired
    private lateinit var writer: NotificationWriter

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }
    private val other by lazy { NotificationsTenant.provision(client, db, schemas) }
    private val json = JsonMapper.builder().build()

    // ---- audience ----

    @Test
    fun `a notification for a user reaches that user and nobody else`() {
        val ana = tenant.createUser()
        val bob = tenant.createUser()
        val id = publish(tenant, info("for ana", Audience.User(ana.id)))

        assertThat(ids(ana)).containsExactly(id)
        assertThat(ids(bob)).isEmpty()
    }

    @Test
    fun `a notification for a role reaches its holders`() {
        val role = tenant.createRole()
        val holder = tenant.createUser(roles = listOf(role))
        val stranger = tenant.createUser(roles = listOf(tenant.createRole()))
        val id = publish(tenant, info("for the role", Audience.Role(role)))

        assertThat(ids(holder)).containsExactly(id)
        assertThat(ids(stranger)).isEmpty()
    }

    @Test
    fun `a notification for a unit reaches its subtree, not a sibling nor the unit above`() {
        val root = tenant.createUnit()
        val parent = tenant.createUnit(parent = root.code)
        val child = tenant.createUnit(parent = parent.code)
        val sibling = tenant.createUnit(parent = root.code)
        val inChild = tenant.createUser(units = listOf(child.code))
        val inParent = tenant.createUser(units = listOf(parent.code))
        val inSibling = tenant.createUser(units = listOf(sibling.code))
        val inRoot = tenant.createUser(units = listOf(root.code))
        val id = publish(tenant, info("for the unit", Audience.Unit(parent.code)))

        assertThat(ids(inChild)).containsExactly(id)
        assertThat(ids(inParent)).containsExactly(id)
        assertThat(ids(inSibling)).isEmpty()
        assertThat(ids(inRoot)).isEmpty()
    }

    @Test
    fun `a notification for everyone reaches the organization only`() {
        val mine = other.createUser()
        val stranger = tenant.createUser()
        val id = publish(other, info("for all", Audience.All))

        assertThat(ids(mine)).containsExactly(id)
        assertThat(ids(stranger)).doesNotContain(id)
        assertThat(ids(mine, token = other.admin)).contains(id)
    }

    // ---- what is not there ----

    @Test
    fun `scheduled, expired and resolved notifications are hidden and answer 404`() {
        val ana = tenant.createUser()
        val now = Instant.now()
        val scheduled = publish(tenant, info("later", Audience.User(ana.id)).copy(publishAt = now.plus(Duration.ofDays(1))))
        val expired =
            publish(
                tenant,
                info("gone", Audience.User(ana.id)).copy(publishAt = now.minus(Duration.ofHours(2)), expiresAt = now.minus(Duration.ofHours(1))),
                at = now.minus(Duration.ofHours(3))
            )
        val resolved = publish(tenant, info("done", Audience.User(ana.id)).copy(key = "done-1"), source = SOURCE)
        runBlocking { writer.resolve(tenant.organizationId, SOURCE, "done-1") }
        val shown = publish(tenant, info("shown", Audience.User(ana.id)))

        assertThat(ids(ana)).containsExactly(shown)
        listOf(scheduled, expired, resolved).forEach { hidden ->
            assertThat(status(ana, "/$hidden/read")).isEqualTo(404)
            assertThat(status(ana, "/$hidden/dismiss")).isEqualTo(404)
            assertThat(status(ana, "/$hidden/snooze", mapOf("until" to now.plus(Duration.ofHours(1)).toString()))).isEqualTo(404)
        }
        assertThat(
            summary(ana)
                .get("kinds")
                .get("INFO")
                .get("active")
                .asLong()
        ).isEqualTo(1)
    }

    @Test
    fun `another tenant's id, one not addressed to the caller and an unknown id answer 404`() {
        val ana = tenant.createUser()
        val bob = tenant.createUser()
        val foreign = other.createUser()
        val id = publish(tenant, info("for ana", Audience.User(ana.id)))
        val until = Instant.now().plus(Duration.ofHours(1)).toString()

        for (caller in listOf(bob, foreign)) {
            assertThat(status(caller, "/$id/read")).isEqualTo(404)
            assertThat(status(caller, "/$id/dismiss")).isEqualTo(404)
            assertThat(status(caller, "/$id/snooze", mapOf("until" to until))).isEqualTo(404)
        }
        assertThat(status(ana, "/${UUID.randomUUID()}/read")).isEqualTo(404)
        // nothing was written for ana by the strangers
        assertThat(page(ana, "?state=unread").get("totalElements").asLong()).isEqualTo(1)
    }

    // ---- states and order ----

    @Test
    fun `read marks an item and keeps it active, unread leaves it out`() {
        val ana = tenant.createUser()
        val first = publish(tenant, info("first", Audience.User(ana.id)).copy(publishAt = Instant.now().minusSeconds(60)))
        val second = publish(tenant, info("second", Audience.User(ana.id)))

        assertThat(status(ana, "/$first/read")).isEqualTo(204)
        // a second read is fine and keeps the first time
        assertThat(status(ana, "/$first/read")).isEqualTo(204)

        val active = page(ana, "")
        assertThat(active.get("content").items().map { it.get("id").asString() }).containsExactly(second.toString(), first.toString())
        assertThat(active.get("content").items().map { it.get("read").asBoolean() }).containsExactly(false, true)
        assertThat(ids(ana, "?state=unread")).containsExactly(second)
        assertThat(ids(ana, "?state=snoozed")).isEmpty()
    }

    @Test
    fun `an item carries its fields`() {
        val ana = tenant.createUser()
        val due = Instant.now().plus(Duration.ofDays(2))
        val id =
            publish(
                tenant,
                NotificationDraft(
                    kind = NotificationKind.ACTION,
                    title = "Firmar",
                    body = "El acta",
                    audience = listOf(Audience.User(ana.id)),
                    link = NotificationLink.Url("https://example.org/acta"),
                    dueAt = due
                )
            )

        val item = page(ana, "").get("content").items().single()
        assertThat(item.get("id").asString()).isEqualTo(id.toString())
        assertThat(item.get("kind").asString()).isEqualTo("ACTION")
        assertThat(item.get("title").asString()).isEqualTo("Firmar")
        assertThat(item.get("body").asString()).isEqualTo("El acta")
        assertThat(item.get("link").get("type").asString()).isEqualTo("URL")
        assertThat(item.get("link").get("url").asString()).isEqualTo("https://example.org/acta")
        assertThat(Instant.parse(item.get("dueAt").asString())).isEqualTo(due.truncatedTo(ChronoUnit.MICROS))
        assertThat(item.get("overdue").asBoolean()).isFalse()
        assertThat(item.get("source").asString()).isEqualTo("manual")
        assertThat(item.get("read").asBoolean()).isFalse()
        assertThat(item.get("snoozedUntil").isNull).isTrue()
        assertThat(item.get("dismissible").asBoolean()).isTrue()
        assertThat(item.has("publishAt")).isTrue()
        assertThat(item.has("expiresAt")).isTrue()
    }

    @Test
    fun `actions come by due date, the overdue first and the undated last`() {
        val ana = tenant.createUser()
        val now = Instant.now()
        val later = publish(tenant, action("later", ana.id, now.plus(Duration.ofDays(2))))
        val undated = publish(tenant, action("undated", ana.id, null))
        val overdue = publish(tenant, action("overdue", ana.id, now.minus(Duration.ofDays(1))))
        val soon = publish(tenant, action("soon", ana.id, now.plus(Duration.ofDays(1))))
        publish(tenant, info("not an action", Audience.User(ana.id)))

        val content = page(ana, "?kind=ACTION").get("content").items()
        assertThat(content.map { it.get("id").asString() }).containsExactly(
            overdue.toString(),
            soon.toString(),
            later.toString(),
            undated.toString()
        )
        assertThat(content.map { it.get("overdue").asBoolean() }).containsExactly(true, false, false, false)
        // lower case reads too
        assertThat(ids(ana, "?kind=action")).hasSize(4)
    }

    @Test
    fun `a bad kind or state is a 400`() {
        val ana = tenant.createUser()
        assertThat(getStatus(ana, "?kind=URGENT")).isEqualTo(400)
        assertThat(getStatus(ana, "?state=gone")).isEqualTo(400)
        assertThat(status(ana, "/read-all", mapOf("kind" to "URGENT"))).isEqualTo(400)
    }

    @Test
    fun `pages are sized`() {
        val ana = tenant.createUser()
        repeat(3) { publish(tenant, info("n$it", Audience.User(ana.id))) }

        val first = page(ana, "?page=0&size=2")
        assertThat(first.get("content").size()).isEqualTo(2)
        assertThat(first.get("totalElements").asLong()).isEqualTo(3)
        assertThat(first.get("totalPages").asInt()).isEqualTo(2)
        assertThat(page(ana, "?page=1&size=2").get("content").size()).isEqualTo(1)
    }

    // ---- summary ----

    @Test
    fun `the summary counts every kind and names the newest active notification`() {
        val ana = tenant.createUser()
        val now = Instant.now()
        publish(tenant, info("info", Audience.User(ana.id)).copy(publishAt = now.minusSeconds(300)))
        val warning = publish(tenant, info("warning", Audience.User(ana.id)).copy(kind = NotificationKind.WARNING, publishAt = now.minusSeconds(200)))
        publish(tenant, action("overdue", ana.id, now.minus(Duration.ofDays(1))).copy(publishAt = now.minusSeconds(150)))
        val newest =
            publish(
                tenant,
                action("soon", ana.id, now.plus(Duration.ofDays(1))).copy(
                    publishAt = now.minusSeconds(100),
                    link = NotificationLink.Route("app:inicio", mapOf("tab" to "x"))
                )
            )
        assertThat(status(ana, "/$warning/read")).isEqualTo(204)

        val summary = summary(ana)
        val kinds = summary.get("kinds")
        assertThat(counts(kinds.get("INFO"))).containsExactly(1L, 1L, 0L)
        assertThat(counts(kinds.get("WARNING"))).containsExactly(1L, 0L, 0L)
        assertThat(counts(kinds.get("ACTION"))).containsExactly(2L, 2L, 1L)
        val latest = summary.get("latest")
        assertThat(latest.get("id").asString()).isEqualTo(newest.toString())
        assertThat(latest.get("kind").asString()).isEqualTo("ACTION")
        assertThat(latest.get("title").asString()).isEqualTo("soon")
        assertThat(latest.get("link").get("route").asString()).isEqualTo("app:inicio")
        assertThat(latest.has("linkObjectId")).isFalse()
        assertThat(latest.has("publishAt")).isTrue()

        // dismissed leaves the counts
        assertThat(status(ana, "/$newest/dismiss")).isEqualTo(204)
        assertThat(counts(summary(ana).get("kinds").get("ACTION"))).containsExactly(1L, 1L, 1L)
    }

    @Test
    fun `an empty inbox summarises to zeros and no latest`() {
        val ana = tenant.createUser()
        val summary = summary(ana)
        NotificationKind.entries.forEach { assertThat(counts(summary.get("kinds").get(it.name))).containsExactly(0L, 0L, 0L) }
        assertThat(summary.get("latest").isNull).isTrue()
    }

    // ---- links ----

    @Test
    fun `a record link is dropped for a reader without READ, and for everyone once the object is gone`() {
        val name = tenant.createObject()
        val record = tenant.createRecord(name, mapOf("codigo" to "C-1"))
        val reading = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ", name)))
        val blind = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ", tenant.createObject())))
        val reader = tenant.createUser(roles = listOf(reading))
        val stranger = tenant.createUser(roles = listOf(blind))
        val audience = listOf(Audience.User(reader.id), Audience.User(stranger.id))
        val now = Instant.now()
        publish(
            tenant,
            NotificationDraft(NotificationKind.INFO, "route", audience, link = NotificationLink.Route("app:inicio"), publishAt = now.minusSeconds(30))
        )
        publish(
            tenant,
            NotificationDraft(NotificationKind.INFO, "url", audience, link = NotificationLink.Url("https://example.org"), publishAt = now.minusSeconds(20))
        )
        val recordLinked =
            publish(
                tenant,
                NotificationDraft(
                    NotificationKind.INFO,
                    "record",
                    audience,
                    link = NotificationLink.Record(name, record, "DETAILS"),
                    publishAt = now.minusSeconds(10)
                )
            )

        fun links(user: NotificationsTenant.User): Map<String, JsonNode?> =
            page(user, "").get("content").items().associate { it.get("title").asString() to it.get("link").takeUnless { link -> link.isNull } }

        val seen = links(reader)
        assertThat(seen["record"]!!.get("type").asString()).isEqualTo("RECORD")
        assertThat(seen["record"]!!.get("object").asString()).isEqualTo(name)
        assertThat(seen["record"]!!.get("recordId").asString()).isEqualTo(record.toString())
        assertThat(seen["record"]!!.get("tab").asString()).isEqualTo("DETAILS")
        assertThat(
            summary(reader)
                .get("latest")
                .get("link")
                .get("type")
                .asString()
        ).isEqualTo("RECORD")

        val blinded = links(stranger)
        assertThat(blinded["record"]).isNull()
        assertThat(blinded["route"]!!.get("type").asString()).isEqualTo("ROUTE")
        assertThat(blinded["url"]!!.get("type").asString()).isEqualTo("URL")
        assertThat(summary(stranger).get("latest").get("id").asString()).isEqualTo(recordLinked.toString())
        assertThat(summary(stranger).get("latest").get("link").isNull).isTrue()

        client
            .delete()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isNoContent
        val after = links(reader)
        assertThat(after.keys).containsExactlyInAnyOrder("record", "route", "url")
        assertThat(after["record"]).isNull()
        assertThat(summary(reader).get("latest").get("link").isNull).isTrue()
    }

    @Test
    fun `the tenant's admin keeps a record link to any object`() {
        val name = tenant.createObject()
        val record = tenant.createRecord(name, mapOf("codigo" to "C-2"))
        val id =
            publish(
                tenant,
                NotificationDraft(NotificationKind.INFO, "admin", listOf(Audience.User(tenant.adminId)), link = NotificationLink.Record(name, record))
            )

        val item = page(tenant.adminUser(), "").get("content").items().single { it.get("id").asString() == id.toString() }
        assertThat(item.get("link").get("object").asString()).isEqualTo(name)
    }

    // ---- dismiss ----

    @Test
    fun `a manual action and a source's info are dismissed, a source's action is a 409`() {
        val ana = tenant.createUser()
        val manual = publish(tenant, action("manual", ana.id, null))
        val sourceAction = publish(tenant, action("from a source", ana.id, null).copy(key = "a-${UUID.randomUUID()}"), source = SOURCE)
        val sourceInfo = publish(tenant, info("info of a source", Audience.User(ana.id)).copy(key = "i-${UUID.randomUUID()}"), source = SOURCE)

        val byId = page(ana, "").get("content").items().associate { it.get("id").asString() to it.get("dismissible").asBoolean() }
        assertThat(byId).containsEntry(manual.toString(), true).containsEntry(sourceAction.toString(), false).containsEntry(sourceInfo.toString(), true)

        assertThat(status(ana, "/$manual/dismiss")).isEqualTo(204)
        assertThat(status(ana, "/$sourceInfo/dismiss")).isEqualTo(204)
        val refused = post(ana, "/$sourceAction/dismiss", null)
        assertThat(refused.first).isEqualTo(409)
        assertThat(refused.second).contains("This notification leaves when its work is done")

        assertThat(ids(ana)).containsExactly(sourceAction)
        // dismissed is still the reader's: reading it again is fine
        assertThat(status(ana, "/$manual/read")).isEqualTo(204)
        assertThat(ids(ana, "?state=unread")).containsExactly(sourceAction)
    }

    // ---- snooze ----

    @Test
    fun `a snooze hides an item until its time, within bounds`() {
        val ana = tenant.createUser()
        val id = publish(tenant, info("later please", Audience.User(ana.id)))
        val now = Instant.now()

        assertThat(status(ana, "/$id/snooze", mapOf("until" to now.minusSeconds(60).toString()))).isEqualTo(400)
        assertThat(status(ana, "/$id/snooze", mapOf("until" to now.plus(Duration.ofDays(31)).toString()))).isEqualTo(400)
        val missing = post(ana, "/$id/snooze", emptyMap<String, Any>())
        assertThat(missing.first).isEqualTo(400)
        assertThat(missing.second).contains("until")
        assertThat(ids(ana)).containsExactly(id)

        val until = now.plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS)
        assertThat(status(ana, "/$id/snooze", mapOf("until" to until.toString()))).isEqualTo(204)
        assertThat(ids(ana)).isEmpty()
        assertThat(ids(ana, "?state=unread")).isEmpty()
        val snoozed = page(ana, "?state=snoozed").get("content").items().single()
        assertThat(snoozed.get("id").asString()).isEqualTo(id.toString())
        assertThat(Instant.parse(snoozed.get("snoozedUntil").asString())).isEqualTo(until)
        assertThat(counts(summary(ana).get("kinds").get("INFO"))).containsExactly(0L, 0L, 0L)

        // a later snooze replaces it; the edge of the bound is allowed
        assertThat(
            status(
                ana,
                "/$id/snooze",
                mapOf(
                    "until" to
                        Instant
                            .now()
                            .plus(Duration.ofDays(30))
                            .minusSeconds(5)
                            .toString()
                )
            )
        ).isEqualTo(204)
    }

    // ---- read-all ----

    @Test
    fun `read-all marks one kind, or every kind without a body`() {
        val ana = tenant.createUser()
        publish(tenant, info("a", Audience.User(ana.id)))
        publish(tenant, info("b", Audience.User(ana.id)))
        val warning = publish(tenant, info("w", Audience.User(ana.id)).copy(kind = NotificationKind.WARNING))

        assertThat(status(ana, "/read-all", mapOf("kind" to "INFO"))).isEqualTo(204)
        assertThat(ids(ana, "?state=unread")).containsExactly(warning)
        assertThat(ids(ana)).hasSize(3)

        assertThat(status(ana, "/read-all")).isEqualTo(204)
        assertThat(ids(ana, "?state=unread")).isEmpty()
        assertThat(status(ana, "/read-all", emptyMap<String, Any>())).isEqualTo(204)
    }

    // ---- who ----

    @Test
    fun `a service account has no notifications`() {
        val role = tenant.createRole()
        val sa = serviceAccountToken(role)
        val user = NotificationsTenant.User(UUID.randomUUID(), "sa", sa)

        assertThat(getStatus(user, "")).isEqualTo(403)
        assertThat(getStatus(user, "/summary")).isEqualTo(403)
        assertThat(status(user, "/${UUID.randomUUID()}/read")).isEqualTo(403)
        assertThat(status(user, "/read-all")).isEqualTo(403)
    }

    @Test
    fun `without a token it is a 401`() {
        client
            .get()
            .uri(BASE)
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    // ---- helpers ----

    private fun info(
        title: String,
        audience: Audience
    ) = NotificationDraft(kind = NotificationKind.INFO, title = title, audience = listOf(audience))

    private fun action(
        title: String,
        user: UUID,
        due: Instant?
    ) = NotificationDraft(kind = NotificationKind.ACTION, title = title, audience = listOf(Audience.User(user)), dueAt = due)

    // the write engine directly: the admin REST is another task's. at: the "now" a draft is checked against
    private fun publish(
        tenant: NotificationsTenant,
        draft: NotificationDraft,
        source: String = "manual",
        at: Instant = Instant.now()
    ): UUID =
        runBlocking {
            val prepared = preparer.prepare(tenant.organizationId, source, draft, at, strict = true, allowReservedSource = true)!!
            writer.publish(tenant.organizationId, source, prepared, null).id
        }

    private fun NotificationsTenant.adminUser() = NotificationsTenant.User(adminId, adminEmail, admin)

    // jackson 3's JsonNode is no plain Iterable for kotlin's map
    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun counts(kind: JsonNode): List<Long> = listOf(kind.get("active").asLong(), kind.get("unread").asLong(), kind.get("overdue").asLong())

    private fun ids(
        user: NotificationsTenant.User,
        query: String = "",
        token: String = user.token
    ): List<UUID> = get(token, query).get("content").items().map { UUID.fromString(it.get("id").asString()) }

    private fun page(
        user: NotificationsTenant.User,
        query: String
    ): JsonNode = get(user.token, query)

    private fun summary(user: NotificationsTenant.User): JsonNode = get(user.token, "/summary")

    private fun get(
        token: String,
        path: String
    ): JsonNode {
        val result =
            client
                .get()
                .uri(BASE + path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertThat(result.status.value()).withFailMessage { "GET $path answered ${result.status}: ${result.responseBody}" }.isEqualTo(200)
        return json.readTree(result.responseBody)
    }

    private fun getStatus(
        user: NotificationsTenant.User,
        path: String
    ): Int =
        client
            .get()
            .uri(BASE + path)
            .header(HttpHeaders.AUTHORIZATION, user.token)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun status(
        user: NotificationsTenant.User,
        path: String,
        body: Any? = null
    ): Int = post(user, path, body).first

    private fun post(
        user: NotificationsTenant.User,
        path: String,
        body: Any?
    ): Pair<Int, String> {
        val request =
            client
                .post()
                .uri(BASE + path)
                .header(HttpHeaders.AUTHORIZATION, user.token)
        val result = (if (body == null) request else request.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
        return result.status.value() to (result.responseBody ?: "")
    }

    private fun serviceAccountToken(role: String): String {
        val created =
            json.readTree(
                client
                    .post()
                    .uri("/api/service-accounts")
                    .header(HttpHeaders.AUTHORIZATION, tenant.admin)
                    .bodyValue(mapOf("name" to "sa-" + UUID.randomUUID().toString().take(8), "roles" to listOf(role)))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody
            )
        val token =
            json.readTree(
                client
                    .post()
                    .uri("/api/auth/token")
                    .bodyValue(mapOf("clientId" to created.get("clientId").asString(), "clientSecret" to created.get("clientSecret").asString()))
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody
            )
        return "Bearer " + token.get("token").asString()
    }

    private companion object {
        const val BASE = "/api/auth/me/notifications"
        const val SOURCE = "test.source"
    }
}
