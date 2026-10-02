package wasichai.it.slice.automation

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.TestPropertySource
import wasichai.automation.AutomationRunner
import wasichai.automation.DocumentIssuer
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.identity.JwtService
import wasichai.it.support.SliceSmokeTest
import java.util.UUID

// no background drain: the test drives the runner by hand
@TestPropertySource(properties = ["wasichai.automation.poll-interval=0s"])
class AutomationOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("automation")

    @Autowired
    private lateinit var runner: AutomationRunner

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    // only wasichai-documents declares one; without it automation falls back to NoDocumentIssuer
    @Autowired
    private lateinit var documentIssuers: ObjectProvider<DocumentIssuer>

    @Test
    fun `a rule runs on record creation without documents installed`() {
        val name = revisionWithRule()
        val created =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "A-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        assertRevised(name, id)
    }

    // ADR-039: a platform write has no user, and the rule it triggers acts as the platform too
    @Test
    fun `a rule runs on a record the platform created`() {
        val name = revisionWithRule()
        val organizationId =
            runBlocking { UUID.fromString(decoder.decode(admin.removePrefix("Bearer ")).awaitSingle().getClaimAsString(JwtService.CLAIM_ORGANIZATION)) }
        val id = runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "P-1"))) } }.id

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        assertRevised(name, id)
    }

    private fun revisionWithRule(): String {
        val name = uniqueName("revision")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Revision",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "revisado", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$name/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("auto"),
                    "label" to "Automatizacion",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "UPDATE_FIELD", "field" to "revisado", "value" to "si"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun assertRevised(
        name: String,
        id: String
    ) {
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.revisado")
            .isEqualTo("si")
    }

    @Test
    fun `no document issuer bean exists, so GENERATE_DOCUMENT is refused when saved`() {
        // the refusal alone cannot tell the fallback from a real issuer that lacks the type
        assertThat(documentIssuers.getIfAvailable()).isNull()
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("auto"),
                    "label" to "Automatizacion",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "GENERATE_DOCUMENT", "documentType" to "fantasma"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains("Unknown document type 'fantasma'") }
    }
}
