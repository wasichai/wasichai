package wasichai.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.platform.ClusterLock
import wasichai.notifications.autoconfigure.NotificationsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ADR-060 against a real postgres: news is fanned out to people in the writer's transaction, the worker sends it
// later through the email channel (a fake MailSender), retries with backoff and records what failed.
class DeliveriesTest : ChannelsIntegrationTest() {
    @Autowired
    private lateinit var notifications: Notifications

    @Autowired
    private lateinit var deliveries: Deliveries

    @Autowired
    private lateinit var clusterLock: ClusterLock

    @Autowired
    private lateinit var properties: NotificationsProperties

    @Autowired
    private lateinit var transactions: TransactionalOperator

    private val source = "it.deliveries"

    @BeforeEach
    fun clean() = mail.reset()

    // a little ahead of the clock: what was enqueued "now" is due
    private fun later(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(5)

    private fun draft(
        vararg audience: Audience,
        kind: NotificationKind = NotificationKind.ACTION,
        key: String? = null
    ) = NotificationDraft(kind, "Pago sin registrar", audience.toList(), body = "Revise la caja 3", key = key)

    @Test
    fun `the email channel is installed and a delivery goes PENDING to SENT, once`(): Unit =
        runBlocking {
            assertThat(deliveries.channels).containsExactly("email")
            val org = organization()
            val ana = user(org)
            val id = notifications.publish(org, source, draft(Audience.User(ana)))!!

            assertThat(deliveries(id).single())
                .extracting("userId", "channel", "status", "attempts")
                .containsExactly(ana, "email", DeliveryStatus.PENDING, 0)
            assertThat(mail.sent).isEmpty()

            val at = later()
            assertThat(deliveries.deliverDue(org, at)).isEqualTo(DeliveryRun(sent = 1, retried = 0, failed = 0, skipped = 0))
            val row = deliveries(id).single()
            assertThat(row.status).isEqualTo(DeliveryStatus.SENT)
            assertThat(row.attempts).isEqualTo(1)
            assertThat(row.sentAt).isEqualTo(at)
            val sent = mail.to(emailOf(ana)).single()
            assertThat(sent.from).isEqualTo("alertas@x.test")
            assertThat(sent.subject).isEqualTo("[Test] Pago sin registrar")
            assertThat(sent.text).isEqualTo("Revise la caja 3")

            // nothing left to send
            assertThat(deliveries.deliverDue(org, at.plusSeconds(60)).sent).isZero()
            assertThat(mail.sent).hasSize(1)
        }

    @Test
    fun `a failing smtp server is retried with backoff, then FAILED, and never fails the write`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            mail.failure = "connection refused"
            // the write commits whatever the server does: it never talks to it
            val id = notifications.publish(org, source, draft(Audience.User(ana)))!!
            assertThat(deliveries(id).single().status).isEqualTo(DeliveryStatus.PENDING)

            val t0 = later()
            assertThat(deliveries.deliverDue(org, t0).retried).isEqualTo(1)
            deliveries(id).single().let {
                assertThat(it.status).isEqualTo(DeliveryStatus.PENDING)
                assertThat(it.attempts).isEqualTo(1)
                assertThat(it.lastError).isEqualTo("MailSendException: connection refused")
                assertThat(it.nextAttemptAt).isEqualTo(t0.plus(Duration.ofMinutes(1)))
            }
            // not before its backoff
            assertThat(deliveries.deliverDue(org, t0.plusSeconds(59))).isEqualTo(DeliveryRun(0, 0, 0, 0))
            // second try, the backoff doubles
            val t1 = t0.plus(Duration.ofMinutes(1))
            assertThat(deliveries.deliverDue(org, t1).retried).isEqualTo(1)
            assertThat(deliveries(id).single().nextAttemptAt).isEqualTo(t1.plus(Duration.ofMinutes(2)))
            // third of delivery-max-attempts=3: FAILED, with the error kept
            assertThat(deliveries.deliverDue(org, t1.plus(Duration.ofMinutes(2))).failed).isEqualTo(1)
            deliveries(id).single().let {
                assertThat(it.status).isEqualTo(DeliveryStatus.FAILED)
                assertThat(it.attempts).isEqualTo(3)
                assertThat(it.lastError).isEqualTo("MailSendException: connection refused")
                assertThat(it.sentAt).isNull()
            }
            // a FAILED row is not tried again, even once the server is back
            mail.failure = null
            assertThat(deliveries.deliverDue(org, t1.plus(Duration.ofHours(1))).sent).isZero()
            assertThat(mail.sent).isEmpty()
        }

    @Test
    fun `a rolled back write takes its deliveries with it`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            var id: UUID? = null
            runCatching {
                transactions.executeAndAwait {
                    id = notifications.publish(org, source, draft(Audience.User(ana)))
                    error("business rule failed")
                }
            }
            assertThat(id).isNotNull()
            assertThat(deliveries(id!!)).isEmpty()
        }

    @Test
    fun `a role, a unit's subtree and everyone are fanned out to the enabled people of the tenant only`(): Unit =
        runBlocking {
            val org = organization()
            val supervisor = role(org)
            val boss = user(org, supervisor)
            val retired = user(org, supervisor, enabled = false)
            val gerencia = unit(org)
            val area = unit(org, parent = gerencia)
            val clerk = user(org)
            member(org, clerk, area)
            val bystander = user(org)
            val other = organization()
            val stranger = user(other, role(other, supervisor))

            val byRole = notifications.publish(org, source, draft(Audience.Role(supervisor)))!!
            assertThat(deliveries(byRole).map { it.userId }).containsExactly(boss)

            val byUnit = notifications.publish(org, source, draft(Audience.Unit(gerencia)))!!
            assertThat(deliveries(byUnit).map { it.userId }).containsExactly(clerk)

            val everyone = notifications.publish(org, source, draft(Audience.All))!!
            assertThat(deliveries(everyone).map { it.userId }).containsExactlyInAnyOrder(boss, clerk, bystander)
            assertThat(deliveries(everyone).map { it.userId }).doesNotContain(retired, stranger)

            // the other tenant's run sends none of it
            assertThat(deliveries.deliverDue(other, later()).sent).isZero()
            assertThat(mail.sent).isEmpty()
            assertThat(deliveries.deliverDue(org, later()).sent).isEqualTo(5)
            assertThat(mail.to(emailOf(stranger))).isEmpty()
        }

    @Test
    fun `news again is sent again, an unchanged or merely edited notification is not`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val warning = draft(Audience.User(ana), kind = NotificationKind.WARNING, key = "k1")
            val id = notifications.publish(org, source, warning)!!
            assertThat(deliveries.deliverDue(org, later()).sent).isEqualTo(1)

            // same content: nothing written, nothing sent
            notifications.publish(org, source, warning)
            // a new title updates in place: not news, the inbox keeps who read it
            notifications.publish(org, source, warning.copy(title = "Pago sin registrar (2)"))
            assertThat(deliveries(id).single().status).isEqualTo(DeliveryStatus.SENT)

            // WARNING becomes ACTION: news, the row starts over
            notifications.publish(org, source, warning.copy(kind = NotificationKind.ACTION))
            assertThat(deliveries(id).single()).extracting("status", "attempts").containsExactly(DeliveryStatus.PENDING, 0)
            assertThat(deliveries.deliverDue(org, later()).sent).isEqualTo(1)

            // resolved, then reported again: reopened, news again
            notifications.resolve(org, source, "k1")
            notifications.publish(org, source, warning.copy(kind = NotificationKind.ACTION))
            assertThat(deliveries(id).single().status).isEqualTo(DeliveryStatus.PENDING)
            assertThat(deliveries.deliverDue(org, later()).sent).isEqualTo(1)
            assertThat(mail.to(emailOf(ana))).hasSize(3)
        }

    @Test
    fun `a scheduled notification waits for its publication, an ended one is skipped`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val publishAt = later().plus(Duration.ofHours(1))
            val scheduled = notifications.publish(org, source, draft(Audience.User(ana)).copy(publishAt = publishAt))!!
            assertThat(deliveries(scheduled).single().nextAttemptAt).isEqualTo(publishAt)
            assertThat(deliveries.deliverDue(org, later()).sent).isZero()
            assertThat(deliveries.deliverDue(org, publishAt).sent).isEqualTo(1)

            val resolved = notifications.publish(org, source, draft(Audience.User(ana), key = "gone"))!!
            notifications.resolve(org, source, "gone")
            assertThat(deliveries.deliverDue(org, later()).skipped).isEqualTo(1)
            deliveries(resolved).single().let {
                assertThat(it.status).isEqualTo(DeliveryStatus.SKIPPED)
                assertThat(it.lastError).isEqualTo("ended before delivery")
            }
            assertThat(mail.sent).hasSize(1)
        }

    @Test
    fun `a person disabled before the send is skipped`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val id = notifications.publish(org, source, draft(Audience.User(ana)))!!
            db
                .sql("UPDATE ${schemas.metadata}.users SET enabled = false WHERE id = :id")
                .bind("id", ana)
                .fetch()
                .rowsUpdated()
                .awaitSingle()

            assertThat(deliveries.deliverDue(org, later()).skipped).isEqualTo(1)
            assertThat(deliveries(id).single().status).isEqualTo(DeliveryStatus.SKIPPED)
            assertThat(mail.sent).isEmpty()
        }

    @Test
    fun `with two replicas an email is sent once`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val id = notifications.publish(org, source, draft(Audience.User(ana)))!!

            // two loops over one database: each takes the cluster lock on a connection of its own
            fun replica() =
                NotificationLoop(
                    listOf(DeliveryWork(deliveries, properties.deliveryInterval)),
                    SourceRunRepository(db, schemas),
                    LoopLock { key, block -> clusterLock.tryLock(key)?.use { block() } != null },
                    { listOf(org) },
                    { 0 },
                    properties,
                    Clock.systemUTC()
                )
            val a = replica()
            val b = replica()
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            mail.entered = entered
            mail.gate = gate

            coroutineScope {
                // a is sending, parked inside the smtp call while it holds the lock
                val first = async(Dispatchers.IO) { a.runSource(Sources.DELIVERIES) }
                withContext(Dispatchers.IO) { assertThat(entered.await(FakeMailSender.WAIT_SECONDS, TimeUnit.SECONDS)).isTrue() }
                // b finds the lock taken and sends nothing
                assertThat(b.runSource(Sources.DELIVERIES)).isFalse()
                gate.countDown()
                assertThat(first.await()).isTrue()
            }
            mail.entered = null
            mail.gate = null
            // b's next turn finds it SENT
            assertThat(b.runSource(Sources.DELIVERIES)).isTrue()
            assertThat(mail.to(emailOf(ana))).hasSize(1)
            assertThat(deliveries(id).single()).extracting("status", "attempts").containsExactly(DeliveryStatus.SENT, 1)
        }

    @Test
    fun `a preference leaves a kind out of the email, the inbox keeps it`(): Unit =
        runBlocking {
            val email = "${uniqueName("pref")}@x.test"
            val token = signedUp(email)
            val me = UUID.fromString(meId(token))

            client
                .get()
                .uri("/api/auth/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"email":["INFO","WARNING","ACTION"]}""", true)

            client
                .put()
                .uri("/api/auth/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("email" to listOf("action", "WARNING")))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"email":["WARNING","ACTION"]}""", true)

            val info = notifications.publish(DEMO, source, draft(Audience.User(me), kind = NotificationKind.INFO))!!
            val action = notifications.publish(DEMO, source, draft(Audience.User(me), kind = NotificationKind.ACTION))!!
            assertThat(deliveries(info)).isEmpty()
            assertThat(deliveries(action).map { it.userId }).containsExactly(me)

            // an empty list stops the channel; a missing key keeps what it had
            client
                .put()
                .uri("/api/auth/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("email" to emptyList<String>()))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"email":[]}""", true)
            client
                .put()
                .uri("/api/auth/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(emptyMap<String, Any>())
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"email":[]}""", true)
        }

    @Test
    fun `preferences refuse an unknown channel or kind, and need a person`() {
        val token = signedUp("${uniqueName("pref")}@x.test")
        listOf(
            mapOf("sms" to listOf("ACTION")),
            mapOf("in-app" to listOf("ACTION")),
            mapOf("email" to listOf("URGENT")),
            mapOf("email" to "ACTION")
        ).forEach { body ->
            client
                .put()
                .uri("/api/auth/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isBadRequest
        }
        client
            .get()
            .uri("/api/auth/me/notification-preferences")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    // a person of the demo tenant with a password, signed in
    private fun signedUp(email: String): String {
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(mapOf("email" to email, "displayName" to "Pref", "password" to PASSWORD, "roles" to emptyList<String>()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, PASSWORD)
    }

    private fun meId(token: String): String =
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .returnResult()
            .responseBody!!
            .decodeToString()
            .let { Regex("\"(?:userId|id)\":\"([0-9a-f-]{36})\"").find(it)!!.groupValues[1] }

    private companion object {
        const val PASSWORD = "Secreto-123456"
    }
}
