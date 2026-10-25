package wasichai.gis

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

data class CreateMapViewRequest(
    val name: String,
    val label: String? = null,
    // null means the first geometry, as on a layer
    val geometryName: String? = null,
    val style: AttributeStyle? = null
)

// label and geometry: null keeps them. style is replaced as sent, so null clears it.
data class UpdateMapViewRequest(
    val label: String? = null,
    val geometryName: String? = null,
    val style: AttributeStyle? = null
)

data class MapViewResponse(
    val id: String,
    val name: String,
    val label: String,
    val objectName: String,
    val geometryName: String,
    val style: AttributeStyle?
)

// views are kept like list views (wasichai-views): read with READ on the object, written with MANAGE_METADATA
@Service
class MapViewService(
    private val views: MapViewRepository,
    private val metadata: MetadataService,
    private val styles: StyleClassService,
    private val currentUser: CurrentUser
) {
    // every object the caller may read; listDefinitions already leaves out the rest
    suspend fun all(): List<MapViewResponse> {
        val user = currentUser.require()
        val names = metadata.listDefinitions().associate { it.obj.id to it.obj.name }
        return views.findAll(user.organizationId).mapNotNull { view -> names[view.objectId]?.let { view.toResponse(it) } }
    }

    suspend fun list(objectName: String): List<MapViewResponse> {
        val (user, definition) = load(objectName, Actions.READ)
        return views.findByObject(user.organizationId, definition.obj.id).map { it.toResponse(definition.obj.name) }
    }

    suspend fun byName(
        objectName: String,
        name: String
    ): MapViewResponse {
        val (user, definition) = load(objectName, Actions.READ)
        return find(user, definition, name).toResponse(definition.obj.name)
    }

    @Transactional
    suspend fun create(
        objectName: String,
        request: CreateMapViewRequest
    ): MapViewResponse {
        val (user, definition) = load(objectName, Actions.MANAGE_METADATA)
        val name = request.name.trim().lowercase()
        if (!VALID_NAME.matches(name)) throw ValidationException("Invalid map view name '$name'", "name", "must match ${VALID_NAME.pattern}")
        if (views.findByName(user.organizationId, definition.obj.id, name) != null) {
            throw ConflictException("Map view '$name' already exists on '${definition.obj.name}'")
        }
        val view =
            MapView(
                id = UUID.randomUUID(),
                organizationId = user.organizationId,
                objectId = definition.obj.id,
                name = name,
                label =
                    request.label
                        ?.trim()
                        .orEmpty()
                        .ifBlank { definition.obj.pluralLabel },
                geometryName = geometry(definition, request.geometryName),
                style = request.style?.also { styles.validate(user, definition, it) }
            )
        return views.insert(view).toResponse(definition.obj.name)
    }

    @Transactional
    suspend fun update(
        objectName: String,
        name: String,
        request: UpdateMapViewRequest
    ): MapViewResponse {
        val (user, definition) = load(objectName, Actions.MANAGE_METADATA)
        val existing = find(user, definition, name)
        val updated =
            existing.copy(
                label = request.label?.trim()?.ifBlank { existing.label } ?: existing.label,
                geometryName = request.geometryName?.let { geometry(definition, it) } ?: existing.geometryName,
                style = request.style?.also { styles.validate(user, definition, it) }
            )
        return views.update(updated).toResponse(definition.obj.name)
    }

    @Transactional
    suspend fun delete(
        objectName: String,
        name: String
    ) {
        val (user, definition) = load(objectName, Actions.MANAGE_METADATA)
        views.delete(user.organizationId, find(user, definition, name).id)
    }

    private suspend fun load(
        objectName: String,
        action: String
    ): Pair<AuthenticatedUser, ObjectDefinition> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, action, definition.obj.id)
        return user to definition
    }

    private suspend fun find(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        name: String
    ): MapView =
        views.findByName(user.organizationId, definition.obj.id, name)
            ?: throw NotFoundException("Map view '$name' does not exist on '${definition.obj.name}'")

    private fun geometry(
        definition: ObjectDefinition,
        name: String?
    ): String {
        val geometries = definition.geometryFields
        if (geometries.isEmpty()) {
            throw ValidationException("Object '${definition.obj.name}' has no geometry", "object", "only objects with a geometry field have a map")
        }
        if (name == null) return geometries.first().name
        return geometries.firstOrNull { it.name == name }?.name
            ?: throw ValidationException("Unknown geometry '$name'", "geometryName", "is not a geometry of '${definition.obj.name}'")
    }

    private fun MapView.toResponse(objectName: String): MapViewResponse = MapViewResponse(id.toString(), name, label, objectName, geometryName, style)

    private companion object {
        // the same names a list view takes
        val VALID_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
    }
}
