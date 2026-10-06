package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.Audience
import wasichai.notifications.NotificationChannel
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.NotificationLink
import wasichai.notifications.NotificationListener
import wasichai.notifications.NotificationLoop
import wasichai.notifications.NotificationPreparer
import wasichai.notifications.NotificationSignals
import wasichai.notifications.NotificationWriter
import wasichai.test.WasichaiTestDatabase
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

// spec D end to end: the stream hears writes through LISTEN. the refresh floor is an hour here, so a new
// summary within seconds can only have come through a signal.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = [
        "wasichai.notifications.stream-refresh=1h",
        "wasichai.notifications.stream-heartbeat=1s",
        "wasichai.notifications.stream-debounce=100ms"
    ]
)
class NotificationStreamApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var preparer: NotificationPreparer

    @Autowired
    private lateinit var writer: NotificationWriter

    @Autowired
    private lateinit var listener: NotificationListener

    @Autowired
    private lateinit var signals: NotificationSignals

    @Autowired
    private lateinit var loop: NotificationLoop

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }

    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun listening() {
        // the listener connects on its own after boot: a signal sent before that would only be a wildcard's luck
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (listener.state != NotificationListener.State.LISTENING) {
            check(System.nanoTime() < deadline) { "the notifications listener is ${listener.state}, not LISTENING" }
            Thread.sleep(50)
        }
    }

    private fun open(token: String): Flux<ServerSentEvent<String>> =
        client
            .get()
            .uri(STREAM)
            .header(HttpHeaders.AUTHORIZATION, token)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("X-Accel-Buffering", "no")
            .returnResult<ServerSentEvent<String>>()
            .responseBody

    private fun summaries(token: String): Flux<JsonNode> = open(token).filter { it.event() == "summary" }.map { json.readTree(it.data()) }

    private fun publish(
        kind: NotificationKind,
        to: UUID,
        title: String = "Firmar ${UUID.randomUUID()}",
        link: NotificationLink? = null,
        more: List<UUID> = emptyList()
    ): UUID =
        runBlocking {
            val draft = NotificationDraft(kind, title, (listOf(to) + more).map { Audience.User(it) }, link = link)
            val prepared = preparer.prepare(tenant.organizationId, SOURCE, draft, Instant.now(), strict = false)!!
            writer.publish(tenant.organizationId, SOURCE, prepared, null).id
        }

    // publish, then wait until this replica has heard its NOTIFY: a stream opened after that cannot get it late
    // (the hub drops what nobody listened to), so the next event can only come from what the test does next
    private fun publishHeard(
        kind: NotificationKind,
        to: UUID
    ): UUID {
        val heard =
            signals
                .signals()
                .filter { it.organizationId == tenant.organizationId && it.userId == null }
                .next()
                .toFuture()
        val id = publish(kind, to)
        heard.get(20, TimeUnit.SECONDS)
        return id
    }

    private fun unread(
        summary: JsonNode,
        kind: NotificationKind
    ): Long =
        summary
            .get("kinds")
            .get(kind.name)
            .get("unread")
            .asLong()

    @Test
    fun `the first event is the summary, and a publish brings a new one`() {
        val ana = tenant.createUser()
        StepVerifier
            .create(summaries(ana.token))
            .assertNext { summary ->
                NotificationKind.entries.forEach {
                    assertThat(
                        summary
                            .get("kinds")
                            .get(it.name)
                            .get("active")
                            .asLong()
                    ).isZero()
                }
                assertThat(summary.get("latest").isNull).isTrue()
            }.then { publish(NotificationKind.ACTION, ana.id, title = "Firmar el acta") }
            .assertNext { summary ->
                assertThat(unread(summary, NotificationKind.ACTION)).isEqualTo(1)
                assertThat(summary.get("latest").get("title").asString()).isEqualTo("Firmar el acta")
            }.thenCancel()
            .verify(Duration.ofSeconds(20))
    }

    // FullAppProperties turns the loop off, and this class's own properties add to them, never replace them
    @Test
    fun `the loop is off here, as in every full app test`() {
        assertThat(loop.isRunning).isFalse()
    }

    @Test
    fun `a heartbeat comment keeps the stream alive`() {
        val ana = tenant.createUser()
        StepVerifier
            .create(open(ana.token))
            .assertNext { assertThat(it.event()).isEqualTo("summary") }
            .assertNext { assertThat(it.comment()).isEqualTo("ping") }
            .thenCancel()
            .verify(Duration.ofSeconds(20))
    }

    @Test
    fun `a raw pg_notify from another connection brings a recompute`() {
        val bea = tenant.createUser()
        val id = publishHeard(NotificationKind.INFO, bea.id)
        StepVerifier
            .create(summaries(bea.token))
            .assertNext { assertThat(unread(it, NotificationKind.INFO)).isEqualTo(1) }
            .then {
                // a change nobody announces: the stream cannot know
                runBlocking {
                    db
                        .sql("INSERT INTO ${schemas.metadata}.notification_receipts (notification_id, user_id, read_at) VALUES (:id, :user, now())")
                        .bind("id", id)
                        .bind("user", bea.id)
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                }
            }.expectNoEvent(Duration.ofSeconds(2))
            .then { rawNotify("""{"o":"${tenant.organizationId}"}""") }
            .assertNext { assertThat(unread(it, NotificationKind.INFO)).isZero() }
            .thenCancel()
            .verify(Duration.ofSeconds(20))
    }

    @Test
    fun `another person's receipts do not wake my stream`() {
        val cata = tenant.createUser()
        val dora = tenant.createUser()
        publishHeard(NotificationKind.INFO, cata.id)
        StepVerifier
            .create(summaries(cata.token))
            .assertNext { assertThat(unread(it, NotificationKind.INFO)).isEqualTo(1) }
            .then { rawNotify("""{"o":"${tenant.organizationId}","u":"${dora.id}"}""") }
            .then { rawNotify("""{"o":"${UUID.randomUUID()}"}""") }
            .then { rawNotify("not json") }
            .expectNoEvent(Duration.ofSeconds(2))
            .thenCancel()
            .verify(Duration.ofSeconds(20))
    }

    @Test
    fun `a record link reaches only who may read the object`() {
        val objectName = tenant.createObject()
        val recordId = tenant.createRecord(objectName, mapOf("codigo" to "EXP-1"))
        val role = tenant.createRole()
        val eva = tenant.createUser(roles = listOf(role))
        publish(NotificationKind.ACTION, eva.id, link = NotificationLink.Record(objectName, recordId), more = listOf(tenant.adminId))

        StepVerifier
            .create(summaries(eva.token).take(1))
            .assertNext { summary ->
                val link = summary.get("latest").get("link")
                assertThat(link == null || link.isNull).describedAs(summary.toString()).isTrue()
            }.verifyComplete()
        StepVerifier
            .create(summaries(tenant.admin).take(1))
            .assertNext { summary ->
                val link = summary.get("latest").get("link")
                assertThat(link.get("type").asString()).isEqualTo("RECORD")
                assertThat(link.get("recordId").asString()).isEqualTo(recordId.toString())
            }.verifyComplete()
    }

    @Test
    fun `without a token the stream is 401`() {
        client
            .get()
            .uri(STREAM)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a token in the query string is not a token`() {
        val ana = tenant.createUser()
        client
            .get()
            .uri("$STREAM?access_token=${ana.token.removePrefix("Bearer ")}")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a service account has no stream`() {
        val created =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, tenant.admin)
                .bodyValue(mapOf("name" to uniqueName("svc"), "roles" to listOf(tenant.createRole())))
                .exchange()
                .expectStatus()
                .isCreated
                .returnResult<String>()
                .responseBody
                .blockFirst()!!
                .let { json.readTree(it) }
        val token =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to created.get("clientId").asString(), "clientSecret" to created.get("clientSecret").asString()))
                .exchange()
                .expectStatus()
                .isOk
                .returnResult<String>()
                .responseBody
                .blockFirst()!!
                .let { json.readTree(it).get("token").asString() }
        client
            .get()
            .uri(STREAM)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    // another session, outside the app's pool and its listener: what any writer of the database would send
    private fun rawNotify(payload: String) {
        val p = WasichaiTestDatabase.properties().mapKeys { it.key.removePrefix("wasichai.database.") }
        DriverManager.getConnection("jdbc:postgresql://${p["host"]}:${p["port"]}/${p["name"]}", p["username"], p["password"]).use { connection ->
            connection.prepareStatement("SELECT pg_notify(?, ?)").use { statement ->
                statement.setString(1, NotificationChannel.name(schemas))
                statement.setString(2, payload)
                statement.execute()
            }
        }
    }

    companion object {
        const val STREAM = "/api/auth/me/notifications/stream"
        const val SOURCE = "it-stream"
    }
}
