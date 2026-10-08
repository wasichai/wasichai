package wasichai.automation

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// ADR-050: a run is drained later, off the request. it keeps the request's correlation id, so its writes carry it.
class AutomationDispatcherTest {
    private val organizationId = UUID.randomUUID()
    private val objectId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val schemas = WasichaiSchemas("wasichai", "app_data")
    private val json = JsonMapper.builder().build()

    private val automation =
        Automation(
            id = UUID.randomUUID(),
            organizationId = organizationId,
            objectId = objectId,
            name = "marca",
            label = "Marca",
            enabled = true,
            definition =
                AutomationDefinition(
                    trigger = AutomationTrigger(TriggerType.RECORD_CREATED),
                    actions = listOf(AutomationAction(ActionType.UPDATE_FIELD, field = "revisado", value = "si"))
                )
        )

    private val queued = mutableListOf<AutomationRun>()

    private val dispatcher =
        AutomationDispatcher(
            object : AutomationRepository(mock(DatabaseClient::class.java), json, schemas) {
                override suspend fun findEnabledByObject(
                    organizationId: UUID,
                    objectId: UUID
                ): List<Automation> = listOf(automation)
            },
            object : AutomationRunRepository(mock(DatabaseClient::class.java), json, schemas) {
                override suspend fun insert(run: AutomationRun): AutomationRun = run.also { queued += it }
            },
            AutomationProperties()
        )

    private val change =
        RecordChange(
            organizationId = organizationId,
            userId = userId,
            objectId = objectId,
            objectName = "orden",
            recordId = UUID.randomUUID(),
            kind = RecordChangeKind.CREATED,
            after = mapOf("numero" to "OC-1")
        )

    @Test
    fun `a run queued by a request keeps its correlation id and its user`() =
        runTest {
            ChangeOrigin.within(ChangeOrigin.API, "req-42") { dispatcher.recordChanged(change) }

            assertThat(queued.single().correlationId).isEqualTo("req-42")
            assertThat(queued.single().userId).isEqualTo(userId)
        }

    @Test
    fun `a run queued by a run keeps the first request's id`() =
        runTest {
            ChangeOrigin.within(ChangeOrigin.automation("otra"), "req-42") {
                dispatcher.recordChanged(change.copy(depth = 1, causedBy = UUID.randomUUID()))
            }

            assertThat(queued.single().correlationId).isEqualTo("req-42")
        }

    @Test
    fun `a change no request made queues a run with no id`() =
        runTest {
            dispatcher.recordChanged(change)

            assertThat(queued.single().correlationId).isNull()
        }
}
