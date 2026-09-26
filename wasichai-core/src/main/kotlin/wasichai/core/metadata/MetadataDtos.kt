package wasichai.core.metadata

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class FieldRequest(
    @field:NotBlank val name: String,
    val label: String? = null,
    @field:NotBlank val type: String,
    val required: Boolean = false,
    val unique: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
    val enumOptions: List<String>? = null,
    // target object technical name, for RELATION fields
    val relationTarget: String? = null,
    val visible: Boolean = true,
    val editable: Boolean = true,
    // properties only a module's field type reads. core keeps them for it. a constructor
    // parameter, not a body property, so copy()/equals/hashCode carry it like every other field.
    @get:JsonIgnore @param:JsonAnySetter val extensions: Map<String, Any?> = emptyMap()
)

data class CreateObjectRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val fields: List<FieldRequest> = emptyList()
)

// null means "leave as it is". name and type are accepted only to be refused: see MetadataService.
data class UpdateFieldRequest(
    val name: String? = null,
    val type: String? = null,
    val label: String? = null,
    val required: Boolean? = null,
    val unique: Boolean? = null,
    val description: String? = null,
    val enumOptions: List<String>? = null,
    val visible: Boolean? = null,
    val editable: Boolean? = null,
    val position: Int? = null
)

data class UpdateObjectRequest(
    // accepted only to be refused: renaming would move the table and the API path
    val name: String? = null,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val enabled: Boolean = true
)

// installed field types add their own keys (FieldTypeRegistry.fieldProperties), flattened in
data class FieldResponse(
    val id: String,
    val name: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val unique: Boolean,
    val defaultValue: String?,
    val description: String?,
    val position: Int,
    val enumOptions: List<String>?,
    val relationTarget: String?,
    val visible: Boolean,
    val editable: Boolean,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

data class ObjectResponse(
    val id: String,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

// what the dynamic UI renders from: object + fields, flat so the client needs no unwrapping.
data class ObjectDefinitionResponse(
    val id: String,
    val name: String,
    val label: String,
    val pluralLabel: String,
    val description: String?,
    val enabled: Boolean,
    val fields: List<FieldResponse>,
    // what installed field types add (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val extensions: Map<String, Any?> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Any?> = extensions
}

// the names the platform keeps for itself. published so the field editor can show them
// instead of letting the admin find out through a 400.
data class SystemFieldResponse(
    val name: String,
    // FieldType vocabulary, not postgres. null when no column is created for the name.
    val type: String?,
    val scope: String
)
