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
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 33 (ADR-031 D29): a RELATION value naming no record of this organization is the caller's
// mistake, a 400 on the field. checked before guards and the store; the FK race stays a 409 (ADR-044).
@Import(RelationTargetApiTest.GuardConfig::class)
class RelationTargetApiTest : WasichaiIntegrationTest() {
    // vetoes a VETO code: shows whether the relation check ran before the guards
    class VetoGuard : RecordWriteGuard {
        override suspend fun beforeWrite(change: RecordWrite) {
            if (change.attributes?.get("codigo") == "VETO") throw ValidationException("Vetoed", "codigo", "the guard said no")
        }
    }

    @TestConfiguration
    class GuardConfig {
        @Bean
        fun relationVetoGuard(): RecordWriteGuard = VetoGuard()
    }

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var customer: String
    private lateinit var receipt: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        customer = uniqueName("customer")
        receipt = uniqueName("receipt")
        applyModel(admin)
    }

    @Test
    fun `a create naming no record is a 400 on the field, and nothing is stored`() {
        val nobody = UUID.randomUUID().toString()

        createReceipt(admin, mapOf("codigo" to "R-1", "customer" to nobody))
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(400)
            .jsonPath("$.errors.length()")
            .isEqualTo(1)
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")
            .jsonPath("$.detail")
            .value<String> { assertThat(it).doesNotContain(nobody) }

        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)
        assertThat(auditOf(receipt)).isEmpty()
    }

    @Test
    fun `an update naming no record is a 400 on the field, and the record keeps its value`() {
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))
        val r = createRecord(admin, receipt, mapOf("codigo" to "R-1", "customer" to c))

        updateReceipt(admin, r, mapOf("codigo" to "R-2", "customer" to UUID.randomUUID().toString()))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")

        getRecord(admin, receipt, r)
            .jsonPath("$.attributes.codigo")
            .isEqualTo("R-1")
            .jsonPath("$.attributes.customer")
            .isEqualTo(c)
        assertThat(auditOf(receipt)).containsExactly("CREATE")
    }

    @Test
    fun `a record of another organization answers exactly as one that does not exist`() {
        val slug = "rel-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Other tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        val stranger = bearer("$slug@wasichai.local", "supersecret")
        // same model over there, so only the record id crosses the tenant line
        applyModel(stranger)
        val theirs = createRecord(stranger, customer, mapOf("codigo" to "THEIRS"))

        val foreign = createReceipt(admin, mapOf("codigo" to "R-1", "customer" to theirs)).expectStatus().isBadRequest.problem()
        val missing = createReceipt(admin, mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())).expectStatus().isBadRequest.problem()

        assertThat(foreign).isEqualTo(missing)
        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `a record deleted before the write is a 400 too`() {
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))
        client
            .delete()
            .uri("/api/objects/$customer/records/$c")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        createReceipt(admin, mapOf("codigo" to "R-1", "customer" to c))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")
    }

    @Test
    fun `the check runs before the guards`() {
        createReceipt(admin, mapOf("codigo" to "VETO", "customer" to UUID.randomUUID().toString()))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")
    }

    @Test
    fun `a record that exists still links, and null still clears`() {
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))
        val r = createRecord(admin, receipt, mapOf("codigo" to "R-1", "customer" to c))
        getRecord(admin, receipt, r).jsonPath("$.attributes.customer").isEqualTo(c)

        updateReceipt(admin, r, mapOf("codigo" to "R-1", "customer" to null)).expectStatus().isOk
        getRecord(admin, receipt, r).jsonPath("$.attributes.customer").isEmpty()

        // a value left out is not looked up at all
        createReceipt(admin, mapOf("codigo" to "R-2")).expectStatus().isCreated
    }

    @Test
    fun `the platform gets the same refusal`() {
        assertThatThrownBy {
            runBlocking {
                records.asPlatform(organizationId) {
                    records.create(receipt, RecordRequest(mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())))
                }
            }
        }.isInstanceOf(ValidationException::class.java)
            .satisfies({ assertThat((it as ValidationException).violations.map { v -> v.field }).containsExactly("customer") })
        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)
    }

    private fun applyModel(token: String) {
        createObject(token, customer, listOf(mapOf("name" to "codigo", "type" to "TEXT")))
        createObject(
            token,
            receipt,
            listOf(
                mapOf("name" to "codigo", "type" to "TEXT"),
                mapOf("name" to "customer", "type" to "RELATION", "relationTarget" to customer)
            )
        )
    }

    private fun createObject(
        token: String,
        name: String,
        fields: List<Map<String, Any>>
    ) = client
        .post()
        .uri("/api/objects")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields))
        .exchange()
        .expectStatus()
        .isCreated

    private fun createReceipt(
        token: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$receipt/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun updateReceipt(
        token: String,
        id: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/objects/$receipt/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun createRecord(
        token: String,
        name: String,
        attributes: Map<String, Any?>
    ): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String

    private fun getRecord(
        token: String,
        name: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun listRecords(
        token: String,
        name: String
    ) = client
        .get()
        .uri("/api/objects/$name/records")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    // the problem body, minus what differs per request
    private fun WebTestClient.ResponseSpec.problem(): Map<*, *> =
        expectBody(Map::class.java)
            .returnResult()
            .responseBody!!
            .filterKeys { it != "instance" }

    // operations of the object's audit rows, oldest first
    private fun auditOf(name: String): List<String> =
        runBlocking {
            db
                .sql("SELECT operation FROM ${schemas.metadata}.audit_log WHERE object_name = :name ORDER BY occurred_at, id")
                .bind("name", name)
                .map { row, _ -> row.get("operation", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }
}
