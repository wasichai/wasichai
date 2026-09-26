package wasichai.forms

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

data class FormSectionRequest(
    val title: String? = null,
    val fields: List<String> = emptyList()
)

data class FormDefinitionRequest(
    val sections: List<FormSectionRequest> = emptyList()
)

data class CreateFormRequest(
    val name: String,
    val label: String? = null,
    val definition: FormDefinitionRequest = FormDefinitionRequest()
)

// null means "leave as it is"
data class UpdateFormRequest(
    val label: String? = null,
    val definition: FormDefinitionRequest? = null
)

// a form plus the two things the wire needs that the row does not carry
data class ResolvedForm(
    val form: Form,
    val objectName: String,
    val generated: Boolean
)

// the name that always resolves, stored or not
const val DEFAULT_FORM_NAME = "default"

@Service
class FormService(
    private val forms: FormRepository,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser
) {
    // stored forms, or the generated default when the admin authored none
    suspend fun list(objectName: String): List<ResolvedForm> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val stored = forms.findByObject(definition.obj.id)
        if (stored.isEmpty()) return listOf(ResolvedForm(generate(definition), definition.obj.name, true))
        return stored.map { ResolvedForm(it, definition.obj.name, false) }
    }

    // stored only. the metadata endpoint reports what is authored, nothing invented.
    suspend fun forObject(objectName: String): List<ResolvedForm> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return forms.findByObject(definition.obj.id).map { ResolvedForm(it, definition.obj.name, false) }
    }

    suspend fun byName(
        objectName: String,
        name: String
    ): ResolvedForm {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        forms.findByName(definition.obj.id, name)?.let { return ResolvedForm(it, definition.obj.name, false) }
        if (name == DEFAULT_FORM_NAME) return ResolvedForm(generate(definition), definition.obj.name, true)
        throw NotFoundException("Form '$name' does not exist on '${definition.obj.name}'")
    }

    // pages point at forms by name. the page validator already checked the permission.
    suspend fun storedNames(objectId: UUID): Set<String> = forms.findByObject(objectId).map { it.name }.toSet()

    @Transactional
    suspend fun create(
        objectName: String,
        request: CreateFormRequest
    ): ResolvedForm {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val name = requireValidFormName(request.name.trim().lowercase())

        if (forms.findByName(definition.obj.id, name) != null) {
            throw ConflictException("Form '$name' already exists on '${definition.obj.name}'")
        }

        val stored =
            forms.insert(
                Form(
                    id = UUID.randomUUID(),
                    organizationId = user.organizationId,
                    objectId = definition.obj.id,
                    name = name,
                    label =
                        request.label
                            ?.trim()
                            .orEmpty()
                            .ifBlank { definition.obj.label },
                    definition = validated(request.definition, definition)
                )
            )
        return ResolvedForm(stored, definition.obj.name, false)
    }

    @Transactional
    suspend fun update(
        objectName: String,
        name: String,
        request: UpdateFormRequest
    ): ResolvedForm {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val existing =
            forms.findByName(definition.obj.id, name)
                ?: throw NotFoundException("Form '$name' does not exist on '${definition.obj.name}'")

        val stored =
            forms.update(
                existing.copy(
                    label = request.label?.trim()?.ifBlank { existing.label } ?: existing.label,
                    definition = request.definition?.let { validated(it, definition) } ?: existing.definition
                )
            )
        return ResolvedForm(stored, definition.obj.name, false)
    }

    @Transactional
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val existing =
            forms.findByName(definition.obj.id, name)
                ?: throw NotFoundException("Form '$name' does not exist on '${definition.obj.name}'")
        forms.delete(existing.id)
    }

    // no form stored: everything the user may type, in metadata order, in one nameless section
    private fun generate(definition: ObjectDefinition): Form =
        Form(
            // stable so the client can key on it, even though nothing is stored
            id = UUID.nameUUIDFromBytes("${definition.obj.id}:form:$DEFAULT_FORM_NAME".toByteArray()),
            organizationId = definition.obj.organizationId,
            objectId = definition.obj.id,
            name = DEFAULT_FORM_NAME,
            label = definition.obj.label,
            definition = FormDefinition(listOf(FormSection(null, definition.fields.filter { it.editable }.map { it.name })))
        )

    private fun validated(
        request: FormDefinitionRequest,
        definition: ObjectDefinition
    ): FormDefinition {
        if (request.sections.isEmpty()) {
            throw ValidationException("Form has no sections", "sections", "at least one section is required")
        }
        val fields = definition.fields.associateBy { it.name }
        val objectName = definition.obj.name
        val seen = mutableSetOf<String>()

        val sections =
            request.sections.map { section ->
                val title = section.title?.trim()
                if (section.title != null && title.isNullOrEmpty()) {
                    throw ValidationException("Blank section title", "sections", "title may be null but not blank")
                }
                section.fields.forEach { field ->
                    val meta =
                        fields[field]
                            ?: throw ValidationException("Unknown field '$field'", "sections", "'$objectName' has no field '$field'")
                    if (!meta.editable) {
                        throw ValidationException("Field '$field' is read-only", "sections", "only editable fields belong on a form")
                    }
                    if (!seen.add(field)) {
                        throw ValidationException("Field '$field' appears twice", "sections", "a field belongs to one section only")
                    }
                }
                FormSection(title, section.fields)
            }
        return FormDefinition(sections)
    }

    private fun requireValidFormName(name: String): String {
        if (!VALID_FORM_NAME.matches(name)) {
            throw ValidationException("Invalid form name '$name'", "name", "must match ${VALID_FORM_NAME.pattern}")
        }
        return name
    }

    companion object {
        // like an object name, but hyphens read better in a url
        private val VALID_FORM_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
    }
}
