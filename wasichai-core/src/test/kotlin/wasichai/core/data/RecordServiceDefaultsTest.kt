package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.audit.AuditService
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.FieldValueCodec
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// issue 60: a field's defaultValue fills a key the create left out, through every create RecordService runs
class RecordServiceDefaultsTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val cantidad = ObjectDefinitionFixtures.field("cantidad", FieldType.INTEGER).copy(defaultValue = "5")

    // required, with a default: what SGSPE declares for states, units, currencies
    private val estado = ObjectDefinitionFixtures.field("estado", FieldType.TEXT).copy(required = true, defaultValue = "NUEVO")
    private val customers = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val customer =
        ObjectDefinitionFixtures.field("customer", FieldType.RELATION).copy(relationTargetObjectId = customers, defaultValue = customerId.toString())

    // what the service loads by name, read when it is built
    private var definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, cantidad, estado, customer))
    private val user = AuthenticatedUser(UUID.randomUUID(), ObjectDefinitionFixtures.obj.organizationId, "user@example.com", listOf("EDITOR"))

    private class Insert(
        val definition: ObjectDefinition,
        val attributes: Map<String, Any?>
    )

    private val inserts = mutableListOf<Insert>()
    private val updates = mutableListOf<Map<String, Any?>>()
    private val changes = mutableListOf<RecordChange>()
    private val scopes = mutableListOf<AuthenticatedUser?>()
    private var stored: RecordRow? = null

    // writes what the real store would: each editable field's value through the codec, null when left out
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
                inserts += Insert(definition, attributes)
                val row =
                    definition.fields.associate { field ->
                        field.name to if (field.editable) FieldValueCodec.fromDatabase(FieldValueCodec.toDatabase(field, attributes[field.name])) else null
                    }
                return RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), row).also { stored = it }
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
                updates += attributes
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
            ): PageResponse<RecordRow> = PageResponse.of(emptyList(), 0, 25, 0)
        }

    // every RELATION value exists; the reader each check runs as is kept
    private val targets =
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

    private suspend fun service(fieldAccess: FieldAccess = FieldAccess.FULL): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(user.organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(
            metadata,
            store,
            mock(AuditService::class.java),
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            listOf(
                object : RecordChangeListener {
                    override suspend fun recordChanged(change: RecordChange) {
                        changes += change
                    }
                }
            ),
            RecordWriteGuards(emptyList(), targets),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopesFixtures.none(),
            TenantDirectoryFixtures.none()
        )
    }

    @Test
    fun `a create without the fields stores their defaults, typed as the api carries them`() {
        lateinit var response: RecordResponse
        runTest { response = service().create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }

        assertThat(inserts.single().attributes)
            .isEqualTo(mapOf("codigo" to "P-1", "cantidad" to 5L, "estado" to "NUEVO", "customer" to customerId.toString()))
        assertThat(response.attributes).containsEntry("cantidad", 5L).containsEntry("estado", "NUEVO")
        // listeners, and so the audit row's after, see the defaults as stored
        assertThat(changes.single().after).containsEntry("cantidad", 5L).containsEntry("estado", "NUEVO")
    }

    @Test
    fun `a value sent wins over the default`() {
        runTest { service().create("predio", RecordRequest(mapOf("cantidad" to 9, "estado" to "ABIERTO"))) }

        assertThat(inserts.single().attributes).containsEntry("cantidad", 9).containsEntry("estado", "ABIERTO")
    }

    @Test
    fun `an explicit null stays null, and a required field still refuses it`() {
        runTest { service().create("predio", RecordRequest(mapOf("cantidad" to null))) }
        assertThat(inserts.single().attributes).containsEntry("cantidad", null)
        assertThat(stored!!.attributes["cantidad"]).isNull()

        assertThatThrownBy { runTest { service().create("predio", RecordRequest(mapOf("estado" to null))) } }
            .isInstanceOf(ValidationException::class.java)
            .satisfies({ assertThat((it as ValidationException).violations.single().field).isEqualTo("estado") })
    }

    @Test
    fun `a required field with a default is created by a caller who may not write it`() {
        val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(estado.id to false, cantidad.id to false))

        runTest { service(fieldAccess).create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }

        val insert = inserts.single()
        assertThat(insert.attributes).containsEntry("estado", "NUEVO").containsEntry("cantidad", 5L)
        // the store writes the defaulted columns: no NOT NULL failure
        assertThat(insert.definition.fields.filter { it.name in setOf("estado", "cantidad") }).allSatisfy { assertThat(it.editable).isTrue() }
        assertThat(stored!!.attributes).containsEntry("estado", "NUEVO")
    }

    @Test
    fun `sending a field the caller may not write is still refused, default or not`() {
        val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(estado.id to false))

        assertThatThrownBy { runTest { service(fieldAccess).create("predio", RecordRequest(mapOf("estado" to "OTRO"))) } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("estado")
    }

    @Test
    fun `a required field without a default the caller may not write is still a 403`() {
        val bare = estado.copy(defaultValue = null)
        val fieldAccess = FieldAccess(read = emptyMap(), write = mapOf(bare.id to false))
        definition = definition.copy(fields = listOf(codigo, bare))

        assertThatThrownBy {
            runTest { service(fieldAccess).create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }
        }.isInstanceOf(ForbiddenException::class.java)
            .hasMessageContaining("estado")
    }

    @Test
    fun `a RELATION default is checked like a value sent, in the caller's read scope`() {
        runTest { service().create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }

        assertThat(scopes).containsExactly(user)
    }

    @Test
    fun `the platform gets the defaults too`() {
        runTest {
            val records = service()
            records.asPlatform(user.organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "P-1"))) }
        }

        assertThat(inserts.single().attributes).containsEntry("cantidad", 5L).containsEntry("estado", "NUEVO")
        assertThat(changes.single().userId).isNull()
    }

    @Test
    fun `an update never applies a default`() {
        val id = UUID.randomUUID()
        stored = RecordRow(id, Instant.now(), Instant.now(), mapOf("codigo" to "P-1", "cantidad" to null, "estado" to "ABIERTO"))

        runTest { service().update("predio", id, RecordRequest(mapOf("codigo" to "P-2", "estado" to "ABIERTO"))) }

        assertThat(updates.single()).isEqualTo(mapOf("codigo" to "P-2", "estado" to "ABIERTO"))
    }
}
