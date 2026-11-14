package wasichai.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.FieldValueCodec
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

// issue 153, ADR-077: what the server fixes on a create, seen by guards, the store, the audit row, the listeners and the answer
class RecordServiceValueProviderTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)

    // the consumer: curso.propietario_id. a UUID, not required, off the forms, and nobody writes it through metadata
    private val owner = ObjectDefinitionFixtures.field("propietario_id", FieldType.UUID).copy(visible = false, editable = false)
    private val required = ObjectDefinitionFixtures.field("codigo_interno", FieldType.TEXT).copy(required = true)
    private val withDefault = ObjectDefinitionFixtures.field("nivel", FieldType.TEXT).copy(defaultValue = "BASICO")
    private val measureType = FieldType("MEASURE")
    private val measure = ObjectDefinitionFixtures.field("medida", measureType)
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val user = AuthenticatedUser(UUID.randomUUID(), organizationId, "user@example.com", listOf("EDITOR"))
    private val spoofed = UUID.randomUUID().toString()

    private val inserted = mutableListOf<Map<String, Any?>>()
    private val guarded = mutableListOf<RecordWrite>()
    private val audited = mutableListOf<Pair<AuditOperation, Any?>>()
    private val changes = mutableListOf<RecordChange>()

    // what the real store does: an editable field's value through the codec, a locked one left alone
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
                val row =
                    definition.fields.filter { it.type != measureType }.associate { field ->
                        field.name to if (field.editable) FieldValueCodec.fromDatabase(FieldValueCodec.toDatabase(field, attributes[field.name])) else null
                    }
                inserted += row
                return RecordRow(UUID.randomUUID(), Instant.now(), Instant.now(), row)
            }

            override suspend fun update(
                definition: ObjectDefinition,
                organizationId: UUID,
                userId: UUID?,
                id: UUID,
                attributes: Map<String, Any?>,
                sections: Map<String, Map<String, Any?>>,
                withState: Boolean
            ): RecordRow = error("a create never updates")

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
                audited += operation to after
            }
        }

    private val recordingGuard =
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

    private val sectionHandler =
        object : FieldTypeHandler {
            override val type = measureType
            override val section = "measures"

            override fun columnType(field: CustomField) = "text"

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

    // the owner is who calls; the platform and automations have nobody to name
    private val ownerIsTheCaller = provider { _, caller -> caller?.let { mapOf("propietario_id" to it.userId.toString()) }.orEmpty() }

    private fun provider(values: suspend (Map<String, Any?>, AuthenticatedUser?) -> Map<String, Any?>) =
        object : RecordValueProvider {
            override suspend fun valuesFor(
                definition: ObjectDefinition,
                attributes: Map<String, Any?>,
                caller: AuthenticatedUser?
            ): Map<String, Any?> = values(attributes, caller)
        }

    private suspend fun service(
        providers: RecordValueProviders,
        fields: List<CustomField> = listOf(codigo, owner),
        fieldAccess: FieldAccess = FieldAccess.FULL,
        guards: List<RecordWriteGuard> = listOf(recordingGuard)
    ): RecordService {
        val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, fields)
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val access = mock(AccessPolicy::class.java)
        doReturn(user).`when`(currentUser).require()
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        doReturn(fieldAccess).`when`(access).fieldAccess(user, definition.obj.id)
        return RecordService(
            metadata,
            store,
            audit,
            currentUser,
            access,
            NoWorkflowStates(),
            FieldTypeRegistry(listOf(sectionHandler)),
            listOf(listener),
            RecordWriteGuards(guards, RelationTargetsFixtures.none()),
            AppendOnlyReferencesFixtures.none(),
            RecordReadScopesFixtures.none(),
            TenantDirectoryFixtures.none(),
            IdempotencyKeysFixtures.none(),
            values = providers
        ) { TransactionalOperatorFixtures.inline() }
    }

    private fun providers(vararg providers: RecordValueProvider) = RecordValueProviders(providers.toList())

    @Test
    fun `the owner is who calls, on one row, one audit entry and the answer, for a field nobody writes`() =
        runTest {
            val created = service(providers(ownerIsTheCaller)).create("predio", RecordRequest(mapOf("codigo" to "C-1")))

            val expected = mapOf("codigo" to "C-1", "propietario_id" to user.userId.toString())
            assertThat(created.attributes).isEqualTo(expected)
            assertThat(inserted).containsExactly(expected)
            assertThat(audited).containsExactly(AuditOperation.CREATE to expected)
            assertThat(changes.single().after).isEqualTo(expected)
            assertThat(changes.single().kind).isEqualTo(RecordChangeKind.CREATED)
        }

    @Test
    fun `a guard sees the final attributes`() =
        runTest {
            service(providers(ownerIsTheCaller)).create("predio", RecordRequest(mapOf("codigo" to "C-1")))

            assertThat(guarded.single().attributes).isEqualTo(mapOf("codigo" to "C-1", "propietario_id" to user.userId.toString()))
        }

    @Test
    fun `a value the caller sends cannot pass for the owner`() =
        runTest {
            val created = service(providers(ownerIsTheCaller)).create("predio", RecordRequest(mapOf("codigo" to "C-1", "propietario_id" to spoofed)))

            assertThat(created.attributes["propietario_id"]).isEqualTo(user.userId.toString())
            assertThat(guarded.single().attributes!!["propietario_id"]).isEqualTo(user.userId.toString())
            assertThat(inserted.single()["propietario_id"]).isEqualTo(user.userId.toString())
        }

    @Test
    fun `a provider may refuse what the caller sent, with a 400 and nothing stored`() =
        runTest {
            val refusing =
                provider { attributes, _ ->
                    if ("propietario_id" in attributes) throw ValidationException("Not yours to set", "propietario_id", "the server sets it")
                    emptyMap()
                }

            assertThatThrownBy {
                runBlocking { service(providers(refusing)).create("predio", RecordRequest(mapOf("propietario_id" to spoofed))) }
            }.isInstanceOf(ValidationException::class.java)
            assertThat(inserted).isEmpty()
            assertThat(guarded).isEmpty()
            assertThat(audited).isEmpty()
        }

    @Test
    fun `a provider that keeps a sent value leaves it to a guard, which refuses with a 400`() =
        runTest {
            // only fills what was left out; the field is writable here, so the caller's value reaches the guard
            val writableOwner = owner.copy(editable = true)
            val onlyIfLeftOut =
                provider { attributes, caller ->
                    if ("propietario_id" in
                        attributes
                    ) {
                        emptyMap()
                    } else {
                        mapOf("propietario_id" to caller!!.userId.toString())
                    }
                }
            val ownerGuard =
                object : RecordWriteGuard {
                    override suspend fun beforeWrite(change: RecordWrite) {
                        if (change.attributes?.get("propietario_id") != change.userId.toString()) {
                            throw ValidationException("Not yours to set", "propietario_id", "must be you")
                        }
                    }
                }
            val records = service(providers(onlyIfLeftOut), listOf(codigo, writableOwner), guards = listOf(ownerGuard))

            assertThat(records.create("predio", RecordRequest()).attributes["propietario_id"]).isEqualTo(user.userId.toString())
            assertThatThrownBy {
                runBlocking { records.create("predio", RecordRequest(mapOf("propietario_id" to spoofed))) }
            }.isInstanceOf(ValidationException::class.java)
            assertThat(inserted).hasSize(1)
        }

    @Test
    fun `a field the caller may not write still refuses what they send, and the provider is not asked`() =
        runTest {
            var asked = 0
            val counting =
                provider { _, caller ->
                    asked++
                    mapOf("propietario_id" to caller!!.userId.toString())
                }
            val locked = FieldAccess(read = emptyMap(), write = mapOf(owner.id to false))

            assertThatThrownBy {
                runBlocking {
                    service(providers(counting), fieldAccess = locked).create("predio", RecordRequest(mapOf("propietario_id" to spoofed)))
                }
            }.isInstanceOf(ValidationException::class.java)
            assertThat(asked).isZero()
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `a field the caller may not write lands all the same when the provider fixes it`() =
        runTest {
            val writableOwner = owner.copy(editable = true)
            val locked = FieldAccess(read = emptyMap(), write = mapOf(writableOwner.id to false))

            val created = service(providers(ownerIsTheCaller), listOf(codigo, writableOwner), locked).create("predio", RecordRequest(mapOf("codigo" to "C-1")))

            assertThat(inserted.single()["propietario_id"]).isEqualTo(user.userId.toString())
            assertThat(created.attributes["propietario_id"]).isEqualTo(user.userId.toString())
        }

    @Test
    fun `a required field is covered by a provider, also one the caller's roles may not write`() =
        runTest {
            val fixed = provider { _, _ -> mapOf("codigo_interno" to "SRV-1") }
            val locked = FieldAccess(read = emptyMap(), write = mapOf(required.id to false))

            val created = service(providers(fixed), listOf(codigo, required), locked).create("predio", RecordRequest(mapOf("codigo" to "C-1")))

            assertThat(created.attributes["codigo_interno"]).isEqualTo("SRV-1")
        }

    @Test
    fun `a required field a provider leaves null is refused like any other`() =
        runTest {
            val blank = provider { _, _ -> mapOf("codigo_interno" to null) }

            assertThatThrownBy {
                runBlocking { service(providers(blank), listOf(required)).create("predio", RecordRequest(mapOf("codigo_interno" to "SENT"))) }
            }.isInstanceOf(ValidationException::class.java)
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `a provider's key wins over the field's default, a null one too`() =
        runTest {
            val fixed = provider { _, _ -> mapOf("nivel" to "ALTO") }
            val cleared = provider { _, _ -> mapOf("nivel" to null) }

            assertThat(service(providers(fixed), listOf(withDefault)).create("predio", RecordRequest()).attributes["nivel"]).isEqualTo("ALTO")
            assertThat(service(providers(cleared), listOf(withDefault)).create("predio", RecordRequest()).attributes["nivel"]).isNull()
        }

    @Test
    fun `a field the provider leaves out keeps its default, and the provider sees only what was sent`() =
        runTest {
            val seen = mutableListOf<Map<String, Any?>>()
            val watching =
                provider { attributes, _ ->
                    seen += attributes
                    emptyMap()
                }

            val created = service(providers(watching), listOf(codigo, withDefault)).create("predio", RecordRequest(mapOf("codigo" to "C-1")))

            assertThat(seen).containsExactly(mapOf("codigo" to "C-1"))
            assertThat(created.attributes["nivel"]).isEqualTo("BASICO")
        }

    @Test
    fun `a key that is no field of the object fails the write, system columns included`() =
        runTest {
            for (column in listOf("created_by", "updated_by", "organization_id", "id", "state", "no_existe")) {
                val rogue = provider { _, _ -> mapOf(column to user.userId.toString()) }

                assertThatThrownBy {
                    runBlocking { service(providers(rogue)).create("predio", RecordRequest(mapOf("codigo" to "C-1"))) }
                }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining(column)
            }
            assertThat(guarded).isEmpty()
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `a field that travels in a section fails the write`() =
        runTest {
            val rogue = provider { _, _ -> mapOf("medida" to "1") }

            assertThatThrownBy {
                runBlocking { service(providers(rogue), listOf(codigo, measure)).create("predio", RecordRequest()) }
            }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("medida")
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `two providers fixing one field fail the write, even with the same value`() =
        runTest {
            val first = provider { _, _ -> mapOf("codigo" to "X") }
            val second = provider { _, _ -> mapOf("codigo" to "X") }

            assertThatThrownBy {
                runBlocking { service(providers(first, second)).create("predio", RecordRequest()) }
            }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("codigo")
            assertThat(guarded).isEmpty()
            assertThat(inserted).isEmpty()
        }

    @Test
    fun `two providers fixing different fields both land, and neither sees the other's`() =
        runTest {
            val seen = mutableListOf<Map<String, Any?>>()
            val first =
                provider { attributes, _ ->
                    seen += attributes
                    mapOf("codigo" to "X")
                }
            val second =
                provider { attributes, caller ->
                    seen += attributes
                    mapOf("propietario_id" to caller!!.userId.toString())
                }

            val created = service(providers(first, second)).create("predio", RecordRequest())

            assertThat(seen).containsExactly(emptyMap(), emptyMap())
            assertThat(created.attributes).isEqualTo(mapOf("codigo" to "X", "propietario_id" to user.userId.toString()))
        }

    @Test
    fun `a batch asks for every record, in order, up front`() =
        runTest {
            val order = mutableListOf<String>()
            val numbered =
                provider { attributes, caller ->
                    order += "asked ${attributes["codigo"]}"
                    mapOf("propietario_id" to caller!!.userId.toString())
                }
            val inOrder =
                object : RecordWriteGuard {
                    override suspend fun beforeWrite(change: RecordWrite) {
                        order += "guard ${change.attributes!!["codigo"]}"
                    }
                }

            val created =
                service(providers(numbered), guards = listOf(inOrder))
                    .createAll("predio", listOf(RecordRequest(mapOf("codigo" to "A")), RecordRequest(mapOf("codigo" to "B", "propietario_id" to spoofed))))

            assertThat(created.map { it.attributes["propietario_id"] }).containsOnly(user.userId.toString())
            assertThat(inserted.map { it["propietario_id"] }).containsOnly(user.userId.toString())
            assertThat(order).containsExactly("asked A", "asked B", "guard A", "guard B")
            assertThat(audited).hasSize(2)
        }

    @Test
    fun `a provider's refusal in a batch fails where a create loop would, after an earlier record's own`() =
        runTest {
            val refusing =
                provider { attributes, _ ->
                    if (attributes["codigo"] == "BAD") throw ValidationException("Refused", "codigo", "by the provider")
                    emptyMap()
                }
            val earlier =
                object : RecordWriteGuard {
                    override suspend fun beforeWrite(change: RecordWrite) {
                        if (change.attributes!!["codigo"] == "FIRST") throw ValidationException("Earlier", "codigo", "by the guard")
                    }
                }
            val records = service(providers(refusing), guards = listOf(earlier))

            assertThatThrownBy {
                runBlocking {
                    records.createAll("predio", listOf(RecordRequest(mapOf("codigo" to "FIRST")), RecordRequest(mapOf("codigo" to "BAD"))))
                }
            }.hasMessageContaining("Earlier")
        }

    @Test
    fun `the platform calls with nobody, and the provider decides`() =
        runTest {
            val callers = mutableListOf<AuthenticatedUser?>()
            val watching =
                provider { _, caller ->
                    callers += caller
                    if (caller == null) mapOf("propietario_id" to SYSTEM_OWNER) else emptyMap()
                }
            val records = service(providers(watching))

            val created = records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "C-1"))) }

            assertThat(callers).containsExactly(null)
            assertThat(created.attributes["propietario_id"]).isEqualTo(SYSTEM_OWNER)
        }

    // a read-only field is the platform's to write (issue 166): what it sends lands when no provider fixes the key
    @Test
    fun `the platform's create with a provider that fixes nothing stores what the platform sent`() =
        runTest {
            val records = service(providers(ownerIsTheCaller))

            val created = records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "C-1", "propietario_id" to spoofed))) }

            assertThat(created.attributes["propietario_id"]).isEqualTo(spoofed)
            assertThat(records.asPlatform(organizationId) { records.create("predio", RecordRequest(mapOf("codigo" to "C-2"))) }.attributes["propietario_id"])
                .isNull()
        }

    @Test
    fun `without a provider nothing changes`() =
        runTest {
            val writableOwner = owner.copy(editable = true)
            val records = service(RecordValueProviders.NONE, listOf(codigo, writableOwner, withDefault))

            val created = records.create("predio", RecordRequest(mapOf("codigo" to "C-1", "propietario_id" to spoofed)))

            assertThat(guarded.single().attributes).isEqualTo(mapOf("codigo" to "C-1", "propietario_id" to spoofed, "nivel" to "BASICO"))
            assertThat(created.attributes).isEqualTo(mapOf("codigo" to "C-1", "propietario_id" to spoofed, "nivel" to "BASICO"))
            // a read-only field stays read-only: what a person sends is a 403, not dropped in silence (issue 166)
            val before = inserted.size
            assertThatThrownBy {
                runBlocking { service(RecordValueProviders.NONE).create("predio", RecordRequest(mapOf("codigo" to "C-2", "propietario_id" to spoofed))) }
            }.isInstanceOf(ForbiddenException::class.java)
                .hasMessageContaining("propietario_id")
            assertThat(inserted).hasSize(before)
        }

    private companion object {
        const val SYSTEM_OWNER = "00000000-0000-0000-0000-000000000001"
    }
}
