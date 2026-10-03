package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// ADR-040: appendOnly refuses UPDATE and DELETE for everyone, a RecordWriteGuard vetoes before the
// store write, apiOnly closes the generic record api and nothing else. ADR-041: requiresReason refuses
// a write with no reason before the store write; a reason given lands on the audit row.
class RecordWriteRulesTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val admin = AuthenticatedUser(UUID.randomUUID(), organizationId, "admin@example.com", listOf(AuthenticatedUser.ADMIN_ROLE))

    private val writes = mutableListOf<String>()
    private val rows = linkedMapOf<UUID, Map<String, Any?>>()
    private val audited = mutableListOf<AuditOperation>()
    private val reasons = mutableListOf<String?>()
    private val changes = mutableListOf<RecordChange>()
    private val seen = mutableListOf<RecordWrite>()

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
                writes += "insert"
                val id = UUID.randomUUID()
                rows[id] = attributes
                return RecordRow(id, Instant.now(), Instant.now(), attributes)
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow {
                writes += "update"
                rows[id] = attributes
                return RecordRow(id, Instant.now(), Instant.now(), attributes)
            }

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
            ): Boolean {
                writes += "delete"
                return rows.remove(id) != null
            }

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean
            ): RecordRow? = rows[id]?.let { RecordRow(id, Instant.now(), Instant.now(), it) }

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> = PageResponse.of(emptyList(), 0, 25, 0)
        }

    private val audit =
        object : AuditService(mock(DatabaseClient::class.java), JsonMapper.builder().build(), WasichaiSchemas("wasichai", "app_data")) {
            override suspend fun record(
                organizationId: UUID,
                userId: UUID?,
                objectName: String,
                recordId: UUID?,
                operation: AuditOperation,
                before: Any?,
                after: Any?,
                documentId: UUID?,
                reason: String?
            ) {
                audited += operation
                reasons += reason
            }
        }

    // records the write, and vetoes any codigo that says so
    private val guard =
        object : RecordWriteGuard {
            override suspend fun beforeWrite(change: RecordWrite) {
                seen += change
                if (change.attributes?.get("codigo") == "VETO") throw ValidationException("vetoed", "codigo", "no")
            }
        }

    private suspend fun service(
        appendOnly: Boolean = false,
        apiOnly: Boolean = false,
        requiresReason: Boolean = false
    ): RecordService {
        val definition =
            ObjectDefinition(
                ObjectDefinitionFixtures.obj.copy(appendOnly = appendOnly, apiOnly = apiOnly, requiresReason = requiresReason),
                listOf(codigo)
            )
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(admin).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(FieldAccess.FULL).`when`(access).fieldAccess(admin, definition.obj.id)
        val listener =
            object : RecordChangeListener {
                override suspend fun recordChanged(change: RecordChange) {
                    changes += change
                }
            }
        return RecordService(
            metadata,
            store,
            audit,
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            listOf(listener),
            RecordWriteGuards(listOf(guard)),
            AppendOnlyReferencesFixtures.none()
        )
    }

    private fun stored(codigo: String): UUID = UUID.randomUUID().also { rows[it] = mapOf("codigo" to codigo) }

    @Test
    fun `append-only takes a create, and refuses ADMIN an update or a delete with a conflict`() =
        runTest {
            val records = service(appendOnly = true)
            records.create("predio", RecordRequest(mapOf("codigo" to "R-1")))
            val id = stored("R-0")

            val update = runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "R-0b"))) }
            val delete = runCatching { records.delete("predio", id) }

            assertThat(update.exceptionOrNull()).isInstanceOf(ConflictException::class.java)
            assertThat(delete.exceptionOrNull()).isInstanceOf(ConflictException::class.java)
            assertThat(writes).containsExactly("insert")
            assertThat(audited).containsExactly(AuditOperation.CREATE)
            assertThat(rows[id]).isEqualTo(mapOf("codigo" to "R-0"))
        }

    @Test
    fun `append-only refuses the platform too`() =
        runTest {
            val records = service(appendOnly = true)
            val id = stored("R-0")

            val refused =
                records.asPlatform(organizationId) {
                    listOf(
                        runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "X"))) },
                        runCatching { records.delete("predio", id) }
                    )
                }

            assertThat(refused).allSatisfy { assertThat(it.exceptionOrNull()).isInstanceOf(ConflictException::class.java) }
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
        }

    @Test
    fun `a guard sees every write before the store, with what it is about to write`() =
        runTest {
            val records = service()
            val created = UUID.fromString(records.create("predio", RecordRequest(mapOf("codigo" to "A"))).id)
            records.update("predio", created, RecordRequest(mapOf("codigo" to "B")))
            records.delete("predio", created)

            assertThat(seen.map { it.kind }).containsExactly(RecordChangeKind.CREATED, RecordChangeKind.UPDATED, RecordChangeKind.DELETED)
            assertThat(seen[0].recordId).isNull()
            assertThat(seen[0].attributes).isEqualTo(mapOf("codigo" to "A"))
            assertThat(seen[1].recordId).isEqualTo(created)
            assertThat(seen[1].before).isEqualTo(mapOf("codigo" to "A"))
            assertThat(seen[1].attributes).isEqualTo(mapOf("codigo" to "B"))
            assertThat(seen[2].before).isEqualTo(mapOf("codigo" to "B"))
            assertThat(seen[2].attributes).isNull()
            assertThat(seen).allSatisfy {
                assertThat(it.organizationId).isEqualTo(organizationId)
                assertThat(it.userId).isEqualTo(admin.userId)
                assertThat(it.objectName).isEqualTo("predio")
            }
        }

    @Test
    fun `a guard that throws aborts the write - nothing stored, nothing audited, no listener`() =
        runTest {
            val records = service()
            val id = stored("R-0")

            val create = runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "VETO"))) }
            val update = runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "VETO"))) }

            assertThat(create.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(update.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
            assertThat(changes).isEmpty()
        }

    @Test
    fun `a guard judges platform writes too`() =
        runTest {
            val records = service()

            val vetoed = records.asPlatform(organizationId) { runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "VETO"))) } }

            assertThat(vetoed.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(seen.single().userId).isNull()
            assertThat(writes).isEmpty()
        }

    @Test
    fun `api-only refuses writes through the generic api, in-process calls still write`() =
        runTest {
            val records = service(apiOnly = true)
            val id = stored("R-0")

            val refused =
                listOf(
                    runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "X")), reason = null, viaApi = true) },
                    runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "X")), reason = null, viaApi = true) },
                    runCatching { records.delete("predio", id, reason = null, viaApi = true) }
                )
            assertThat(refused).allSatisfy { assertThat(it.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java) }
            assertThat(writes).isEmpty()

            val created = records.create("predio", RecordRequest(mapOf("codigo" to "IN")))
            records.update("predio", UUID.fromString(created.id), RecordRequest(mapOf("codigo" to "IN-2")))
            records.delete("predio", id)
            assertThat(writes).containsExactly("insert", "update", "delete")
            // reads are untouched
            assertThat(records.get("predio", UUID.fromString(created.id)).attributes["codigo"]).isEqualTo("IN-2")
        }

    @Test
    fun `requires-reason refuses a write with no reason on reason - nothing stored, audited or guarded`() =
        runTest {
            val records = service(requiresReason = true)
            val id = stored("R-0")

            val refused =
                listOf(
                    runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "X"))) },
                    runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "X")), "   ") },
                    runCatching { records.delete("predio", id, null) },
                    runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "X")), reason = null, viaApi = true) }
                )

            assertThat(refused).allSatisfy {
                assertThat(it.exceptionOrNull()).isInstanceOfSatisfying(ValidationException::class.java) { e ->
                    assertThat(e.violations.single().field).isEqualTo("reason")
                }
            }
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
            assertThat(changes).isEmpty()
            assertThat(seen).isEmpty()
        }

    @Test
    fun `requires-reason holds the platform too, and a reason given passes`() =
        runTest {
            val records = service(requiresReason = true)

            val refused = records.asPlatform(organizationId) { runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "X"))) } }
            records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "J")), "cierre nocturno") }

            assertThat(refused.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(writes).containsExactly("insert")
            assertThat(reasons).containsExactly("cierre nocturno")
        }

    @Test
    fun `a reason is trimmed, handed to the guard and stored on the write's audit row`() =
        runTest {
            val records = service(requiresReason = true)
            val created = UUID.fromString(records.create("predio", RecordRequest(mapOf("codigo" to "A")), "  alta  ").id)
            records.update("predio", created, RecordRequest(mapOf("codigo" to "B")), "corrección")
            records.delete("predio", created, "duplicado")

            assertThat(audited).containsExactly(AuditOperation.CREATE, AuditOperation.UPDATE, AuditOperation.DELETE)
            assertThat(reasons).containsExactly("alta", "corrección", "duplicado")
            assertThat(seen.map { it.reason }).containsExactly("alta", "corrección", "duplicado")
        }

    @Test
    fun `without requires-reason a write needs none, and a reason given is still stored`() =
        runTest {
            val records = service()
            records.create("predio", RecordRequest(mapOf("codigo" to "A")))
            records.create("predio", RecordRequest(mapOf("codigo" to "B")), "importado")

            assertThat(writes).containsExactly("insert", "insert")
            assertThat(reasons).containsExactly(null, "importado")
        }
}
