package wasichai.core.metadata

import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser

// actions per object name, built-in then declared. only objects the caller may READ are listed, like GET /api/objects.
data class CallerPermissionsResponse(
    val admin: Boolean,
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
        // declared actions (ADR-042) follow the record ones, by name
        if (user.isAdmin) {
            val declared = actions.namesByObject(user.organizationId)
            return CallerPermissionsResponse(true, all.associate { it.name to RECORD_ACTIONS + declared[it.id].orEmpty() })
        }
        // one query per action, not one per object
        val permitted = RECORD_ACTIONS.associateWith { currentUser.permittedObjects(user, it) }
        val held = actions.heldBy(user.roles, user.organizationId)
        val byObject =
            all
                .map { obj -> obj.name to RECORD_ACTIONS.filter { permitted.getValue(it).allows(obj.id) } + held[obj.id].orEmpty() }
                .filter { (_, names) -> Actions.READ in names }
                .toMap()
        return CallerPermissionsResponse(false, byObject)
    }

    companion object {
        // record actions only. metadata and organization rights belong to the console, not to this question.
        val RECORD_ACTIONS = listOf(Actions.READ, Actions.CREATE, Actions.UPDATE, Actions.DELETE)
    }
}
