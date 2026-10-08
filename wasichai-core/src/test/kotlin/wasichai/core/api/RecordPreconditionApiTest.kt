package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.PreconditionFailedException
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// issue 51 (ADR-051): every single-record answer carries the record's ETag, If-Match turns a write into a
// compare-and-write in one statement (412 when stale), and PATCH writes only the keys sent.
class RecordPreconditionApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private val json = JsonMapper.builder().build()

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var name: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        name = uniqueName("caso")
        createObject(name)
    }

    @Test
    fun `get, post, put and patch answer the record's etag, and the etag of a write is accepted by the next`() {
        val created = send(post(name, mapOf("codigo" to "C-1", "estado" to "ABIERTO"))).expectOk(HttpStatus.CREATED)
        val id = created.body["id"].asString()
        assertThat(created.etag).isEqualTo("\"${created.body["updatedAt"].asString()}\"")

        val read = send(get(name, id)).expectOk()
        assertThat(read.etag).isEqualTo(created.etag)

        val put = send(put(name, id, mapOf("codigo" to "C-1", "estado" to "EN_CURSO"), ifMatch = read.etag)).expectOk()
        assertThat(put.etag).isNotEqualTo(read.etag).isEqualTo("\"${put.body["updatedAt"].asString()}\"")

        val patch = send(patch(name, id, mapOf("nota" to "visita"), ifMatch = put.etag)).expectOk()
        assertThat(patch.etag).isNotEqualTo(put.etag)

        // a list item carries the same version as its updatedAt
        val page =
            send(list(name))
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
        assertThat(page.responseHeaders.eTag).isNull()
        val listed = json.readTree(page.responseBody)["content"][0]["updatedAt"].asString()
        assertThat("\"$listed\"").isEqualTo(patch.etag)

        send(delete(name, id, ifMatch = patch.etag)).expectStatus().isNoContent
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE", "UPDATE", "DELETE")
    }

    @Test
    fun `two clients put with the etag they read - the first wins, the second gets a 412 and nothing`() {
        val id = createRecord(mapOf("codigo" to "C-1", "estado" to "ABIERTO"))
        val etag = send(get(name, id)).expectOk().etag

        send(put(name, id, mapOf("codigo" to "C-1", "estado" to "FIRST"), ifMatch = etag)).expectOk()
        send(put(name, id, mapOf("codigo" to "C-1", "estado" to "SECOND"), ifMatch = etag))
            .expectStatus()
            .isEqualTo(HttpStatus.PRECONDITION_FAILED)
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(412)
            .jsonPath("$.errors[0].field")
            .isEqualTo("If-Match")

        assertThat(send(get(name, id)).expectOk().body["attributes"]["estado"].asString()).isEqualTo("FIRST")
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE")
    }

    @Test
    fun `concurrent writers holding the same etag - exactly one succeeds`() {
        val id = createRecord(mapOf("codigo" to "C-1", "estado" to "ABIERTO"))
        val etag = send(get(name, id)).expectOk().etag
        val writers = 8
        val start = CyclicBarrier(writers)
        val pool = Executors.newFixedThreadPool(writers)
        val statuses =
            try {
                (1..writers)
                    .map { n ->
                        pool.submit<Int> {
                            start.await(10, TimeUnit.SECONDS)
                            send(put(name, id, mapOf("codigo" to "C-1", "estado" to "W$n"), ifMatch = etag)).returnResult().status.value()
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses.count { it == 200 }).isEqualTo(1)
        assertThat(statuses.count { it == 412 }).isEqualTo(writers - 1)
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE")
    }

    @Test
    fun `a put without if-match is the old full replace, and if-match star is too`() {
        val id = createRecord(mapOf("codigo" to "C-1", "estado" to "ABIERTO", "nota" to "n"))
        val stale = send(get(name, id)).expectOk().etag
        send(put(name, id, mapOf("codigo" to "C-2"))).expectOk()

        // nothing compared: a field left out is cleared, as it always was
        val after = send(get(name, id)).expectOk().body["attributes"]
        assertThat(after["codigo"].asString()).isEqualTo("C-2")
        assertThat(after["estado"].isNull).isTrue()
        assertThat(after["nota"].isNull).isTrue()

        send(put(name, id, mapOf("codigo" to "C-3"), ifMatch = "*")).expectOk()
        send(put(name, id, mapOf("codigo" to "C-4"), ifMatch = stale)).expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED)
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE", "UPDATE")
    }

    @Test
    fun `a delete with a stale etag is a 412 and the record stays`() {
        val id = createRecord(mapOf("codigo" to "C-1"))
        val stale = send(get(name, id)).expectOk().etag
        send(patch(name, id, mapOf("nota" to "x"))).expectOk()

        send(delete(name, id, ifMatch = stale)).expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED)
        send(get(name, id)).expectOk()
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE")
    }

    @Test
    fun `a weak etag never matches, a malformed if-match is a 400, a missing record with one a 404`() {
        val id = createRecord(mapOf("codigo" to "C-1"))
        val etag = send(get(name, id)).expectOk().etag

        send(put(name, id, mapOf("codigo" to "C-2"), ifMatch = "W/$etag")).expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED)
        send(put(name, id, mapOf("codigo" to "C-2"), ifMatch = etag.trim('"')))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("If-Match")
        send(put(name, UUID.randomUUID().toString(), mapOf("codigo" to "C-2"), ifMatch = etag)).expectStatus().isNotFound
        send(delete(name, UUID.randomUUID().toString(), ifMatch = etag)).expectStatus().isNotFound
        // one of a list is enough
        send(put(name, id, mapOf("codigo" to "C-3"), ifMatch = "\"2001-01-01T00:00:00Z\", $etag")).expectOk()
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE")
    }

    @Test
    fun `a patch leaves every other field alone, null clears, an unknown field is a 400 and a read-only one a 403`() {
        val id = createRecord(mapOf("codigo" to "C-1", "estado" to "ABIERTO", "nota" to "n", "monto" to 10))

        val patched = send(patch(name, id, mapOf("monto" to 1))).expectOk().body["attributes"]
        assertThat(patched["monto"].asInt()).isEqualTo(1)
        assertThat(patched["codigo"].asString()).isEqualTo("C-1")
        assertThat(patched["estado"].asString()).isEqualTo("ABIERTO")
        assertThat(patched["nota"].asString()).isEqualTo("n")

        val cleared = send(patch(name, id, mapOf("nota" to null))).expectOk().body["attributes"]
        assertThat(cleared["nota"].isNull).isTrue()
        assertThat(cleared["estado"].asString()).isEqualTo("ABIERTO")

        send(patch(name, id, mapOf("noexiste" to 1)))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("noexiste")
        send(patch(name, id, mapOf("folio" to "F-2"))).expectStatus().isForbidden
        // a PUT still ignores a read-only field it is sent, as before
        send(put(name, id, mapOf("codigo" to "C-1", "folio" to "F-2"))).expectOk()

        // the history shows each patch as an UPDATE that changed only what was sent
        assertThat(auditOf(id)).containsExactly("CREATE", "UPDATE", "UPDATE", "UPDATE")
        assertThat(changedKeys(id)[1]).containsExactly("monto")
        assertThat(changedKeys(id)[2]).containsExactly("nota")
    }

    @Test
    fun `a patch is held to append-only, api-only and requires-reason like a put`() {
        val receipt = uniqueName("recibo")
        createObject(receipt, appendOnly = true)
        val r = send(post(receipt, mapOf("codigo" to "R-1"))).expectOk(HttpStatus.CREATED).body["id"].asString()
        send(patch(receipt, r, mapOf("nota" to "x"))).expectStatus().isEqualTo(HttpStatus.CONFLICT)

        val outbox = uniqueName("outbox")
        createObject(outbox, apiOnly = true)
        val o = runBlocking { records.asPlatform(organizationId) { records.create(outbox, RecordRequest(mapOf("codigo" to "O-1"))) } }.id
        send(patch(outbox, o, mapOf("nota" to "x"))).expectStatus().isForbidden

        val reasoned = uniqueName("motivado")
        createObject(reasoned, requiresReason = true)
        val m = send(post(reasoned, mapOf("codigo" to "M-1"), reason = "alta")).expectOk(HttpStatus.CREATED).body["id"].asString()
        send(patch(reasoned, m, mapOf("nota" to "x")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("reason")
        send(patch(reasoned, m, mapOf("nota" to "x"), reason = "visita")).expectOk()
        assertThat(auditOf(m)).containsExactly("CREATE", "UPDATE")
    }

    @Test
    fun `the delete that checks append-only references under a row lock compares the etag there too`() {
        val customer = uniqueName("cliente")
        val receipt = uniqueName("recibo")
        createObject(customer)
        createObject(receipt, appendOnly = true, relationTo = customer)
        val c = createRecord(mapOf("codigo" to "C-1"), customer)
        val stale = send(get(customer, c)).expectOk().etag
        send(patch(customer, c, mapOf("nota" to "x"))).expectOk()

        send(delete(customer, c, ifMatch = stale)).expectStatus().isEqualTo(HttpStatus.PRECONDITION_FAILED)
        val current = send(get(customer, c)).expectOk().etag
        send(delete(customer, c, ifMatch = current)).expectStatus().isNoContent
        assertThat(auditOf(c)).containsExactly("CREATE", "UPDATE", "DELETE")
    }

    @Test
    fun `in one transaction every write gets its own version, so a stale one still fails there`() {
        val id = UUID.fromString(createRecord(mapOf("codigo" to "C-1")))
        val first =
            runBlocking {
                records.asPlatform(organizationId) {
                    transactions.executeAndAwait {
                        val read = records.get(name, id).updatedAt!!
                        val one = records.update(name, id, RecordRequest(mapOf("codigo" to "C-2")), null, read)
                        val two = records.update(name, id, RecordRequest(mapOf("codigo" to "C-3")), null, one.updatedAt)
                        assertThat(two.updatedAt).isAfter(one.updatedAt)
                        val lost = runCatching { records.update(name, id, RecordRequest(mapOf("codigo" to "LOST")), null, one.updatedAt) }
                        assertThat(lost.exceptionOrNull()).isInstanceOf(PreconditionFailedException::class.java)
                        read
                    }
                }
            }

        assertThat(send(get(name, id.toString())).expectOk().body["attributes"]["codigo"].asString()).isEqualTo("C-3")
        // committed: the version first read is stale outside the transaction too
        assertThatThrownBy {
            runBlocking { records.asPlatform(organizationId) { records.delete(name, id, null, first) } }
        }.isInstanceOf(PreconditionFailedException::class.java)
    }

    // ---- helpers

    private class Answer(
        val etag: String,
        val body: JsonNode
    )

    private fun WebTestClient.ResponseSpec.expectOk(status: HttpStatus = HttpStatus.OK): Answer {
        val result: EntityExchangeResult<String> =
            expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
        val etag = result.responseHeaders.eTag
        assertThat(etag).`as`("ETag of ${result.method} ${result.url}").isNotNull()
        return Answer(etag!!, json.readTree(result.responseBody))
    }

    private fun send(request: WebTestClient.RequestHeadersSpec<*>): WebTestClient.ResponseSpec = request.exchange()

    private fun get(
        objectName: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$objectName/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)

    private fun list(objectName: String) =
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)

    private fun post(
        objectName: String,
        attributes: Map<String, Any?>,
        reason: String? = null
    ) = client
        .post()
        .uri("/api/objects/$objectName/records")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .headers { headers -> reason?.let { headers.set(ChangeReason.HEADER, it) } }
        .bodyValue(mapOf("attributes" to attributes))

    private fun put(
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
        ifMatch: String? = null
    ) = client
        .put()
        .uri("/api/objects/$objectName/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .headers { headers -> ifMatch?.let { headers.set(HttpHeaders.IF_MATCH, it) } }
        .bodyValue(mapOf("attributes" to attributes))

    private fun patch(
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
        ifMatch: String? = null,
        reason: String? = null
    ) = client
        .patch()
        .uri("/api/objects/$objectName/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .headers { headers ->
            ifMatch?.let { headers.set(HttpHeaders.IF_MATCH, it) }
            reason?.let { headers.set(ChangeReason.HEADER, it) }
        }.bodyValue(mapOf("attributes" to attributes))

    private fun delete(
        objectName: String,
        id: String,
        ifMatch: String? = null
    ) = client
        .delete()
        .uri("/api/objects/$objectName/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .headers { headers -> ifMatch?.let { headers.set(HttpHeaders.IF_MATCH, it) } }

    private fun createRecord(
        attributes: Map<String, Any?>,
        objectName: String = name
    ): String = send(post(objectName, attributes)).expectOk(HttpStatus.CREATED).body["id"].asString()

    private fun createObject(
        objectName: String,
        appendOnly: Boolean = false,
        apiOnly: Boolean = false,
        requiresReason: Boolean = false,
        relationTo: String? = null
    ) {
        val fields =
            buildList {
                add(mapOf("name" to "codigo", "type" to "TEXT"))
                add(mapOf("name" to "estado", "type" to "TEXT"))
                add(mapOf("name" to "nota", "type" to "TEXT"))
                add(mapOf("name" to "monto", "type" to "INTEGER"))
                add(mapOf("name" to "folio", "type" to "TEXT", "editable" to false))
                relationTo?.let { add(mapOf("name" to "cliente", "type" to "RELATION", "relationTarget" to it)) }
            }
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to objectName,
                    "appendOnly" to appendOnly,
                    "apiOnly" to apiOnly,
                    "requiresReason" to requiresReason,
                    "fields" to fields
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    // operations of the record's audit rows, oldest first
    private fun auditOf(id: String): List<String> =
        runBlocking {
            db
                .sql("SELECT operation FROM ${schemas.metadata}.audit_log WHERE record_id = :id ORDER BY occurred_at, id")
                .bind("id", UUID.fromString(id))
                .map { row, _ -> row.get("operation", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    // per audit row, oldest first: the attribute keys whose value differs between before and after
    private fun changedKeys(id: String): List<Set<String>> =
        runBlocking {
            db
                .sql(
                    "SELECT CAST(before_state AS text) AS b, CAST(after_state AS text) AS a FROM ${schemas.metadata}.audit_log " +
                        "WHERE record_id = :id ORDER BY occurred_at, id"
                ).bind("id", UUID.fromString(id))
                .map { row, _ -> row.get("b", String::class.java) to row.get("a", String::class.java) }
                .all()
                .collectList()
                .awaitSingle()
                .map { (before, after) ->
                    val b = before?.let { json.readTree(it) }
                    val a = after?.let { json.readTree(it) }
                    if (b == null || a == null) {
                        emptySet()
                    } else {
                        a
                            .propertyNames()
                            .asSequence()
                            .filter { key -> b[key] != a[key] }
                            .toSet()
                    }
                }
        }
}
