package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.Audience
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.NotificationLink
import wasichai.notifications.Notifications
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

// Notifications, the bean apps call (spec B: the public contract, upsert semantics, strict vs lenient)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsPublishTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var notifications: Notifications

    @Autowired
    private lateinit var transactions: TransactionalOperator

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }
    private val org get() = tenant.organizationId

    @Test
    fun `publishing the same keyed draft twice writes once`() =
        runBlocking<Unit> {
            val source = source()
            val draft = draft("Turno abierto", key = "turno-1")

            val first = notifications.publish(org, source, draft)!!
            val updatedAt = stored(first).updatedAt
            val second = notifications.publish(org, source, draft)

            assertThat(second).isEqualTo(first)
            assertThat(stored(first).updatedAt).isEqualTo(updatedAt)
            assertThat(countOf(source)).isEqualTo(1)
        }

    @Test
    fun `changed content updates in place and keeps who read it`() =
        runBlocking<Unit> {
            val source = source()
            val ana = tenant.createUser()
            val id = notifications.publish(org, source, draft("3 pagos pendientes", key = "pendientes"))!!
            read(id, ana.id)

            val again = notifications.publish(org, source, draft("4 pagos pendientes", key = "pendientes"))

            assertThat(again).isEqualTo(id)
            assertThat(stored(id).title).isEqualTo("4 pagos pendientes")
            assertThat(receipts(id)).isEqualTo(1)
        }

    @Test
    fun `a kind change is news, so who read it reads it again`() =
        runBlocking<Unit> {
            val source = source()
            val ana = tenant.createUser()
            val id = notifications.publish(org, source, draft("Vence pronto", key = "tasa-1", kind = NotificationKind.WARNING))!!
            read(id, ana.id)

            notifications.publish(org, source, draft("Vence pronto", key = "tasa-1", kind = NotificationKind.ACTION))

            assertThat(stored(id).kind).isEqualTo("ACTION")
            assertThat(receipts(id)).isZero()
        }

    @Test
    fun `publishing a resolved key reopens it as new`() =
        runBlocking<Unit> {
            val source = source()
            val ana = tenant.createUser()
            val draft = draft("Conciliación descuadrada", key = "2026-10-06")
            val id = notifications.publish(org, source, draft)!!
            read(id, ana.id)

            assertThat(notifications.resolve(org, source, "2026-10-06")).isTrue()
            assertThat(stored(id).resolvedAt).isNotNull()
            // nothing open with that key any more
            assertThat(notifications.resolve(org, source, "2026-10-06")).isFalse()

            assertThat(notifications.publish(org, source, draft)).isEqualTo(id)
            assertThat(stored(id).resolvedAt).isNull()
            assertThat(receipts(id)).isZero()
        }

    @Test
    fun `an unknown recipient is dropped and the others kept`() =
        runBlocking<Unit> {
            val ana = tenant.createUser()
            val id =
                notifications.publish(
                    org,
                    source(),
                    draft("Pago muerto", audience = listOf(Audience.Email(" " + ana.email.uppercase()), Audience.Email("gone@${tenant.slug}.local")))
                )!!

            assertThat(targets(id)).containsExactly(ana.id)
        }

    @Test
    fun `nobody left writes nothing and answers null`() =
        runBlocking<Unit> {
            val source = source()

            val id =
                notifications.publish(
                    org,
                    source,
                    draft("Para nadie", audience = listOf(Audience.Email("gone@${tenant.slug}.local"), Audience.Role("NO_SUCH_ROLE")))
                )

            assertThat(id).isNull()
            assertThat(countOf(source)).isZero()
        }

    @Test
    fun `a long title is cut, never refused`() =
        runBlocking<Unit> {
            val id = notifications.publish(org, source(), draft("x".repeat(300)))!!

            val title = stored(id).title
            assertThat(title.codePointCount(0, title.length)).isEqualTo(200)
            assertThat(title).endsWith("…")
        }

    @Test
    fun `the module's own sources are refused`() {
        listOf("manual", "rule:vencimientos").forEach { source ->
            assertThatThrownBy { runBlocking { notifications.publish(org, source, draft("Ajeno")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { notifications.resolveAll(org, source) } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { runBlocking { notifications.resolveAll(org, "Not A Source") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { notifications.resolve(org, source(), "a key with spaces") } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a link to an object the organization lacks is a bug in the app`() {
        val draft = draft("Ver registro", link = NotificationLink.Record("no_such_object", UUID.randomUUID()))

        assertThatThrownBy { runBlocking { notifications.publish(org, source(), draft) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("link.object")
    }

    @Test
    fun `a record link to an existing object stores the object`() =
        runBlocking<Unit> {
            val obj = tenant.createObject()
            val record = tenant.createRecord(obj, mapOf("codigo" to "R-1"))

            val id = notifications.publish(org, source(), draft("Ver registro", link = NotificationLink.Record(obj, record, "details")))!!

            assertThat(stored(id).linkObjectId).isNotNull()
        }

    @Test
    fun `a rolled back caller takes the notification with it`() =
        runBlocking<Unit> {
            val source = source()

            assertThatThrownBy {
                runBlocking {
                    transactions.executeAndAwait<Unit> {
                        notifications.publish(org, source, draft("En la transacción", key = "k"))
                        throw IllegalStateException("business failed")
                    }
                }
            }.isInstanceOf(IllegalStateException::class.java)

            assertThat(countOf(source)).isZero()
        }

    @Test
    fun `resolveAll resolves every open notification of the source`() =
        runBlocking<Unit> {
            val source = source()
            val other = source()
            listOf("a", "b", "c").forEach { notifications.publish(org, source, draft("Pendiente $it", key = it)) }
            notifications.publish(org, other, draft("Otro", key = "a"))
            notifications.resolve(org, source, "c")

            assertThat(notifications.resolveAll(org, source)).isEqualTo(2)
            assertThat(notifications.resolveAll(org, source)).isZero()
            assertThat(openOf(other)).isEqualTo(1)
        }

    // ---- helpers ----

    private data class Stored(
        val title: String,
        val kind: String,
        val resolvedAt: OffsetDateTime?,
        val updatedAt: OffsetDateTime,
        val linkObjectId: UUID?
    )

    private fun source() = "it.publish." + UUID.randomUUID().toString().take(8)

    private fun draft(
        title: String,
        key: String? = null,
        kind: NotificationKind = NotificationKind.INFO,
        audience: List<Audience> = listOf(Audience.All),
        link: NotificationLink? = null,
        publishAt: Instant? = null
    ) = NotificationDraft(kind = kind, title = title, audience = audience, link = link, key = key, publishAt = publishAt)

    private suspend fun stored(id: UUID): Stored =
        db
            .sql("SELECT title, kind, resolved_at, updated_at, link_object_id FROM ${schemas.metadata}.notifications WHERE id = :id")
            .bind("id", id)
            .map { row, _ ->
                Stored(
                    row.get("title", String::class.java)!!,
                    row.get("kind", String::class.java)!!,
                    row.get("resolved_at", OffsetDateTime::class.java),
                    row.get("updated_at", OffsetDateTime::class.java)!!,
                    row.get("link_object_id", UUID::class.java)
                )
            }.one()
            .awaitSingle()

    private suspend fun countOf(source: String): Long =
        db
            .sql("SELECT count(*) AS c FROM ${schemas.metadata}.notifications WHERE organization_id = :org AND source = :source")
            .bind("org", org)
            .bind("source", source)
            .map { row, _ -> row.get("c", Long::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    private suspend fun openOf(source: String): Long =
        db
            .sql("SELECT count(*) AS c FROM ${schemas.metadata}.notifications WHERE organization_id = :org AND source = :source AND resolved_at IS NULL")
            .bind("org", org)
            .bind("source", source)
            .map { row, _ -> row.get("c", Long::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    private suspend fun receipts(id: UUID): Long =
        db
            .sql("SELECT count(*) AS c FROM ${schemas.metadata}.notification_receipts WHERE notification_id = :id")
            .bind("id", id)
            .map { row, _ -> row.get("c", Long::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    // USER targets only: who it reaches by name
    private suspend fun targets(id: UUID): List<UUID> =
        db
            .sql("SELECT user_id FROM ${schemas.metadata}.notification_targets WHERE notification_id = :id")
            .bind("id", id)
            .map { row, _ -> Optional.ofNullable(row.get("user_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
            .mapNotNull { it.orElse(null) }

    private suspend fun read(
        id: UUID,
        userId: UUID
    ) {
        db
            .sql("INSERT INTO ${schemas.metadata}.notification_receipts (notification_id, user_id, read_at) VALUES (:n, :u, :at)")
            .bind("n", id)
            .bind("u", userId)
            .bind("at", OffsetDateTime.now(ZoneOffset.UTC))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
