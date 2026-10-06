package wasichai.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import wasichai.test.WasichaiTestDatabase
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// the module's sql against a real postgres. every test works in an organization of its own, so counts are exact.
class NotificationStorageTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var preparer: NotificationPreparer

    @Autowired
    private lateinit var writer: NotificationWriter

    @Autowired
    private lateinit var notifications: NotificationRepository

    @Autowired
    private lateinit var inbox: InboxRepository

    @Autowired
    private lateinit var units: OrgUnitDirectory

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val source = "it.storage"

    @Test
    fun `an upsert follows the spec's table`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val reader = InboxReader(org, ana, emptyList(), emptyList())
            val draft = NotificationDraft(NotificationKind.WARNING, "Vence pronto", listOf(Audience.All), key = "k1")

            val created = writer.publish(org, source, prepare(org, draft), ana)
            assertThat(created.outcome).isEqualTo(UpsertOutcome.CREATED)
            val first = notifications.findById(org, created.id)!!
            assertThat(first.createdBy).isEqualTo(ana)
            assertThat(first.key).isEqualTo("k1")

            // same content: nothing written
            val unchanged = writer.publish(org, source, prepare(org, draft), ana)
            assertThat(unchanged).isEqualTo(UpsertResult(created.id, UpsertOutcome.UNCHANGED))
            assertThat(notifications.findById(org, created.id)!!.updatedAt).isEqualTo(first.updatedAt)

            // other wording, same kind: updated, receipts kept, publish_at kept
            assertThat(inbox.markRead(reader, created.id, Instant.now())).isTrue()
            val reworded = writer.publish(org, source, prepare(org, draft.copy(title = "Vence mañana")), ana)
            assertThat(reworded).isEqualTo(UpsertResult(created.id, UpsertOutcome.UPDATED))
            val updated = notifications.findById(org, created.id)!!
            assertThat(updated.title).isEqualTo("Vence mañana")
            assertThat(updated.publishAt).isEqualTo(first.publishAt)
            assertThat(updated.updatedAt).isAfter(first.updatedAt)
            assertThat(updated.readCount).isEqualTo(1)

            // WARNING -> ACTION is news: receipts go
            val escalated = writer.publish(org, source, prepare(org, draft.copy(title = "Vence mañana", kind = NotificationKind.ACTION)), ana)
            assertThat(escalated.outcome).isEqualTo(UpsertOutcome.UPDATED)
            assertThat(notifications.findById(org, created.id)!!.readCount).isZero()

            // resolved, then reported again: reopened, receipts reset, published anew
            inbox.markRead(reader, created.id, Instant.now())
            assertThat(writer.resolve(org, source, "k1")).isTrue()
            assertThat(writer.resolve(org, source, "k1")).isFalse()
            assertThat(notifications.findById(org, created.id)!!.resolvedAt).isNotNull()
            val reopened = writer.publish(org, source, prepare(org, draft.copy(title = "Vence mañana", kind = NotificationKind.ACTION)), ana)
            assertThat(reopened).isEqualTo(UpsertResult(created.id, UpsertOutcome.REOPENED))
            val back = notifications.findById(org, created.id)!!
            assertThat(back.resolvedAt).isNull()
            assertThat(back.readCount).isZero()
            assertThat(back.publishAt).isAfter(first.publishAt)

            // no key: always a new row
            val keyless = draft.copy(key = null)
            val one = writer.publish(org, source, prepare(org, keyless), null).id
            val two = writer.publish(org, source, prepare(org, keyless), null).id
            assertThat(one).isNotEqualTo(two)
            assertThat(writer.resolveAll(org, source)).isEqualTo(3)
        }

    @Test
    fun `two writers racing on one key end with one row`(): Unit =
        runBlocking {
            val org = organization()
            val first = prepare(org, NotificationDraft(NotificationKind.INFO, "Primero", listOf(Audience.All), key = "race"))
            val second = prepare(org, NotificationDraft(NotificationKind.INFO, "Segundo", listOf(Audience.All), key = "race"))
            val inserted = CompletableDeferred<Unit>()

            val (a, b) =
                coroutineScope {
                    // a inserts and holds its transaction open; b meets it on the unique key and waits
                    val a =
                        async {
                            transactions.executeAndAwait {
                                val result = notifications.upsert(org, source, first, null, Instant.now())
                                inserted.complete(Unit)
                                delay(700)
                                result
                            }
                        }
                    inserted.await()
                    val b = async { writer.publish(org, source, second, null) }
                    a.await() to b.await()
                }

            assertThat(a.outcome).isEqualTo(UpsertOutcome.CREATED)
            assertThat(b).isEqualTo(UpsertResult(a.id, UpsertOutcome.UPDATED))
            assertThat(rows(org)).isEqualTo(1)
            assertThat(notifications.findById(org, a.id)!!.title).isEqualTo("Segundo")
        }

    @Test
    fun `reconcile creates, updates, reopens and resolves what is missing`(): Unit =
        runBlocking {
            val org = organization()

            fun d(
                key: String,
                title: String = key
            ) = NotificationDraft(NotificationKind.ACTION, title, listOf(Audience.All), key = key)

            assertThat(writer.reconcile(org, source, listOf(prepare(org, d("a")), prepare(org, d("b"))))).isEqualTo(ReconcileResult(2, 0, 0, 0))
            // a keyless row of the source is not part of the set: resolved too
            writer.publish(org, source, prepare(org, d("x").copy(key = null)), null)
            assertThat(writer.reconcile(org, source, listOf(prepare(org, d("a", "a2")), prepare(org, d("c")))))
                .isEqualTo(ReconcileResult(created = 1, updated = 1, reopened = 0, resolved = 2))
            assertThat(writer.reconcile(org, source, listOf(prepare(org, d("b"))))).isEqualTo(ReconcileResult(0, 0, 1, 2))
            assertThat(writer.reconcile(org, source, listOf(prepare(org, d("b"))))).isEqualTo(ReconcileResult(0, 0, 0, 0))
            assertThat(notifications.openBySource(org, source, null).map { it.key }).containsExactly("b")
            assertThat(notifications.openBySource(org, source, listOf("a")).map { it.key }).containsExactlyInAnyOrder("a", "b")

            assertThatThrownBy { runBlocking { writer.reconcile(org, source, listOf(prepare(org, d("b")), prepare(org, d("b", "otra")))) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { writer.reconcile(org, source, listOf(prepare(org, d("b").copy(key = null)))) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            // another source is not touched
            assertThat(writer.reconcile(org, "it.other", emptyList())).isEqualTo(ReconcileResult(0, 0, 0, 0))
            assertThat(notifications.openBySource(org, source, null)).hasSize(1)
        }

    @Test
    fun `a reader sees what is addressed to them, in its window, in their organization`(): Unit =
        runBlocking {
            val org = organization()
            val role = role(org, "CAJERO_${uniqueName("").uppercase()}")
            val ana = user(org, role)
            val bob = user(org)
            val gerencia = unit(org, null)
            val area = unit(org, gerencia)
            member(ana, area)
            val now = Instant.now()

            suspend fun publish(
                audience: List<Audience>,
                publishAt: Instant? = null,
                expiresAt: Instant? = null,
                orgId: UUID = org
            ) = writer
                .publish(
                    orgId,
                    source,
                    prepare(orgId, NotificationDraft(NotificationKind.INFO, "n", audience, publishAt = publishAt, expiresAt = expiresAt)),
                    null
                ).id

            val all = publish(listOf(Audience.All))
            val toAna = publish(listOf(Audience.User(ana)))
            val toRole = publish(listOf(Audience.Role(role)))
            // a unit above ana's reaches her
            val toGerencia = publish(listOf(Audience.Unit(code(gerencia))))
            val toBob = publish(listOf(Audience.User(bob)))
            val scheduled = publish(listOf(Audience.All), publishAt = now.plus(Duration.ofHours(1)))
            val expired = publish(listOf(Audience.All), publishAt = now.minus(Duration.ofHours(2)), expiresAt = now.minus(Duration.ofHours(1)))
            val resolved = writer.publish(org, source, prepare(org, NotificationDraft(NotificationKind.INFO, "r", listOf(Audience.All), key = "r")), null).id
            writer.resolve(org, source, "r")
            val elsewhere = publish(listOf(Audience.All), orgId = organization())
            val dismissed = publish(listOf(Audience.All))
            val snoozed = publish(listOf(Audience.All))

            val reader = reader(org, ana, role)
            val readAt = Instant.now()
            assertThat(inbox.dismiss(reader, dismissed, readAt)).isTrue()
            assertThat(inbox.snooze(reader, snoozed, readAt.plus(Duration.ofHours(1)), readAt)).isTrue()

            assertThat(ids(reader, InboxState.ACTIVE)).containsExactlyInAnyOrder(all, toAna, toRole, toGerencia)
            assertThat(ids(reader, InboxState.SNOOZED)).containsExactly(snoozed)
            // receipts are visibility-checked: a stranger's id writes nothing
            listOf(toBob, scheduled, expired, resolved, elsewhere).forEach { id ->
                assertThat(inbox.visible(reader, id, Instant.now())).describedAs("$id").isNull()
                assertThat(inbox.markRead(reader, id, Instant.now())).isFalse()
            }
            assertThat(inbox.visible(reader, dismissed, Instant.now())).isEqualTo(VisibleRow(dismissed, NotificationKind.INFO, source))
            // without the role or the unit, only what names ana or everyone
            assertThat(ids(InboxReader(org, ana, emptyList(), emptyList()), InboxState.ACTIVE)).containsExactlyInAnyOrder(all, toAna)
            assertThat(ids(reader(org, bob), InboxState.ACTIVE)).containsExactlyInAnyOrder(all, toBob, dismissed, snoozed)
            // the snooze ends: back in the active list
            assertThat(ids(reader, InboxState.ACTIVE, readAt.plus(Duration.ofHours(2)))).contains(snoozed).doesNotContain(dismissed)
        }

    @Test
    fun `the summary counts per kind, and read-all marks what is active`(): Unit =
        runBlocking {
            val org = organization()
            val ana = user(org)
            val reader = reader(org, ana)
            val now = Instant.now()

            suspend fun publish(
                kind: NotificationKind,
                dueAt: Instant? = null,
                publishAt: Instant? = null
            ) = writer.publish(org, source, prepare(org, NotificationDraft(kind, "$kind", listOf(Audience.All), dueAt = dueAt, publishAt = publishAt)), null).id

            publish(NotificationKind.INFO, publishAt = now.minus(Duration.ofMinutes(5)))
            val overdue = publish(NotificationKind.ACTION, dueAt = now.minus(Duration.ofDays(1)), publishAt = now.minus(Duration.ofMinutes(4)))
            val later = publish(NotificationKind.ACTION, dueAt = now.plus(Duration.ofDays(3)), publishAt = now.minus(Duration.ofMinutes(3)))
            val undated = publish(NotificationKind.ACTION, publishAt = now.minus(Duration.ofMinutes(2)))
            val warning =
                writer
                    .publish(
                        org,
                        source,
                        prepare(
                            org,
                            NotificationDraft(
                                NotificationKind.WARNING,
                                "w",
                                listOf(Audience.All),
                                link = NotificationLink.Url("https://a.pe/x.pdf"),
                                publishAt = now.minus(Duration.ofMinutes(1))
                            )
                        ),
                        null
                    ).id
            inbox.markRead(reader, later, Instant.now())

            val summary = inbox.summary(reader, Instant.now())
            assertThat(summary.kinds[NotificationKind.INFO]).isEqualTo(KindCount(1, 1, 0))
            assertThat(summary.kinds[NotificationKind.WARNING]).isEqualTo(KindCount(1, 1, 0))
            assertThat(summary.kinds[NotificationKind.ACTION]).isEqualTo(KindCount(3, 2, 1))
            assertThat(summary.latest!!.id).isEqualTo(warning)
            assertThat(summary.latest!!.link).isEqualTo(LinkJson("URL", url = "https://a.pe/x.pdf"))

            // ACTION is a to-do list: due first, undated last
            val actions = inbox.page(reader, NotificationKind.ACTION, InboxState.ACTIVE, Instant.now(), PageRequest(0, 10))
            assertThat(actions.content.map { it.item.id }).containsExactly(overdue, later, undated)
            assertThat(actions.totalElements).isEqualTo(3)
            assertThat(actions.content.map { it.item.overdue }).containsExactly(true, false, false)
            // an ACTION of a source is not dismissible
            assertThat(actions.content.map { it.item.dismissible }).containsOnly(false)
            assertThat(inbox.page(reader, null, InboxState.UNREAD, Instant.now(), PageRequest(0, 2)).totalElements).isEqualTo(4)

            assertThat(inbox.readAll(reader, NotificationKind.ACTION, Instant.now())).isEqualTo(2)
            assertThat(inbox.summary(reader, Instant.now()).kinds[NotificationKind.ACTION]).isEqualTo(KindCount(3, 0, 1))
            assertThat(inbox.readAll(reader, null, Instant.now())).isEqualTo(2)
            assertThat(
                inbox
                    .summary(reader, Instant.now())
                    .kinds.values
                    .sumOf { it.unread }
            ).isZero()
        }

    @Test
    fun `admin list filters by source, kind, status and unit`(): Unit =
        runBlocking {
            val org = organization()
            val now = Instant.now()
            val sgft = unit(org, null)
            val open = writer.publish(org, source, prepare(org, NotificationDraft(NotificationKind.INFO, "o", listOf(Audience.Unit(code(sgft))))), null).id
            val scheduled =
                writer
                    .publish(
                        org,
                        source,
                        prepare(org, NotificationDraft(NotificationKind.ACTION, "s", listOf(Audience.All), publishAt = now.plusSeconds(3600))),
                        null
                    ).id
            val ended = writer.publish(org, "it.other", prepare(org, NotificationDraft(NotificationKind.INFO, "e", listOf(Audience.All), key = "e")), null).id
            writer.resolve(org, "it.other", "e")

            suspend fun ids(
                source: String? = null,
                kind: NotificationKind? = null,
                status: NotificationStatus? = null,
                unit: UUID? = null
            ) = notifications.adminPage(org, source, kind, status, unit, Instant.now(), PageRequest(0, 50)).content.map { it.id }

            assertThat(ids()).containsExactly(ended, scheduled, open)
            assertThat(ids(source = source)).containsExactly(scheduled, open)
            assertThat(ids(kind = NotificationKind.ACTION)).containsExactly(scheduled)
            assertThat(ids(status = NotificationStatus.OPEN)).containsExactly(open)
            assertThat(ids(status = NotificationStatus.SCHEDULED)).containsExactly(scheduled)
            assertThat(ids(status = NotificationStatus.ENDED)).containsExactly(ended)
            assertThat(ids(unit = sgft)).containsExactly(open)
            assertThat(notifications.targetsOf(org, listOf(open, scheduled))).isEqualTo(
                mapOf(open to listOf(StoredTarget.unit(sgft)), scheduled to listOf(StoredTarget.all()))
            )
            // another organization sees none of it
            val other = organization()
            assertThat(notifications.findById(other, open)).isNull()
            assertThat(notifications.targetsOf(other, listOf(open))).isEmpty()
            assertThat(writer.delete(other, open)).isFalse()
            assertThat(writer.delete(org, open)).isTrue()
            assertThat(notifications.findById(org, open)).isNull()
        }

    @Test
    fun `strict prepare names every problem, lenient drops recipients or answers null`(): Unit =
        runBlocking {
            val org = organization()
            val now = Instant.now()
            val draft =
                NotificationDraft(
                    NotificationKind.INFO,
                    "t",
                    listOf(Audience.Email("nadie@x.pe"), Audience.All),
                    link = NotificationLink.Record("no_such_object", UUID.randomUUID())
                )

            assertThatThrownBy { runBlocking { preparer.prepare(org, source, draft, now, strict = true) } }
                .isInstanceOf(ValidationException::class.java)
                .extracting { (it as ValidationException).violations }
                .isEqualTo(
                    listOf(FieldViolation("link.object", "unknown object 'no_such_object'"), FieldViolation("audience[0]", "unknown email 'nadie@x.pe'"))
                )
            assertThatThrownBy { runBlocking { preparer.prepare(org, source, draft, now, strict = false) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("link.object")

            val lenient = preparer.prepare(org, source, draft.copy(link = null), now, strict = false)!!
            assertThat(lenient.targets).containsExactly(StoredTarget.all())
            assertThat(preparer.prepare(org, source, draft.copy(link = null, audience = listOf(Audience.Email("nadie@x.pe"))), now, strict = false)).isNull()
            // a reserved source only for the module itself
            assertThatThrownBy { runBlocking { preparer.prepare(org, Sources.MANUAL, draft.copy(link = null), now, strict = false) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(preparer.prepare(org, Sources.MANUAL, draft.copy(link = null), now, strict = false, allowReservedSource = true)).isNotNull()
        }

    @Test
    fun `a write signals its organization on commit, and nothing on rollback`(): Unit =
        runBlocking {
            val org = organization()
            val p = WasichaiTestDatabase.properties().mapKeys { it.key.removePrefix("wasichai.database.") }
            val url = "jdbc:postgresql://${p["host"]}:${p["port"]}/${p["name"]}"
            DriverManager.getConnection(url, p["username"], p["password"]).use { connection ->
                connection.createStatement().use { it.execute("LISTEN \"${NotificationChannel.name(schemas)}\"") }
                val listener = connection.unwrap(PGConnection::class.java)

                fun ours(): List<String> {
                    // timeout in ms; other tests' signals carry other organizations
                    val heard = listener.getNotifications(1500).orEmpty()
                    return heard.map { it.parameter }.filter { org.toString() in it }
                }

                val draft = NotificationDraft(NotificationKind.INFO, "t", listOf(Audience.All), key = "k")
                assertThatThrownBy {
                    runBlocking {
                        transactions.executeAndAwait {
                            writer.publish(org, source, prepare(org, draft), null)
                            error("the caller fails")
                        }
                    }
                }.hasMessage("the caller fails")
                assertThat(rows(org)).isZero()
                assertThat(ours()).isEmpty()

                writer.publish(org, source, prepare(org, draft), null)
                assertThat(ours()).containsExactly("""{"o":"$org"}""")

                // unchanged: no write, no signal
                writer.publish(org, source, prepare(org, draft), null)
                assertThat(ours()).isEmpty()

                // a receipt signals its reader only
                val ana = user(org)
                val id = notifications.openBySource(org, source, null).single().id
                inbox.markRead(reader(org, ana), id, Instant.now())
                assertThat(ours()).containsExactly("""{"o":"$org","u":"$ana"}""")
            }
        }

    @Test
    fun `purge deletes what ended before the cut, in every organization`(): Unit =
        runBlocking {
            val org = organization()
            val now = Instant.now()
            val old =
                writer
                    .publish(
                        org,
                        source,
                        prepare(
                            org,
                            NotificationDraft(
                                NotificationKind.INFO,
                                "old",
                                listOf(Audience.All),
                                publishAt = now.minus(Duration.ofDays(9)),
                                expiresAt = now.minus(Duration.ofDays(8))
                            )
                        ),
                        null
                    ).id
            val fresh = writer.publish(org, source, prepare(org, NotificationDraft(NotificationKind.INFO, "fresh", listOf(Audience.All))), null).id

            assertThat(notifications.purge(now.minus(Duration.ofDays(7)))).isGreaterThanOrEqualTo(1)
            assertThat(notifications.findById(org, old)).isNull()
            assertThat(notifications.findById(org, fresh)).isNotNull()
        }

    // ---- fixtures: straight to the tables, in an organization of the test's own ----

    private suspend fun prepare(
        org: UUID,
        draft: NotificationDraft
    ): PreparedNotification = preparer.prepare(org, source, draft, Instant.now().truncatedTo(ChronoUnit.MICROS), strict = true)!!

    private suspend fun ids(
        reader: InboxReader,
        state: InboxState,
        now: Instant = Instant.now()
    ): List<UUID> = inbox.page(reader, null, state, now, PageRequest(0, 100)).content.map { it.item.id }

    private suspend fun reader(
        org: UUID,
        user: UUID,
        vararg roles: String
    ) = InboxReader(org, user, roles.toList(), units.closureOf(org, user))

    private suspend fun rows(org: UUID): Long =
        db
            .sql("SELECT count(*) AS n FROM ${schemas.metadata}.notifications WHERE organization_id = :org")
            .bind("org", org)
            .map { row, _ -> Rows.long(row, "n") }
            .one()
            .awaitSingle()

    private suspend fun organization(): UUID {
        val slug = uniqueName("org")
        return db
            .sql("INSERT INTO ${schemas.metadata}.organizations (name, slug) VALUES (:slug, :slug) RETURNING id")
            .bind("slug", slug)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()
    }

    private suspend fun user(
        org: UUID,
        vararg roles: String
    ): UUID {
        val email = "${uniqueName("p")}@x.test"
        val id =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.users (organization_id, email, password_hash, display_name) " +
                        "VALUES (:org, :email, 'x', :email) RETURNING id"
                ).bind("org", org)
                .bind("email", email)
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

    private suspend fun role(
        org: UUID,
        name: String
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

    private val codes = mutableMapOf<UUID, String>()

    private fun code(unit: UUID): String = codes.getValue(unit)

    private suspend fun unit(
        org: UUID,
        parent: UUID?
    ): UUID {
        val code = "U_" + uniqueName("").uppercase()
        val spec =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.org_units (organization_id, parent_id, code, label) " +
                        "VALUES (:org, :parent, :code, :code) RETURNING id"
                ).bind("org", org)
                .bind("code", code)
        val bound = parent?.let { spec.bind("parent", it) } ?: spec.bindNull("parent", UUID::class.java)
        return bound
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()
            .also { codes[it] = code }
    }

    private suspend fun member(
        user: UUID,
        unit: UUID
    ) {
        db
            .sql("INSERT INTO ${schemas.metadata}.user_org_units (user_id, unit_id) VALUES (:user, :unit)")
            .bind("user", user)
            .bind("unit", unit)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
