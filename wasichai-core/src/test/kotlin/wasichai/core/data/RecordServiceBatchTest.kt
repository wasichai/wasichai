package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.audit.AuditService
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
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// issue 77: createAll looks the definition, the caller's access and each relation target up once per batch,
// and still answers and fails record by record exactly as a create loop would
class RecordServiceBatchTest {
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val parcels = UUID.randomUUID()
    private val catalog = UUID.randomUUID()
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val predio = relation("predio", parcels)
    private val contribuyente = relation("contribuyente", parcels)
    private val servicio = relation("servicio", catalog)
    private val parametro = relation("parametro", catalog)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, predio, contribuyente, servicio, parametro))
    private val user = AuthenticatedUser(UUID.randomUUID(), organizationId, "user@example.com", listOf("EDITOR"))

    private val inserted = mutableListOf<Map<String, Any?>>()
    private val lookups = mutableListOf<Pair<UUID, List<UUID>>>()

    // ids no lookup ever finds
    private val missing = mutableSetOf<UUID>()

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
                inserted += attributes
                return RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), attributes, sections)
            }

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
            ): RecordRow? = null

            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> = PageResponse.of(emptyList(), 0, 25, 0)
        }

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
                lookups += targetObjectId to ids
                return ids.filter { it !in missing }.toSet()
            }
        }

    private val metadata = mock(MetadataService::class.java)
    private val currentUser = mock(CurrentUser::class.java)
    private val access = mock(AccessPolicy::class.java)
    private val transactions = TransactionalOperatorFixtures.inline()

    private suspend fun service(fieldAccess: FieldAccess = FieldAccess.FULL): RecordService {
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(
            metadata,
            store,
            mock(AuditService::class.java),
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(emptyList()),
            emptyList(),
            RecordWriteGuards(emptyList(), targets),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopesFixtures.none(),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none()
        ) { transactions }
    }

    private val parcelIds = List(12) { UUID.randomUUID() }
    private val catalogIds = List(2) { UUID.randomUUID() }

    // the measured case: 48 records, four relations, servicio and parametro the same in all, predio and contribuyente repeating
    private fun requests(count: Int = 48): List<RecordRequest> =
        List(count) { i ->
            RecordRequest(
                mapOf(
                    "codigo" to "R-$i",
                    "predio" to parcelIds[i % parcelIds.size].toString(),
                    "contribuyente" to parcelIds[(i + 1) % parcelIds.size].toString(),
                    "servicio" to catalogIds[0].toString(),
                    "parametro" to catalogIds[1].toString()
                )
            )
        }

    private fun calls(
        mock: Any,
        method: String
    ): Int = mockingDetails(mock).invocations.count { it.method.name == method }

    @Test
    fun `the definition, the permission and the field access are asked once for the whole batch`() =
        runTest {
            service().createAll("predio", requests())

            assertThat(calls(metadata, "loadDefinition")).isEqualTo(1)
            assertThat(calls(currentUser, "requirePermission")).isEqualTo(1)
            assertThat(calls(access, "fieldAccess")).isEqualTo(1)
            assertThat(inserted).hasSize(48)
            assertThat(transactions.opened).isEqualTo(1)
        }

    @Test
    fun `each target object is looked up once, with its distinct ids only`() =
        runTest {
            service().createAll("predio", requests())

            assertThat(lookups.map { it.first }).containsExactlyInAnyOrder(parcels, catalog)
            val parcelLookup = lookups.single { it.first == parcels }.second
            assertThat(parcelLookup).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(parcelIds)
            assertThat(lookups.single { it.first == catalog }.second).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(catalogIds)
        }

    @Test
    fun `a create loop over the same requests looks up per record, which the batch saves`() =
        runTest {
            val records = service()
            requests().forEach { records.create("predio", it) }

            // the baseline the batch is measured against: two target objects, every record
            assertThat(lookups).hasSize(96)
            assertThat(calls(metadata, "loadDefinition")).isEqualTo(48)
        }

    @Test
    fun `a missing target fails its record with the error create gives it, and nothing after it is written`() =
        runTest {
            val batch = requests(10).toMutableList()
            val nobody = UUID.randomUUID()
            missing += nobody
            batch[4] = RecordRequest(batch[4].attributes + ("contribuyente" to nobody.toString()))

            val expected = runCatching { service().create("predio", batch[4]) }.exceptionOrNull() as ValidationException
            inserted.clear()
            val thrown = runCatching { service().createAll("predio", batch) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(ValidationException::class.java)
            thrown as ValidationException
            assertThat(thrown.message).isEqualTo(expected.message).isEqualTo("Invalid value for 'contribuyente'")
            assertThat(thrown.violations).isEqualTo(expected.violations)
            // the four before it reached the fake store; the real rollback is the transaction's
            assertThat(inserted.map { it["codigo"] }).containsExactly("R-0", "R-1", "R-2", "R-3")
        }

    @Test
    fun `the first failing record wins, in request order, whichever check fails it`() =
        runTest {
            val locked = FieldAccess(read = emptyMap(), write = mapOf(codigo.id to false))
            val nobody = UUID.randomUUID()
            missing += nobody
            val badTarget = RecordRequest(mapOf("predio" to nobody.toString()))
            val unwritable = RecordRequest(mapOf("codigo" to "X"))

            val targetFirst = runCatching { service(locked).createAll("predio", listOf(badTarget, unwritable)) }.exceptionOrNull()
            val unwritableFirst = runCatching { service(locked).createAll("predio", listOf(unwritable, badTarget)) }.exceptionOrNull()

            assertThat(targetFirst).isInstanceOf(ValidationException::class.java).hasMessage("Invalid value for 'predio'")
            assertThat(unwritableFirst).isInstanceOf(ValidationException::class.java).hasMessage("Field 'codigo' is not writable for you")
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `answers come back in request order, each one what create answers`() =
        runTest {
            val batch = requests(5)

            val answers = service().createAll("predio", batch)

            assertThat(answers.map { it.attributes }).isEqualTo(batch.map { it.attributes })
        }

    @Test
    fun `an empty batch answers nothing and asks nothing`() =
        runTest {
            val answers = service().createAll("predio", emptyList())

            assertThat(answers).isEmpty()
            assertThat(calls(metadata, "loadDefinition")).isZero()
            assertThat(transactions.opened).isZero()
        }

    @Test
    fun `a batch's lookups answer only the reader they were made for`() =
        runTest {
            val other = user.copy(userId = UUID.randomUUID())
            val attributes = mapOf<String, Any?>("predio" to parcelIds[0].toString())
            val checked = targets.check(organizationId, definition, listOf(attributes), user)

            val refused = runCatching { targets.rejectMissing(organizationId, definition, attributes, reader = other, checked = checked) }.exceptionOrNull()
            targets.rejectMissing(organizationId, definition, attributes, reader = user, checked = checked)

            assertThat(refused).isInstanceOf(IllegalArgumentException::class.java)
            // the check's own lookup, and nothing more: the cache answered the right reader
            assertThat(lookups).hasSize(1)
        }

    @Test
    fun `an id the batch did not look up is still looked up`() =
        runTest {
            val checked = targets.check(organizationId, definition, listOf(mapOf("predio" to parcelIds[0].toString())), user)
            val nobody = UUID.randomUUID()
            missing += nobody

            val thrown =
                runCatching {
                    targets.rejectMissing(
                        organizationId,
                        definition,
                        mapOf("predio" to parcelIds[0].toString(), "contribuyente" to nobody.toString()),
                        reader = user,
                        checked = checked
                    )
                }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(ValidationException::class.java).hasMessage("Invalid value for 'contribuyente'")
            assertThat(lookups).containsExactly(parcels to listOf(parcelIds[0]), parcels to listOf(nobody))
        }

    @Test
    fun `a target the batch found missing is read again, so one written meanwhile passes as in a create loop`() =
        runTest {
            val later = UUID.randomUUID()
            missing += later
            val attributes = mapOf<String, Any?>("predio" to later.toString())
            val checked = targets.check(organizationId, definition, listOf(attributes), user)
            // an earlier record's listener wrote it
            missing -= later

            targets.rejectMissing(organizationId, definition, attributes, reader = user, checked = checked)

            assertThat(lookups).containsExactly(parcels to listOf(later), parcels to listOf(later))
        }

    @Test
    fun `nothing to look up is no lookup at all`() =
        runTest {
            service().createAll("predio", listOf(RecordRequest(mapOf("codigo" to "A")), RecordRequest(mapOf("codigo" to "B"))))

            assertThat(lookups).isEmpty()
            assertThat(inserted).hasSize(2)
        }

    private fun relation(
        name: String,
        target: UUID
    ) = ObjectDefinitionFixtures.field(name, FieldType.RELATION).copy(relationTargetObjectId = target)
}
