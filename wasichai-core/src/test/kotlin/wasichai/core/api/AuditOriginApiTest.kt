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
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-050: every audit row says which request it came from and what wrote it. the id comes from the client when
// well formed, generated otherwise; the source only ever from code.
class AuditOriginApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var audit: AuditService

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var organizationId: UUID
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        val claims = runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }
        organizationId = UUID.fromString(claims.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        objectName = uniqueName("origin")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Origin",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "valor", "type" to "DECIMAL"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a request's own id comes back in the header and on every row it wrote`() {
        val id = uniqueId()

        val created = write(id) { post().uri("/api/objects/$objectName/records").bodyValue(attributes("C-1", 1)) }
        assertThat(created.responseHeaders.getFirst(ChangeOrigin.HEADER)).isEqualTo(id)
        val record =
            created.responseBody!!
                .decodeToString()
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
        write(id) { put().uri("/api/objects/$objectName/records/$record").bodyValue(attributes("C-2", 2)) }
        // another request's row stays out of the filter
        write(uniqueId()) { post().uri("/api/objects/$objectName/records").bodyValue(attributes("OTHER", 3)) }

        audit(admin, "/api/audit?correlationId=$id")
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[1].operation")
            .isEqualTo("CREATE")
            .jsonPath("$[*].correlationId")
            .isEqualTo(listOf(id, id))
            .jsonPath("$[*].source")
            .isEqualTo(listOf("api", "api"))
        // the history of the record says the same
        audit(admin, "/api/objects/$objectName/records/$record/history")
            .jsonPath("$[0].correlationId")
            .isEqualTo(id)
            .jsonPath("$[0].source")
            .isEqualTo("api")
    }

    @Test
    fun `without a header the generated id is in the response and on the row`() {
        val created = write(null) { post().uri("/api/objects/$objectName/records").bodyValue(attributes("G-1", 1)) }

        val generated = created.responseHeaders.getFirst(ChangeOrigin.HEADER)
        assertThat(UUID.fromString(generated)).isNotNull()
        audit(admin, "/api/audit?correlationId=$generated")
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].objectName")
            .isEqualTo(objectName)
            .jsonPath("$[0].source")
            .isEqualTo("api")
    }

    @Test
    fun `a malformed or oversized id is replaced, never echoed nor stored`() {
        listOf("has space", "<script>", "a;b", "x".repeat(65)).forEach { bad ->
            val created = write(bad) { post().uri("/api/objects/$objectName/records").bodyValue(attributes("B-1", 1)) }

            val echoed = created.responseHeaders.getFirst(ChangeOrigin.HEADER)
            assertThat(echoed).describedAs(bad).isNotEqualTo(bad)
            assertThat(UUID.fromString(echoed)).isNotNull()
            audit(admin, "/api/audit?correlationId=$echoed")
                .jsonPath("$.length()")
                .isEqualTo(1)
        }
        assertThat(stored()).allSatisfy { assertThat(UUID.fromString(it)).isNotNull() }
    }

    @Test
    fun `a 401 from the security chain carries the id too`() {
        val id = uniqueId()

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(ChangeOrigin.HEADER, id)
            .bodyValue(attributes("NOBODY", 1))
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectHeader()
            .valueEquals(ChangeOrigin.HEADER, id)

        // and a malformed one is replaced there as well
        val replaced =
            client
                .get()
                .uri("/api/audit")
                .header(ChangeOrigin.HEADER, "bad id")
                .exchange()
                .expectStatus()
                .isUnauthorized
                .returnResult(String::class.java)
                .responseHeaders
                .getFirst(ChangeOrigin.HEADER)
        assertThat(UUID.fromString(replaced)).isNotNull()
    }

    @Test
    fun `platform writes say platform, an app's own label says itself`() {
        runBlocking {
            records.asPlatform(organizationId) { records.create(objectName, RecordRequest(mapOf("codigo" to "P-1"))) }
            records.asPlatform(organizationId, source = "job:retention") { records.create(objectName, RecordRequest(mapOf("codigo" to "P-2"))) }
        }

        audit(admin, "/api/audit?objectName=$objectName&source=platform")
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].changes.length()")
            .isEqualTo(0)
            .jsonPath("$[0].correlationId")
            .doesNotExist()
        audit(admin, "/api/audit?objectName=$objectName&source=job:retention")
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].source")
            .isEqualTo("job:retention")
        audit(admin, "/api/audit?objectName=$objectName&source=api")
            .jsonPath("$.length()")
            .isEqualTo(0)
    }

    @Test
    fun `an in-process write nothing labelled says app`() {
        runBlocking { audit.record(organizationId, null, objectName, UUID.randomUUID(), AuditOperation.CREATE, after = mapOf("codigo" to "A-1")) }

        audit(admin, "/api/audit?objectName=$objectName")
            .jsonPath("$[0].source")
            .isEqualTo("app")
            .jsonPath("$[0].correlationId")
            .doesNotExist()
    }

    @Test
    fun `nothing a client sends sets the source`() {
        val id = uniqueId()

        client
            .post()
            .uri("/api/objects/$objectName/records?source=platform")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .header(ChangeOrigin.HEADER, id)
            .header("X-Source", "platform")
            .header("X-Change-Source", "platform")
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "S-1"), "source" to "platform"))
            .exchange()
            .expectStatus()
            .isCreated

        audit(admin, "/api/audit?correlationId=$id")
            .jsonPath("$[0].source")
            .isEqualTo("api")
    }

    @Test
    fun `rows written before ADR-050 read back without either`() {
        val record = UUID.randomUUID()
        runBlocking {
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.audit_log (organization_id, user_id, object_name, record_id, operation, after_state)
                    VALUES (:organizationId, NULL, :objectName, :recordId, 'CREATE', CAST(:after AS jsonb))
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("objectName", objectName)
                .bind("recordId", record)
                .bind("after", "{\"codigo\":\"OLD\"}")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        val body =
            audit(admin, "/api/audit?objectName=$objectName")
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].recordId")
                .isEqualTo(record.toString())
                .jsonPath("$[0].correlationId")
                .doesNotExist()
                .jsonPath("$[0].source")
                .doesNotExist()
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(body).doesNotContain("correlationId", "\"source\"")
    }

    @Test
    fun `the id filter still hides what field permissions hide, and admin rows from who may not read them`() {
        val role = "R" + uniqueName("").uppercase()
        val id = uniqueId()
        // the role and its grants are admin rows of this request (ADR-049): written by a person through the api
        write(id) { post().uri("/api/roles").bodyValue(mapOf("name" to role, "label" to "Blind", "ownRecordsOnly" to false)) }
        write(id) {
            put()
                .uri("/api/roles/$role/permissions")
                .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "READ", "allowed" to true))))
        }
        write(id) {
            put()
                .uri("/api/roles/$role/field-permissions")
                .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to "valor", "read" to false, "write" to false))))
        }
        val created = write(id) { post().uri("/api/objects/$objectName/records").bodyValue(attributes("F-1", 7)) }
        val record =
            created.responseBody!!
                .decodeToString()
                .substringAfter("\"id\":\"")
                .substringBefore("\"")
        write(id) { put().uri("/api/objects/$objectName/records/$record").bodyValue(attributes("F-2", 70)) }
        val email = "${uniqueName("blind")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Blind", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        val blind = bearer(email, "supersecret")

        // the administrator: every row of the request, admin ones included, all said api, the hidden field too
        val full =
            audit(admin, "/api/audit?correlationId=$id&limit=50")
                .jsonPath("$[?(@.objectName == 'admin:role')]")
                .isNotEmpty()
                .jsonPath("$[?(@.objectName == 'admin:permission')]")
                .isNotEmpty()
                .jsonPath("$[?(@.objectName == '$objectName')]")
                .isNotEmpty()
                .jsonPath("$[?(@.source != 'api')]")
                .isEmpty()
                .jsonPath("$[?(@.correlationId != '$id')]")
                .isEmpty()
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(full).contains("\"field\":\"valor\"")
        // the reader: only the record's rows, and the hidden field nowhere
        val body =
            audit(blind, "/api/audit?correlationId=$id&limit=50")
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[*].objectName")
                .isEqualTo(listOf(objectName, objectName))
                .jsonPath("$[0].changes.length()")
                .isEqualTo(1)
                .jsonPath("$[0].changes[0].field")
                .isEqualTo("codigo")
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(body).doesNotContain("valor")
    }

    // ------------------------------------------------------------------ helpers

    private fun uniqueId(): String = "req-" + uniqueName("")

    private fun attributes(
        codigo: String,
        valor: Int
    ) = mapOf("attributes" to mapOf("codigo" to codigo, "valor" to valor))

    // one request as the administrator, with [correlationId] when given; any 2xx
    private fun write(
        correlationId: String?,
        request: WebTestClient.() -> WebTestClient.RequestHeadersSpec<*>
    ) = client
        .request()
        .header(HttpHeaders.AUTHORIZATION, admin)
        .also { if (correlationId != null) it.header(ChangeOrigin.HEADER, correlationId) }
        .exchange()
        .expectStatus()
        .is2xxSuccessful
        .expectBody()
        .returnResult()

    private fun audit(
        token: String,
        uri: String
    ) = client
        .get()
        .uri(uri)
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    // every correlation id stored for this object
    private fun stored(): List<String> =
        runBlocking {
            db
                .sql("SELECT correlation_id FROM ${schemas.metadata}.audit_log WHERE organization_id = :organizationId AND object_name = :name")
                .bind("organizationId", organizationId)
                .bind("name", objectName)
                .map { row, _ -> row.get("correlation_id", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }
}
