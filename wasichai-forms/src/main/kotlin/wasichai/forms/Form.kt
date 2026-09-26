package wasichai.forms

import java.util.UUID

// a group of fields shown together. title is optional: one nameless section is a flat form.
data class FormSection(
    val title: String?,
    val fields: List<String>
)

data class FormDefinition(
    val sections: List<FormSection> = emptyList()
)

data class Form(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val definition: FormDefinition
)
