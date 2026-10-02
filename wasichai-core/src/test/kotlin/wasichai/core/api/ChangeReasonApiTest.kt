package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-041: X-Change-Reason lands on the write's audit row and comes back from the audit api;
// requiresReason refuses a write without one, 400 on reason, before anything is stored.
class ChangeReasonApiTest : WasichaiIntegrationTest() {
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
    fun `requires-reason shows on the object and a put without it keeps it`() {
        val name = uniqueName("reasoned")
        createObject(name, requiresReason = true).expectStatus().isCreated

        getObject(name).jsonPath("$.requiresReason").isEqualTo(true)
        client
            .get()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectBody()
            .jsonPath("$[?(@.name == '$name')].requiresReason")
            .isEqualTo(true)
        putObject(name, mapOf("label" to "Renamed")).expectStatus().isOk
        getObject(name).jsonPath("$.requiresReason").isEqualTo(true)
        putObject(name, mapOf("label" to "Renamed", "requiresReason" to false)).expectStatus().isOk
        getObject(name).jsonPath("$.requiresReason").isEqualTo(false)
        createObject(uniqueName("plain")).expectBody().jsonPath("$.requiresReason").isEqualTo(false)
    }

    @Test
    fun `a reason is stored on each write's audit row and the audit api returns it`() {
        val name = uniqueName("reasoned")
        createObject(name).expectStatus().isCreated

        // trimming is ChangeReasonTest's: a client never sends a header value with edge whitespace
        val id = createRecord(name, "A", "alta inicial").expectStatus().isCreated.idOf()
        updateRecord(name, id, "B", "UTF-8''correcci%C3%B3n %E2%80%94 monto").expectStatus().isOk
        deleteRecord(name, id, "duplicado").expectStatus().isNoContent

        assertThat(auditOf(name)).containsExactly("CREATE" to "alta inicial", "UPDATE" to "corrección — monto", "DELETE" to "duplicado")
        history(name, id)
            .jsonPath("$[0].operation")
            .isEqualTo("DELETE")
            .jsonPath("$[0].reason")
            .isEqualTo("duplicado")
            .jsonPath("$[2].reason")
            .isEqualTo("alta inicial")
        client
            .get()
            .uri("/api/audit?objectName=$name&operation=UPDATE")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].reason")
            .isEqualTo("corrección — monto")
    }

    @Test
    fun `requires-reason refuses a write without one on reason - nothing stored`() {
        val name = uniqueName("reasoned")
        createObject(name, requiresReason = true).expectStatus().isCreated

        createRecord(name, "A", null).expectReasonRefused()
        createRecord(name, "A", "").expectReasonRefused()
        listRecords(name).jsonPath("$.totalElements").isEqualTo(0)

        val id = createRecord(name, "A", "alta").expectStatus().isCreated.idOf()
        updateRecord(name, id, "B", null).expectReasonRefused()
        deleteRecord(name, id, null).expectReasonRefused()

        getRecord(name, id).jsonPath("$.attributes.codigo").isEqualTo("A")
        assertThat(auditOf(name)).containsExactly("CREATE" to "alta")
        // a plain value is iso-8859-1, so spanish travels as is
        updateRecord(name, id, "B", "corrección").expectStatus().isOk
        deleteRecord(name, id, "baja").expectStatus().isNoContent
        assertThat(auditOf(name).map { it.second }).containsExactly("alta", "corrección", "baja")
    }

    @Test
    fun `without requires-reason a write needs none and its row has none`() {
        val name = uniqueName("plain")
        createObject(name).expectStatus().isCreated

        val id = createRecord(name, "A", null).expectStatus().isCreated.idOf()

        history(name, id).jsonPath("$[0].reason").isEmpty
        assertThat(auditOf(name)).containsExactly("CREATE" to null)
    }

    @Test
    fun `a reason over the cap is refused on reason`() {
        val name = uniqueName("plain")
        createObject(name).expectStatus().isCreated

        createRecord(name, "A", "x".repeat(ChangeReason.MAX_LENGTH + 1)).expectReasonRefused()
        listRecords(name).jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test
    fun `in-process the platform passes its reason, and is held to requires-reason`() {
        val name = uniqueName("reasoned")
        createObject(name, requiresReason = true).expectStatus().isCreated

        val refused = runCatching { runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "J"))) } } }
        runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "J")), "cierre nocturno") } }

        assertThat(refused.exceptionOrNull()).hasMessageContaining("requires a reason")
        assertThat(auditOf(name)).containsExactly("CREATE" to "cierre nocturno")
    }

    @Test
    fun `a link is held to requires-reason on either end, and both rows carry the reason`() {
        val ruled = uniqueName("reasoned")
        val tag = uniqueName("tag")
        createObject(ruled, requiresReason = true).expectStatus().isCreated
        createObject(tag).expectStatus().isCreated
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Tags", "type" to "MANY_TO_MANY", "source" to tag, "target" to ruled))
            .exchange()
            .expectStatus()
            .isCreated
        val r = createRecord(ruled, "R-1", "alta").expectStatus().isCreated.idOf()
        val t = createRecord(tag, "T-1", null).expectStatus().isCreated.idOf()

        // the titular end is the plain one: the other end still asks
        link(tag, t, relationship, r, null).expectReasonRefused()
        assertThat(auditOf(tag)).hasSize(1)
        link(tag, t, relationship, r, "etiquetado").expectStatus().isNoContent
        unlink(tag, t, relationship, r, null).expectReasonRefused()
        unlink(tag, t, relationship, r, "retiro").expectStatus().isNoContent

        assertThat(auditOf(tag)).containsExactly("CREATE" to null, "UPDATE" to "etiquetado", "UPDATE" to "retiro")
        assertThat(auditOf(ruled)).containsExactly("CREATE" to "alta", "UPDATE" to "etiquetado", "UPDATE" to "retiro")
    }

    private fun WebTestClient.ResponseSpec.expectReasonRefused() {
        expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("reason")
    }

    private fun createObject(
        name: String,
        requiresReason: Boolean? = null
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
                    requiresReason?.let { put("requiresReason", it) }
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

    private fun WebTestClient.RequestHeadersSpec<*>.reason(reason: String?): WebTestClient.RequestHeadersSpec<*> =
        if (reason == null) this else header(ChangeReason.HEADER, reason)

    private fun createRecord(
        name: String,
        codigo: String,
        reason: String?
    ) = client
        .post()
        .uri("/api/objects/$name/records")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
        .reason(reason)
        .exchange()

    private fun updateRecord(
        name: String,
        id: String,
        codigo: String,
        reason: String?
    ) = client
        .put()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
        .reason(reason)
        .exchange()

    private fun deleteRecord(
        name: String,
        id: String,
        reason: String?
    ) = client
        .delete()
        .uri("/api/objects/$name/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .reason(reason)
        .exchange()

    private fun link(
        name: String,
        id: String,
        relationship: String,
        otherId: String,
        reason: String?
    ) = client
        .post()
        .uri("/api/objects/$name/records/$id/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("otherId" to otherId))
        .reason(reason)
        .exchange()

    private fun unlink(
        name: String,
        id: String,
        relationship: String,
        otherId: String,
        reason: String?
    ) = client
        .delete()
        .uri("/api/objects/$name/records/$id/related/$relationship/$otherId")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .reason(reason)
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

    private fun history(
        name: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$name/records/$id/history")
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

    // (operation, reason) of the object's audit rows, oldest first
    private fun auditOf(name: String): List<Pair<String, String?>> =
        runBlocking {
            db
                .sql("SELECT operation, reason FROM ${schemas.metadata}.audit_log WHERE object_name = :name ORDER BY occurred_at, id")
                .bind("name", name)
                .map { row, _ -> row.get("operation", String::class.java)!! to row.get("reason", String::class.java) }
                .all()
                .collectList()
                .awaitSingle()
        }
}
