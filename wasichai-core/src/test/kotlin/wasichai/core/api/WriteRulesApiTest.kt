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
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-040: appendOnly refuses UPDATE and DELETE for everyone (ADMIN, the platform, links), a
// RecordWriteGuard vetoes before anything is stored, apiOnly closes the generic record api only.
@Import(WriteRulesApiTest.GuardConfig::class)
class WriteRulesApiTest : WasichaiIntegrationTest() {
    // app code: an endpoint of the app itself that writes as the calling user, in-process
    @RestController
    class AppEndpoint(
        private val records: RecordService
    ) {
        @PostMapping("/app-endpoint/{objectName}")
        suspend fun write(
            @PathVariable objectName: String
        ): String = records.create(objectName, RecordRequest(mapOf("codigo" to "BY-THE-APP"))).id
    }

    // vetoes a VETO code on the way in, and a KEEP record on the way out
    class VetoGuard : RecordWriteGuard {
        override suspend fun beforeWrite(change: RecordWrite) {
            if (change.attributes?.get("codigo") == "VETO") throw ValidationException("Vetoed", "codigo", "the guard said no")
            if (change.before?.get("codigo") == "KEEP") throw ConflictException("Kept by the guard")
        }
    }

    @TestConfiguration
    class GuardConfig {
        @Bean
        fun vetoGuard(): RecordWriteGuard = VetoGuard()

        @Bean
        fun appEndpoint(records: RecordService) = AppEndpoint(records)
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

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
    }

    @Test
    fun `the rules show on the object and the metadata api edits them`() {
        val name = uniqueName("rules")
        createObject(name, appendOnly = true, apiOnly = true)
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.appendOnly")
            .isEqualTo(true)
            .jsonPath("$.apiOnly")
            .isEqualTo(true)
        getObject(name)
            .jsonPath("$.appendOnly")
            .isEqualTo(true)
            .jsonPath("$.apiOnly")
            .isEqualTo(true)
        client
            .get()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.name == '$name')].appendOnly")
            .value<List<Boolean>> { assertThat(it).containsExactly(true) }

        // a client that knows nothing of the rules saves a label: the rules stay
        putObject(name, mapOf("label" to "Renamed")).expectStatus().isOk
        getObject(name)
            .jsonPath("$.label")
            .isEqualTo("Renamed")
            .jsonPath("$.appendOnly")
            .isEqualTo(true)
            .jsonPath("$.apiOnly")
            .isEqualTo(true)

        putObject(name, mapOf("label" to "Renamed", "appendOnly" to false)).expectStatus().isOk
        getObject(name)
            .jsonPath("$.appendOnly")
            .isEqualTo(false)
            .jsonPath("$.apiOnly")
            .isEqualTo(true)

        // left out at creation, both are off
        val plain = uniqueName("plain")
        createObject(plain)
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.appendOnly")
            .isEqualTo(false)
            .jsonPath("$.apiOnly")
            .isEqualTo(false)
    }

    @Test
    fun `append-only takes a create and refuses ADMIN an update or a delete`() {
        val name = uniqueName("receipt")
        createObject(name, appendOnly = true).expectStatus().isCreated
        val id = createRecord(name, "R-1").expectStatus().isCreated.idOf()

        updateRecord(name, id, "R-1b").expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.detail").value<String> {
            assertThat(it).contains("append-only")
        }
        deleteRecord(name, id).expectStatus().isEqualTo(HttpStatus.CONFLICT)

        getRecord(name, id).jsonPath("$.attributes.codigo").isEqualTo("R-1")
        assertThat(auditOf(name)).containsExactly("CREATE")
    }

    @Test
    fun `append-only refuses the platform too`() {
        val name = uniqueName("receipt")
        createObject(name, appendOnly = true).expectStatus().isCreated
        val id = UUID.fromString(createRecord(name, "R-1").expectStatus().isCreated.idOf())

        assertThatThrownBy { runBlocking { records.asPlatform(organizationId) { records.update(name, id, RecordRequest(mapOf("codigo" to "X"))) } } }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { runBlocking { records.asPlatform(organizationId) { records.delete(name, id) } } }
            .isInstanceOf(ConflictException::class.java)
        // creating stays open to it
        runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "R-2"))) } }

        getRecord(name, id.toString()).jsonPath("$.attributes.codigo").isEqualTo("R-1")
        assertThat(auditOf(name)).containsExactly("CREATE", "CREATE")
    }

    @Test
    fun `append-only refuses a link or an unlink that would touch its records`() {
        val receipt = uniqueName("receipt")
        val tag = uniqueName("tag")
        createObject(receipt, appendOnly = true).expectStatus().isCreated
        createObject(tag).expectStatus().isCreated
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Tags", "type" to "MANY_TO_MANY", "source" to tag, "target" to receipt))
            .exchange()
            .expectStatus()
            .isCreated
        val r = createRecord(receipt, "R-1").expectStatus().isCreated.idOf()
        val t = createRecord(tag, "T-1").expectStatus().isCreated.idOf()

        // from either end: the receipt is written all the same
        client
            .post()
            .uri("/api/objects/$tag/records/$t/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("otherId" to r))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
        client
            .delete()
            .uri("/api/objects/$receipt/records/$r/related/$relationship/$t")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(auditOf(receipt)).containsExactly("CREATE")
    }

    @Test
    fun `append-only keeps its stored values from metadata deletes until the rule is switched off`() {
        val name = uniqueName("receipt")
        createObject(name, appendOnly = true).expectStatus().isCreated

        client
            .delete()
            .uri("/api/metadata/objects/$name/fields/codigo")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
        deleteObject(name).expectStatus().isEqualTo(HttpStatus.CONFLICT)

        putObject(name, mapOf("label" to name, "appendOnly" to false)).expectStatus().isOk
        deleteObject(name).expectStatus().isNoContent
    }

    @Test
    fun `append-only keeps its links when the other end, or the relationship, is deleted`() {
        val receipt = uniqueName("receipt")
        val tag = uniqueName("tag")
        createObject(receipt).expectStatus().isCreated
        createObject(tag).expectStatus().isCreated
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Tags", "type" to "MANY_TO_MANY", "source" to tag, "target" to receipt))
            .exchange()
            .expectStatus()
            .isCreated
        val r = createRecord(receipt, "R-1").expectStatus().isCreated.idOf()
        val t = createRecord(tag, "T-1").expectStatus().isCreated.idOf()
        client
            .post()
            .uri("/api/objects/$tag/records/$t/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("otherId" to r))
            .exchange()
            .expectStatus()
            .isNoContent
        // linked first, then the receipt becomes append-only: its links are its values now
        putObject(receipt, mapOf("label" to receipt, "appendOnly" to true)).expectStatus().isOk

        deleteObject(tag).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        client
            .delete()
            .uri("/api/relationships/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)

        client
            .get()
            .uri("/api/objects/$receipt/records/$r/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
    }

    @Test
    fun `a record an append-only relation column points at is not deleted`() {
        val customer = uniqueName("customer")
        val receipt = uniqueName("receipt")
        createObject(customer).expectStatus().isCreated
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to receipt,
                    "label" to receipt,
                    "appendOnly" to true,
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "customer", "type" to "RELATION", "relationTarget" to customer)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val c = createRecord(customer, "C-1").expectStatus().isCreated.idOf()
        val unused = createRecord(customer, "C-2").expectStatus().isCreated.idOf()
        val r =
            client
                .post()
                .uri("/api/objects/$receipt/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-1", "customer" to c)))
                .exchange()
                .expectStatus()
                .isCreated
                .idOf()

        // ON DELETE SET NULL would blank the receipt's customer behind its back
        deleteRecord(customer, c).expectStatus().isEqualTo(HttpStatus.CONFLICT).expectBody().jsonPath("$.detail").value<String> {
            assertThat(it).contains(receipt)
        }
        assertThatThrownBy { runBlocking { records.asPlatform(organizationId) { records.delete(customer, UUID.fromString(c)) } } }
            .isInstanceOf(ConflictException::class.java)
        getRecord(receipt, r).jsonPath("$.attributes.customer").isEqualTo(c)
        getRecord(customer, c).jsonPath("$.attributes.codigo").isEqualTo("C-1")
        assertThat(auditOf(customer)).containsExactly("CREATE", "CREATE")

        // nothing append-only points at this one
        deleteRecord(customer, unused).expectStatus().isNoContent
    }

    @Test
    fun `a record linked to an append-only one is not deleted`() {
        val receipt = uniqueName("receipt")
        val tag = uniqueName("tag")
        createObject(receipt).expectStatus().isCreated
        createObject(tag).expectStatus().isCreated
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Tags", "type" to "MANY_TO_MANY", "source" to tag, "target" to receipt))
            .exchange()
            .expectStatus()
            .isCreated
        val r = createRecord(receipt, "R-1").expectStatus().isCreated.idOf()
        val t = createRecord(tag, "T-1").expectStatus().isCreated.idOf()
        val unlinked = createRecord(tag, "T-2").expectStatus().isCreated.idOf()
        client
            .post()
            .uri("/api/objects/$tag/records/$t/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("otherId" to r))
            .exchange()
            .expectStatus()
            .isNoContent
        putObject(receipt, mapOf("label" to receipt, "appendOnly" to true)).expectStatus().isOk

        // ON DELETE CASCADE would drop the receipt's link with no history row
        deleteRecord(tag, t).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        client
            .get()
            .uri("/api/objects/$receipt/records/$r/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)

        deleteRecord(tag, unlinked).expectStatus().isNoContent
    }

    @Test
    fun `a guard veto aborts the write - nothing stored, nothing audited`() {
        val name = uniqueName("guarded")
        createObject(name).expectStatus().isCreated

        createRecord(name, "VETO")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Vetoed")
        val id = createRecord(name, "OK").expectStatus().isCreated.idOf()
        updateRecord(name, id, "VETO").expectStatus().isBadRequest
        val kept = createRecord(name, "KEEP").expectStatus().isCreated.idOf()
        deleteRecord(name, kept).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        // the platform is judged the same
        assertThatThrownBy { runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "VETO"))) } } }
            .isInstanceOf(ValidationException::class.java)

        getRecord(name, id).jsonPath("$.attributes.codigo").isEqualTo("OK")
        getRecord(name, kept).jsonPath("$.attributes.codigo").isEqualTo("KEEP")
        listRecords(name).jsonPath("$.totalElements").isEqualTo(2)
        assertThat(auditOf(name)).containsExactly("CREATE", "CREATE")
    }

    @Test
    fun `api-only refuses the generic record api, the app's own code still writes`() {
        val name = uniqueName("outbox")
        createObject(name, apiOnly = true).expectStatus().isCreated

        createRecord(name, "R-1").expectStatus().isForbidden
        // in-process, as the calling user: the app's endpoint
        val id =
            client
                .post()
                .uri("/app-endpoint/$name")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        // in-process, as the platform
        runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "BY-A-JOB"))) } }

        updateRecord(name, id, "X").expectStatus().isForbidden
        deleteRecord(name, id).expectStatus().isForbidden
        // reads are untouched
        getRecord(name, id).jsonPath("$.attributes.codigo").isEqualTo("BY-THE-APP")
        listRecords(name).jsonPath("$.totalElements").isEqualTo(2)
    }

    @Test
    fun `api-only refuses a link through the generic api`() {
        val outbox = uniqueName("outbox")
        val tag = uniqueName("tag")
        createObject(outbox, apiOnly = true).expectStatus().isCreated
        createObject(tag).expectStatus().isCreated
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Tags", "type" to "MANY_TO_MANY", "source" to tag, "target" to outbox))
            .exchange()
            .expectStatus()
            .isCreated
        val o = runBlocking { records.asPlatform(organizationId) { records.create(outbox, RecordRequest(mapOf("codigo" to "O-1"))) } }.id
        val t = createRecord(tag, "T-1").expectStatus().isCreated.idOf()

        client
            .post()
            .uri("/api/objects/$tag/records/$t/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("otherId" to o))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    private fun createObject(
        name: String,
        appendOnly: Boolean? = null,
        apiOnly: Boolean? = null
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                buildMap {
                    put("name", name)
                    put("label", name)
                    put("fields", listOf(mapOf("name" to "codigo", "type" to "TEXT")))
                    appendOnly?.let { put("appendOnly", it) }
                    apiOnly?.let { put("apiOnly", it) }
                }
            ).exchange()

    private fun putObject(
        name: String,
        body: Map<String, Any>
    ) = client
        .put()
        .uri("/api/objects/$name")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(body)
        .exchange()

    private fun getObject(name: String) =
        client
            .get()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun deleteObject(name: String) =
        client
            .delete()
            .uri("/api/objects/$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()

    private fun createRecord(
        name: String,
        codigo: String
    ) = client
        .post()
        .uri("/api/objects/$name/records")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
        .exchange()

    private fun updateRecord(
        name: String,
        id: String,
        codigo: String
    ) = client
        .put()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
        .exchange()

    private fun deleteRecord(
        name: String,
        id: String
    ) = client
        .delete()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()

    private fun getRecord(
        name: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun listRecords(name: String) =
        client
            .get()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun WebTestClient.ResponseSpec.idOf(): String =
        expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

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
