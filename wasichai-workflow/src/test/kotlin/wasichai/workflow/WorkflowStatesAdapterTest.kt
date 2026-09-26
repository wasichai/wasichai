package wasichai.workflow

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

class WorkflowStatesAdapterTest {
    private val org = UUID.randomUUID()
    private val obj = UUID.randomUUID()

    private fun adapterWith(workflow: Workflow?) =
        WorkflowStatesAdapter(
            object : WorkflowRepository(mock(DatabaseClient::class.java), JsonMapper.builder().build(), WasichaiSchemas("wasichai", "app_data")) {
                override suspend fun findByObject(
                    organizationId: UUID,
                    objectId: UUID
                ): Workflow? = workflow
            }
        )

    private fun workflow(enabled: Boolean) =
        Workflow(
            id = UUID.randomUUID(),
            organizationId = org,
            objectId = obj,
            name = "tramite",
            label = "Trámite",
            enabled = enabled,
            definition =
                WorkflowDefinition(
                    states = listOf(WorkflowState("draft", "Draft", StateType.INITIAL), WorkflowState("done", "Done", StateType.FINAL)),
                    transitions = listOf(WorkflowTransition("finish", "Finish", "draft", "done"))
                )
        )

    @Test
    fun `no workflow, no state and nothing to fire`() =
        runTest {
            val adapter = adapterWith(null)
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState.NONE)
            assertThat(adapter.transitionNames(org, obj)).isEmpty()
        }

    @Test
    fun `an enabled workflow starts records in its initial state`() =
        runTest {
            val adapter = adapterWith(workflow(enabled = true))
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState(attached = true, initialState = "draft"))
            assertThat(adapter.transitionNames(org, obj)).containsExactly("finish")
        }

    @Test
    fun `a disabled workflow keeps the column but starts nothing and fires nothing`() =
        runTest {
            val adapter = adapterWith(workflow(enabled = false))
            assertThat(adapter.stateOf(org, obj)).isEqualTo(ObjectWorkflowState(attached = true, initialState = null))
            assertThat(adapter.transitionNames(org, obj)).isEmpty()
        }
}
