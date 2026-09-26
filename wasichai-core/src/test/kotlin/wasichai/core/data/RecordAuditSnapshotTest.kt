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
import wasichai.core.common.PageResponse
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ADR-0025 known gap: the audit and the listeners must see the record as stored, not what one
// caller was allowed to write. the fake store answers a write with only what it wrote, which the
// RecordStore port allows; the read-back after it is what makes the snapshot whole.
class RecordAuditSnapshotTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val valor = ObjectDefinitionFixtures.field("valor", FieldType.DECIMAL)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, valor))
    private val user = AuthenticatedUser(UUID.randomUUID(), ObjectDefinitionFixtures.obj.organizationId, "user@example.com", listOf("EDITOR"))

    // valor is readable but locked for this caller
    private val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(valor.id to false))

    private val rows = linkedMapOf<UUID, Map<String, Any?>>()

    // a column default the caller never wrote, like one the database fills in
    private val defaults = mapOf<String, Any?>("valor" to BigDecimal("7"))

    // false: the store answers a write with only what it wrote. true: with every field, like RETURNING *
    private var fullReturning = false

    // the next update lands, then another writer changes the row before anyone reads it again
    private var concurrentWrite: Map<String, Any?> = emptyMap()

    private var findByIdCalls = 0

    private val store =
        object : RecordStore {
            private fun written(definition: ObjectDefinition) =
                definition.fields
                    .filter { it.editable }
                    .map { it.name }
                    .toSet()

            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow {
                val id = UUID.randomUUID()
                rows[id] = definition.fields.associate { it.name to (attributes[it.name] ?: defaults[it.name]) }
                val returned = if (fullReturning) rows.getValue(id) else attributes.filterKeys { it in written(definition) }
                return RecordRow(id, Instant.now(), Instant.now(), returned)
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow {
                val kept = written(definition)
                rows[id] = rows.getValue(id) + attributes.filterKeys { it in kept }
                val returned = if (fullReturning) rows.getValue(id) else rows.getValue(id).filterKeys { it in kept }
                rows[id] = rows.getValue(id) + concurrentWrite
                return RecordRow(id, Instant.now(), Instant.now(), returned)
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
            ) = true

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean
            ): RecordRow? {
                findByIdCalls++
                return rows[id]?.let { RecordRow(id, Instant.now(), Instant.now(), it) }
            }

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ) = PageResponse.of(emptyList<RecordRow>(), 0, 25, 0)
        }

    private data class Recorded(
        val operation: AuditOperation,
        val before: Any?,
        val after: Any?
    )

    private val recorded = mutableListOf<Recorded>()
    private val changes = mutableListOf<RecordChange>()

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
                documentId: UUID?
            ) {
                recorded += Recorded(operation, before, after)
            }
        }

    private val listener =
        object : RecordChangeListener {
            override suspend fun recordChanged(change: RecordChange) {
                changes += change
            }
        }

    private suspend fun service(): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(user.organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(metadata, store, audit, currentUser, access, NoWorkflowStates(), FieldTypeRegistry(emptyList()), listOf(listener))
    }

    @Test
    fun `an update the caller could not fully write is audited as the whole stored row`() =
        runTest {
            val id = UUID.randomUUID()
            rows[id] = mapOf("codigo" to "S-1", "valor" to BigDecimal("5"))

            service().update("predio", id, RecordRequest(attributes = mapOf("codigo" to "S-2")))

            val entry = recorded.single()
            assertThat(entry.operation).isEqualTo(AuditOperation.UPDATE)
            assertThat(entry.before).isEqualTo(mapOf("codigo" to "S-1", "valor" to BigDecimal("5")))
            // the locked field is still there, unchanged: the history must not read "valor: 5 -> (cleared)"
            assertThat(entry.after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
            assertThat(changes.single().after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
        }

    @Test
    fun `a create is audited and announced with every stored field`() =
        runTest {
            service().create("predio", RecordRequest(attributes = mapOf("codigo" to "S-1")))

            // valor was never sent: what the history shows is the stored default, not a missing key
            assertThat(recorded.single().after).isEqualTo(mapOf("codigo" to "S-1", "valor" to BigDecimal("7")))
            assertThat(changes.single().after).isEqualTo(mapOf("codigo" to "S-1", "valor" to BigDecimal("7")))
        }

    // the re-read runs outside any transaction, so a concurrent PUT could slip in before it. a
    // RETURNING row that is already whole is atomic with the write: use it, read nothing more.
    @Test
    fun `a write that returns every field is taken as is, with no re-read`() =
        runTest {
            fullReturning = true
            val id = UUID.randomUUID()
            rows[id] = mapOf("codigo" to "S-1", "valor" to BigDecimal("5"))
            concurrentWrite = mapOf("codigo" to "SOMEONE-ELSE")

            service().update("predio", id, RecordRequest(attributes = mapOf("codigo" to "S-2")))

            // the one findById is the `before` read
            assertThat(findByIdCalls).isEqualTo(1)
            assertThat(recorded.single().after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
            assertThat(changes.single().after).isEqualTo(mapOf("codigo" to "S-2", "valor" to BigDecimal("5")))
        }

    @Test
    fun `a create that returns every field is not read back`() =
        runTest {
            fullReturning = true

            service().create("predio", RecordRequest(attributes = mapOf("codigo" to "S-1")))

            assertThat(findByIdCalls).isZero()
            assertThat(recorded.single().after).isEqualTo(mapOf("codigo" to "S-1", "valor" to BigDecimal("7")))
        }
}
