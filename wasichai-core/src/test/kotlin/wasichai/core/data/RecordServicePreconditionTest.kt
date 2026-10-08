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
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageResponse
import wasichai.core.common.PreconditionFailedException
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

// ADR-051: If-Match is compared by the store in the write itself; a stale write stores, audits and tells
// nothing. PATCH writes only the keys sent, under every rule PUT has.
class RecordServicePreconditionTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val area = ObjectDefinitionFixtures.field("area", FieldType.TEXT)
    private val folio = ObjectDefinitionFixtures.field("folio", FieldType.TEXT).copy(editable = false)
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val admin = AuthenticatedUser(UUID.randomUUID(), organizationId, "admin@example.com", listOf(AuthenticatedUser.ADMIN_ROLE))

    private class Stored(
        val attributes: Map<String, Any?>,
        val updatedAt: Instant
    )

    private val rows = linkedMapOf<UUID, Stored>()
    private val writes = mutableListOf<String>()
    private val audited = mutableListOf<AuditOperation>()
    private val changes = mutableListOf<RecordChange>()
    private val seen = mutableListOf<RecordWrite>()
    private var written: ObjectDefinition? = null

    // ids the caller's read scope no longer reaches, and whether a compare moves its record there first
    private val hidden = mutableSetOf<UUID>()
    private var hideOnCompare = false
    private var clock = Instant.parse("2026-10-07T10:00:00Z")

    // a store with the physical one's semantics: editable fields written from attributes, others kept,
    // and every write a new updated_at
    private val store =
        object : RecordStore {
            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow = error("not used")

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow {
                val row = rows[id] ?: throw NotFoundException("Record $id does not exist")
                writes += "update"
                written = definition
                val next = row.attributes + definition.fields.filter { it.editable }.associate { it.name to attributes[it.name] }
                clock = clock.plusMillis(1)
                rows[id] = Stored(next, clock)
                return RecordRow(id, clock, clock, next)
            }

            override suspend fun updateIfUnchanged(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean,
                expectedUpdatedAt: List<Instant>
            ): RecordRow? {
                val row = rows[id] ?: return null
                if (hideOnCompare) {
                    // a concurrent write moved it out of the caller's scope, and on: the compare misses
                    hidden += id
                    return null
                }
                if (row.updatedAt !in expectedUpdatedAt) return null
                return update(definition, organizationId, userId, id, attributes, sections, withState)
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

            override suspend fun deleteIfUnchanged(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                expectedUpdatedAt: List<Instant>
            ): Boolean {
                val row = rows[id] ?: return false
                return row.updatedAt in expectedUpdatedAt && delete(definition, organizationId, id)
            }

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow? {
                if (criteria.isNotEmpty() && id in hidden) return null
                return rows[id]?.let { RecordRow(id, it.updatedAt, it.updatedAt, it.attributes) }
            }

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
            }
        }

    private val guard =
        object : RecordWriteGuard {
            override suspend fun beforeWrite(change: RecordWrite) {
                seen += change
            }
        }

    private suspend fun service(
        appendOnly: Boolean = false,
        apiOnly: Boolean = false,
        requiresReason: Boolean = false,
        fieldAccess: FieldAccess = FieldAccess.FULL,
        caller: AuthenticatedUser = admin,
        scopes: List<RecordReadScope> = emptyList()
    ): RecordService {
        val definition =
            ObjectDefinition(
                ObjectDefinitionFixtures.obj.copy(appendOnly = appendOnly, apiOnly = apiOnly, requiresReason = requiresReason),
                listOf(codigo, area, folio)
            )
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(caller).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(caller, definition.obj.id)
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
            RecordWriteGuards(listOf(guard), RelationTargetsFixtures.none()),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopes(scopes, mock(RecordStore::class.java)),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none()
        )
    }

    private fun stored(vararg attributes: Pair<String, Any?>): UUID =
        UUID.randomUUID().also {
            rows[it] = Stored(mapOf("codigo" to null, "area" to null, "folio" to null) + attributes, clock)
        }

    @Test
    fun `an update holding the current version writes, and its answer carries the next one`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1")
            val read = records.get("predio", id).updatedAt!!

            val updated = records.update("predio", id, RecordRequest(mapOf("codigo" to "P-2")), null, read)

            assertThat(updated.updatedAt).isAfter(read)
            assertThat(rows[id]!!.attributes["codigo"]).isEqualTo("P-2")
            assertThat(audited).containsExactly(AuditOperation.UPDATE)
        }

    @Test
    fun `a stale update is a 412 - nothing stored, audited or told`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1")
            val read = records.get("predio", id).updatedAt!!
            records.update("predio", id, RecordRequest(mapOf("codigo" to "FIRST")), null, read)

            val second = runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "SECOND")), null, read) }

            assertThat(second.exceptionOrNull()).isInstanceOf(PreconditionFailedException::class.java)
            assertThat(rows[id]!!.attributes["codigo"]).isEqualTo("FIRST")
            assertThat(audited).containsExactly(AuditOperation.UPDATE)
            assertThat(changes).hasSize(1)
        }

    @Test
    fun `a record gone between the read and the compare is a 404, not a 412`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1")
            val read = rows[id]!!.updatedAt
            // the store matched no row and the re-read finds none either
            rows.remove(id)

            val update = runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "X")), null, read) }

            assertThat(update.exceptionOrNull()).isInstanceOf(NotFoundException::class.java)
        }

    @Test
    fun `no precondition writes as before, last writer wins`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1", "area" to "10")

            records.update("predio", id, RecordRequest(mapOf("codigo" to "A")))
            records.update("predio", id, RecordRequest(mapOf("codigo" to "B")), reason = null)

            // a full replace: area was not sent, so it is cleared
            assertThat(rows[id]!!.attributes).containsEntry("codigo", "B").containsEntry("area", null)
            assertThat(writes).containsExactly("update", "update")
        }

    @Test
    fun `a stale delete is a 412 and the record stays, a current one deletes`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1")
            val read = rows[id]!!.updatedAt
            records.update("predio", id, RecordRequest(mapOf("codigo" to "P-2")))

            val stale = runCatching { records.delete("predio", id, null, read) }

            assertThat(stale.exceptionOrNull()).isInstanceOf(PreconditionFailedException::class.java)
            assertThat(rows).containsKey(id)
            assertThat(audited).containsExactly(AuditOperation.UPDATE)

            records.delete("predio", id, null, rows[id]!!.updatedAt)
            assertThat(rows).doesNotContainKey(id)
            assertThat(audited).containsExactly(AuditOperation.UPDATE, AuditOperation.DELETE)
        }

    @Test
    fun `a patch writes only the keys sent, and null clears one`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1", "area" to "10", "folio" to "F-1")

            records.patch("predio", id, RecordRequest(mapOf("area" to "20")))
            assertThat(rows[id]!!.attributes).containsEntry("codigo", "P-1").containsEntry("area", "20").containsEntry("folio", "F-1")
            // the store is handed every field but the sent ones locked
            assertThat(written!!.fields.filter { it.editable }.map { it.name }).containsExactly("area")

            records.patch("predio", id, RecordRequest(mapOf("area" to null)))
            assertThat(rows[id]!!.attributes).containsEntry("codigo", "P-1").containsEntry("area", null)
            assertThat(audited).containsExactly(AuditOperation.UPDATE, AuditOperation.UPDATE)
        }

    @Test
    fun `a guard sees a patch as the put of the same change`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1", "area" to "10")

            records.patch("predio", id, RecordRequest(mapOf("area" to "20")))

            val write = seen.single()
            assertThat(write.kind).isEqualTo(RecordChangeKind.UPDATED)
            assertThat(write.before).containsEntry("area", "10")
            assertThat(write.attributes).containsEntry("codigo", "P-1").containsEntry("area", "20")
        }

    @Test
    fun `a patch naming no attribute is a 400, a read-only field a 403, and nothing is written`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1", "folio" to "F-1")

            val unknown = runCatching { records.patch("predio", id, RecordRequest(mapOf("nope" to 1))) }
            val readOnly = runCatching { records.patch("predio", id, RecordRequest(mapOf("folio" to "F-2"))) }

            assertThat(unknown.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat((unknown.exceptionOrNull() as ValidationException).violations.single().field).isEqualTo("nope")
            assertThat(readOnly.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
            assertThat(seen).isEmpty()
        }

    @Test
    fun `a patch of a field the caller's roles may not write is a 400, as on put`() =
        runTest {
            val records = service(fieldAccess = FieldAccess(emptyMap(), mapOf(area.id to false)))
            val id = stored("codigo" to "P-1")

            val put = runCatching { records.update("predio", id, RecordRequest(mapOf("area" to "1"))) }
            val patch = runCatching { records.patch("predio", id, RecordRequest(mapOf("area" to "1"))) }

            assertThat(put.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(patch.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat((patch.exceptionOrNull() as ValidationException).violations.single().field).isEqualTo("area")
            assertThat(writes).isEmpty()
        }

    @Test
    fun `a patch is held to append-only, api-only and requires-reason as a put is`() =
        runTest {
            val id = stored("codigo" to "P-1")

            val appendOnly = runCatching { service(appendOnly = true).patch("predio", id, RecordRequest(mapOf("codigo" to "X"))) }
            val apiOnly = runCatching { service(apiOnly = true).patch("predio", id, RecordRequest(mapOf("codigo" to "X")), null, viaApi = true, null) }
            val noReason = runCatching { service(requiresReason = true).patch("predio", id, RecordRequest(mapOf("codigo" to "X"))) }
            val inProcess = service(apiOnly = true).patch("predio", id, RecordRequest(mapOf("codigo" to "BY-THE-APP")))

            assertThat(appendOnly.exceptionOrNull()).isInstanceOf(ConflictException::class.java)
            assertThat(apiOnly.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
            assertThat((noReason.exceptionOrNull() as ValidationException).violations.single().field).isEqualTo("reason")
            assertThat(inProcess.attributes["codigo"]).isEqualTo("BY-THE-APP")
            assertThat(writes).containsExactly("update")
        }

    @Test
    fun `a stale patch is a 412 like a stale put`() =
        runTest {
            val records = service()
            val id = stored("codigo" to "P-1", "area" to "10")
            val read = rows[id]!!.updatedAt
            records.patch("predio", id, RecordRequest(mapOf("area" to "20")), expectedUpdatedAt = read)

            val stale = runCatching { records.patch("predio", id, RecordRequest(mapOf("codigo" to "LOST")), expectedUpdatedAt = read) }

            assertThat(stale.exceptionOrNull()).isInstanceOf(PreconditionFailedException::class.java)
            assertThat(rows[id]!!.attributes).containsEntry("codigo", "P-1").containsEntry("area", "20")
            assertThat(audited).containsExactly(AuditOperation.UPDATE)
        }

    @Test
    fun `a record that left the caller's read scope before the compare is a 404, never a 412`() =
        runTest {
            val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))
            val projectA =
                object : RecordReadScope {
                    override suspend fun criterion(
                        caller: AuthenticatedUser,
                        definition: ObjectDefinition
                    ) = RecordCriterion { _, bind -> "project_id = ${bind("A")}" }
                }
            val records = service(caller = person, scopes = listOf(projectA))
            val id = stored("codigo" to "P-1")
            val read = rows[id]!!.updatedAt
            hideOnCompare = true

            val update = runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "X")), null, read) }
            val delete = runCatching { records.delete("predio", id, null, read) }

            assertThat(update.exceptionOrNull()).isInstanceOf(NotFoundException::class.java)
            assertThat(delete.exceptionOrNull()).isInstanceOf(NotFoundException::class.java)
            assertThat(audited).isEmpty()
        }
}
