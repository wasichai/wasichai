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

    // the built-in write rules come first (ADR-040, ADR-041): a write that can never happen is refused as such
    @Test
    fun `an update of an append-only record is a 409 whatever its relation names`() {
        val ledger = uniqueName("ledger")
        createObject(admin, ledger, relationFields("customer"), mapOf("appendOnly" to true))
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))
        val r = createRecord(admin, ledger, mapOf("codigo" to "L-1", "customer" to c))

        client
            .put()
            .uri("/api/objects/$ledger/records/$r")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "L-1", "customer" to UUID.randomUUID().toString())))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `a missing reason is reported before a relation naming no record`() {
        val strict = uniqueName("strict")
        createObject(admin, strict, relationFields("customer"), mapOf("requiresReason" to true))

        client
            .post()
            .uri("/api/objects/$strict/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "S-1", "customer" to UUID.randomUUID().toString())))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors.length()")
            .isEqualTo(1)
            .jsonPath("$.errors[0].field")
            .isEqualTo("reason")
    }

    @Test
    fun `two relations to the same object are both named, in field order`() {
        val sale = uniqueName("sale")
        createObject(admin, sale, relationFields("buyer", "seller"))
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))

        client
            .post()
            .uri("/api/objects/$sale/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("seller" to UUID.randomUUID().toString(), "buyer" to UUID.randomUUID().toString())))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Invalid value for 'buyer', 'seller'")
            .jsonPath("$.errors.length()")
            .isEqualTo(2)
            .jsonPath("$.errors[0].field")
            .isEqualTo("buyer")
            .jsonPath("$.errors[1].field")
            .isEqualTo("seller")

        // one good, one not: only the bad one is named
        client
            .post()
            .uri("/api/objects/$sale/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("buyer" to c, "seller" to UUID.randomUUID().toString())))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors.length()")
            .isEqualTo(1)
            .jsonPath("$.errors[0].field")
            .isEqualTo("seller")
        listRecords(admin, sale).jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `a value that is no uuid is still the codec's 400`() {
        createReceipt(admin, mapOf("codigo" to "R-1", "customer" to "not-a-uuid"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")
            .jsonPath("$.errors[0].message")
            .isEqualTo("is not a UUID")
    }

    // issue 39 (ADR-031 D30): a caller may only name a record they can read. out of scope reads as missing.

    @Test
    fun `an own-records-only caller cannot name another user's record, and the answer is the one for a missing record`() {
        val role = newRole(ownRecordsOnly = true)
        grant(role, customer to "READ", customer to "CREATE", receipt to "READ", receipt to "CREATE")
        val member = newUserToken(role)
        val theirs = createRecord(admin, customer, mapOf("codigo" to "THEIRS"))
        val mine = createRecord(member, customer, mapOf("codigo" to "MINE"))

        val unseen = createReceipt(member, mapOf("codigo" to "R-1", "customer" to theirs)).expectStatus().isBadRequest.problem()
        val missing = createReceipt(member, mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())).expectStatus().isBadRequest.problem()

        assertThat(unseen).isEqualTo(missing)
        assertThat((unseen["errors"] as List<*>).map { (it as Map<*, *>)["field"] }).containsExactly("customer")
        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)
        assertThat(auditOf(receipt)).isEmpty()

        // their own record links
        createReceipt(member, mapOf("codigo" to "R-2", "customer" to mine)).expectStatus().isCreated
        // the admin's view is not narrowed by anyone's role
        createReceipt(admin, mapOf("codigo" to "R-3", "customer" to mine)).expectStatus().isCreated
    }

    @Test
    fun `a caller without READ on the target object cannot name any of its records`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, receipt to "READ", receipt to "CREATE")
        val member = newUserToken(role)
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))

        val unseen = createReceipt(member, mapOf("codigo" to "R-1", "customer" to c)).expectStatus().isBadRequest.problem()
        val missing = createReceipt(member, mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())).expectStatus().isBadRequest.problem()
        assertThat(unseen).isEqualTo(missing)
        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)

        // READ on it, and the same write goes through
        grant(role, receipt to "READ", receipt to "CREATE", customer to "READ")
        createReceipt(member, mapOf("codigo" to "R-1", "customer" to c)).expectStatus().isCreated
    }

    @Test
    fun `an org-wide READ covers every target object`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, null to "READ", receipt to "CREATE")
        val member = newUserToken(role)
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))

        createReceipt(member, mapOf("codigo" to "R-1", "customer" to c)).expectStatus().isCreated
    }

    @Test
    fun `a service account is scoped by its roles like a person`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, receipt to "READ", receipt to "CREATE")
        val account = serviceAccountToken(role)
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))

        val unseen = createReceipt(account, mapOf("codigo" to "R-1", "customer" to c)).expectStatus().isBadRequest.problem()
        val missing = createReceipt(account, mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())).expectStatus().isBadRequest.problem()
        assertThat(unseen).isEqualTo(missing)

        grant(role, receipt to "READ", receipt to "CREATE", customer to "READ")
        createReceipt(account, mapOf("codigo" to "R-1", "customer" to c)).expectStatus().isCreated
    }

    // keeping a value is no new claim on its target: the record was linked by someone who could see it
    @Test
    fun `an update that keeps a value the caller cannot read still goes through, changing it does not`() {
        val role = newRole(ownRecordsOnly = false)
        grant(role, receipt to "READ", receipt to "UPDATE")
        val member = newUserToken(role)
        val c = createRecord(admin, customer, mapOf("codigo" to "C-1"))
        val other = createRecord(admin, customer, mapOf("codigo" to "C-2"))
        val r = createRecord(admin, receipt, mapOf("codigo" to "R-1", "customer" to c))

        updateReceipt(member, r, mapOf("codigo" to "R-2", "customer" to c)).expectStatus().isOk

        updateReceipt(member, r, mapOf("codigo" to "R-3", "customer" to other))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("customer")
        getRecord(admin, receipt, r)
            .jsonPath("$.attributes.codigo")
            .isEqualTo("R-2")
            .jsonPath("$.attributes.customer")
            .isEqualTo(c)
    }

    @Test
    fun `the platform still names any record of the organization`() {
        val role = newRole(ownRecordsOnly = true)
        grant(role, customer to "READ", customer to "CREATE")
        val member = newUserToken(role)
        val theirs = createRecord(member, customer, mapOf("codigo" to "THEIRS"))

        val created =
            runBlocking {
                records.asPlatform(organizationId) { records.create(receipt, RecordRequest(mapOf("codigo" to "R-1", "customer" to theirs))) }
            }
        assertThat(created.attributes["customer"].toString()).isEqualTo(theirs)
    }

    // a platform record has no creator: an own-records-only caller cannot read it, so cannot name it either
    @Test
    fun `an own-records-only caller cannot name a record the platform created`() {
        val role = newRole(ownRecordsOnly = true)
        grant(role, customer to "READ", receipt to "READ", receipt to "CREATE")
        val member = newUserToken(role)
        val platformMade =
            runBlocking { records.asPlatform(organizationId) { records.create(customer, RecordRequest(mapOf("codigo" to "PLATFORM"))) } }.id

        val unseen = createReceipt(member, mapOf("codigo" to "R-1", "customer" to platformMade)).expectStatus().isBadRequest.problem()
        val missing = createReceipt(member, mapOf("codigo" to "R-1", "customer" to UUID.randomUUID().toString())).expectStatus().isBadRequest.problem()

        assertThat(unseen).isEqualTo(missing)
        listRecords(admin, receipt).jsonPath("$.totalElements").isEqualTo(0)
    }

    // own records only binds when every role says so, as for reads
    @Test
    fun `one role without own records only lifts the owner filter`() {
        val owner = newRole(ownRecordsOnly = true)
        grant(owner, customer to "READ", receipt to "READ", receipt to "CREATE")
        val plain = newRole(ownRecordsOnly = false)
        grant(plain, customer to "READ")
        val member = newUserToken(owner, plain)
        val theirs = createRecord(admin, customer, mapOf("codigo" to "THEIRS"))

        createReceipt(member, mapOf("codigo" to "R-1", "customer" to theirs)).expectStatus().isCreated
    }

    private fun newRole(ownRecordsOnly: Boolean): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Relations", "ownRecordsOnly" to ownRecordsOnly))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // replaces the role's grants with these. object null: every object (an org-wide row)
    private fun grant(
        role: String,
        vararg entries: Pair<String?, String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to entries.map { (target, action) -> mapOf("objectName" to target, "action" to action, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(vararg roles: String): String {
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    private fun serviceAccountToken(role: String): String {
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

    private fun relationFields(vararg names: String): List<Map<String, Any>> =
        listOf(mapOf("name" to "codigo", "type" to "TEXT")) + names.map { mapOf("name" to it, "type" to "RELATION", "relationTarget" to customer) }

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
        fields: List<Map<String, Any>>,
        flags: Map<String, Any> = emptyMap()
    ) = client
        .post()
        .uri("/api/objects")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields) + flags)
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
