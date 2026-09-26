package wasichai.core.data

import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

object ObjectDefinitionFixtures {
    val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)

    fun empty(): ObjectDefinition = ObjectDefinition(obj, emptyList())

    fun field(
        name: String,
        type: FieldType
    ) = CustomField(UUID.randomUUID(), obj.id, name, name, type, name, false, false, null, null, 0, null, null, emptyMap(), true, true)
}
