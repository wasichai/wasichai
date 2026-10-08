package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageResponse
import wasichai.core.common.UnauthorizedException
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

// what one write tells its guards, the audit log and the listeners, and the order of the checks in
// front of it: create, update and delete, as a user and as the platform. the three must always
// describe the same write, whoever assembles them.
class RecordWriteTrailTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val objectId = ObjectDefinitionFixtures.obj.id
    private val user = AuthenticatedUser(UUID.randomUUID(), organizationId, "user@example.com", listOf("EDITOR"))

    private val rows = linkedMapOf<UUID, Map<String, Any?>>()
    private val writes = mutableListOf<String>()
    private val guarded = mutableListOf<RecordWrite>()
    private val audited = mutableListOf<AuditCall>()
    private val changes = mutableListOf<RecordChange>()

    private data class AuditCall(
        val organizationId: UUID,
        val userId: UUID?,
        val objectName: String,
        val recordId: UUID?,
        val operation: AuditOperation,
        val before: Any?,
        val after: Any?,
        val documentId: UUID?,
        val reason: String?
    )

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

            // a state the read can only see when it asks for one, as the physical table answers
            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow? = rows[id]?.let { RecordRow(id, Instant.now(), Instant.now(), it, state = if (withState) "abierto" else null) }

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
                audited += AuditCall(organizationId, userId, objectName, recordId, operation, before, after, documentId, reason)
            }
        }

    private val guard =
        object : RecordWriteGuard {
            override suspend fun beforeWrite(change: RecordWrite) {
                guarded += change
            }
        }

    private val listener =
        object : RecordChangeListener {
            override suspend fun recordChanged(change: RecordChange) {
                changes += change
            }
        }

    // [denied]: the action the user's roles refuse, null when they allow everything
    private suspend fun service(
        enabled: Boolean = true,
        apiOnly: Boolean = false,
        denied: String? = null,
        authenticated: Boolean = true
    ): RecordService {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj.copy(enabled = enabled, apiOnly = apiOnly), listOf(codigo))
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        if (authenticated) {
            doReturn(user).`when`(currentUser).require()
        } else {
            doThrow(UnauthorizedException("Authentication required")).`when`(currentUser).require()
        }
        if (denied != null) doThrow(ForbiddenException("Missing permission $denied")).`when`(currentUser).requirePermission(user, denied, objectId)
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(FieldAccess.FULL).`when`(access).fieldAccess(user, objectId)
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
            RecordReadScopesFixtures.none(),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none()
        )
    }

    private fun stored(codigo: String): UUID = UUID.randomUUID().also { rows[it] = mapOf("codigo" to codigo) }

    @Test
    fun `a user's create tells guards, audit and listeners the same write`() =
        runTest {
            val created = service().create("predio", RecordRequest(mapOf("codigo" to "S-1")), " corrección ")
            val id = UUID.fromString(created.id)

            assertThat(guarded).containsExactly(
                RecordWrite(
                    organizationId,
                    user.userId,
                    objectId,
                    "predio",
                    null,
                    RecordChangeKind.CREATED,
                    attributes = mapOf("codigo" to "S-1"),
                    reason = "corrección"
                )
            )
            assertThat(audited).containsExactly(
                AuditCall(organizationId, user.userId, "predio", id, AuditOperation.CREATE, null, mapOf("codigo" to "S-1"), null, "corrección")
            )
            assertThat(changes).containsExactly(
                RecordChange(organizationId, user.userId, objectId, "predio", id, RecordChangeKind.CREATED, after = mapOf("codigo" to "S-1"))
            )
        }

    @Test
    fun `a user's update tells guards, audit and listeners the same write`() =
        runTest {
            val id = stored("S-1")

            service().update("predio", id, RecordRequest(mapOf("codigo" to "S-2")), "corrección")

            assertThat(guarded).containsExactly(
                RecordWrite(
                    organizationId,
                    user.userId,
                    objectId,
                    "predio",
                    id,
                    RecordChangeKind.UPDATED,
                    before = mapOf("codigo" to "S-1"),
                    attributes = mapOf("codigo" to "S-2"),
                    reason = "corrección"
                )
            )
            assertThat(audited).containsExactly(
                AuditCall(
                    organizationId,
                    user.userId,
                    "predio",
                    id,
                    AuditOperation.UPDATE,
                    mapOf("codigo" to "S-1"),
                    mapOf("codigo" to "S-2"),
                    null,
                    "corrección"
                )
            )
            assertThat(changes).containsExactly(
                RecordChange(
                    organizationId,
                    user.userId,
                    objectId,
                    "predio",
                    id,
                    RecordChangeKind.UPDATED,
                    before = mapOf("codigo" to "S-1"),
                    after = mapOf("codigo" to "S-2")
                )
            )
        }

    // the read before a delete asks for no state, so the change carries none: kept as it always was
    @Test
    fun `a user's delete tells guards, audit and listeners the same write`() =
        runTest {
            val id = stored("S-1")

            service().delete("predio", id, "duplicado")

            assertThat(writes).containsExactly("delete")
            assertThat(guarded).containsExactly(
                RecordWrite(
                    organizationId,
                    user.userId,
                    objectId,
                    "predio",
                    id,
                    RecordChangeKind.DELETED,
                    before = mapOf("codigo" to "S-1"),
                    reason = "duplicado"
                )
            )
            assertThat(audited).containsExactly(
                AuditCall(organizationId, user.userId, "predio", id, AuditOperation.DELETE, mapOf("codigo" to "S-1"), null, null, "duplicado")
            )
            assertThat(changes).containsExactly(
                RecordChange(organizationId, user.userId, objectId, "predio", id, RecordChangeKind.DELETED, before = mapOf("codigo" to "S-1"))
            )
        }

    @Test
    fun `the platform's writes carry no user, anywhere`() =
        runTest {
            val records = service(authenticated = false)
            val updated = stored("S-1")
            val deleted = stored("S-2")

            records.asPlatform(organizationId) {
                records.create("predio", RecordRequest(mapOf("codigo" to "S-3")))
                records.update("predio", updated, RecordRequest(mapOf("codigo" to "S-4")))
                records.delete("predio", deleted)
            }

            assertThat(writes).containsExactly("insert", "update", "delete")
            assertThat(guarded.map { it.kind to it.userId }).containsExactly(
                RecordChangeKind.CREATED to null,
                RecordChangeKind.UPDATED to null,
                RecordChangeKind.DELETED to null
            )
            assertThat(audited.map { it.operation to it.userId }).containsExactly(
                AuditOperation.CREATE to null,
                AuditOperation.UPDATE to null,
                AuditOperation.DELETE to null
            )
            assertThat(changes.map { it.kind to it.userId }).containsExactly(
                RecordChangeKind.CREATED to null,
                RecordChangeKind.UPDATED to null,
                RecordChangeKind.DELETED to null
            )
            assertThat(audited.map { it.organizationId }.toSet()).containsExactly(organizationId)
            assertThat(changes.map { it.organizationId }.toSet()).containsExactly(organizationId)
        }

    @Test
    fun `a malformed reason is refused before the caller is even asked for`() =
        runTest {
            val refused = runCatching { service(authenticated = false).create("predio", RecordRequest(mapOf("codigo" to "S-1")), "a\u0000b") }

            assertThat(refused.exceptionOrNull()).isInstanceOf(ValidationException::class.java)
            assertThat(writes).isEmpty()
        }

    @Test
    fun `api-only is judged before the caller's permission`() =
        runTest {
            val refused =
                runCatching { service(apiOnly = true, denied = Actions.CREATE).create("predio", RecordRequest(mapOf("codigo" to "S-1")), null, viaApi = true) }

            assertThat(refused.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java).hasMessageContaining("not through the record API")
        }

    @Test
    fun `the caller's permission is judged before a disabled object`() =
        runTest {
            val id = stored("S-1")

            val refused = runCatching { service(enabled = false, denied = Actions.UPDATE).update("predio", id, RecordRequest(mapOf("codigo" to "S-2"))) }

            assertThat(refused.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java).hasMessage("Missing permission UPDATE")
        }

    @Test
    fun `a disabled object refuses every write before a guard is asked`() =
        runTest {
            val id = stored("S-1")
            val records = service(enabled = false)

            val refused =
                listOf(
                    runCatching { records.create("predio", RecordRequest(mapOf("codigo" to "S-1"))) },
                    runCatching { records.update("predio", id, RecordRequest(mapOf("codigo" to "S-2"))) },
                    runCatching { records.delete("predio", id) }
                )

            assertThat(refused).allSatisfy { assertThat(it.exceptionOrNull()).isInstanceOf(ConflictException::class.java) }
            assertThat(guarded).isEmpty()
            assertThat(writes).isEmpty()
        }
}
