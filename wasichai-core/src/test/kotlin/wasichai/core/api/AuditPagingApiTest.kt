package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditFilter
import wasichai.core.audit.AuditPage
import wasichai.core.audit.AuditQuery
import wasichai.core.audit.AuditQueryService
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.JwtService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// issue 52 (ADR-052): GET /api/audit and the record history page by X-Next-Cursor and narrow by period and user.
// 1,200 rows of one object, in a window of 2001 nothing else writes to: 3 per second, so ties on occurred_at.
@Import(AuditPagingApiTest.ScopeConfig::class)
class AuditPagingApiTest : WasichaiIntegrationTest() {
    // a caller named here reads only the records whose grupo is A; anyone else is not restricted
    class GrupoScope : RecordReadScope {
        val scoped: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override suspend fun criterion(
            caller: AuthenticatedUser,
            definition: ObjectDefinition
        ): RecordCriterion? {
            if (caller.email !in scoped) return null
            val field = definition.fields.firstOrNull { it.name == "grupo" } ?: return null
            val column = SqlIdentifier.quote(field.columnName)
            return RecordCriterion { _, bind -> "$column = ${bind("A")}" }
        }
    }

    @TestConfiguration
    class ScopeConfig {
        @Bean
        fun grupoScope(): GrupoScope = GrupoScope()
    }

    @Autowired
    private lateinit var scope: GrupoScope

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    @Autowired
    private lateinit var audit: AuditQueryService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    private val json = JsonMapper.builder().build()

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var adminId: UUID
    private lateinit var objectName: String

    // who wrote the rows: g % 4 = 0 the administrator, 1 a service account, else someone with no users row
    private val someone = UUID.randomUUID()
    private lateinit var account: String
    private lateinit var accountId: UUID

    // the rows of one record, g % 100 = 0: twelve, all the administrator's
    private val record = UUID.randomUUID()

    // a day of 2001 per test, so no test's rows fall in another's window. microseconds, to prove the cursor keeps them
    private val base = Instant.parse("2001-02-03T04:05:06.123456Z").plus(Duration.ofDays(day.incrementAndGet().toLong()))
    private val window = "from=${base.minus(Duration.ofHours(1))}&to=${base.plus(Duration.ofHours(1))}"

    @BeforeEach
    fun setUp() {
        admin = bearer()
        val claims = runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }
        organizationId = UUID.fromString(claims.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        adminId = UUID.fromString(claims.subject)
        objectName = uniqueName("paged")
        account = uniqueName("bot")
        accountId =
            UUID.fromString(
                client
                    .post()
                    .uri("/api/service-accounts")
                    .header(HttpHeaders.AUTHORIZATION, admin)
                    .bodyValue(mapOf("name" to account, "roles" to emptyList<String>()))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
                    .substringAfter("\"id\":\"")
                    .substringBefore("\"")
            )
        runBlocking {
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.audit_log (organization_id, user_id, object_name, record_id, operation, occurred_at, source)
                    SELECT :organizationId,
                           CASE WHEN g % 4 = 0 THEN :admin WHEN g % 4 = 1 THEN :account ELSE :someone END,
                           :objectName,
                           CASE WHEN g % 100 = 0 THEN :record ELSE gen_random_uuid() END,
                           (ARRAY['CREATE', 'UPDATE', 'DELETE'])[g % 3 + 1],
                           :base + (g / 3) * interval '1 second',
                           'app'
                    FROM generate_series(1, 1200) g
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("admin", adminId)
                .bind("account", accountId)
                .bind("someone", someone)
                .bind("objectName", objectName)
                .bind("record", record)
                .bind("base", base)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `following X-Next-Cursor at limit 500 returns every row exactly once, in order, the last page without one`() {
        val all = expected("")
        assertThat(all).hasSize(1200)

        val pages = walk(admin, "/api/audit?objectName=$objectName&limit=500")
        assertThat(pages.map { it.entries.size }).containsExactly(500, 500, 200)
        assertThat(pages.map { it.next != null }).containsExactly(true, true, false)
        assertThat(pages.flatMap { it.ids() }).isEqualTo(all)

        // the tenant's rows of the window, no object named: the same 1,200, nothing else writes there
        assertThat(walk(admin, "/api/audit?$window&limit=500").flatMap { it.ids() }).isEqualTo(all)
        // a smaller page walks the same list
        assertThat(walk(admin, "/api/audit?objectName=$objectName&limit=170").flatMap { it.ids() }).isEqualTo(all)
    }

    @Test
    fun `limit keeps its 500 cap per page`() {
        val page = page(admin, "/api/audit?objectName=$objectName&limit=5000")

        assertThat(page.entries).hasSize(500)
        assertThat(page.next).isNotNull()
    }

    @Test
    fun `from and to, user and service account return only their rows and compose with the other filters`() {
        val from = base.plusSeconds(100)
        val to = base.plusSeconds(200)
        val period = "from=$from&to=$to"
        val cases =
            listOf(
                period to "AND occurred_at >= '$from' AND occurred_at < '$to'",
                "from=$from" to "AND occurred_at >= '$from'",
                "to=$to" to "AND occurred_at < '$to'",
                "userId=$adminId" to "AND user_id = '$adminId'",
                "userId=$someone" to "AND user_id = '$someone'",
                "serviceAccount=$account" to "AND user_id = '$accountId'",
                "userId=$adminId&$period&operation=UPDATE" to
                    "AND user_id = '$adminId' AND occurred_at >= '$from' AND occurred_at < '$to' AND operation = 'UPDATE'",
                "serviceAccount=$account&$period&operation=delete" to
                    "AND user_id = '$accountId' AND occurred_at >= '$from' AND occurred_at < '$to' AND operation = 'DELETE'",
                "recordId=$record&userId=$adminId" to "AND record_id = '$record' AND user_id = '$adminId'",
                "recordId=$record&userId=$someone" to "AND false",
                "userId=${UUID.randomUUID()}" to "AND false",
                "from=$to&to=$from" to "AND false"
            )

        cases.forEach { (query, where) ->
            val expected = expected(where)
            val got = walk(admin, "/api/audit?objectName=$objectName&$query&limit=500").flatMap { it.ids() }
            assertThat(got).describedAs(query).isEqualTo(expected)
        }
        assertThat(expected("AND record_id = '$record' AND user_id = '$adminId'")).hasSize(12)
        // what each entry says agrees with the filter
        page(admin, "/api/audit?objectName=$objectName&serviceAccount=$account&limit=20").entries.forEach {
            assertThat(it["serviceAccount"]).isEqualTo(account)
        }
        page(admin, "/api/audit?objectName=$objectName&$period&limit=500").entries.forEach {
            assertThat(Instant.parse(it["occurredAt"] as String)).isBetween(from, to.minusNanos(1))
        }
    }

    @Test
    fun `an offset instant reads like its Z twin, a plus left unencoded included`() {
        val minute = base.plusSeconds(60).truncatedTo(ChronoUnit.MINUTES)
        // 2001-02-04T05:06:00+01:00, the + as is in the url
        val plusOne = minute.atOffset(ZoneOffset.ofHours(1)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val z = walk(admin, "/api/audit?objectName=$objectName&from=$minute&limit=500").flatMap { it.ids() }
        val offset = walk(admin, "/api/audit?objectName=$objectName&from=$plusOne&limit=500").flatMap { it.ids() }

        assertThat(z).isNotEmpty().isEqualTo(offset)
    }

    @Test
    fun `invalid from, to, userId or after is a 400 naming the parameter`() {
        val cursor = page(admin, "/api/audit?objectName=$objectName&limit=10").next!!
        val target = uniqueName("historia")
        createObject(target)
        val history = "/api/objects/$target/records/${createRecord(target, "A")}/history"

        listOf(
            "/api/audit?from=yesterday" to "from",
            "/api/audit?to=2001-13-01T00:00:00Z" to "to",
            "/api/audit?to=2001-02-03" to "to",
            "/api/audit?userId=nobody" to "userId",
            "/api/audit?userId=1-2-3-4-5" to "userId",
            "/api/audit?objectName=$objectName&after=not-a-cursor" to "after",
            // the cursor of another filter set: here another operation, then another user
            "/api/audit?objectName=$objectName&operation=UPDATE&after=$cursor" to "after",
            "/api/audit?objectName=$objectName&userId=$adminId&after=$cursor" to "after",
            "$history?from=soon" to "from",
            "$history?userId=nobody" to "userId",
            "$history?after=not-a-cursor" to "after"
        ).forEach { (uri, name) ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo(name)
        }
    }

    @Test
    fun `without the new parameters the answer is the one it was`() {
        val all = expected("")

        val plain = page(admin, "/api/audit?objectName=$objectName")
        // newest 100, as before; the header is all that is new
        assertThat(plain.ids()).isEqualTo(all.take(100))
        assertThat(plain.next).isNotNull()
        // blank values are no filter: byte for byte the same body
        val blank = page(admin, "/api/audit?objectName=$objectName&from=&to=&userId=&serviceAccount=&after=")
        assertThat(blank.body).isEqualTo(plain.body)
        // a list that fits in one page carries no header
        val small = page(admin, "/api/audit?objectName=$objectName&recordId=$record")
        assertThat(small.entries).hasSize(12)
        assertThat(small.next).isNull()
    }

    @Test
    fun `a record's history pages and narrows the same way`() {
        val history = uniqueName("historia")
        createObject(history)
        val id = createRecord(history, "H-0")
        (1..6).forEach { updateRecord(history, id, "H-$it") }
        val path = "/api/objects/$history/records/$id/history"

        val whole = page(admin, path)
        assertThat(whole.entries).hasSize(7)
        assertThat(whole.next).isNull()
        val pages = walk(admin, "$path?limit=3")
        assertThat(pages.map { it.entries.size }).containsExactly(3, 3, 1)
        assertThat(pages.flatMap { it.ids() }).isEqualTo(whole.ids())

        assertThat(page(admin, "$path?userId=$adminId").ids()).isEqualTo(whole.ids())
        assertThat(page(admin, "$path?userId=$someone").entries).isEmpty()
        assertThat(page(admin, "$path?from=2999-01-01T00:00:00Z").entries).isEmpty()
        assertThat(page(admin, "$path?to=2001-01-01T00:00:00Z").entries).isEmpty()
        // the newest entry alone: from its own instant on
        val newest = whole.entries.first()["occurredAt"] as String
        assertThat(page(admin, "$path?from=$newest").ids()).isEqualTo(whole.ids().take(1))

        // a cursor of the tenant list does not continue a history, nor the other way round
        val listCursor = page(admin, "/api/audit?objectName=$history&recordId=$id&limit=3").next!!
        val historyCursor = pages.first().next!!
        listOf("$path?limit=3&after=$listCursor", "/api/audit?objectName=$history&recordId=$id&limit=3&after=$historyCursor").forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("after")
        }
    }

    // ADR-048 drops out-of-scope entries after the page is read: a scoped caller's pages may come short, even empty,
    // but the cursor still walks every row once, so nothing in scope is skipped or repeated
    @Test
    fun `a scoped caller walks the same pages, short ones included, and sees each in-scope entry once`() {
        val scopedObject = uniqueName("ambito")
        createObject(scopedObject)
        val created = (1..10).map { createRecord(scopedObject, if (it % 2 == 0) "A" else "B") to (it % 2 == 0) }
        val email = "${uniqueName("scoped")}@wasichai.local"
        val token = newUserWithOrganizationRead(email)
        scope.scoped += email

        val everyone = walk(admin, "/api/audit?objectName=$scopedObject&limit=3")
        assertThat(everyone.map { it.entries.size }).containsExactly(3, 3, 3, 1)
        val inScope = everyone.flatMap { it.entries }.filter { entry -> created.any { (id, a) -> a && id == entry["recordId"] } }
        assertThat(inScope).hasSize(5)

        val scoped = walk(token, "/api/audit?objectName=$scopedObject&limit=3")
        assertThat(scoped).hasSize(4)
        assertThat(scoped.map { it.entries.size }.sum()).isEqualTo(5)
        assertThat(scoped.flatMap { it.ids() }).isEqualTo(inScope.map { it["id"] })
    }

    @Test
    fun `the plan for a user and a period uses the user index`() {
        val query = AuditQuery("list", null, null, null, 500, AuditFilter(from = base, to = base.plusSeconds(60), userId = adminId))
        val (sql, bindings) = audit.select(organizationId, query, withAdmin = true)

        val plan =
            runBlocking {
                db
                    .sql("ANALYZE ${schemas.metadata}.audit_log")
                    .then()
                    .awaitFirstOrNull()
                transactions.executeAndAwait {
                    // a small test table may be cheaper to scan whole; the question is which index, not whether one
                    db
                        .sql("SET LOCAL enable_seqscan = off")
                        .then()
                        .awaitFirstOrNull()
                    var spec = db.sql("EXPLAIN $sql")
                    bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
                    spec
                        .map { row, _ -> row.get(0, String::class.java)!! }
                        .all()
                        .collectList()
                        .awaitSingle()
                        .joinToString("\n")
                }
            }

        assertThat(plan).describedAs(plan).contains("audit_log_user_time_idx")
    }

    private companion object {
        val day = AtomicInteger()
    }

    // ------------------------------------------------------------------ helpers

    private class Page(
        val body: String,
        val entries: List<Map<String, Any?>>,
        val next: String?
    ) {
        fun ids(): List<Any?> = entries.map { it["id"] }
    }

    private fun page(
        token: String,
        uri: String
    ): Page {
        val result =
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
        val body = result.responseBody!!
        return Page(
            body,
            json.readValue(body, object : TypeReference<List<Map<String, Any?>>>() {}),
            result.responseHeaders.getFirst(AuditPage.NEXT_CURSOR_HEADER)
        )
    }

    // every page, following the header until a page comes without it
    private fun walk(
        token: String,
        uri: String
    ): List<Page> {
        val pages = mutableListOf(page(token, uri))
        while (pages.last().next != null) {
            check(pages.size < 50) { "no end to $uri" }
            pages += page(token, "$uri&after=${pages.last().next}")
        }
        return pages
    }

    // this object's rows that [where] keeps, in list order. where holds only values this test made
    private fun expected(where: String): List<Any?> =
        runBlocking {
            db
                .sql(
                    "SELECT id FROM ${schemas.metadata}.audit_log WHERE organization_id = :organizationId AND object_name = :objectName $where " +
                        "ORDER BY occurred_at DESC, id DESC"
                ).bind("organizationId", organizationId)
                .bind("objectName", objectName)
                .map { row, _ -> row.get("id", UUID::class.java)!!.toString() }
                .all()
                .collectList()
                .awaitSingle()
        }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Paged", "fields" to listOf(mapOf("name" to "grupo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        target: String,
        grupo: String
    ): String =
        client
            .post()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("grupo" to grupo)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun updateRecord(
        target: String,
        id: String,
        grupo: String
    ) {
        client
            .put()
            .uri("/api/objects/$target/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("grupo" to grupo)))
            .exchange()
            .expectStatus()
            .isOk
    }

    // a person with an organization-wide READ, what /api/audit asks for, and nothing else
    private fun newUserWithOrganizationRead(email: String): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Auditor", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Scoped", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
