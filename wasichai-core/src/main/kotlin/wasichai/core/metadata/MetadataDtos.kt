package wasichai.core.metadata

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
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
    // a single-column index on the field's column (ADR-036)
    val indexed: Boolean = false,
    // properties only a module's field type reads. core keeps them for it. a constructor
    // parameter, not a body property, so copy()/equals/hashCode carry it like every other field.
    @get:JsonIgnore @param:JsonAnySetter val extensions: Map<String, Any?> = emptyMap()
)

data class CreateObjectRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val fields: List<FieldRequest> = emptyList(),
    // composite indexes, field names in index order: [["anio", "predio"]] (ADR-036)
    val indexes: List<List<String>> = emptyList(),
    // composite uniques, same shape: [["sistema_origen", "referencia_externa"]] (ADR-037)
    val uniqueConstraints: List<List<String>> = emptyList(),
    // write rules (ADR-040): no update or delete of its records, for anyone
    val appendOnly: Boolean = false,
    // the generic record api does not write it; only the app's own code does
    val apiOnly: Boolean = false
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
    val position: Int? = null,
    val indexed: Boolean? = null
)

data class UpdateObjectRequest(
    // accepted only to be refused: renaming would move the table and the API path
    val name: String? = null,
    @field:NotBlank val label: String,
    val pluralLabel: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    // null leaves the declared indexes as they are; a list replaces them, [] drops them all
    val indexes: List<List<String>>? = null,
    // same rule for the composite uniques (ADR-037)
    val uniqueConstraints: List<List<String>>? = null,
    // null leaves the rule as it is: a client that predates it must not switch it off by saving a label (ADR-040)
    val appendOnly: Boolean? = null,
    val apiOnly: Boolean? = null
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
    // only written when true, so a field that declares nothing reads exactly as before (ADR-036)
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT) val indexed: Boolean = false,
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
    // only written when some are declared (ADR-036)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val indexes: List<List<String>> = emptyList(),
    // only written when some are declared (ADR-037)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val uniqueConstraints: List<List<String>> = emptyList(),
    val appendOnly: Boolean = false,
    val apiOnly: Boolean = false,
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
    // only written when some are declared (ADR-036)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val indexes: List<List<String>> = emptyList(),
    // only written when some are declared (ADR-037)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY) val uniqueConstraints: List<List<String>> = emptyList(),
    val appendOnly: Boolean = false,
    val apiOnly: Boolean = false,
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
