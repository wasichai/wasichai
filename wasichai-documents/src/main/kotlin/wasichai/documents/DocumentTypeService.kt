package wasichai.documents

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.RelationshipService
import java.util.UUID

data class TemplateNodeRequest(
    val type: String,
    val attrs: Map<String, Any?>? = null,
    val content: List<TemplateNodeRequest>? = null,
    val marks: List<TemplateMarkRequest>? = null,
    val text: String? = null
)

data class TemplateMarkRequest(
    val type: String,
    val attrs: Map<String, Any?>? = null
)

data class CreateDocumentTypeRequest(
    val name: String,
    val label: String? = null,
    val prefix: String? = null,
    val template: TemplateNodeRequest? = null
)

// null means "leave as it is"
data class UpdateDocumentTypeRequest(
    val label: String? = null,
    val prefix: String? = null,
    val template: TemplateNodeRequest? = null
)

// a type plus the object it belongs to, which the row carries only as an id
data class ResolvedDocumentType(
    val type: DocumentType,
    val objectName: String
)

// like an object name, but hyphens read better in a url
private val VALID_TYPE_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")

// a sigla is printed on the document, so it is upper case and short. letters and digits only: it
// ends up in SGTM-2026-001, where a hyphen would be read as a separator.
private val VALID_PREFIX = Regex("^[A-Z][A-Z0-9]{0,11}$")

private const val MAX_TEMPLATE_NODES = 5000

@Service
class DocumentTypeService(
    private val types: DocumentTypeRepository,
    private val documents: DocumentRepository,
    private val metadata: MetadataService,
    private val relationships: RelationshipService,
    private val currentUser: CurrentUser
) {
    suspend fun list(objectName: String): List<ResolvedDocumentType> {
        val definition = readable(objectName)
        return types.findByObject(definition.obj.id).map { ResolvedDocumentType(it, definition.obj.name) }
    }

    suspend fun byName(
        objectName: String,
        name: String
    ): ResolvedDocumentType {
        val definition = readable(objectName)
        val stored =
            types.findByName(definition.obj.id, name.trim().lowercase())
                ?: throw NotFoundException("Document type '$name' does not exist")
        return ResolvedDocumentType(stored, definition.obj.name)
    }

    @Transactional
    suspend fun create(
        objectName: String,
        request: CreateDocumentTypeRequest
    ): ResolvedDocumentType {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val name = validName(request.name)
        val prefix = validPrefix(request.prefix)
        if (types.findByName(definition.obj.id, name) != null) {
            throw ConflictException("Document type '$name' already exists")
        }
        requireFreePrefix(user.organizationId, prefix, null)
        val template = validated(request.template, definition)
        val stored =
            types.insert(
                DocumentType(
                    id = UUID.randomUUID(),
                    organizationId = user.organizationId,
                    objectId = definition.obj.id,
                    name = name,
                    label = request.label?.trim()?.ifBlank { null } ?: name,
                    prefix = prefix,
                    template = template
                )
            )
        return ResolvedDocumentType(stored, definition.obj.name)
    }

    @Transactional
    suspend fun update(
        objectName: String,
        name: String,
        request: UpdateDocumentTypeRequest
    ): ResolvedDocumentType {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val current =
            types.findByName(definition.obj.id, name.trim().lowercase())
                ?: throw NotFoundException("Document type '$name' does not exist")
        val prefix = request.prefix?.let { validPrefix(it) } ?: current.prefix
        if (prefix != current.prefix) requireFreePrefix(user.organizationId, prefix, current.id)
        val stored =
            types.update(
                current.copy(
                    label = request.label?.trim()?.ifBlank { null } ?: current.label,
                    prefix = prefix,
                    template = request.template?.let { validated(it, definition) } ?: current.template
                )
            )
        return ResolvedDocumentType(stored, definition.obj.name)
    }

    @Transactional
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val current =
            types.findByName(definition.obj.id, name.trim().lowercase())
                ?: throw NotFoundException("Document type '$name' does not exist")
        // an issued document is a fact, not a setting. the FK says RESTRICT too, but a 409 with a
        // sentence beats a constraint violation with a stack trace.
        if (documents.countForType(current.id) > 0) {
            throw ConflictException("Document type '$name' has issued documents")
        }
        types.delete(current.id)
    }

    private suspend fun readable(objectName: String): ObjectDefinition {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return definition
    }

    private suspend fun requireFreePrefix(
        organizationId: UUID,
        prefix: String,
        selfId: UUID?
    ) {
        val owner = types.findByPrefix(organizationId, prefix)
        if (owner != null && owner.id != selfId) {
            throw ConflictException("Prefix '$prefix' already belongs to '${owner.name}'")
        }
    }

    private fun validName(raw: String?): String {
        val name = raw?.trim()?.lowercase().orEmpty()
        if (!VALID_TYPE_NAME.matches(name)) {
            throw ValidationException("Invalid name '$name'", "name", "must match ${VALID_TYPE_NAME.pattern}")
        }
        return name
    }

    private fun validPrefix(raw: String?): String {
        val prefix = raw?.trim()?.uppercase().orEmpty()
        if (!VALID_PREFIX.matches(prefix)) {
            throw ValidationException("Invalid prefix '$prefix'", "prefix", "must match ${VALID_PREFIX.pattern}")
        }
        return prefix
    }

    // the server never renders the template, so it checks only what the template NAMES: a field or
    // a relationship that does not exist would issue a document with a hole in it, and the hole
    // would be discovered by whoever the document was for.
    private suspend fun validated(
        request: TemplateNodeRequest?,
        definition: ObjectDefinition
    ): TemplateNode {
        val root = request ?: throw ValidationException("A template with no content", "template", "template must hold a document")
        if (root.type != "doc") {
            throw ValidationException("The template root is '${root.type}'", "template", "the root node must be a doc")
        }
        val fieldNames = definition.fields.map { it.name }.toSet()
        val relationshipNames = relationships.forObject(definition.obj.name).map { it.relationship.name }.toSet()
        var seen = 0

        fun walk(node: TemplateNodeRequest): TemplateNode {
            seen += 1
            if (seen > MAX_TEMPLATE_NODES) {
                throw ValidationException("The template holds more than $MAX_TEMPLATE_NODES nodes", "template", "write a shorter document")
            }
            when (node.type) {
                TemplateNodes.OBJECT_FIELD -> {
                    val field = attr(node, "field")
                    if (field !in fieldNames) {
                        throw ValidationException("Unknown field '$field'", "template", "'${definition.obj.name}' has no field '$field'")
                    }
                }
                TemplateNodes.RELATED_TABLE -> {
                    val relationship = attr(node, "relationship")
                    if (relationship !in relationshipNames) {
                        throw ValidationException(
                            "Unknown relationship '$relationship'",
                            "template",
                            "'${definition.obj.name}' has no relationship '$relationship'"
                        )
                    }
                }
                TemplateNodes.PLATFORM_VALUE -> PlatformValue.parse(attr(node, "value"))
                else -> Unit
            }
            return TemplateNode(
                type = node.type,
                attrs = node.attrs,
                content = node.content?.map(::walk),
                marks = node.marks?.map { TemplateMark(it.type, it.attrs) },
                text = node.text
            )
        }
        return walk(root)
    }

    private fun attr(
        node: TemplateNodeRequest,
        key: String
    ): String {
        val value =
            node.attrs
                ?.get(key)
                ?.toString()
                ?.trim()
                .orEmpty()
        if (value.isBlank()) {
            throw ValidationException("A ${node.type} names no $key", "template", "$key must not be blank")
        }
        return value
    }
}
