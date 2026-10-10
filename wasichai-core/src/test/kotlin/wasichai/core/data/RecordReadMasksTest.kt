package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import wasichai.core.common.PageResponse
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// issue 90 (ADR-063): who is asked, what a mask decides on, what it may change, and what it costs
class RecordReadMasksTest {
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val nombre = ObjectDefinitionFixtures.field("nombre", FieldType.TEXT)
    private val clasificacion = ObjectDefinitionFixtures.field("clasificacion", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(nombre, clasificacion))

    // the caller cannot read the classification
    private val visible = definition.copy(fields = listOf(nombre))

    private val person = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("SOCIAL"))

    private val reserved = UUID.randomUUID()
    private val open = UUID.randomUUID()
    private val storedRows =
        mapOf(
            reserved to mapOf("nombre" to "Juan Perez", "clasificacion" to "RESERVADO"),
            open to mapOf("nombre" to "Plaza", "clasificacion" to "PUBLICO")
        )

    private val queries = mutableListOf<Pair<ObjectDefinition, RecordQuery>>()

    private val store =
        object : RecordStore by mock(RecordStore::class.java) {
            override suspend fun query(
                definition: ObjectDefinition,
                organizationId: UUID,
                query: RecordQuery
            ): PageResponse<RecordRow> {
                queries += definition to query
                val rows = query.ids.orEmpty().map { RecordRow(it, null, null, storedRows.getValue(it)) }
                return PageResponse.of(rows, 0, query.page.size, null)
            }
        }

    private val seen = mutableListOf<Map<String, Any?>>()

    // the app's rule: a reserved record's name reads as "Reservado", and a key it adds goes nowhere
    private val reservedName =
        object : RecordReadMask {
            override suspend fun mask(
                caller: AuthenticatedUser,
                definition: ObjectDefinition,
                stored: Map<String, Any?>,
                attributes: Map<String, Any?>
            ): Map<String, Any?> {
                seen += stored
                if (stored["clasificacion"] != "RESERVADO") return attributes
                return attributes + mapOf("nombre" to "Reservado", "clasificacion" to "masked")
            }
        }

    private fun projected(id: UUID) = RecordRow(id, null, null, mapOf("nombre" to storedRows.getValue(id)["nombre"]))

    @Test
    fun `with no mask the rows are the same and nothing more is read`() =
        runTest {
            val rows = listOf(projected(reserved))

            val read = RecordReadMasks(emptyList(), store).rows(person, organizationId, definition, visible, rows)

            assertThat(read).isSameAs(rows)
            assertThat(queries).isEmpty()
            assertThat(RecordReadMasks.NONE.appliesTo(person, definition)).isFalse()
        }

    @Test
    fun `the platform is never masked`() =
        runTest {
            val rows = listOf(projected(reserved))

            val read = RecordReadMasks(listOf(reservedName), store).rows(null, organizationId, definition, visible, rows)

            assertThat(read).isSameAs(rows)
            assertThat(seen).isEmpty()
            assertThat(RecordReadMasks(listOf(reservedName), store).appliesTo(null, definition)).isFalse()
        }

    @Test
    fun `a projected read is decided on the stored records, read once by ids, and only the caller's keys come back`() =
        runTest {
            val read =
                RecordReadMasks(listOf(reservedName), store)
                    .rows(person, organizationId, definition, visible, listOf(projected(reserved), projected(open)))

            assertThat(read.map { it.attributes }).containsExactly(mapOf("nombre" to "Reservado"), mapOf("nombre" to "Plaza"))
            assertThat(seen).containsExactly(storedRows[reserved], storedRows[open])
            val (readWith, query) = queries.single()
            assertThat(readWith).isSameAs(definition)
            assertThat(query.ids).containsExactly(reserved, open)
            assertThat(query.criteria).isEmpty()
            assertThat(query.count).isFalse()
        }

    @Test
    fun `a whole read, or one whose stored record the caller holds, reads nothing more`() =
        runTest {
            val masks = RecordReadMasks(listOf(reservedName), store)
            val whole = RecordRow(reserved, null, null, storedRows.getValue(reserved))

            val wholeRead = masks.rows(person, organizationId, definition, definition, listOf(whole))
            val held = masks.row(person, organizationId, definition, visible, projected(reserved), storedRows.getValue(reserved))

            assertThat(wholeRead.single().attributes).isEqualTo(mapOf("nombre" to "Reservado", "clasificacion" to "masked"))
            assertThat(held.attributes).isEqualTo(mapOf("nombre" to "Reservado"))
            assertThat(queries).isEmpty()
        }

    @Test
    fun `a mask that does not apply to the object is not asked and costs nothing`() =
        runTest {
            val elsewhere =
                object : RecordReadMask by reservedName {
                    override fun appliesTo(definition: ObjectDefinition) = false
                }
            val masks = RecordReadMasks(listOf(elsewhere), store)
            val rows = listOf(projected(reserved))

            assertThat(masks.rows(person, organizationId, definition, visible, rows)).isSameAs(rows)
            assertThat(masks.appliesTo(person, definition)).isFalse()
            assertThat(seen).isEmpty()
            assertThat(queries).isEmpty()
        }

    @Test
    fun `masks run in order, each on what the one before answered, and may leave a field out`() =
        runTest {
            val dropName =
                object : RecordReadMask {
                    override suspend fun mask(
                        caller: AuthenticatedUser,
                        definition: ObjectDefinition,
                        stored: Map<String, Any?>,
                        attributes: Map<String, Any?>
                    ): Map<String, Any?> {
                        assertThat(attributes["nombre"]).isEqualTo("Reservado")
                        return attributes - "nombre"
                    }
                }

            val read = RecordReadMasks(listOf(reservedName, dropName), store).row(person, organizationId, definition, visible, projected(reserved))

            assertThat(read.attributes).isEmpty()
        }

    @Test
    fun `an audit state is both what the masks decide on and what they rewrite`() =
        runTest {
            val state = storedRows.getValue(reserved)

            val read = RecordReadMasks(listOf(reservedName), store).state(person, definition, state)

            assertThat(read).isEqualTo(mapOf("nombre" to "Reservado", "clasificacion" to "masked"))
            assertThat(seen).containsExactly(state)
            assertThat(queries).isEmpty()
        }
}
