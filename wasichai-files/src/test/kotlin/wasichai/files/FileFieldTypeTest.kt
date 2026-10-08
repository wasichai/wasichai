package wasichai.files

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.UpdateFieldRequest
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

class FileFieldTypeTest {
    private val schemas = WasichaiSchemas("wasichai", "app_data")
    private val json = JsonMapper.builder().build()
    private val file = FileFieldType(FILE, schemas, json, 1_000)
    private val image = FileFieldType(IMAGE, schemas, json, 1_000)

    @Test
    fun `both types install next to core's, sharing their two attribute columns`() {
        val registry = FieldTypeRegistry(listOf(file, image))
        assertThat(registry.types).endsWith(FILE, IMAGE)
        assertThat(registry.attributeColumns).containsEntry(MAX_BYTES_COLUMN, java.lang.Long::class.java)
        assertThat(registry.attributeColumns).containsEntry(CONTENT_TYPES_COLUMN, String::class.java)
        assertThat(registry.sections).isEmpty()
    }

    @Test
    fun `settings default to nothing stored, so the configuration and the type decide`() {
        assertThat(file.attributesOf("adjunto", FieldRequest(name = "adjunto", type = "FILE")))
            .containsEntry(MAX_BYTES_COLUMN, null)
            .containsEntry(CONTENT_TYPES_COLUMN, null)
        val stored = field(IMAGE, file.attributesOf("foto", FieldRequest(name = "foto", type = "IMAGE")))
        assertThat(image.maxBytes(stored)).isEqualTo(1_000)
        assertThat(image.contentTypes(stored)).containsExactly("image/png", "image/jpeg", "image/webp")
        assertThat(file.contentTypes(field(FILE))).isNull()
    }

    @Test
    fun `maxBytes and contentTypes are checked and kept`() {
        val attributes =
            file.attributesOf(
                "acta",
                FieldRequest(name = "acta", type = "FILE", extensions = mapOf("maxBytes" to 500, "contentTypes" to listOf("application/PDF", "image/*")))
            )
        assertThat(attributes).containsEntry(MAX_BYTES_COLUMN, 500L).containsEntry(CONTENT_TYPES_COLUMN, "application/pdf,image/*")
        val acta = field(FILE, attributes)
        assertThat(file.maxBytes(acta)).isEqualTo(500)
        assertThat(file.contentTypes(acta)).containsExactly("application/pdf", "image/*")
        assertThat(file.fieldProperties(acta)).isEqualTo(mapOf("file" to mapOf("maxBytes" to 500L, "contentTypes" to listOf("application/pdf", "image/*"))))
    }

    @Test
    fun `a cap above the configured one, a fraction or a bad media type is a 400 on its property`() {
        listOf<Any>(1_001, 0, -1, 2.5, "mucho").forEach { raw ->
            refused("maxBytes") { file.attributesOf("acta", FieldRequest(name = "acta", type = "FILE", extensions = mapOf("maxBytes" to raw))) }
        }
        listOf<Any>("pdf", listOf("image/png", 3), emptyList<String>(), "image/png;q=1").forEach { raw ->
            refused("contentTypes") { file.attributesOf("acta", FieldRequest(name = "acta", type = "FILE", extensions = mapOf("contentTypes" to raw))) }
        }
    }

    @Test
    fun `a file is never unique and never defaulted`() {
        refused("unique") { file.attributesOf("acta", FieldRequest(name = "acta", type = "FILE", unique = true)) }
        refused("defaultValue") { file.attributesOf("acta", FieldRequest(name = "acta", type = "FILE", defaultValue = UUID.randomUUID().toString())) }
        refused("unique") { file.checkUpdate(field(FILE), UpdateFieldRequest(unique = true)) }
        refused("defaultValue") { file.checkUpdate(field(FILE), UpdateFieldRequest(defaultValue = "x")) }
        file.checkUpdate(field(FILE), UpdateFieldRequest(label = "Acta", defaultValue = ""))
    }

    @Test
    fun `only file fields carry the file key, so every other field's json is unchanged`() {
        assertThat(file.fieldProperties(field(FieldType.TEXT))).isEmpty()
        assertThat(file.fieldProperties(field(IMAGE))).containsOnlyKeys("file")
        assertThat(file.objectProperties(wasichai.core.metadata.ObjectDefinition(obj(), listOf(field(FILE))))).isEmpty()
    }

    @Test
    fun `the column is a uuid with an index, read back as the descriptor in the row's organization`() {
        val acta = field(FILE)
        assertThat(file.columnType(acta)).isEqualTo("uuid")
        assertThat(file.indexes(obj(), "\"app_data\".\"exp__1\"", acta))
            .containsExactly("CREATE INDEX \"exp__1_acta_fix\" ON \"app_data\".\"exp__1\" (\"acta\")")
        assertThat(file.select(acta, "\"acta\"")).isEqualTo("wasichai.stored_file_descriptor(\"acta\", organization_id) AS \"acta__file\"")
        assertThat(file.readName(acta)).isEqualTo("acta__file")
        assertThat(file.javaType(acta)).isEqualTo(UUID::class.java)
        assertThat(
            file
                .rejectFilterOrSort(acta)
                .violations
                .single()
                .field
        ).isEqualTo("acta")
    }

    @Test
    fun `the descriptor is id, name, type, size and hash, in that order, never bytes`() {
        val id = UUID.randomUUID()
        val raw = """{"id" : "$id", "name" : "acta.pdf", "contentType" : "application/pdf", "size" : 12, "sha256" : "${"a".repeat(64)}"}"""
        val read = file.fromDatabase(field(FILE), raw) as Map<*, *>
        assertThat(read.keys).containsExactly("id", "name", "contentType", "size", "sha256")
        assertThat(read["id"]).isEqualTo(id.toString())
        assertThat(read["size"]).isEqualTo(12L)
        assertThat(file.fromDatabase(field(FILE), null)).isNull()
    }

    @Test
    fun `a value is an id, its text or a descriptor, and null clears unless required`() {
        val id = UUID.randomUUID()
        val acta = field(FILE)
        assertThat(file.toDatabase(acta, id)).isEqualTo(id)
        assertThat(file.toDatabase(acta, id.toString())).isEqualTo(id)
        assertThat(file.toDatabase(acta, mapOf("id" to id.toString(), "name" to "x"))).isEqualTo(id)
        assertThat(file.toDatabase(acta, null)).isNull()
        refused("acta") { file.toDatabase(acta.copy(required = true), null) }
        refused("acta") { file.toDatabase(acta, "no-uuid") }
        refused("acta") { file.toDatabase(acta, mapOf("name" to "x")) }
        refused("acta") { file.toDatabase(acta, 42) }
    }

    private fun refused(
        property: String,
        block: () -> Unit
    ) {
        assertThatThrownBy(block).isInstanceOfSatisfying(ValidationException::class.java) { e ->
            assertThat(e.violations.map { it.field }).containsExactly(property)
        }
    }

    private fun obj() =
        CustomObject(
            id = UUID.randomUUID(),
            organizationId = UUID.randomUUID(),
            name = "exp",
            label = "Expediente",
            pluralLabel = "Expedientes",
            description = null,
            enabled = true,
            physicalTable = "exp__1",
            createdAt = null,
            updatedAt = null
        )

    private fun field(
        type: FieldType,
        attributes: Map<String, Any?> = mapOf(MAX_BYTES_COLUMN to null, CONTENT_TYPES_COLUMN to null)
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = UUID.randomUUID(),
        name = "acta",
        label = "Acta",
        type = type,
        columnName = "acta",
        required = false,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = null,
        relationTargetObjectId = null,
        attributes = attributes,
        visible = true,
        editable = true
    )
}
