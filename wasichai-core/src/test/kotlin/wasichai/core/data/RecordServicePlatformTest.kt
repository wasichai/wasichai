package wasichai.core.data

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.UnauthorizedException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.TenantDirectory
import wasichai.core.platform.TenantRef
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// ADR-039: background work calls RecordService as the platform. no user, no permission check, the
// organization it was given, null in the audit row like an automation (ADR-016).
class RecordServicePlatformTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo))
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId

    private data class Write(
        val kind: String,
        val organizationId: UUID,
        val userId: UUID?
    )

    private val writes = mutableListOf<Write>()
    private val rows = linkedMapOf<UUID, Map<String, Any?>>()
    private val queries = mutableListOf<RecordQuery>()

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
                writes += Write("insert", organizationId, userId)
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
                writes += Write("update", organizationId, userId)
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
                writes += Write("delete", organizationId, null)
                return rows.remove(id) != null
            }

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow? = rows[id]?.let { RecordRow(id, Instant.now(), Instant.now(), it) }

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> {
                queries += query
                return PageResponse.of(rows.map { (id, it) -> RecordRow(id, null, null, it) }, 0, 25, rows.size.toLong())
            }
        }

    private data class Audited(
        val organizationId: UUID,
        val userId: UUID?,
        val operation: AuditOperation,
        // what AuditService would store as the row's source (ADR-050)
        val source: String?
    )

    private val audited = mutableListOf<Audited>()
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
                documentId: UUID?,
                reason: String?
            ) {
                audited += Audited(organizationId, userId, operation, ChangeOrigin.source())
            }
        }

    // no user anywhere: CurrentUser says so, and AccessPolicy is never consulted (an unstubbed mock answers null)
    private suspend fun service(tenants: TenantDirectory = TenantDirectoryFixtures.none()): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        doThrow(UnauthorizedException("Authentication required")).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        tenants.organizations().forEach { doReturn(definition).`when`(metadata).loadDefinition(it.id, "predio") }
        return RecordService(
            metadata,
            store,
            audit,
            currentUser,
            mock(AccessPolicy::class.java),
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            listOf(
                object : RecordChangeListener {
                    override suspend fun recordChanged(change: RecordChange) {
                        changes += change
                    }
                }
            ),
            RecordWriteGuards(emptyList(), RelationTargetsFixtures.none()),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopesFixtures.none(),
            tenants,
            IdempotencyKeysFixtures.none()
        )
    }

    @Test
    fun `the platform creates, updates and deletes in its organization with no user`() =
        runTest {
            val records = service()

            records.asPlatform(organizationId) {
                val created = records.create("predio", RecordRequest(mapOf("codigo" to "P-1")))
                val id = UUID.fromString(created.id)
                records.update("predio", id, RecordRequest(mapOf("codigo" to "P-2")))
                assertThat(records.get("predio", id).attributes["codigo"]).isEqualTo("P-2")
                records.delete("predio", id)
            }

            assertThat(writes.map { it.kind }).containsExactly("insert", "update", "delete")
            assertThat(writes).allSatisfy {
                assertThat(it.organizationId).isEqualTo(organizationId)
                assertThat(it.userId).isNull()
            }
            assertThat(audited.map { it.operation }).containsExactly(AuditOperation.CREATE, AuditOperation.UPDATE, AuditOperation.DELETE)
            assertThat(audited).allSatisfy {
                assertThat(it.organizationId).isEqualTo(organizationId)
                assertThat(it.userId).isNull()
            }
            assertThat(changes).allSatisfy { assertThat(it.userId).isNull() }
        }

    // ADR-050: the audit row says who wrote it without a user: the platform, or the app's own label
    @Test
    fun `platform writes are labelled platform, or the app's own label`() =
        runTest {
            val records = service()

            records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }
            records.asPlatform(organizationId, source = "job:retention") { records.create("predio", RecordRequest(mapOf("codigo" to "P-2"))) }

            assertThat(audited.map { it.source }).containsExactly("platform", "job:retention")
        }

    @Test
    fun `a malformed label is refused before anything is written`() =
        runTest {
            val records = service()

            val refused =
                runCatching {
                    records.asPlatform(organizationId, source = "job retention") { records.create("predio", RecordRequest(mapOf("codigo" to "X"))) }
                }

            assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
        }

    @Test
    fun `the platform reads every record, with no owner filter`() =
        runTest {
            val records = service()
            rows[UUID.randomUUID()] = mapOf("codigo" to "R-1")

            val page = records.asPlatform(organizationId) { records.list("predio", RecordQuery(PageRequest(0, 25))) }

            assertThat(page.content.map { it.attributes["codigo"] }).containsExactly("R-1")
            assertThat(queries.single().createdBy).isNull()
        }

    @Test
    fun `outside the block the platform is gone`() =
        runTest {
            val records = service()
            records.asPlatform(organizationId) { records.list("predio", RecordQuery(PageRequest(0, 25))) }

            val after = runCatching { records.list("predio", RecordQuery(PageRequest(0, 25))) }

            assertThat(after.exceptionOrNull()).isInstanceOf(UnauthorizedException::class.java)
        }

    @Test
    fun `a caller with a user never becomes the platform`() =
        runTest {
            val records = service()
            val user = TestingAuthenticationToken("someone", "n/a", "ROLE_ADMIN")

            val refused =
                withContext(ReactiveSecurityContextHolder.withAuthentication(user).asCoroutineContext()) {
                    runCatching { records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "X"))) } }
                }

            assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(writes).isEmpty()
        }

    @Test
    fun `a request without a token never becomes the platform either`() =
        runTest {
            val records = service()
            // what the security web filter gives an anonymous request: the key, with no authentication behind it
            val anonymous = ReactiveSecurityContextHolder.withSecurityContext(Mono.empty<SecurityContext>())

            val refused =
                withContext(anonymous.asCoroutineContext()) {
                    runCatching { records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "X"))) } }
                }

            assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(writes).isEmpty()
            assertThat(audited).isEmpty()
        }

    // ADR-057: forEachOrganization = the directory's tenants, each inside asPlatform
    private val tenantA = TenantRef(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "a")
    private val tenantB = TenantRef(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "b")
    private val tenantC = TenantRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"), "c")
    private val three = TenantDirectoryFixtures.of(listOf(tenantA, tenantB, tenantC)) { if (it == "predio") listOf(tenantB) else emptyList() }

    @Test
    fun `forEachOrganization runs the block once per organization, in order, as the platform of each`() =
        runTest {
            val records = service(three)
            val seen = mutableListOf<UUID>()

            records.forEachOrganization { organizationId ->
                seen += organizationId
                records.create("predio", RecordRequest(mapOf("codigo" to "P-$organizationId")))
            }

            assertThat(seen).containsExactly(tenantA.id, tenantB.id, tenantC.id)
            assertThat(writes).containsExactly(
                Write("insert", tenantA.id, null),
                Write("insert", tenantB.id, null),
                Write("insert", tenantC.id, null)
            )
            assertThat(audited.map { it.organizationId to it.userId }).containsExactly(tenantA.id to null, tenantB.id to null, tenantC.id to null)
            assertThat(audited.map { it.source }).containsOnly(ChangeOrigin.PLATFORM)
        }

    @Test
    fun `forEachOrganization with an object visits only the organizations that define it`() =
        runTest {
            val records = service(three)
            val seen = mutableListOf<UUID>()

            records.forEachOrganization("predio") { seen += it }
            records.forEachOrganization("nobody-has-it") { seen += it }

            assertThat(seen).containsExactly(tenantB.id)
        }

    @Test
    fun `one organization's failure is logged and the others still run`() =
        runTest {
            val records = service(three)
            val seen = mutableListOf<UUID>()

            records.forEachOrganization { organizationId ->
                seen += organizationId
                if (organizationId == tenantA.id) error("tenant a is broken")
                records.create("predio", RecordRequest(mapOf("codigo" to "OK")))
            }

            assertThat(seen).containsExactly(tenantA.id, tenantB.id, tenantC.id)
            assertThat(writes.map { it.organizationId }).containsExactly(tenantB.id, tenantC.id)
        }

    @Test
    fun `forEachOrganization labels its audit rows with the given source, and refuses a bad one before anything runs`() =
        runTest {
            val records = service(three)
            var ran = 0

            records.forEachOrganization("predio", source = "job:retention") { records.create("predio", RecordRequest(mapOf("codigo" to "J"))) }
            val refused = runCatching { records.forEachOrganization(source = "job retention") { ran++ } }

            assertThat(audited.map { it.source }).containsExactly("job:retention")
            assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(ran).isZero()
        }

    @Test
    fun `forEachOrganization inside a request throws before any block runs, with a token or anonymous`() =
        runTest {
            // the fixture directory has no tripwire of its own: forEachOrganization must not rely on it
            val records = service(three)
            var ran = 0
            val user = ReactiveSecurityContextHolder.withAuthentication(TestingAuthenticationToken("someone", "n/a", "ROLE_ADMIN"))
            val anonymous = ReactiveSecurityContextHolder.withSecurityContext(Mono.empty<SecurityContext>())

            listOf(user, anonymous).forEach { request ->
                val refused = withContext(request.asCoroutineContext()) { runCatching { records.forEachOrganization { ran++ } } }

                assertThat(refused.exceptionOrNull())
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("never becomes the platform")
            }
            assertThat(ran).isZero()
            assertThat(writes).isEmpty()
        }
}
