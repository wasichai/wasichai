package wasichai.files

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// issue 90 (ADR-064): the policies see every file value of a record, in order, and never lose its id
class FileDescriptorReadPoliciesTest {
    private val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "evidencia", "Evidencia", "Evidencias", null, true, "evidencia__1", null, null)

    private fun field(
        name: String,
        type: FieldType
    ) = CustomField(UUID.randomUUID(), obj.id, name, name, type, name, false, false, null, null, 0, null, null, emptyMap(), true, true)

    private val clasificacion = field("clasificacion", FieldType.TEXT)
    private val acta = field("acta", FILE)
    private val foto = field("foto", IMAGE)
    private val definition = ObjectDefinition(obj, listOf(clasificacion, acta, foto))
    private val person = AuthenticatedUser(UUID.randomUUID(), obj.organizationId, "ana@example.com", listOf("SOCIAL"))

    private fun descriptor(name: String) = FileDescriptor(UUID.randomUUID(), name, "application/pdf", 10, "ab").toMap()

    private val stored = mapOf("clasificacion" to "RESERVADO", "acta" to descriptor("juan.pdf"), "foto" to descriptor("juan.png"))

    private val seen = mutableListOf<FileRead>()

    private val reservedName =
        FileDescriptorReadPolicy { read ->
            seen += read
            if (read.record?.get("clasificacion") == "RESERVADO") read.descriptor + ("name" to "Archivo reservado") else read.descriptor
        }

    @Test
    fun `with no policy nothing applies and a descriptor is what it was`() =
        runTest {
            val read = FileRead(person, definition, acta, null, descriptor("juan.pdf"))

            assertThat(FileDescriptorReadPolicies.NONE.appliesTo(definition)).isFalse()
            assertThat(FileDescriptorReadPolicies.NONE.descriptor(read)).isSameAs(read.descriptor)
        }

    @Test
    fun `an object with no file field is never asked about`() {
        val plain = ObjectDefinition(obj, listOf(clasificacion))

        assertThat(FileDescriptorReadPolicies(listOf(reservedName)).appliesTo(plain)).isFalse()
        assertThat(FileDescriptorReadPolicies(listOf(reservedName)).appliesTo(definition)).isTrue()
    }

    @Test
    fun `every FILE and IMAGE value the caller reads is asked about, with the stored record, the rest untouched`() =
        runTest {
            // the caller cannot read the classification: it is in stored, not in attributes
            val attributes = stored - "clasificacion"

            val masked = FileDescriptorReadPolicies(listOf(reservedName)).mask(person, definition, stored, attributes)

            assertThat(masked.keys).containsExactly("acta", "foto")
            assertThat((masked["acta"] as Map<*, *>)["name"]).isEqualTo("Archivo reservado")
            assertThat((masked["foto"] as Map<*, *>)["name"]).isEqualTo("Archivo reservado")
            assertThat(seen.map { it.field }).containsExactly(acta, foto)
            assertThat(seen).allSatisfy {
                assertThat(it.record).isEqualTo(stored)
                assertThat(it.caller).isEqualTo(person)
            }
        }

    @Test
    fun `an empty file field is not asked about`() =
        runTest {
            val attributes = mapOf("acta" to null, "foto" to stored["foto"])

            val masked = FileDescriptorReadPolicies(listOf(reservedName)).mask(person, definition, stored, attributes)

            assertThat(masked["acta"]).isNull()
            assertThat(seen.map { it.field }).containsExactly(foto)
        }

    @Test
    fun `policies run in order, and the id stays first and as stored whatever they answer`() =
        runTest {
            val dropAll = FileDescriptorReadPolicy { read -> mapOf("name" to "${read.descriptor["name"]} (sin metadatos)", "id" to "forged") }
            val original = descriptor("juan.pdf")

            val answered = FileDescriptorReadPolicies(listOf(reservedName, dropAll)).descriptor(FileRead(person, definition, acta, stored, original))

            assertThat(answered).isEqualTo(mapOf("id" to original["id"], "name" to "Archivo reservado (sin metadatos)"))
            assertThat(answered.keys.first()).isEqualTo("id")
        }
}
