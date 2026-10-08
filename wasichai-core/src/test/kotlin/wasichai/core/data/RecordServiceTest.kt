package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.audit.AuditService
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// fix round 1, finding 2: a field's write permission is checked against ITS OWN section, never
// any section that happens to carry a key with the same name.
class RecordServiceTest {
    private val measure = FieldType("MEASURE")

    private val measureHandler =
        object : FieldTypeHandler {
            override val type = measure
            override val section = "measures"

            override fun columnType(field: CustomField) = "numeric"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val area = ObjectDefinitionFixtures.field("area", measure)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, area))
    private val user = AuthenticatedUser(UUID.randomUUID(), ObjectDefinitionFixtures.obj.organizationId, "user@example.com", listOf("EDITOR"))

    // a bare interface fake: no matcher/null gymnastics, and it is the record actually returned.
    private val fakeStore =
        object : RecordStore {
            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ) = RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), attributes, sections)

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ) = RecordRow(id, Instant.now(), Instant.now(), attributes, sections)

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
            ): RecordRow? = stored?.copy(id = id)

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> {
                lastQuery = query
                return PageResponse.of(emptyList(), 0, 25, if (query.count) 0 else null, nextCursor = "next")
            }
        }

    private var lastQuery: RecordQuery? = null

    // what findById answers. null: no such record
    private var stored: RecordRow? = null

    // fieldAccess restricted (not FieldAccess.FULL) so the write check actually runs.
    // suspend: stubbing a suspend function means calling it, which needs a coroutine.
    private suspend fun service(
        fieldAccess: FieldAccess,
        relationTargets: RelationTargets = RelationTargetsFixtures.none(),
        definition: ObjectDefinition = this.definition
    ): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        val audit = mock(AuditService::class.java)
        val types = FieldTypeRegistry(listOf(measureHandler))
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(user.organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(
            metadata,
            fakeStore,
            audit,
            currentUser,
            access,
            NoWorkflowStates(),
            types,
            emptyList(),
            RecordWriteGuards(emptyList(), relationTargets),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopesFixtures.none(),
            TenantDirectoryFixtures.none()
        )
    }

    // issue 39 (D30): the relation check reads in the caller's scope, so it must be told who calls
    private val customers = UUID.randomUUID()
    private val customer = ObjectDefinitionFixtures.field("customer", FieldType.RELATION).copy(relationTargetObjectId = customers)
    private val withRelation = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, customer))
    private val scopes = mutableListOf<AuthenticatedUser?>()
    private val recordingTargets =
        object : RelationTargets(
            mock(DatabaseClient::class.java),
            mock(WasichaiSchemas::class.java),
            mock(CustomObjectRepository::class.java),
            mock(CustomFieldRepository::class.java),
            RecordReadScopesFixtures.none()
        ) {
            override suspend fun existing(
                organizationId: UUID,
                targetObjectId: UUID,
                ids: List<UUID>,
                scope: AuthenticatedUser?
            ): Set<UUID> {
                scopes += scope
                return ids.toSet()
            }
        }

    @Test
    fun `a create hands the caller to the relation check`() {
        runTest {
            service(FieldAccess.FULL, recordingTargets, withRelation).create("predio", RecordRequest(mapOf("customer" to UUID.randomUUID().toString())))
        }

        assertThat(scopes).containsExactly(user)
    }

    @Test
    fun `an update hands the caller to the relation check`() {
        val id = UUID.randomUUID()
        stored = RecordRow(id, Instant.now(), Instant.now(), mapOf("customer" to UUID.randomUUID()), emptyMap())

        runTest {
            service(FieldAccess.FULL, recordingTargets, withRelation).update("predio", id, RecordRequest(mapOf("customer" to UUID.randomUUID().toString())))
        }

        assertThat(scopes).containsExactly(user)
    }

    @Test
    fun `the platform hands no reader to the relation check`() {
        runTest {
            val records = service(FieldAccess.FULL, recordingTargets, withRelation)
            records.asPlatform(user.organizationId) {
                records.create("predio", RecordRequest(mapOf("customer" to UUID.randomUUID().toString())))
            }
        }

        assertThat(scopes).containsExactly(null)
    }

    @Test
    fun `a plain attribute field is not blocked just because an unrelated section carries a same-named key`() {
        // codigo has no section of its own; denying its write must not be triggered by a "measures"
        // payload that happens to use "codigo" as one of ITS keys
        val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(codigo.id to false))
        val request = RecordRequest(attributes = emptyMap())
        request.sections["measures"] = mapOf("codigo" to "unrelated")

        // runTest's body must return Unit; capture the result through a var instead of the call's return value
        lateinit var response: RecordResponse
        runTest {
            response = service(fieldAccess).create("predio", request)
        }

        assertThat(response.attributes).isEmpty()
    }

    @Test
    fun `a section field is still blocked when the caller sends it under its own section`() {
        val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(area.id to false))
        val request = RecordRequest(attributes = emptyMap())
        request.sections["measures"] = mapOf("area" to "5.0")

        assertThatThrownBy {
            runTest {
                service(fieldAccess).create("predio", request)
            }
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("area")
    }

    // issue 21: an in-process caller reaches count and keyset through RecordService.list too
    @Test
    fun `list hands count and after to the store and gives back the cursor`() {
        lateinit var page: PageResponse<RecordResponse>
        runTest {
            page =
                service(FieldAccess.FULL).list(
                    "predio",
                    RecordQuery(
                        page =
                            wasichai.core.common.PageRequest
                                .of(0, 25),
                        count = false,
                        after = "c"
                    )
                )
        }

        assertThat(lastQuery!!.count).isFalse()
        assertThat(lastQuery!!.after).isEqualTo("c")
        assertThat(page.totalElements).isNull()
        assertThat(page.totalPages).isNull()
        assertThat(page.nextCursor).isEqualTo("next")
    }
}
