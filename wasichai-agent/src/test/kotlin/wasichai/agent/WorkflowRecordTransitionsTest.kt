package wasichai.agent

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditService
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.RelationTargets
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleDirectory
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.WasichaiSchemas
import wasichai.workflow.AvailableTransition
import wasichai.workflow.WorkflowRepository
import wasichai.workflow.WorkflowService
import java.util.UUID

class WorkflowRecordTransitionsTest {
    private val available =
        listOf(
            AvailableTransition("finish", "Finish", "done", "Done", true),
            AvailableTransition("reject", "Reject", "rejected", "Rejected", false, "requires role 'reviewer'")
        )

    private val workflows =
        object : WorkflowService(
            mock(RoleDirectory::class.java),
            mock(WorkflowRepository::class.java),
            mock(MetadataService::class.java),
            mock(ObjectSchemaManager::class.java),
            mock(RecordStore::class.java),
            mock(AuditService::class.java),
            mock(CurrentUser::class.java),
            mock(AccessPolicy::class.java),
            emptyList(),
            RecordWriteGuards(
                emptyList(),
                RelationTargets(mock(DatabaseClient::class.java), mock(WasichaiSchemas::class.java), mock(CustomObjectRepository::class.java))
            )
        ) {
            override suspend fun transitionsOf(
                objectName: String,
                id: UUID
            ): List<AvailableTransition> = available
        }

    // the tool answer must read exactly as it did when the agent called WorkflowService itself
    @Test
    fun `transitions reach the assistant with the same json`() =
        runTest {
            val mapper = JsonMapper.builder().build()
            val answered = WorkflowRecordTransitions(workflows).transitionsOf("predio", UUID.randomUUID())
            assertThat(answered.count { it.allowed }).isEqualTo(1)
            assertThat(mapper.writeValueAsString(answered)).isEqualTo(mapper.writeValueAsString(available))
        }
}
