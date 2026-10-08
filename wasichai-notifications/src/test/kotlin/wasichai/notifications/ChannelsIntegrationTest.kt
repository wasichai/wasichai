package wasichai.notifications

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mail.MailSendException
import org.springframework.mail.MailSender
import org.springframework.mail.SimpleMailMessage
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ADR-060: the module with its email channel over a fake MailSender, and automation next to it.
// one context for every channel test: same imports, same properties. no loop, no automation drain:
// the tests drive both by hand.
@Import(ChannelsIntegrationTest.FakeMail::class)
@TestPropertySource(
    properties = [
        "wasichai.notifications.tick=0s",
        "wasichai.notifications.email.enabled=true",
        "wasichai.notifications.email.from=alertas@x.test",
        "wasichai.notifications.email.subject-prefix=[Test] ",
        "wasichai.notifications.delivery-max-attempts=3",
        "wasichai.notifications.delivery-backoff=1m",
        "wasichai.automation.poll-interval=0s"
    ]
)
abstract class ChannelsIntegrationTest : WasichaiIntegrationTest() {
    @Autowired
    protected lateinit var mail: FakeMailSender

    @Autowired
    protected lateinit var db: DatabaseClient

    @Autowired
    protected lateinit var schemas: WasichaiSchemas

    @TestConfiguration(proxyBeanMethods = false)
    class FakeMail {
        @Bean
        fun fakeMailSender(): FakeMailSender = FakeMailSender()
    }

    // an smtp server in a list: what it got, and how it fails when told to
    class FakeMailSender : MailSender {
        val sent = CopyOnWriteArrayList<SimpleMailMessage>()

        @Volatile
        var failure: String? = null

        // the replica test parks a send here while the other replica tries its turn
        @Volatile
        var entered: CountDownLatch? = null

        @Volatile
        var gate: CountDownLatch? = null

        override fun send(simpleMessage: SimpleMailMessage) {
            entered?.countDown()
            gate?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            failure?.let { throw MailSendException(it) }
            sent += simpleMessage
        }

        override fun send(vararg simpleMessages: SimpleMailMessage) = simpleMessages.forEach { send(it) }

        fun to(email: String): List<SimpleMailMessage> = sent.filter { it.to?.contains(email) == true }

        fun reset() {
            sent.clear()
            failure = null
            entered = null
            gate = null
        }

        companion object {
            const val WAIT_SECONDS = 10L
        }
    }

    data class DeliveryRow(
        val userId: UUID,
        val channel: String,
        val status: DeliveryStatus,
        val attempts: Int,
        val lastError: String?,
        val nextAttemptAt: Instant,
        val sentAt: Instant?
    )

    protected suspend fun deliveries(notificationId: UUID): List<DeliveryRow> =
        db
            .sql(
                "SELECT user_id, channel, status, attempts, last_error, next_attempt_at, sent_at " +
                    "FROM ${schemas.metadata}.notification_deliveries WHERE notification_id = :id"
            ).bind("id", notificationId)
            .map { row, _ ->
                DeliveryRow(
                    Rows.uuid(row, "user_id"),
                    Rows.string(row, "channel"),
                    DeliveryStatus.valueOf(Rows.string(row, "status")),
                    Rows.int(row, "attempts"),
                    Rows.stringOrNull(row, "last_error"),
                    Rows.instantOrNull(row, "next_attempt_at")!!,
                    Rows.instantOrNull(row, "sent_at")
                )
            }.all()
            .collectList()
            .awaitSingle()

    protected suspend fun organization(): UUID {
        val slug = uniqueName("org")
        return db
            .sql("INSERT INTO ${schemas.metadata}.organizations (name, slug) VALUES (:slug, :slug) RETURNING id")
            .bind("slug", slug)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()
    }

    protected suspend fun user(
        org: UUID,
        vararg roles: String,
        enabled: Boolean = true
    ): UUID {
        val email = "${uniqueName("p")}@x.test"
        val id =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.users (organization_id, email, password_hash, display_name, enabled) " +
                        "VALUES (:org, :email, 'x', :email, :enabled) RETURNING id"
                ).bind("org", org)
                .bind("email", email)
                .bind("enabled", enabled)
                .map { row, _ -> Rows.uuid(row, "id") }
                .one()
                .awaitSingle()
        roles.forEach { role ->
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id) " +
                        "SELECT :id, r.id FROM ${schemas.metadata}.roles r WHERE r.organization_id = :org AND r.name = :role"
                ).bind("id", id)
                .bind("org", org)
                .bind("role", role)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return id
    }

    protected suspend fun emailOf(user: UUID): String =
        db
            .sql("SELECT email FROM ${schemas.metadata}.users WHERE id = :id")
            .bind("id", user)
            .map { row, _ -> Rows.string(row, "email") }
            .one()
            .awaitSingle()

    protected suspend fun role(
        org: UUID,
        name: String = "R_" + uniqueName("").uppercase()
    ): String {
        db
            .sql("INSERT INTO ${schemas.metadata}.roles (organization_id, name, label) VALUES (:org, :name, :name)")
            .bind("org", org)
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return name
    }

    // answers the unit's code; ids by unitId
    protected suspend fun unit(
        org: UUID,
        parent: String? = null
    ): String {
        val code = "U_" + uniqueName("").uppercase()
        val spec =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.org_units (organization_id, parent_id, code, label) " +
                        "VALUES (:org, (SELECT id FROM ${schemas.metadata}.org_units WHERE organization_id = :org AND code = :parent), :code, :code)"
                ).bind("org", org)
                .bind("code", code)
        (parent?.let { spec.bind("parent", it) } ?: spec.bindNull("parent", String::class.java)).fetch().rowsUpdated().awaitSingle()
        return code
    }

    protected suspend fun member(
        org: UUID,
        user: UUID,
        code: String
    ) {
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.user_org_units (user_id, unit_id) " +
                    "SELECT :user, u.id FROM ${schemas.metadata}.org_units u WHERE u.organization_id = :org AND u.code = :code"
            ).bind("user", user)
            .bind("org", org)
            .bind("code", code)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    companion object {
        // the seeded demo organization (wasichai.seed.dev), where the admin token lives
        val DEMO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
