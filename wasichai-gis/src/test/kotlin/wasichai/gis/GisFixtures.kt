package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

object GisFixtures {
    val obj =
        CustomObject(
            id = UUID.randomUUID(),
            organizationId = UUID.randomUUID(),
            name = "predio",
            label = "Predio",
            pluralLabel = "Predios",
            description = null,
            enabled = true,
            physicalTable = "predio__00000000",
            createdAt = null,
            updatedAt = null
        )

    fun geometry(
        name: String,
        type: String? = "POLYGON",
        srid: Int? = 32718,
        dimension: Int? = 2
    ): CustomField = field(name, GEOMETRY, mapOf(GEOMETRY_TYPE_COLUMN to type, SRID_COLUMN to srid, DIMENSION_COLUMN to dimension))

    fun text(name: String): CustomField = field(name, FieldType.TEXT, mapOf(GEOMETRY_TYPE_COLUMN to null, SRID_COLUMN to null, DIMENSION_COLUMN to null))

    fun definition(vararg fields: CustomField) = ObjectDefinition(obj, fields.toList())

    private fun field(
        name: String,
        type: FieldType,
        attributes: Map<String, Any?>
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = obj.id,
        name = name,
        label = name,
        type = type,
        columnName = name,
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

    // a 400 naming the field and the message, the way the original answered
    fun refused(
        message: String,
        field: String,
        block: () -> Unit
    ) {
        val error = assertThrows<ValidationException> { block() }
        assertThat(error.message).isEqualTo(message)
        assertThat(error.violations.single().field).isEqualTo(field)
    }
}
