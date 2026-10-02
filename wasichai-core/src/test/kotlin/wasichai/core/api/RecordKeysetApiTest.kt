package wasichai.core.api

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.util.UriUtils
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

data class KeysetPage(
    val ids: List<String>,
    val totalElements: Long?,
    val totalPages: Int?,
    val nextCursor: String?
)

// issue 21: a list that skips the count, and keyset reads that see every row exactly once
class RecordKeysetApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private lateinit var name: String
    private lateinit var token: String
    private lateinit var all: Set<String>

    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun createRecords() {
        name = uniqueName("arbitrio")
        token = bearer()
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Arbitrio",
                    "fields" to listOf(mapOf("name" to "grupo", "type" to "TEXT"), mapOf("name" to "monto", "type" to "INTEGER", "required" to true))
                )
            ).exchange()
            .expectStatus()
            .isCreated

        // one statement, one transaction: all 23 rows share created_at, most share grupo, some have none
        runBlocking {
            val table =
                db
                    .sql("SELECT physical_table, organization_id FROM ${schemas.metadata}.custom_objects WHERE name = :name")
                    .bind("name", name)
                    .map { row, _ -> row.get("physical_table", String::class.java)!! to row.get("organization_id", UUID::class.java)!! }
                    .one()
                    .awaitFirstOrNull()!!
            db
                .sql(
                    "INSERT INTO ${schemas.dataTable(table.first)} (organization_id, grupo, monto) " +
                        "SELECT :org, CASE WHEN g % 5 = 0 THEN NULL WHEN g % 2 = 0 THEN 'A' ELSE 'B' END, g % 3 " +
                        "FROM generate_series(1, 23) g"
                ).bind("org", table.second)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }
        all = readAll("")
        assertThat(all).hasSize(23)
    }

    @Test
    fun `a list without its count says so, and the default still counts`() {
        val uncounted = page("/api/objects/$name/records?size=5&count=false")
        assertThat(uncounted.totalElements).isNull()
        assertThat(uncounted.totalPages).isNull()
        assertThat(uncounted.ids).hasSize(5)
        assertThat(uncounted.nextCursor).isNotNull()

        val counted = page("/api/objects/$name/records?size=5")
        assertThat(counted.totalElements).isEqualTo(23)
        assertThat(counted.totalPages).isEqualTo(5)

        // the last page has nothing after it, and says so by leaving the cursor out
        client
            .get()
            .uri("/api/objects/$name/records?size=5&page=4")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content.length()")
            .isEqualTo(3)
            .jsonPath("$.nextCursor")
            .doesNotExist()
    }

    @Test
    fun `keyset reads see every row exactly once, ties and nulls included, in every sort`() {
        listOf("", "&dir=desc", "&sort=grupo", "&sort=grupo&dir=desc", "&sort=monto", "&sort=monto&dir=desc", "&sort=id&dir=desc").forEach { sort ->
            assertThat(readAll(sort)).describedAs(sort).isEqualTo(all)
        }
    }

    @Test
    fun `a keyset page follows the offset order exactly`() {
        val offset = (0..4).flatMap { page("/api/objects/$name/records?size=5&page=$it&sort=grupo").ids }
        val keyset = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = page("/api/objects/$name/records?size=5&sort=grupo&count=false" + (cursor?.let { "&after=${encode(it)}" } ?: ""))
            keyset.addAll(page.ids)
            cursor = page.nextCursor
        } while (cursor != null)

        assertThat(keyset).isEqualTo(offset)
    }

    @Test
    fun `after with a page, a cursor from another sort, or a made-up one is a 400 on after`() {
        val cursor = page("/api/objects/$name/records?size=5").nextCursor!!

        listOf(
            "/api/objects/$name/records?size=5&page=1&after=${encode(cursor)}",
            "/api/objects/$name/records?size=5&sort=grupo&after=${encode(cursor)}",
            "/api/objects/$name/records?size=5&dir=desc&after=${encode(cursor)}",
            "/api/objects/$name/records?size=5&after=not-a-cursor"
        ).forEach { uri ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("after")
        }
        client
            .get()
            .uri("/api/objects/$name/records?count=maybe")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    // every id across the pages of one keyset read; a repeat fails at once
    private fun readAll(sort: String): Set<String> {
        val seen = linkedSetOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = page("/api/objects/$name/records?size=5&count=false$sort" + (cursor?.let { "&after=${encode(it)}" } ?: ""))
            page.ids.forEach { id -> assertThat(seen.add(id)).describedAs("repeated $id in '$sort'").isTrue() }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 20)
        return seen
    }

    private fun page(uri: String): KeysetPage {
        val body =
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val tree = json.readTree(body)
        return KeysetPage(
            ids = tree.get("content").toList().map { it.get("id").asString() },
            totalElements = tree.get("totalElements").takeUnless { it.isNull }?.asLong(),
            totalPages = tree.get("totalPages").takeUnless { it.isNull }?.asInt(),
            nextCursor = tree.get("nextCursor")?.asString()
        )
    }

    private fun encode(cursor: String) = UriUtils.encodeQueryParam(cursor, Charsets.UTF_8)
}
