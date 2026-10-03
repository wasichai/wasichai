package wasichai.it.full

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import wasichai.automation.AutomationRunner
import wasichai.core.common.ValidationException
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import java.util.UUID

// ADR-040: an automation writes as the platform, through RecordStore. appendOnly and every
// RecordWriteGuard hold it all the same; apiOnly does not, it is in-process. ADR-041: it has no user to
// ask for a reason, so it gives its own, "automation '<name>'", and requiresReason holds it like anyone.
@Import(AutomationWriteRulesTest.GuardConfig::class)
class AutomationWriteRulesTest : FullAppIntegrationTest() {
    @TestConfiguration
    class GuardConfig {
        @Bean
        fun noVetoCodes(): RecordWriteGuard =
            object : RecordWriteGuard {
                override suspend fun beforeWrite(change: RecordWrite) {
                    if (change.attributes?.get("codigo") == "LOG-VETO") throw ValidationException("Vetoed", "codigo", "the guard said no")
                }
            }
    }

    @Autowired
    private lateinit var runner: AutomationRunner

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `an automation may not update an append-only record`() {
        val name = uniqueName("autoreceipt")
        createObject(name, appendOnly = true)
        val automation = save(name, mapOf("type" to "UPDATE_FIELD", "field" to "uso", "value" to "changed"))

        val id = createRecord(name, "A-1")
        assertThat(drain()).isEqualTo(1)

        runOf(name, automation)
            .jsonPath("$[0].status")
            .isEqualTo("FAILED")
            .jsonPath("$[0].error")
            .value<String> { assertThat(it).contains("append-only") }
        record(name, id).jsonPath("$.attributes.uso").isEmpty
    }

    @Test
    fun `an automation creates on an api-only object, and a guard veto fails the run`() {
        val source = uniqueName("autosrc")
        val target = uniqueName("autooutbox")
        createObject(source, appendOnly = false)
        createObject(target, appendOnly = false, apiOnly = true)
        val automation = save(source, mapOf("type" to "CREATE_RECORD", "targetObject" to target, "values" to mapOf("codigo" to "LOG-{{codigo}}")))

        createRecord(source, "OK")
        createRecord(source, "VETO")
        assertThat(drain()).isEqualTo(2)

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
            .isEqualTo("LOG-OK")
        runOf(source, automation)
            .jsonPath("$[?(@.status == 'FAILED')].error")
            .value<List<String>> { assertThat(it.single()).contains("Vetoed") }
    }

    @Test
    fun `an automation writes a requires-reason object, its rows say which automation`() {
        val source = uniqueName("autoreasonsrc")
        val target = uniqueName("autoreasontgt")
        createObject(source, appendOnly = false, requiresReason = true)
        createObject(target, appendOnly = false, requiresReason = true)
        val updater = save(source, mapOf("type" to "UPDATE_FIELD", "field" to "uso", "value" to "visto"))
        val creator = save(source, mapOf("type" to "CREATE_RECORD", "targetObject" to target, "values" to mapOf("codigo" to "LOG-{{codigo}}")))

        val id = createRecord(source, "A-1", "alta")
        assertThat(drain()).isEqualTo(2)

        runOf(source, updater).jsonPath("$[0].status").isEqualTo("SUCCEEDED")
        runOf(source, creator).jsonPath("$[0].status").isEqualTo("SUCCEEDED")
        history(source, id)
            .jsonPath("$[?(@.operation == 'CREATE')].reason")
            .isEqualTo("alta")
            .jsonPath("$[?(@.operation == 'UPDATE')].reason")
            .isEqualTo("automation '$updater'")
        client
            .get()
            .uri("/api/audit?objectName=$target")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[0].reason")
            .isEqualTo("automation '$creator'")
    }

    // D29: a rendered RELATION value naming no record fails the run on the field, through the same check
    // as the record api, not on the foreign key. one naming a record still writes.
    @Test
    fun `an automation writing a relation that names no record fails the run on the field`() {
        val customer = uniqueName("autocustomer")
        val source = uniqueName("autorelsrc")
        val target = uniqueName("autoreltgt")
        createObject(customer, appendOnly = false)
        createRelationObject(source, customer)
        createRelationObject(target, customer)
        val known = createRecord(customer, "C-1")
        val nobody = UUID.randomUUID().toString()
        val updater = save(source, mapOf("type" to "UPDATE_FIELD", "field" to "cliente", "value" to nobody))
        val creator = save(source, mapOf("type" to "CREATE_RECORD", "targetObject" to target, "values" to mapOf("codigo" to "BAD", "cliente" to nobody)))
        val linker = save(source, mapOf("type" to "CREATE_RECORD", "targetObject" to target, "values" to mapOf("codigo" to "OK", "cliente" to known)))

        val id = createRecord(source, "S-1")
        assertThat(drain()).isEqualTo(3)

        runOf(source, updater)
            .jsonPath("$[0].status")
            .isEqualTo("FAILED")
            .jsonPath("$[0].error")
            .value<String> { assertThat(it).contains("Invalid value for 'cliente'") }
        runOf(source, creator)
            .jsonPath("$[0].status")
            .isEqualTo("FAILED")
            .jsonPath("$[0].error")
            .value<String> { assertThat(it).contains("Invalid value for 'cliente'") }
        runOf(source, linker).jsonPath("$[0].status").isEqualTo("SUCCEEDED")
        record(source, id).jsonPath("$.attributes.cliente").isEmpty
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
            .jsonPath("$.content[0].attributes.cliente")
            .isEqualTo(known)
    }

    private fun createRelationObject(
        name: String,
        customer: String
    ) {
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
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "cliente", "type" to "RELATION", "relationTarget" to customer)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun history(
        objectName: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$objectName/records/$id/history")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun drain(): Int = runBlocking { runner.drainOnce(50) }

    private fun createObject(
        name: String,
        appendOnly: Boolean,
        apiOnly: Boolean = false,
        requiresReason: Boolean = false
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Recibo",
                    "appendOnly" to appendOnly,
                    "apiOnly" to apiOnly,
                    "requiresReason" to requiresReason,
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "uso", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun save(
        objectName: String,
        action: Map<String, Any>
    ): String {
        val name = uniqueName("auto")
        client
            .post()
            .uri("/api/objects/$objectName/automations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Automatizacion",
                    "definition" to mapOf("trigger" to mapOf("type" to "RECORD_CREATED"), "actions" to listOf(action))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(
        objectName: String,
        codigo: String,
        reason: String? = null
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .headers { headers -> reason?.let { headers.set(ChangeReason.HEADER, it) } }
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun runOf(
        objectName: String,
        automation: String
    ) = client
        .get()
        .uri("/api/objects/$objectName/automations/$automation/runs")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun record(
        objectName: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$objectName/records/$id")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()
}
