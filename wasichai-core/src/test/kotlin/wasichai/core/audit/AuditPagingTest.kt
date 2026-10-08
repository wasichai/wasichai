package wasichai.core.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.Base64
import java.util.UUID

// issue 52 (ADR-052): the audit list pages by (occurred_at DESC, id DESC) and narrows by period and user
class AuditPagingTest {
    private val organizationId = UUID.randomUUID()
    private val user = UUID.fromString("7d1c2f1e-0b4a-4c1e-9f3d-2a5b6c7d8e9f")
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val to = Instant.parse("2026-10-08T00:00:00Z")

    private val service =
        AuditQueryService(
            mock(DatabaseClient::class.java),
            JsonMapper.builder().build(),
            mock(CurrentUser::class.java),
            mock(MetadataService::class.java),
            mock(AccessPolicy::class.java),
            WasichaiSchemas("wasichai", "app_data"),
            mock(AuditRecordScope::class.java)
        )

    private fun query(
        filter: AuditFilter = AuditFilter(),
        limit: Int? = null,
        operation: String? = null
    ) = AuditQuery("list", "predio", null, operation, limit, filter)

    @Test
    fun `without the new parameters the sql is the one it was, one row past the page`() {
        val (sql, bindings) = service.select(organizationId, query(), withAdmin = true)

        assertThat(sql).doesNotContain(":from", ":to", ":userId", ":serviceAccount", ":afterAt", ":afterId")
        assertThat(sql).contains("ORDER BY a.occurred_at DESC, a.id DESC\nLIMIT :limit")
        assertThat(bindings).containsOnlyKeys("organizationId", "objectName", "operation", "limit")
        // default 100, so 101 read
        assertThat(bindings["limit"]).isEqualTo(101)
    }

    @Test
    fun `the limit keeps its 500 cap per page`() {
        assertThat(service.select(organizationId, query(limit = 5000), withAdmin = true).second["limit"]).isEqualTo(501)
        assertThat(service.select(organizationId, query(limit = 0), withAdmin = true).second["limit"]).isEqualTo(2)
    }

    @Test
    fun `period and user bind as values, never as text`() {
        val filter = AuditFilter(from = from, to = to, userId = user, serviceAccount = " etl ")
        val (sql, bindings) = service.select(organizationId, query(filter), withAdmin = true)

        assertThat(sql)
            .contains("AND a.occurred_at >= :from")
            .contains("AND a.occurred_at < :to")
            .contains("AND a.user_id = :userId")
            .contains("AND sa.name = :serviceAccount")
            .doesNotContain(user.toString(), "2026-10", "etl")
        assertThat(bindings)
            .containsEntry("from", from)
            .containsEntry("to", to)
            .containsEntry("userId", user)
            .containsEntry("serviceAccount", "etl")
    }

    @Test
    fun `a blank service account is no filter`() {
        val (sql, bindings) = service.select(organizationId, query(AuditFilter(serviceAccount = "  ")), withAdmin = true)

        assertThat(sql).doesNotContain(":serviceAccount")
        assertThat(bindings).doesNotContainKey("serviceAccount")
    }

    @Test
    fun `a cursor resumes strictly after its row in list order`() {
        val at = Instant.parse("2026-10-05T10:11:12.123456Z")
        val id = UUID.randomUUID()
        val cursor = AuditCursor(query().filters(), at, id)
        val (sql, bindings) = service.select(organizationId, query(), withAdmin = true, cursor)

        assertThat(sql).contains("AND a.occurred_at <= :afterAt AND (a.occurred_at < :afterAt OR a.id < :afterId)")
        assertThat(bindings).containsEntry("afterAt", at).containsEntry("afterId", id)
    }

    @Test
    fun `a cursor round-trips, microseconds included`() {
        val filters = query().filters()
        val cursor = AuditCursor(filters, Instant.parse("2001-02-03T04:05:06.123456Z"), UUID.randomUUID())

        assertThat(AuditCursor.decode(cursor.encode(), filters)).isEqualTo(cursor)
    }

    @Test
    fun `every filter, and the route, is part of the cursor's filter set`() {
        val base = query()
        val others =
            listOf(
                base.copy(kind = "history"),
                base.copy(objectName = "persona"),
                base.copy(recordId = UUID.randomUUID()),
                base.copy(operation = "UPDATE"),
                base.copy(filter = AuditFilter(correlationId = "req-1")),
                base.copy(filter = AuditFilter(source = "api")),
                base.copy(filter = AuditFilter(from = from)),
                base.copy(filter = AuditFilter(to = from)),
                base.copy(filter = AuditFilter(userId = user)),
                base.copy(filter = AuditFilter(serviceAccount = "etl"))
            )

        assertThat(others.map { it.filters() }).doesNotContain(base.filters()).doesNotHaveDuplicates()
        // the page size is not a filter, and a filter is the same however it is spelled
        assertThat(base.copy(limit = 7).filters()).isEqualTo(base.filters())
        assertThat(base.copy(operation = " update ").filters()).isEqualTo(base.copy(operation = "UPDATE").filters())
        assertThat(base.copy(filter = AuditFilter(source = " ")).filters()).isEqualTo(base.filters())
    }

    @Test
    fun `a cursor of another filter set, or none at all, is a 400 on after`() {
        val cursor = AuditCursor(query().filters(), from, UUID.randomUUID()).encode()
        val other = query(operation = "DELETE").filters()
        val notOne = Base64.getUrlEncoder().encodeToString("1\nx\nnot-an-instant\nnope".toByteArray())

        listOf<() -> Unit>(
            { AuditCursor.decode(cursor, other) },
            { AuditCursor.decode("not a cursor", other) },
            { AuditCursor.decode(notOne, other) },
            { AuditCursor.decode("", other) }
        ).forEach { decode ->
            assertThatThrownBy { decode() }
                .isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).violations.single().field).isEqualTo("after") })
        }
    }

    @Test
    fun `instants are ISO-8601, with Z or an offset, even one whose plus became a space`() {
        assertThat(AuditController.instant("from", "2026-10-01T00:00:00Z")).isEqualTo(from)
        assertThat(AuditController.instant("from", "2026-10-01T02:00:00+02:00")).isEqualTo(from)
        assertThat(AuditController.instant("from", "2026-10-01T02:00:00 02:00")).isEqualTo(from)
        assertThat(AuditController.instant("from", "2026-09-30T19:00:00-05:00")).isEqualTo(from)
        assertThat(AuditController.instant("from", " ")).isNull()
        assertThat(AuditController.instant("from", null)).isNull()
    }

    @Test
    fun `a malformed instant or user id is a 400 naming its parameter`() {
        listOf(
            "from" to { AuditController.instant("from", "yesterday") },
            "to" to { AuditController.instant("to", "2026-10-01") },
            "userId" to { AuditController.uuid("userId", "not-a-uuid") },
            "userId" to { AuditController.uuid("userId", "1-2-3-4-5") }
        ).forEach { (name, parse) ->
            assertThatThrownBy { parse() }
                .isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).violations.single().field).isEqualTo(name) })
        }
        assertThat(AuditController.uuid("userId", user.toString().uppercase())).isEqualTo(user)
    }
}
