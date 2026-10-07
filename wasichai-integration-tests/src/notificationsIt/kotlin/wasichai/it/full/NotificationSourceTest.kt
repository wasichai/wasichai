package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordService
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.Audience
import wasichai.notifications.InboxReader
import wasichai.notifications.InboxRepository
import wasichai.notifications.InboxState
import wasichai.notifications.NotificationDraft
import wasichai.notifications.NotificationKind
import wasichai.notifications.NotificationLink
import wasichai.notifications.NotificationLoop
import wasichai.notifications.NotificationSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// an app's source, run by the module's loop: create, update in place, resolve, reopen; once per interval; purge.
// the loop is off (tick 0s): the test drives it. a source bean changes the context, so this class boots its own.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NotificationSourceTest.SourceConfig::class)
@TestPropertySource(properties = ["wasichai.notifications.tick=0s"])
class NotificationSourceTest : FullAppIntegrationTest() {
    @TestConfiguration
    class SourceConfig {
        @Bean
        fun recordsSource(records: RecordService): RecordsSource = RecordsSource(records)
    }

    // one ACTION per record of the watched object, read through RecordService as the platform (no token here)
    class RecordsSource(
        private val records: RecordService
    ) : NotificationSource {
        data class Watch(
            val objectName: String,
            val audience: List<Audience>,
            // a keyless draft: the organization's batch is refused
            val malformed: Boolean = false
        )

        override val key = KEY

        val watched = ConcurrentHashMap<UUID, Watch>()

        override suspend fun currentNotifications(
            organizationId: UUID,
            now: Instant
        ): List<NotificationDraft> {
            val watch = watched[organizationId] ?: return emptyList()
            if (watch.malformed) return listOf(NotificationDraft(NotificationKind.ACTION, "no key", watch.audience))
            val (_, rows) = records.rows(watch.objectName, RecordQuery(PageRequest(0, 200)))
            return rows.map { row ->
                val code = row.attributes["codigo"].toString()
                NotificationDraft(
                    kind = NotificationKind.ACTION,
                    title = "Vence $code el ${row.attributes["vence"]}",
                    audience = watch.audience,
                    link = NotificationLink.Record(watch.objectName, row.id),
                    key = "rec:$code"
                )
            }
        }

        companion object {
            const val KEY = "test.source"
        }
    }

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var loop: NotificationLoop

    @Autowired
    private lateinit var source: RecordsSource

    @Autowired
    private lateinit var inbox: InboxRepository

    @Autowired
    private lateinit var units: OrgUnitDirectory

    // a tenant per test: the source answers per organization, and counts stay exact
    private fun watchedTenant(): Triple<NotificationsTenant, String, String> {
        val tenant = NotificationsTenant.provision(client, db, schemas)
        val role = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ")))
        val objectName = tenant.createObject(mapOf("codigo" to "TEXT", "vence" to "DATE"))
        source.watched[tenant.organizationId] = RecordsSource.Watch(objectName, listOf(Audience.Role(role)))
        return Triple(tenant, objectName, role)
    }

    @Test
    fun `a source's notifications are created, updated in place, resolved and reopened`() {
        val (tenant, objectName, role) = watchedTenant()
        val ana = tenant.createUser(roles = listOf(role))
        val other = tenant.createUser()
        val record = tenant.createRecord(objectName, mapOf("codigo" to "S-1", "vence" to "2026-12-31"))

        assertThat(run()).isTrue()
        val created = inboxOf(tenant, ana.id, role).single()
        assertThat(created.item.title).isEqualTo("Vence S-1 el 2026-12-31")
        assertThat(created.item.source).isEqualTo(RecordsSource.KEY)
        assertThat(created.item.kind).isEqualTo(NotificationKind.ACTION)
        assertThat(inboxOf(tenant, other.id)).isEmpty()

        // nothing changed: nothing written
        val stamp = updatedAt(created.item.id)
        run()
        assertThat(updatedAt(created.item.id)).isEqualTo(stamp)

        updateRecord(tenant, objectName, record, mapOf("codigo" to "S-1", "vence" to "2027-01-15"))
        run()
        val updated = inboxOf(tenant, ana.id, role).single()
        assertThat(updated.item.id).isEqualTo(created.item.id)
        assertThat(updated.item.title).isEqualTo("Vence S-1 el 2027-01-15")

        deleteRecord(tenant, objectName, record)
        run()
        assertThat(inboxOf(tenant, ana.id, role)).isEmpty()
        assertThat(resolvedAt(created.item.id)).isNotNull()

        tenant.createRecord(objectName, mapOf("codigo" to "S-1", "vence" to "2027-02-01"))
        run()
        val reopened = inboxOf(tenant, ana.id, role).single()
        assertThat(reopened.item.id).isEqualTo(created.item.id)
        assertThat(reopened.item.title).isEqualTo("Vence S-1 el 2027-02-01")
        assertThat(resolvedAt(created.item.id)).isNull()
    }

    @Test
    fun `the loop runs a source once per interval`() {
        val (tenant, objectName, _) = watchedTenant()
        tenant.createRecord(objectName, mapOf("codigo" to "S-1", "vence" to "2026-12-31"))
        run()
        val last = lastRun()!!
        assertThat(keysOf(tenant)).containsExactly("rec:S-1")

        tenant.createRecord(objectName, mapOf("codigo" to "S-2", "vence" to "2026-12-31"))
        runBlocking { loop.runOnce(last.plus(Duration.ofMinutes(1))) }
        assertThat(keysOf(tenant)).containsExactly("rec:S-1")
        assertThat(lastRun()).isEqualTo(last)

        runBlocking { loop.runOnce(last.plus(Duration.ofMinutes(15))) }
        assertThat(keysOf(tenant)).containsExactlyInAnyOrder("rec:S-1", "rec:S-2")
        assertThat(lastRun()).isEqualTo(last.plus(Duration.ofMinutes(15)))
    }

    @Test
    fun `a malformed batch is refused for its organization and the others still run`() {
        val (broken, brokenObject, brokenRole) = watchedTenant()
        val (fine, fineObject, _) = watchedTenant()
        broken.createRecord(brokenObject, mapOf("codigo" to "S-1", "vence" to "2026-12-31"))
        run()
        assertThat(keysOf(broken)).containsExactly("rec:S-1")

        source.watched[broken.organizationId] = RecordsSource.Watch(brokenObject, listOf(Audience.Role(brokenRole)), malformed = true)
        fine.createRecord(fineObject, mapOf("codigo" to "F-1", "vence" to "2026-12-31"))
        assertThat(run()).isTrue()

        // refused: what was open stays open, nothing keyless was written
        assertThat(keysOf(broken)).containsExactly("rec:S-1")
        assertThat(keysOf(fine)).containsExactly("rec:F-1")
    }

    @Test
    fun `the daily purge deletes what ended before the retention`() {
        val (tenant, objectName, _) = watchedTenant()
        val old = tenant.createRecord(objectName, mapOf("codigo" to "OLD", "vence" to "2026-12-31"))
        tenant.createRecord(objectName, mapOf("codigo" to "OPEN", "vence" to "2026-12-31"))
        run()
        deleteRecord(tenant, objectName, old)
        run()
        val oldId = idOf(tenant, "rec:OLD")
        sql("UPDATE ${schemas.metadata}.notifications SET resolved_at = now() - interval '100 days' WHERE id = :id", "id", oldId)
        // the purge is due whatever ran before in this database
        sql("DELETE FROM ${schemas.metadata}.notification_source_runs WHERE source = :source", "source", "purge")

        runBlocking { loop.runOnce() }

        assertThat(keysOf(tenant)).containsExactly("rec:OPEN")
        assertThat(purgedAt()).isNotNull()
    }

    private fun run(): Boolean = runBlocking { loop.runSource(RecordsSource.KEY) }

    private fun inboxOf(
        tenant: NotificationsTenant,
        userId: UUID,
        vararg roles: String
    ) = runBlocking {
        val reader = InboxReader(tenant.organizationId, userId, roles.toList(), units.closureOf(tenant.organizationId, userId))
        inbox.page(reader, null, InboxState.ACTIVE, Instant.now(), PageRequest(0, 50)).content
    }

    // every notification of the source in the tenant, open or resolved
    private fun keysOf(tenant: NotificationsTenant): List<String> =
        runBlocking {
            db
                .sql("SELECT source_key FROM ${schemas.metadata}.notifications WHERE organization_id = :org AND source = :source")
                .bind("org", tenant.organizationId)
                .bind("source", RecordsSource.KEY)
                .map { row, _ -> Rows.string(row, "source_key") }
                .all()
                .collectList()
                .awaitSingle()
        }

    private fun idOf(
        tenant: NotificationsTenant,
        key: String
    ): UUID =
        runBlocking {
            db
                .sql("SELECT id FROM ${schemas.metadata}.notifications WHERE organization_id = :org AND source = :source AND source_key = :key")
                .bind("org", tenant.organizationId)
                .bind("source", RecordsSource.KEY)
                .bind("key", key)
                .map { row, _ -> Rows.uuid(row, "id") }
                .one()
                .awaitSingle()
        }

    private fun updatedAt(id: UUID): Instant? = column(id, "updated_at")

    private fun resolvedAt(id: UUID): Instant? = column(id, "resolved_at")

    private fun column(
        id: UUID,
        column: String
    ): Instant? =
        runBlocking {
            db
                .sql("SELECT $column AS at FROM ${schemas.metadata}.notifications WHERE id = :id")
                .bind("id", id)
                .map { row, _ -> Optional(Rows.instantOrNull(row, "at")) }
                .one()
                .awaitSingle()
                .value
        }

    private fun lastRun(): Instant? = runOf(RecordsSource.KEY)

    private fun purgedAt(): Instant? = runOf("purge")

    private fun runOf(key: String): Instant? =
        runBlocking {
            db
                .sql("SELECT last_run_at FROM ${schemas.metadata}.notification_source_runs WHERE source = :source")
                .bind("source", key)
                .map { row, _ -> Optional(Rows.instantOrNull(row, "last_run_at")) }
                .all()
                .collectList()
                .awaitSingle()
                .singleOrNull()
                ?.value
        }

    private fun sql(
        statement: String,
        name: String,
        value: Any
    ) {
        runBlocking {
            db
                .sql(statement)
                .bind(name, value)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun updateRecord(
        tenant: NotificationsTenant,
        objectName: String,
        id: UUID,
        attributes: Map<String, Any?>
    ) {
        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun deleteRecord(
        tenant: NotificationsTenant,
        objectName: String,
        id: UUID
    ) {
        client
            .delete()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    // r2dbc maps no null rows
    private data class Optional<T>(
        val value: T?
    )
}
