package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-039: background work runs RecordService as the platform for one organization. no token, no
// permission check, null user in the row and in the audit (ADR-016), and nothing a request carries
// can turn it on.
@Import(PlatformRecordServiceTest.ProbeConfig::class)
class PlatformRecordServiceTest : WasichaiIntegrationTest() {
    // app code that wrongly reaches for the platform while serving a request
    @RestController
    class PlatformProbe(
        private val records: RecordService
    ) {
        // the 500 body is generic on purpose; the test reads the cause here
        @Volatile
        var lastFailure: Throwable? = null

        // OPTIONS is the one method the security chain lets through without a token: the anonymous case
        @RequestMapping("/platform-probe/{organizationId}/{objectName}", method = [RequestMethod.POST, RequestMethod.OPTIONS])
        suspend fun write(
            @PathVariable organizationId: UUID,
            @PathVariable objectName: String
        ): String =
            try {
                records
                    .asPlatform(organizationId) { records.create(objectName, RecordRequest(mapOf("codigo" to "FROM-A-REQUEST"))) }
                    .id
            } catch (e: Exception) {
                lastFailure = e
                throw e
            }
    }

    @TestConfiguration
    class ProbeConfig {
        @Bean
        fun platformProbe(records: RecordService) = PlatformProbe(records)
    }

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var probe: PlatformProbe

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var tenant: String
    private lateinit var tenantId: UUID
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        probe.lastFailure = null
        admin = bearer()
        val slug = "tenant-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Platform tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        tenant = bearer("$slug@wasichai.local", "supersecret")
        tenantId = organizationOf(tenant)
        objectName = uniqueName("plat")
        // the same object in both tenants: only the organization tells the writes apart
        createObject(admin)
        createObject(tenant)
    }

    @Test
    fun `platform create, update and delete land in the given organization with a null user`() {
        val (kept, gone) =
            runBlocking {
                records.asPlatform(tenantId) {
                    val kept = records.create(objectName, RecordRequest(mapOf("codigo" to "P-1")))
                    records.update(objectName, UUID.fromString(kept.id), RecordRequest(mapOf("codigo" to "P-1b")))
                    val gone = records.create(objectName, RecordRequest(mapOf("codigo" to "P-2")))
                    records.delete(objectName, UUID.fromString(gone.id))
                    kept.id to gone.id
                }
            }

        // the tenant sees the record, the other organization does not
        listCodes(tenant).let { assertThat(it).containsExactly("P-1b") }
        listCodes(admin).let { assertThat(it).isEmpty() }
        assertThat(audit()).containsExactly(
            Triple("CREATE", kept, null),
            Triple("UPDATE", kept, null),
            Triple("CREATE", gone, null),
            Triple("DELETE", gone, null)
        )
        // nobody created or touched it: the platform did
        assertThat(owners()).containsExactly(null to null)
    }

    @Test
    fun `the platform reads every record of its organization`() {
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "BY-A-USER")))
            .exchange()
            .expectStatus()
            .isCreated

        val codes = runBlocking { records.asPlatform(tenantId) { records.list(objectName, everything).content.map { it.attributes["codigo"] } } }

        assertThat(codes).containsExactly("BY-A-USER")
    }

    @Test
    fun `the platform inside the caller's transaction rolls back with it`() {
        assertThatThrownBy {
            runBlocking {
                transactions.executeAndAwait {
                    records.asPlatform(tenantId) {
                        records.create(objectName, RecordRequest(mapOf("codigo" to "T-1")))
                        records.create(objectName, RecordRequest(mapOf("codigo" to "T-2")))
                    }
                    error("the job gives up")
                }
            }
        }.hasMessageContaining("the job gives up")

        assertThat(listCodes(tenant)).isEmpty()
        assertThat(audit()).isEmpty()
    }

    @Test
    fun `a transaction inside the platform block commits as one`() {
        assertThatThrownBy {
            runBlocking {
                records.asPlatform(tenantId) {
                    transactions.executeAndAwait {
                        records.create(objectName, RecordRequest(mapOf("codigo" to "U-1")))
                        error("half way")
                    }
                }
            }
        }.hasMessageContaining("half way")
        runBlocking {
            records.asPlatform(tenantId) {
                transactions.executeAndAwait {
                    records.create(objectName, RecordRequest(mapOf("codigo" to "U-2")))
                    records.create(objectName, RecordRequest(mapOf("codigo" to "U-3")))
                }
            }
        }

        assertThat(listCodes(tenant)).containsExactlyInAnyOrder("U-2", "U-3")
        assertThat(audit().map { it.third }).containsOnlyNulls().hasSize(2)
    }

    @Test
    fun `a request with a token never becomes the platform`() {
        client
            .post()
            .uri("/platform-probe/$tenantId/$objectName")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(500)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unexpected error")

        // refused by the guard, not by something else on the way
        assertThat(probe.lastFailure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("never becomes the platform")
        assertThat(listCodes(tenant)).isEmpty()
        assertThat(audit()).isEmpty()
    }

    @Test
    fun `a request without a token never becomes the platform either`() {
        client
            .options()
            .uri("/platform-probe/$tenantId/$objectName")
            .exchange()
            .expectStatus()
            .isEqualTo(500)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unexpected error")

        // refused by the guard, not by something else on the way
        assertThat(probe.lastFailure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("never becomes the platform")
        assertThat(listCodes(tenant)).isEmpty()
        assertThat(audit()).isEmpty()
    }

    @Test
    fun `nothing in a token turns a user into the platform`() {
        // a request is the token's user, always: the write carries that user, never null
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, tenant)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "MINE")))
            .exchange()
            .expectStatus()
            .isCreated

        assertThat(audit().single().third).isEqualTo(userOf(tenant))
        // and with no token there is no request at all
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "NOBODY")))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    private val everything = RecordQuery(PageRequest(0, 50))

    private fun createObject(token: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to objectName, "label" to objectName, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Suppress("UNCHECKED_CAST")
    private fun listCodes(token: String): List<Any?> {
        val body =
            client
                .get()
                .uri("/api/objects/$objectName/records?size=50")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        return (body["content"] as List<Map<String, Any?>>).map { (it["attributes"] as Map<String, Any?>)["codigo"] }
    }

    private fun claims(token: String) = runBlocking { decoder.decode(token.removePrefix("Bearer ")).awaitSingle() }

    private fun organizationOf(token: String): UUID = UUID.fromString(claims(token).getClaimAsString(JwtService.CLAIM_ORGANIZATION))

    private fun userOf(token: String): UUID = UUID.fromString(claims(token).subject)

    // operation, record, user of every audit row of the tenant's object, oldest first
    private fun audit(): List<Triple<String, String, UUID?>> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT operation, record_id, user_id FROM ${schemas.metadata}.audit_log
                    WHERE organization_id = :organizationId AND object_name = :name
                    ORDER BY occurred_at
                    """.trimIndent()
                ).bind("organizationId", tenantId)
                .bind("name", objectName)
                .map { row, _ ->
                    Triple(row.get("operation", String::class.java)!!, row.get("record_id", UUID::class.java).toString(), row.get("user_id", UUID::class.java))
                }.all()
                .collectList()
                .awaitSingle()
        }

    private fun owners(): List<Pair<UUID?, UUID?>> =
        runBlocking {
            val table =
                db
                    .sql("SELECT physical_table FROM ${schemas.metadata}.custom_objects WHERE organization_id = :organizationId AND name = :name")
                    .bind("organizationId", tenantId)
                    .bind("name", objectName)
                    .map { row, _ -> row.get("physical_table", String::class.java)!! }
                    .one()
                    .awaitSingle()
            db
                .sql("SELECT created_by, updated_by FROM ${schemas.dataTable(table)}")
                .map { row, _ -> row.get("created_by", UUID::class.java) to row.get("updated_by", UUID::class.java) }
                .all()
                .collectList()
                .awaitSingle()
        }
}
