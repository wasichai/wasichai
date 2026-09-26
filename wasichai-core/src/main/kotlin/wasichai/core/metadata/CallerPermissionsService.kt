package wasichai.core.metadata

import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.identity.CurrentUser

// actions per object name. only objects the caller may READ are listed, like GET /api/objects.
data class CallerPermissionsResponse(
    val admin: Boolean,
    val objects: Map<String, List<String>>
)

// the answer a client needs to hide actions it would only be refused. asking it grants nothing:
// every write is still checked by the service that performs it.
@Service
class CallerPermissionsService(
    private val objects: CustomObjectRepository,
    private val currentUser: CurrentUser
) {
    suspend fun ofCaller(): CallerPermissionsResponse {
        val user = currentUser.require()
        val all = objects.findAll(user.organizationId)
        if (user.isAdmin) return CallerPermissionsResponse(true, all.associate { it.name to RECORD_ACTIONS })
        // one query per action, not one per object
        val permitted = RECORD_ACTIONS.associateWith { currentUser.permittedObjects(user, it) }
        val byObject =
            all
                .map { obj -> obj.name to RECORD_ACTIONS.filter { permitted.getValue(it).allows(obj.id) } }
                .filter { (_, actions) -> Actions.READ in actions }
                .toMap()
        return CallerPermissionsResponse(false, byObject)
    }

    companion object {
        // record actions only. metadata and organization rights belong to the console, not to this question.
        val RECORD_ACTIONS = listOf(Actions.READ, Actions.CREATE, Actions.UPDATE, Actions.DELETE)
    }
}
