package wasichai.core.metadata

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// a verb an object has beyond CRUD, declared by the app (ADR-042). granted and checked like a built-in action.
data class ObjectAction(
    val objectId: UUID,
    val name: String,
    val label: String
)

data class ObjectActionRequest(
    @field:NotBlank val name: String,
    val label: String? = null
)

data class ObjectActionResponse(
    val name: String,
    val label: String
)

@Repository
class ObjectActionRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun findByObject(objectId: UUID): List<ObjectAction> =
        db
            .sql("SELECT object_id, name, label FROM ${schemas.metadata}.object_actions WHERE object_id = :objectId ORDER BY name")
            .bind("objectId", objectId)
            .map { row, _ -> ObjectAction(Rows.uuid(row, "object_id"), Rows.string(row, "name"), Rows.string(row, "label")) }
            .all()
            .asFlow()
            .toList()

    suspend fun exists(
        objectId: UUID,
        name: String
    ): Boolean =
        db
            .sql("SELECT true FROM ${schemas.metadata}.object_actions WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map { _, _ -> true }
            .one()
            .awaitFirstOrNull() ?: false

    // every declared action of the tenant, by object. the administrator holds them all.
    suspend fun namesByObject(organizationId: UUID): Map<UUID, List<String>> =
        db
            .sql(
                """
                SELECT a.object_id, a.name
                FROM ${schemas.metadata}.object_actions a
                JOIN ${schemas.metadata}.custom_objects o ON o.id = a.object_id
                WHERE o.organization_id = :organizationId
                ORDER BY a.name
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .map { row, _ -> Rows.uuid(row, "object_id") to Rows.string(row, "name") }
            .all()
            .asFlow()
            .toList()
            .groupBy({ it.first }, { it.second })

    // declared actions these roles hold, by object. declared_object_id is set only on declared grants.
    suspend fun heldBy(
        roleNames: List<String>,
        organizationId: UUID
    ): Map<UUID, List<String>> {
        if (roleNames.isEmpty()) return emptyMap()
        return db
            .sql(
                """
                SELECT DISTINCT p.declared_object_id AS object_id, p.action
                FROM ${schemas.metadata}.permissions p
                JOIN ${schemas.metadata}.roles r ON r.id = p.role_id
                WHERE r.organization_id = :organizationId
                  AND r.name IN (:roleNames)
                  AND p.allowed
                  AND p.declared_object_id IS NOT NULL
                ORDER BY p.action
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("roleNames", roleNames)
            .map { row, _ -> Rows.uuid(row, "object_id") to Rows.string(row, "action") }
            .all()
            .asFlow()
            .toList()
            .groupBy({ it.first }, { it.second })
    }

    suspend fun insert(action: ObjectAction): ObjectAction {
        db
            .sql("INSERT INTO ${schemas.metadata}.object_actions (object_id, name, label) VALUES (:objectId, :name, :label)")
            .bind("objectId", action.objectId)
            .bind("name", action.name)
            .bind("label", action.label)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return action
    }

    // its grants go with it: the permissions foreign key cascades
    suspend fun delete(
        objectId: UUID,
        name: String
    ): Boolean =
        db
            .sql("DELETE FROM ${schemas.metadata}.object_actions WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0
}

@Service
class ObjectActionService(
    private val objects: CustomObjectRepository,
    private val actions: ObjectActionRepository,
    private val currentUser: CurrentUser
) {
    // what may be granted on the object. whoever may read the object may know its verbs.
    suspend fun list(objectName: String): List<ObjectAction> {
        val user = currentUser.require()
        val obj = objectOrFail(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, obj.id)
        return actions.findByObject(obj.id)
    }

    @Transactional
    suspend fun declare(
        objectName: String,
        request: ObjectActionRequest
    ): ObjectAction {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj = objectOrFail(user.organizationId, objectName)
        val name = requireValidName(request.name)
        if (actions.exists(obj.id, name)) {
            throw ConflictException("Action '$name' already exists on '${obj.name}'")
        }
        return actions.insert(ObjectAction(obj.id, name, request.label?.trim()?.ifBlank { null } ?: name))
    }

    @Transactional
    suspend fun remove(
        objectName: String,
        actionName: String
    ) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj = objectOrFail(user.organizationId, objectName)
        val name = actionName.trim().uppercase()
        if (!actions.delete(obj.id, name)) {
            throw NotFoundException("Action '$name' does not exist on '${obj.name}'")
        }
    }

    private suspend fun objectOrFail(
        organizationId: UUID,
        objectName: String
    ): CustomObject =
        objects.findByName(organizationId, objectName.trim().lowercase())
            ?: throw NotFoundException("Object '$objectName' does not exist")

    companion object {
        // same shape as a role name: upper snake, so it reads like the built-in ones it sits beside
        private val NAME = Regex("^[A-Z][A-Z0-9_]{1,48}$")

        fun requireValidName(raw: String): String {
            val name = raw.trim().uppercase()
            if (!NAME.matches(name)) {
                throw ValidationException("Invalid action name '$name'", "name", "must match ^[A-Z][A-Z0-9_]{1,48}$")
            }
            if (name in Actions.BUILT_IN) {
                throw ValidationException("Action '$name' is built in", "name", "must not be one of ${Actions.BUILT_IN.joinToString(", ")}")
            }
            return name
        }
    }
}

@RestController
@RequestMapping("/api/metadata/objects/{object}/actions")
class ObjectActionController(
    private val actions: ObjectActionService
) {
    @GetMapping
    suspend fun list(
        @PathVariable("object") objectName: String
    ): List<ObjectActionResponse> = actions.list(objectName).map { ObjectActionResponse(it.name, it.label) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun declare(
        @PathVariable("object") objectName: String,
        @Valid @RequestBody request: ObjectActionRequest
    ): ObjectActionResponse = actions.declare(objectName, request).let { ObjectActionResponse(it.name, it.label) }

    @DeleteMapping("/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun remove(
        @PathVariable("object") objectName: String,
        @PathVariable("action") actionName: String
    ) = actions.remove(objectName, actionName)
}
