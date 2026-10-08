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
import wasichai.core.platform.ChangeOrigin
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

    // ADR-050: the run is drained later, off the request, and still carries the request's id
    @Test
    fun `a rule's write carries the request's correlation id and says which rule wrote it`() {
        val rule = uniqueName("auto")
        val name = revisionWithRule(rule)
        val correlationId = "req-" + uniqueName("")
        val id =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .header(ChangeOrigin.HEADER, correlationId)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectHeader()
                .valueEquals(ChangeOrigin.HEADER, correlationId)
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"id\":\"")
                .substringBefore("\"")

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        assertRevised(name, id)
        client
            .get()
            .uri("/api/audit?correlationId=$correlationId")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].source")
            .isEqualTo("automation:$rule")
            .jsonPath("$[0].reason")
            .isEqualTo("automation '$rule'")
            .jsonPath("$[0].recordId")
            .isEqualTo(id)
            .jsonPath("$[1].operation")
            .isEqualTo("CREATE")
            .jsonPath("$[1].source")
            .isEqualTo("api")
            .jsonPath("$[*].correlationId")
            .isEqualTo(listOf(correlationId, correlationId))
        client
            .get()
            .uri("/api/audit?correlationId=$correlationId&source=automation:$rule")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
    }

    // ADR-050: no request, no id; the platform's row and the rule's each say who wrote them
    @Test
    fun `a rule on a platform write says automation, the platform's row says platform`() {
        val rule = uniqueName("auto")
        val name = revisionWithRule(rule)
        val organizationId =
            runBlocking { UUID.fromString(decoder.decode(admin.removePrefix("Bearer ")).awaitSingle().getClaimAsString(JwtService.CLAIM_ORGANIZATION)) }
        runBlocking { records.asPlatform(organizationId) { records.create(name, RecordRequest(mapOf("codigo" to "P-1"))) } }

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        client
            .get()
            .uri("/api/audit?objectName=$name")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].source")
            .isEqualTo(listOf("automation:$rule", "platform"))
            .jsonPath("$[0].correlationId")
            .doesNotExist()
            .jsonPath("$[1].correlationId")
            .doesNotExist()
    }

    // issue 60: what CREATE_RECORD leaves out takes the field's default, a required one and a locked one included
    @Test
    fun `a rule's CREATE_RECORD stores the target's defaults`() {
        val target = uniqueName("bitacora")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to target,
                    "label" to "Bitacora",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "estado", "type" to "TEXT", "required" to true, "defaultValue" to "PENDIENTE"),
                            mapOf("name" to "prioridad", "type" to "INTEGER", "defaultValue" to "2"),
                            mapOf("name" to "origen", "type" to "TEXT", "editable" to false, "defaultValue" to "regla")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        // the required estado is left out: its default fills it, so the rule is accepted
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("auto"),
                    "label" to "Bitacora",
                    "definition" to
                        mapOf(
                            "trigger" to mapOf("type" to "RECORD_CREATED"),
                            "actions" to listOf(mapOf("type" to "CREATE_RECORD", "targetObject" to target, "values" to mapOf("codigo" to "LOG-{{codigo}}")))
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "D-1")))
            .exchange()
            .expectStatus()
            .isCreated

        assertThat(runBlocking { runner.drainOnce(50) }).isGreaterThanOrEqualTo(1)

        client
            .get()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("LOG-D-1")
            .jsonPath("$.content[0].attributes.estado")
            .isEqualTo("PENDIENTE")
            .jsonPath("$.content[0].attributes.prioridad")
            .isEqualTo(2)
            .jsonPath("$.content[0].attributes.origen")
            .isEqualTo("regla")
    }

    private fun revisionWithRule(rule: String = uniqueName("auto")): String {
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
                    "name" to rule,
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
