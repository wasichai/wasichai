package wasichai.notifications

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import wasichai.automation.AutomationDispatcher
import wasichai.automation.AutomationNotifier
import wasichai.automation.AutomationRunner
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID

// ADR-060: automation's NOTIFY action, served by this module. a STATE_ENTERED change goes through
// automation's own dispatcher and runner, as a workflow transition would send it; the full app's
// AutomationApiTest does the same through a real transition.
class AutomationNotifyTest : ChannelsIntegrationTest() {
    @Autowired
    private lateinit var notifier: AutomationNotifier

    @Autowired
    private lateinit var dispatcher: AutomationDispatcher

    @Autowired
    private lateinit var runner: AutomationRunner

    @Autowired
    private lateinit var metadata: MetadataService

    @Autowired
    private lateinit var inbox: InboxRepository

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        mail.reset()
        admin = bearer()
        objectName = uniqueName("tramite")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Tramite",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "responsable", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `the module serves automation's notify port`() {
        assertThat(notifier).isInstanceOf(AutomationNotifierAdapter::class.java)
        assertThat(notifier.available).isTrue()
    }

    @Test
    fun `a NOTIFY fired by STATE_ENTERED reaches the record's user and every holder of the role`(): Unit =
        runBlocking {
            val supervisor = role(DEMO)
            val owner = user(DEMO)
            val boss = user(DEMO, supervisor)
            val bystander = user(DEMO)
            val name = uniqueName("notify")
            save(
                name,
                mapOf(
                    "trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"),
                    "actions" to
                        listOf(
                            mapOf(
                                "type" to "NOTIFY",
                                "to" to "{{responsable}}, role:$supervisor",
                                "kind" to "warning",
                                "title" to "Tramite {{codigo}} aprobado",
                                "body" to "Pase a {{state}}"
                            )
                        )
                )
            ).expectStatus()
                .isCreated
                .expectBody()
                .jsonPath("$.definition.actions[0].type")
                .isEqualTo("NOTIFY")
                .jsonPath("$.definition.actions[0].to")
                .isEqualTo("{{responsable}}, role:$supervisor")
                .jsonPath("$.definition.actions[0].kind")
                .isEqualTo("WARNING")

            val recordId = UUID.randomUUID()
            enter(recordId, "aprobado", mapOf("codigo" to "T-7", "responsable" to owner.toString()))
            assertThat(runner.drainOnce(50)).isGreaterThanOrEqualTo(1)

            val forOwner = page(owner)
            assertThat(forOwner).hasSize(1)
            val item = forOwner.single()
            assertThat(item.kind).isEqualTo(NotificationKind.WARNING)
            assertThat(item.title).isEqualTo("Tramite T-7 aprobado")
            assertThat(item.body).isEqualTo("Pase a aprobado")
            assertThat(item.source).isEqualTo("automation:$name")
            assertThat(item.link?.objectName).isEqualTo(objectName)
            assertThat(item.link?.recordId).isEqualTo(recordId)
            assertThat(page(boss, supervisor).map { it.id }).containsExactly(item.id)
            assertThat(page(bystander)).isEmpty()

            // with the email channel on, both get it by email too
            assertThat(deliveries(item.id).map { it.userId }).containsExactlyInAnyOrder(owner, boss)

            // entering the state again is the same notification, not a second one
            enter(recordId, "aprobado", mapOf("codigo" to "T-7", "responsable" to owner.toString()))
            runner.drainOnce(50)
            assertThat(page(owner).map { it.id }).containsExactly(item.id)
        }

    @Test
    fun `NOTIFY needs a recipient and a title, and an ACTION is refused`() {
        listOf(
            mapOf("type" to "NOTIFY", "title" to "Sin destino"),
            mapOf("type" to "NOTIFY", "to" to "role:SUPERVISOR"),
            mapOf("type" to "NOTIFY", "to" to "role:SUPERVISOR", "title" to "x", "kind" to "ACTION"),
            mapOf("type" to "NOTIFY", "to" to "role:SUPERVISOR", "title" to "x", "kind" to "URGENT")
        ).forEach { action ->
            save(uniqueName("bad"), mapOf("trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"), "actions" to listOf(action)))
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `a recipient nobody is leaves the run succeeded with nobody notified`(): Unit =
        runBlocking {
            val name = uniqueName("notify")
            save(
                name,
                mapOf(
                    "trigger" to mapOf("type" to "STATE_ENTERED", "state" to "aprobado"),
                    "actions" to listOf(mapOf("type" to "NOTIFY", "to" to "{{responsable}}", "title" to "Aprobado"))
                )
            ).expectStatus()
                .isCreated
            enter(UUID.randomUUID(), "aprobado", mapOf("codigo" to "T-8"))
            runner.drainOnce(50)

            client
                .get()
                .uri("/api/objects/$objectName/automations/$name/runs")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[0].status")
                .isEqualTo("SUCCEEDED")
                .jsonPath("$[0].steps[0].action")
                .isEqualTo("NOTIFY")
                .jsonPath("$[0].steps[0].detail")
                .isEqualTo("notified nobody: no recipient in ''")
        }

    private fun save(
        name: String,
        definition: Map<String, Any>
    ) = client
        .post()
        .uri("/api/objects/$objectName/automations")
        .header(HttpHeaders.AUTHORIZATION, admin)
        .bodyValue(mapOf("name" to name, "label" to name, "definition" to definition))
        .exchange()

    // what a workflow transition tells core's listeners
    private suspend fun enter(
        recordId: UUID,
        state: String,
        after: Map<String, Any?>
    ) {
        val obj = metadata.loadDefinition(DEMO, objectName).obj
        dispatcher.recordChanged(
            RecordChange(
                organizationId = DEMO,
                userId = null,
                objectId = obj.id,
                objectName = objectName,
                recordId = recordId,
                kind = RecordChangeKind.TRANSITIONED,
                before = after,
                after = after,
                state = state,
                transition = "approve"
            )
        )
    }

    private suspend fun page(
        user: UUID,
        vararg roles: String
    ): List<InboxItem> =
        inbox
            .page(InboxReader(DEMO, user, roles.toList(), emptyList()), null, InboxState.ACTIVE, Instant.now(), PageRequest.of(0, 50))
            .content
            .map { it.item }
}
