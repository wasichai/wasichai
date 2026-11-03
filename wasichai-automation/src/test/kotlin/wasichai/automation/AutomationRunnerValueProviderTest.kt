package wasichai.automation

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.ObjectMapper
import wasichai.core.audit.AuditService
import wasichai.core.common.PageResponse
import wasichai.core.data.NoWorkflowStates
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordValueProvider
import wasichai.core.data.RecordValueProviders
import wasichai.core.data.RecordWriteGuards
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// ADR-077: CREATE_RECORD writes through the store, not RecordService, and asks the providers all the same, with nobody as the caller
class AutomationRunnerValueProviderTest {
    private val organizationId = UUID.randomUUID()
    private val triggeredBy = UUID.randomUUID()
    private val objectId = UUID.randomUUID()
    private val obj = CustomObject(objectId, organizationId, "bitacora", "Bitacora", "Bitacoras", null, true, "bitacora__1234abcd", null, null)

    private fun field(
        name: String,
        editable: Boolean = true
    ) = CustomField(UUID.randomUUID(), objectId, name, name, FieldType.TEXT, name, false, false, null, null, 0, null, null, emptyMap(), true, editable)

    private val definition = ObjectDefinition(obj, listOf(field("codigo"), field("origen", editable = false)))

    private class Insert(
        val writable: Set<String>,
        val attributes: Map<String, Any?>
    )

    private val inserts = mutableListOf<Insert>()
    private val finished = mutableListOf<RunStatus>()

    private val store =
        object : RecordStore {
            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow {
                inserts +=
                    Insert(
                        definition.fields
                            .filter { it.editable }
                            .map { it.name }
                            .toSet(),
                        attributes
                    )
                return RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), attributes)
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow = error("not this action")

            override suspend fun transitionState(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                id: UUID,
                from: String?,
                to: String
            ): RecordRow? = null

            override suspend fun delete(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID
            ) = true

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow? = null

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> = PageResponse.of(emptyList(), 0, 25, 0)
        }

    private suspend fun runner(values: RecordValueProviders): AutomationRunner {
        val automation =
            Automation(
                UUID.randomUUID(),
                organizationId,
                objectId,
                "log",
                "Log",
                true,
                AutomationDefinition(
                    AutomationTrigger(TriggerType.RECORD_CREATED),
                    actions =
                        listOf(
                            AutomationAction(
                                ActionType.CREATE_RECORD,
                                targetObject = "bitacora",
                                values =
                                    mapOf(
                                        "codigo" to "LOG",
                                        "origen" to "FROM-RULE"
                                    )
                            )
                        )
                )
            )
        val run =
            AutomationRun(
                UUID.randomUUID(),
                organizationId,
                automation.id,
                "fuente",
                UUID.randomUUID(),
                TriggerType.RECORD_CREATED,
                RunStatus.RUNNING,
                0,
                RunPayload(UUID.randomUUID()),
                userId = triggeredBy
            )
        val automations = mock(AutomationRepository::class.java)
        val runs =
            object : AutomationRunRepository(mock(DatabaseClient::class.java), mock(ObjectMapper::class.java), WasichaiSchemas("wasichai", "app_data")) {
                override suspend fun claim(limit: Int) = listOf(run)

                override suspend fun finish(
                    id: UUID,
                    status: RunStatus,
                    steps: List<RunStep>,
                    error: String?
                ) {
                    finished += status
                }
            }
        val metadata = mock(MetadataService::class.java)
        doReturn(automation).`when`(automations).findById(organizationId, automation.id)
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "bitacora")
        return AutomationRunner(
            automations,
            runs,
            metadata,
            store,
            NoWorkflowStates(),
            mock(AuditService::class.java),
            mock(AutomationDispatcher::class.java),
            mock(WebhookSender::class.java),
            mock(DocumentIssuer::class.java),
            mock(RecordWriteGuards::class.java),
            FieldTypeRegistry(emptyList()),
            values = values
        )
    }

    @Test
    fun `an automation asks with nobody as the caller, even when a person triggered it, and the value lands on a locked field`() =
        runTest {
            val callers = mutableListOf<AuthenticatedUser?>()
            val fixing =
                object : RecordValueProvider {
                    override suspend fun valuesFor(
                        definition: ObjectDefinition,
                        attributes: Map<String, Any?>,
                        caller: AuthenticatedUser?
                    ): Map<String, Any?> {
                        callers += caller
                        assertThat(attributes).containsEntry("origen", "FROM-RULE")
                        return mapOf("origen" to "SERVER")
                    }
                }

            runner(RecordValueProviders(listOf(fixing))).drainOnce(1)

            assertThat(finished).containsExactly(RunStatus.SUCCEEDED)
            assertThat(callers).containsExactly(null)
            assertThat(inserts.single().attributes).containsEntry("codigo", "LOG").containsEntry("origen", "SERVER")
            assertThat(inserts.single().writable).contains("origen")
        }

    @Test
    fun `without a provider an automation writes what it did, a locked field stays locked`() =
        runTest {
            runner(RecordValueProviders.NONE).drainOnce(1)

            assertThat(finished).containsExactly(RunStatus.SUCCEEDED)
            assertThat(inserts.single().attributes).isEqualTo(mapOf("codigo" to "LOG", "origen" to "FROM-RULE"))
            assertThat(inserts.single().writable).containsExactly("codigo")
        }

    @Test
    fun `a provider's bad key fails the run and writes nothing`() =
        runTest {
            val rogue =
                object : RecordValueProvider {
                    override suspend fun valuesFor(
                        definition: ObjectDefinition,
                        attributes: Map<String, Any?>,
                        caller: AuthenticatedUser?
                    ): Map<String, Any?> = mapOf("created_by" to "nobody")
                }

            runner(RecordValueProviders(listOf(rogue))).drainOnce(1)

            assertThat(finished).containsExactly(RunStatus.FAILED)
            assertThat(inserts).isEmpty()
        }
}
