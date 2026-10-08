package wasichai.core.metadata

import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser

// actions per object name, built-in then declared. only objects the caller may READ are listed, like GET /api/objects.
// capabilities: the tenant-wide built-in actions held with no object, in CAPABILITIES order, never missing (ADR-053).
// a later read scope gets a key of its own beside them, not a place in this list.
data class CallerPermissionsResponse(
    val admin: Boolean,
    val capabilities: List<String>,
    val objects: Map<String, List<String>>
)

// the answer a client needs to hide actions it would only be refused. asking it grants nothing:
// every write is still checked by the service that performs it.
@Service
class CallerPermissionsService(
    private val objects: CustomObjectRepository,
    private val actions: ObjectActionRepository,
    private val currentUser: CurrentUser
) {
    suspend fun ofCaller(): CallerPermissionsResponse {
        val user = currentUser.require()
        val all = objects.findAll(user.organizationId)
        // the services' own check, so the answer never disagrees with them: ADMIN holds the first two, a service
        // account never MANAGE_ORGANIZATION (ADR-043), and only a grant with no object counts. MANAGE_TENANTS
        // follows MANAGE_ORGANIZATION, or with the switch on its own grant only (ADR-055)
        val capabilities = CAPABILITIES.filter { currentUser.hasPermission(user, it) }
        // declared actions (ADR-042) follow the record ones, by name
        if (user.isAdmin) {
            val declared = actions.namesByObject(user.organizationId)
            return CallerPermissionsResponse(true, capabilities, all.associate { it.name to RECORD_ACTIONS + declared[it.id].orEmpty() })
        }
        // one query per action, not one per object
        val permitted = RECORD_ACTIONS.associateWith { currentUser.permittedObjects(user, it) }
        val held = actions.heldBy(user.roles, user.organizationId)
        val byObject =
            all
                .map { obj -> obj.name to RECORD_ACTIONS.filter { permitted.getValue(it).allows(obj.id) } + held[obj.id].orEmpty() }
                .filter { (_, names) -> Actions.READ in names }
                .toMap()
        return CallerPermissionsResponse(false, capabilities, byObject)
    }

    companion object {
        // per object: record actions only. metadata and organization rights are tenant-wide, so they go in capabilities.
        val RECORD_ACTIONS = listOf(Actions.READ, Actions.CREATE, Actions.UPDATE, Actions.DELETE)

        // object-less built-in actions, in the order they are reported. one more is one more entry.
        val CAPABILITIES = listOf(Actions.MANAGE_METADATA, Actions.MANAGE_ORGANIZATION, Actions.MANAGE_TENANTS)
    }
}
