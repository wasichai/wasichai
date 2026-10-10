package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import wasichai.core.audit.AuditService
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.readableBy
import java.time.Instant
import java.util.UUID

// issue 90 (ADR-065): every record RecordService hands its caller goes through the app's read masks, decided on
// the record as stored, a field the caller cannot read included
class RecordServiceReadMaskTest {
    private val nombre = ObjectDefinitionFixtures.field("nombre", FieldType.TEXT)
    private val clasificacion = ObjectDefinitionFixtures.field("clasificacion", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(nombre, clasificacion))
    private val organizationId = definition.obj.organizationId
    private val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    // the classification is hidden from the caller
    private val fieldAccess = FieldAccess(read = mapOf(clasificacion.id to false), write = mapOf(clasificacion.id to false))

    private val id = UUID.randomUUID()
    private val whole = mapOf("nombre" to "Juan Perez", "clasificacion" to "RESERVADO")

    // the store answers what it was asked for: the fields of the definition it was handed
    private fun row(definition: ObjectDefinition) =
        RecordRow(
            id,
            Instant.now(),
            Instant.now(),
            whole.filterKeys { name ->
                definition.fields.any {
                    it.name ==
                        name
                }
            }
        )

    private val store =
        object : RecordStore by mock(RecordStore::class.java) {
            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow = row(definition)

            override suspend fun insert(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                workflow: ObjectWorkflowState
            ): RecordRow = row(definition)

            override suspend fun findById(
                definition: ObjectDefinition,
                organizationId: UUID,
                id: UUID,
                createdBy: UUID?,
                withState: Boolean,
                criteria: List<RecordCriterion>
            ): RecordRow = row(definition)

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> = PageResponse.of(listOf(row(definition)), 0, 25, 1)
        }

    private val decidedOn = mutableListOf<Map<String, Any?>>()

    private val reservedName =
        object : RecordReadMask {
            override suspend fun mask(
                caller: AuthenticatedUser,
                definition: ObjectDefinition,
                stored: Map<String, Any?>,
                attributes: Map<String, Any?>
            ): Map<String, Any?> {
                decidedOn += stored
                return if (stored["clasificacion"] == "RESERVADO") attributes + ("nombre" to "Reservado") else attributes
            }
        }

    private suspend fun service(vararg masks: RecordReadMask): RecordService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(person).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(person, definition.obj.id)
        return RecordService(
            metadata,
            store,
            mock(AuditService::class.java),
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            emptyList(),
            RecordWriteGuards(emptyList(), RelationTargetsFixtures.none()),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopes(emptyList(), store),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none(),
            RecordReadMasks(masks.toList(), store)
        ) { TransactionalOperatorFixtures.inline() }
    }

    private val query = RecordQuery(page = PageRequest.of(0, 25))

    @Test
    fun `with no mask every answer is the caller's readable fields, as before`() =
        runTest {
            val records = service()

            assertThat(records.get("predio", id).attributes).isEqualTo(mapOf("nombre" to "Juan Perez"))
            assertThat(
                records
                    .list("predio", query)
                    .content
                    .single()
                    .attributes
            ).isEqualTo(mapOf("nombre" to "Juan Perez"))
        }

    @Test
    fun `get, list and rows are masked on the stored record`() =
        runTest {
            val records = service(reservedName)

            val got = records.get("predio", id)
            val listed = records.list("predio", query)
            val (visible, rows) = records.rows("predio", query)

            assertThat(got.attributes).isEqualTo(mapOf("nombre" to "Reservado"))
            assertThat(listed.content.single().attributes).isEqualTo(mapOf("nombre" to "Reservado"))
            assertThat(listed.totalElements).isEqualTo(1)
            assertThat(rows.single().attributes).isEqualTo(mapOf("nombre" to "Reservado"))
            assertThat(visible).isEqualTo(definition.readableBy(fieldAccess))
            assertThat(decidedOn).hasSize(3).allSatisfy { assertThat(it).isEqualTo(whole) }
        }

    @Test
    fun `the answers of create, update and patch are masked too`() =
        runTest {
            val records = service(reservedName)

            val created = records.create("predio", RecordRequest(mapOf("nombre" to "Juan Perez")))
            val updated = records.update("predio", id, RecordRequest(mapOf("nombre" to "Juan Perez")))
            val patched = records.patch("predio", id, RecordRequest(mapOf("nombre" to "Juan Perez")))

            assertThat(listOf(created, updated, patched)).allSatisfy { assertThat(it.attributes).isEqualTo(mapOf("nombre" to "Reservado")) }
            assertThat(decidedOn).hasSize(3).allSatisfy { assertThat(it).isEqualTo(whole) }
        }
}
