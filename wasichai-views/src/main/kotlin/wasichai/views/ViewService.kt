package wasichai.views

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

data class ViewSortRequest(
    val field: String,
    val direction: String? = null
)

data class ViewDefinitionRequest(
    val columns: List<String> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
    val sort: ViewSortRequest? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE
)

data class CreateViewRequest(
    val name: String,
    val label: String? = null,
    val isDefault: Boolean = false,
    val definition: ViewDefinitionRequest = ViewDefinitionRequest()
)

// null means "leave as it is"
data class UpdateViewRequest(
    val label: String? = null,
    val isDefault: Boolean? = null,
    val definition: ViewDefinitionRequest? = null
)

// a view plus the two things the wire needs that the row does not carry
data class ResolvedView(
    val view: View,
    val objectName: String,
    val generated: Boolean
)

// the name that always resolves, stored or not
const val DEFAULT_VIEW_NAME = "default"

@Service
class ViewService(
    private val views: ViewRepository,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser
) {
    // stored views, or the generated default when the admin authored none
    suspend fun list(objectName: String): List<ResolvedView> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val stored = views.findByObject(definition.obj.id)
        if (stored.isEmpty()) return listOf(ResolvedView(generate(definition), definition.obj.name, true))
        return stored.map { ResolvedView(it, definition.obj.name, false) }
    }

    // stored only. the metadata endpoint reports what is authored, nothing invented.
    suspend fun forObject(objectName: String): List<ResolvedView> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return views.findByObject(definition.obj.id).map { ResolvedView(it, definition.obj.name, false) }
    }

    suspend fun byName(
        objectName: String,
        name: String
    ): ResolvedView {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        views.findByName(definition.obj.id, name)?.let { return ResolvedView(it, definition.obj.name, false) }
        if (name == DEFAULT_VIEW_NAME) {
            // whatever opens the list: the flagged view, else one derived from the metadata
            views.findDefault(definition.obj.id)?.let { return ResolvedView(it, definition.obj.name, false) }
            return ResolvedView(generate(definition), definition.obj.name, true)
        }
        throw NotFoundException("View '$name' does not exist on '${definition.obj.name}'")
    }

    @Transactional
    suspend fun create(
        objectName: String,
        request: CreateViewRequest
    ): ResolvedView {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val name = requireValidViewName(request.name.trim().lowercase())

        if (views.findByName(definition.obj.id, name) != null) {
            throw ConflictException("View '$name' already exists on '${definition.obj.name}'")
        }

        val view =
            View(
                id = UUID.randomUUID(),
                organizationId = user.organizationId,
                objectId = definition.obj.id,
                name = name,
                label =
                    request.label
                        ?.trim()
                        .orEmpty()
                        .ifBlank { definition.obj.pluralLabel },
                isDefault = request.isDefault,
                definition = validated(request.definition, definition)
            )
        if (view.isDefault) views.clearDefault(definition.obj.id, view.id)
        return ResolvedView(views.insert(view), definition.obj.name, false)
    }

    @Transactional
    suspend fun update(
        objectName: String,
        name: String,
        request: UpdateViewRequest
    ): ResolvedView {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val existing = views.findByName(definition.obj.id, name) ?: throw NotFoundException("View '$name' does not exist on '${definition.obj.name}'")

        val updated =
            existing.copy(
                label = request.label?.trim()?.ifBlank { existing.label } ?: existing.label,
                isDefault = request.isDefault ?: existing.isDefault,
                definition = request.definition?.let { validated(it, definition) } ?: existing.definition
            )
        if (updated.isDefault) views.clearDefault(definition.obj.id, updated.id)
        return ResolvedView(views.update(updated), definition.obj.name, false)
    }

    @Transactional
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        val existing = views.findByName(definition.obj.id, name) ?: throw NotFoundException("View '$name' does not exist on '${definition.obj.name}'")
        views.delete(existing.id)
    }

    // no view stored: show the visible fields, oldest first, and stop before the table gets wide
    private fun generate(definition: ObjectDefinition): View =
        View(
            // stable so the client can key on it, even though nothing is stored
            id = UUID.nameUUIDFromBytes("${definition.obj.id}:view:$DEFAULT_VIEW_NAME".toByteArray()),
            organizationId = definition.obj.organizationId,
            objectId = definition.obj.id,
            name = DEFAULT_VIEW_NAME,
            label = definition.obj.pluralLabel,
            isDefault = true,
            definition =
                ViewDefinition(
                    columns =
                        definition.fields
                            .filter { it.visible }
                            .map { it.name }
                            .take(GENERATED_COLUMN_LIMIT)
                )
        )

    private fun validated(
        request: ViewDefinitionRequest,
        definition: ObjectDefinition
    ): ViewDefinition {
        val fieldNames = definition.fields.map { it.name }.toSet()
        val objectName = definition.obj.name

        request.columns.firstOrNull { it !in fieldNames }?.let {
            throw ValidationException("Unknown column '$it'", "columns", "'$objectName' has no field '$it'")
        }
        request.filters.keys.firstOrNull { it !in fieldNames }?.let {
            throw ValidationException("Unknown filter '$it'", "filters", "'$objectName' has no field '$it'")
        }
        if (request.pageSize !in MIN_PAGE_SIZE..MAX_PAGE_SIZE) {
            throw ValidationException(
                "Invalid page size ${request.pageSize}",
                "pageSize",
                "must be between $MIN_PAGE_SIZE and $MAX_PAGE_SIZE"
            )
        }

        val sort =
            request.sort?.let {
                val field = it.field.trim()
                if (field !in fieldNames && field !in SORTABLE_SYSTEM_COLUMNS) {
                    throw ValidationException(
                        "Unknown sort field '$field'",
                        "sort",
                        "must be a field of '$objectName' or one of ${SORTABLE_SYSTEM_COLUMNS.joinToString(", ")}"
                    )
                }
                ViewSort(field, SortDirection.parse(it.direction))
            }

        return ViewDefinition(
            columns = request.columns,
            filters = request.filters,
            sort = sort,
            pageSize = request.pageSize
        )
    }

    private fun requireValidViewName(name: String): String {
        if (!VALID_VIEW_NAME.matches(name)) {
            throw ValidationException("Invalid view name '$name'", "name", "must match ${VALID_VIEW_NAME.pattern}")
        }
        return name
    }

    companion object {
        // like an object name, but hyphens read better in a url
        private val VALID_VIEW_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
        private const val GENERATED_COLUMN_LIMIT = 8
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 200
    }
}
