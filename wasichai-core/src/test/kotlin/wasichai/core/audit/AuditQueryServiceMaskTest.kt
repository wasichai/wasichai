package wasichai.core.audit

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.ObjectDefinitionFixtures
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 90 (ADR-065): history and /api/audit show each state as the app's masks leave it, decided on that state
class AuditQueryServiceMaskTest {
    private val nombre = ObjectDefinitionFixtures.field("nombre", FieldType.TEXT)
    private val clasificacion = ObjectDefinitionFixtures.field("clasificacion", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(nombre, clasificacion))
    private val organizationId = definition.obj.organizationId
    private val reader = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val metadata = mock(MetadataService::class.java)
    private val access = mock(AccessPolicy::class.java)
    private val decidedOn = mutableListOf<Map<String, Any?>>()

    private val reservedName =
        object : AuditStateMask {
            override fun appliesTo(
                caller: AuthenticatedUser?,
                definition: ObjectDefinition
            ) = true

            override suspend fun state(
                caller: AuthenticatedUser,
                definition: ObjectDefinition,
                state: Map<String, Any?>
            ): Map<String, Any?> {
                decidedOn += state
                return if (state["clasificacion"] == "RESERVADO") state + ("nombre" to "Reservado") else state
            }
        }

    private suspend fun service(masks: AuditStateMask?): AuditQueryService {
        doReturn(definition).`when`(metadata).loadDefinition(organizationId, "predio")
        // the classification is hidden from the reader
        doReturn(FieldAccess(mapOf(clasificacion.id to false), emptyMap())).`when`(access).fieldAccess(reader, definition.obj.id)
        return AuditQueryService(
            mock(DatabaseClient::class.java),
            JsonMapper.builder().build(),
            mock(CurrentUser::class.java),
            metadata,
            access,
            WasichaiSchemas("wasichai", "app_data"),
            mock(AuditRecordScope::class.java),
            masks
        )
    }

    private fun row(
        objectName: String,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?
    ) = AuditRow(UUID.randomUUID(), "ana@example.com", objectName, UUID.randomUUID(), "UPDATE", null, before, after, null, null, null)

    @Test
    fun `each state is masked on itself, before the field permissions narrow it`() =
        runTest {
            // reclassified: the name was public before and reserved after
            val rows =
                listOf(
                    row(
                        "predio",
                        mapOf("nombre" to "Plaza", "clasificacion" to "PUBLICO"),
                        mapOf("nombre" to "Juan Perez", "clasificacion" to "RESERVADO")
                    )
                )

            val entry = service(reservedName).toEntries(reader, rows).single()

            assertThat(entry.changes).containsExactly(FieldChange("nombre", "Plaza", "Reservado"))
            assertThat(decidedOn).containsExactly(rows.single().before, rows.single().after)
        }

    @Test
    fun `with no mask, and on an admin entry, the states stand as stored`() =
        runTest {
            val record = row("predio", mapOf("nombre" to "A", "clasificacion" to "RESERVADO"), mapOf("nombre" to "B", "clasificacion" to "RESERVADO"))
            val admin = row(AdminEntity.ROLE.objectName, mapOf("label" to "A"), mapOf("label" to "B"))

            assertThat(service(null).toEntries(reader, listOf(record)).single().changes).containsExactly(FieldChange("nombre", "A", "B"))
            assertThat(service(reservedName).toEntries(reader, listOf(admin)).single().changes).containsExactly(FieldChange("label", "A", "B"))
            assertThat(decidedOn).isEmpty()
        }
}
