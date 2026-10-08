package wasichai.core.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.UnprocessableContentException
import wasichai.core.data.IdempotencyKeyPurge
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// issue 63 (ADR-058): Idempotency-Key on POST /api/objects/{object}/records. one record per caller and key,
// a replay is the stored answer and writes, audits and tells nothing.
@Import(RecordIdempotencyApiTest.ListenerConfig::class)
class RecordIdempotencyApiTest : WasichaiIntegrationTest() {
    // every change, by object; and a listener that fails the write of POISON, after it
    class CountingListener : RecordChangeListener {
        val changes = ConcurrentLinkedQueue<RecordChange>()

        override suspend fun recordChanged(change: RecordChange) {
            changes += change
            if (change.after?.get("codigo") == POISON) error("listener refuses $POISON")
        }
    }

    @TestConfiguration
    class ListenerConfig {
        @Bean
        fun countingListener(): CountingListener = CountingListener()
    }

    @Autowired
    private lateinit var listener: CountingListener

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var purge: IdempotencyKeyPurge

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private val json = JsonMapper.builder().build()
    private val sentKeys = ConcurrentLinkedQueue<String>()

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var name: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId = UUID.fromString(jwt(admin).getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        name = uniqueName("caso")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to name,
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT", "required" to true),
                            mapOf("name" to "monto", "type" to "DECIMAL")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `two identical posts with one key create one record and the second replays the same status and body`() {
        val key = UUID.randomUUID().toString()
        val first = post(key, mapOf("codigo" to "C-1", "monto" to 10.5)).exchange().answer(HttpStatus.CREATED)
        val second = post(key, mapOf("monto" to 10.5, "codigo" to "C-1"), correlationId = "retry-1").exchange().answer(HttpStatus.CREATED)

        assertThat(first.replayed).isNull()
        assertThat(second.replayed).isEqualTo("true")
        assertThat(second.body).isEqualTo(first.body)
        assertThat(second.contentType).startsWith("application/json")
        // the record's ETag as the first answer gave it (ADR-051); the correlation id is the retry's own (ADR-050)
        assertThat(second.etag).isEqualTo(first.etag).isEqualTo("\"${json.readTree(first.body)["updatedAt"].asString()}\"")
        assertThat(second.correlationId).isEqualTo("retry-1")

        val id = json.readTree(first.body)["id"].asString()
        assertThat(recordCount()).isEqualTo(1)
        assertThat(auditOf(id)).containsExactly("CREATE")
        assertThat(listener.changes.filter { it.objectName == name }).hasSize(1)
        assertThat(keyRows()).isEqualTo(1)
    }

    @Test
    fun `the same key with another body or object is a 422 naming the header, and nothing is written`() {
        val key = UUID.randomUUID().toString()
        post(key, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED)

        post(key, mapOf("codigo" to "C-2"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(422)
            .jsonPath("$.errors[0].field")
            .isEqualTo("Idempotency-Key")

        val other = uniqueName("otro")
        createObject(other)
        post(key, mapOf("codigo" to "C-1"), objectName = other).exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)

        assertThat(recordCount()).isEqualTo(1)
        assertThat(recordCount(other)).isZero()
    }

    @Test
    fun `two callers with the same key do not interfere`() {
        val key = UUID.randomUUID().toString()
        val robot = serviceAccountToken()

        val mine = post(key, mapOf("codigo" to "MINE")).exchange().answer(HttpStatus.CREATED)
        val theirs = post(key, mapOf("codigo" to "THEIRS"), token = robot).exchange().answer(HttpStatus.CREATED)
        assertThat(theirs.replayed).isNull()
        assertThat(json.readTree(theirs.body)["id"]).isNotEqualTo(json.readTree(mine.body)["id"])

        // each one replays its own
        assertThat(post(key, mapOf("codigo" to "MINE")).exchange().answer(HttpStatus.CREATED).body).isEqualTo(mine.body)
        assertThat(post(key, mapOf("codigo" to "THEIRS"), token = robot).exchange().answer(HttpStatus.CREATED).body).isEqualTo(theirs.body)
        assertThat(recordCount()).isEqualTo(2)
    }

    @Test
    fun `concurrent requests with one key create one record, the others get 409 with Retry-After or the replay`() {
        val key = UUID.randomUUID().toString()
        val senders = 8
        val start = CyclicBarrier(senders)
        val pool = Executors.newFixedThreadPool(senders)
        val results =
            try {
                (1..senders)
                    .map {
                        pool.submit<EntityExchangeResult<String>> {
                            start.await(10, TimeUnit.SECONDS)
                            post(key, mapOf("codigo" to "C-1"))
                                .exchange()
                                .expectBody(String::class.java)
                                .returnResult()
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(results.map { it.status.value() }).allMatch { it == 201 || it == 409 }
        val created = results.filter { it.status.value() == 201 }
        assertThat(created).isNotEmpty
        assertThat(created.map { it.responseBody }.distinct()).hasSize(1)
        assertThat(created.count { it.responseHeaders.getFirst("Idempotent-Replayed") == null }).isEqualTo(1)
        results.filter { it.status.value() == 409 }.forEach { assertThat(it.responseHeaders.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1") }
        assertThat(recordCount()).isEqualTo(1)
        assertThat(listener.changes.filter { it.objectName == name }).hasSize(1)

        // once the first has committed, the key replays
        assertThat(post(key, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED).body).isEqualTo(created.first().responseBody)
    }

    @Test
    fun `while the first request with a key still runs, the same key is a 409 with Retry-After and writes nothing`() {
        val key = UUID.randomUUID().toString()
        sentKeys += key
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runBlocking {
            // the first one, in process: created, not yet committed
            val first =
                async(Dispatchers.IO) {
                    asAdmin {
                        transactions.executeAndAwait {
                            records.create(name, RecordRequest(mapOf("codigo" to "C-1")), null, key).also {
                                holding.complete(Unit)
                                release.await()
                            }
                        }
                    }
                }
            holding.await()
            withContext(Dispatchers.IO) {
                post(key, mapOf("codigo" to "C-1"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.CONFLICT)
                    .expectHeader()
                    .valueEquals(HttpHeaders.RETRY_AFTER, "1")
                    .expectHeader()
                    .contentType("application/problem+json")
                    .expectBody()
                    .jsonPath("$.errors[0].field")
                    .isEqualTo("Idempotency-Key")
            }
            release.complete(Unit)
            val created = first.await()

            // committed: the same request over the api is its replay. one key, in process or not
            val replay = withContext(Dispatchers.IO) { post(key, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED) }
            assertThat(replay.replayed).isEqualTo("true")
            assertThat(json.readTree(replay.body)["id"].asString()).isEqualTo(created.id)
        }
        assertThat(recordCount()).isEqualTo(1)
    }

    @Test
    fun `a request that fails stores nothing, so the key can be retried with a corrected body`() {
        val key = UUID.randomUUID().toString()
        post(key, mapOf("monto" to 1))
            .exchange()
            .expectStatus()
            .isBadRequest
        assertThat(keyRows()).isZero()

        val fixed = post(key, mapOf("codigo" to "C-1", "monto" to 1)).exchange().answer(HttpStatus.CREATED)
        assertThat(fixed.replayed).isNull()
        assertThat(recordCount()).isEqualTo(1)
    }

    @Test
    fun `a keyed create is one transaction - a listener that fails after the write leaves no record and no key`() {
        val key = UUID.randomUUID().toString()
        post(key, mapOf("codigo" to POISON))
            .exchange()
            .expectStatus()
            .is5xxServerError

        assertThat(recordCount()).isZero()
        assertThat(keyRows()).isZero()
    }

    @Test
    fun `a malformed key is a 400 on the header`() {
        listOf("", "x".repeat(129)).forEach { bad ->
            post(bad, mapOf("codigo" to "C-1"))
                .exchange()
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("Idempotency-Key")
        }
        assertThat(recordCount()).isZero()
    }

    @Test
    fun `without the header two posts are two records, as before, and no key is stored`() {
        val before = organizationKeyRows()
        repeat(2) {
            val result = post(null, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED)
            assertThat(result.replayed).isNull()
        }

        assertThat(recordCount()).isEqualTo(2)
        assertThat(organizationKeyRows()).isEqualTo(before)
    }

    @Test
    fun `an expired key is gone - purged, or skipped before the purge - and a retry creates a new record`() {
        val purged = UUID.randomUUID().toString()
        val first = json.readTree(post(purged, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED).body)["id"]
        age(purged)
        assertThat(runBlocking { purge.runOnce() }).isNotNull().isGreaterThanOrEqualTo(1)
        assertThat(keyRows()).isZero()

        val retried = post(purged, mapOf("codigo" to "C-1")).exchange().answer(HttpStatus.CREATED)
        assertThat(retried.replayed).isNull()
        assertThat(json.readTree(retried.body)["id"]).isNotEqualTo(first)

        // past the ttl a key never replays, whether the purge ran or not
        val skipped = UUID.randomUUID().toString()
        post(skipped, mapOf("codigo" to "C-2")).exchange().answer(HttpStatus.CREATED)
        age(skipped)
        assertThat(post(skipped, mapOf("codigo" to "C-2")).exchange().answer(HttpStatus.CREATED).replayed).isNull()

        assertThat(recordCount()).isEqualTo(4)
    }

    @Test
    fun `in process the same key returns the stored record, once, also for the platform`() {
        val key = UUID.randomUUID().toString()
        val request = RecordRequest(mapOf("codigo" to "C-1", "monto" to BigDecimal("2.50")))
        val first = asAdmin { records.create(name, request, null, key) }
        val again = asAdmin { records.create(name, request, null, key) }

        assertThat(again.id).isEqualTo(first.id)
        assertThat(again.updatedAt).isEqualTo(first.updatedAt)
        assertThat(again.attributes["codigo"]).isEqualTo("C-1")
        assertThatThrownBy { asAdmin { records.create(name, RecordRequest(mapOf("codigo" to "C-9")), null, key) } }
            .isInstanceOf(UnprocessableContentException::class.java)

        // the platform is a caller of its own: the admin's key is not its key
        val platform = runBlocking { records.asPlatform(organizationId) { records.create(name, request, null, key) } }
        val platformAgain = runBlocking { records.asPlatform(organizationId) { records.create(name, request, null, key) } }
        assertThat(platform.id).isNotEqualTo(first.id).isEqualTo(platformAgain.id)

        assertThat(recordCount()).isEqualTo(2)
        assertThat(auditOf(first.id)).containsExactly("CREATE")
    }

    @Test
    fun `in process the key joins the caller's transaction - rolled back, the key is free again`() {
        val key = UUID.randomUUID().toString()
        sentKeys += key
        assertThatThrownBy {
            asAdmin {
                transactions.executeAndAwait {
                    records.create(name, RecordRequest(mapOf("codigo" to "C-1")), null, key)
                    error("the app gives up")
                }
            }
        }.hasMessageContaining("the app gives up")
        assertThat(recordCount()).isZero()
        assertThat(keyRows()).isZero()

        val created = asAdmin { transactions.executeAndAwait { records.create(name, RecordRequest(mapOf("codigo" to "C-1")), null, key) } }
        assertThat(asAdmin { records.create(name, RecordRequest(mapOf("codigo" to "C-1")), null, key) }.id).isEqualTo(created.id)
        assertThat(recordCount()).isEqualTo(1)
    }

    // ---- helpers

    private class Answer(
        val body: String,
        val etag: String?,
        val replayed: String?,
        val correlationId: String?,
        val contentType: String?
    )

    private fun WebTestClient.ResponseSpec.answer(status: HttpStatus): Answer {
        val result =
            expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
        return Answer(
            result.responseBody!!,
            result.responseHeaders.eTag,
            result.responseHeaders.getFirst("Idempotent-Replayed"),
            result.responseHeaders.getFirst(ChangeOrigin.HEADER),
            result.responseHeaders.contentType?.toString()
        )
    }

    private fun post(
        key: String?,
        attributes: Map<String, Any?>,
        token: String = admin,
        objectName: String = name,
        correlationId: String? = null
    ) = client
        .post()
        .uri("/api/objects/$objectName/records")
        .header(HttpHeaders.AUTHORIZATION, token)
        .headers { headers ->
            key?.let {
                sentKeys += it
                headers.set("Idempotency-Key", it)
            }
            correlationId?.let { headers.set(ChangeOrigin.HEADER, it) }
        }.bodyValue(mapOf("attributes" to attributes))

    private fun createObject(objectName: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to objectName, "label" to objectName, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    // a server-to-server caller (ADR-043) that may create and read this test's records
    private fun serviceAccountToken(): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Robot", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf("permissions" to listOf("READ", "CREATE").map { mapOf("objectName" to name, "action" to it, "allowed" to true) })
            ).exchange()
            .expectStatus()
            .isOk
        val body =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to uniqueName("erp"), "roles" to listOf(role)))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        return "Bearer " +
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to body["clientId"], "clientSecret" to body["clientSecret"]))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!["token"] as String
    }

    private fun jwt(token: String) = runBlocking { decoder.decode(token.removePrefix("Bearer ")).awaitSingle() }

    // a RecordService caller is a request; a test gives the token's authentication to the reactor context, as the filter would
    private fun <T> asAdmin(block: suspend () -> T): T =
        runBlocking {
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt(admin))))
                .awaitSingle()
        }

    private fun recordCount(objectName: String = name): Long =
        runBlocking {
            val table =
                db
                    .sql("SELECT physical_table FROM ${schemas.metadata}.custom_objects WHERE organization_id = :organizationId AND name = :name")
                    .bind("organizationId", organizationId)
                    .bind("name", objectName)
                    .map { row, _ -> row.get("physical_table", String::class.java)!! }
                    .one()
                    .awaitSingle()
            db
                .sql("SELECT count(*) AS n FROM ${schemas.dataTable(table)}")
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    // this test's keys: the organization is shared with every other test, the keys are not
    private fun keyRows(): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM ${schemas.metadata}.idempotency_keys WHERE organization_id = :org AND key = ANY(:keys)")
                .bind("org", organizationId)
                .bind("keys", sentKeys.toTypedArray())
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun organizationKeyRows(): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM ${schemas.metadata}.idempotency_keys WHERE organization_id = :org")
                .bind("org", organizationId)
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    // the key's row as if written a ttl and a day ago
    private fun age(key: String) {
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.idempotency_keys SET created_at = now() - interval '25 hours' WHERE organization_id = :org AND key = :key")
                .bind("org", organizationId)
                .bind("key", key)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

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

    private companion object {
        const val POISON = "BOOM"
    }
}
