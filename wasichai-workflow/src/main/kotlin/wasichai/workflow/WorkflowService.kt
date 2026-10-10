package wasichai.workflow

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.ChangeReason
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordETag
import wasichai.core.data.RecordReadMasks
import wasichai.core.data.RecordReadScopes
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordStore
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuards
import wasichai.core.data.toResponse
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleDirectory
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.metadata.readableBy
import java.time.Instant
import java.util.UUID

// nullable on purpose: a missing field must read as a validation error, not a 500 from jackson
data class WorkflowStateRequest(
    val name: String? = null,
    val label: String? = null,
    val type: String? = null,
    // canvas coordinates. presentation, not behaviour: nothing validates them.
    val x: Double? = null,
    val y: Double? = null
)

data class WorkflowTransitionRequest(
    val name: String? = null,
    val label: String? = null,
    val from: String? = null,
    val to: String? = null,
    val roles: List<String> = emptyList()
)

data class WorkflowDefinitionRequest(
    val states: List<WorkflowStateRequest> = emptyList(),
    val transitions: List<WorkflowTransitionRequest> = emptyList()
)

data class SaveWorkflowRequest(
    val name: String? = null,
    val label: String? = null,
    val enabled: Boolean = true,
    val definition: WorkflowDefinitionRequest = WorkflowDefinitionRequest()
)

// every transition leaving the record's state, allowed or not, so the UI can explain why
data class AvailableTransition(
    val name: String,
    val label: String,
    val to: String,
    val toLabel: String,
    val allowed: Boolean,
    val reason: String? = null
)

@Service
class WorkflowService(
    private val roles: RoleDirectory,
    private val workflows: WorkflowRepository,
    private val metadata: MetadataService,
    private val schema: ObjectSchemaManager,
    private val store: RecordStore,
    private val audit: AuditService,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val changes: List<RecordChangeListener>,
    private val guards: RecordWriteGuards,
    private val readScopes: RecordReadScopes,
    // the app's read masks (ADR-065). defaulted: code that builds this service itself keeps compiling
    private val masks: RecordReadMasks = RecordReadMasks.NONE
) {
    suspend fun byObject(objectName: String): Pair<Workflow, String> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val workflow =
            workflows.findByObject(user.organizationId, definition.obj.id)
                ?: throw NotFoundException("Object '${definition.obj.name}' has no workflow")
        return workflow to definition.obj.name
    }

    // create or replace. one workflow per object, so PUT is the whole api.
    @Transactional
    suspend fun save(
        objectName: String,
        request: SaveWorkflowRequest
    ): Pair<Workflow, String> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.MANAGE_METADATA, definition.obj.id)

        val name = requireValidName(request.name?.trim()?.lowercase(), "name", "workflow")
        val roleNames = roles.namesOf(user.organizationId)
        val validated = validate(request.definition, roleNames)
        val existing = workflows.findByObject(user.organizationId, definition.obj.id)

        // the db has a unique name per org; fail cleanly before the constraint fires
        val clash = workflows.findByName(user.organizationId, name)
        if (clash != null && clash.id != existing?.id) {
            throw ConflictException("Workflow '$name' already exists")
        }

        val workflow =
            Workflow(
                id = existing?.id ?: UUID.randomUUID(),
                organizationId = user.organizationId,
                objectId = definition.obj.id,
                name = name,
                label = request.label?.trim()?.ifBlank { null } ?: definition.obj.label,
                enabled = request.enabled,
                definition = validated
            )
        // the column outlives every definition, so adding it is idempotent
        schema.addStateColumn(definition.obj)
        // records that already exist are not backfilled: they keep a null state, and every
        // transition is reported as not allowed until an admin decides what to do with them.
        val stored = if (existing == null) workflows.insert(workflow) else workflows.update(workflow)
        return stored to definition.obj.name
    }

    @Transactional
    suspend fun delete(objectName: String) {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.MANAGE_METADATA, definition.obj.id)
        val existing =
            workflows.findByObject(user.organizationId, definition.obj.id)
                ?: throw NotFoundException("Object '${definition.obj.name}' has no workflow")
        // the definition goes, workflow_state and its data stay. dropping the column would
        // destroy history for a decision the admin may well be reversing.
        workflows.delete(existing.id)
    }

    suspend fun transitionsOf(
        objectName: String,
        id: UUID
    ): List<AvailableTransition> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val workflow = workflows.findByObject(user.organizationId, definition.obj.id)
        if (workflow == null || !workflow.enabled) return emptyList()

        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        // the owner filter and the app's read scope, as a GET of the record (ADR-048)
        val record =
            store.findById(visible, user.organizationId, id, access.ownerFilter(user), true, readScopes.criteria(user, definition))
                ?: throw NotFoundException("Record $id does not exist")

        // a record older than the workflow has no state. show the moves out of the initial
        // state so the ui has something to explain, but none of them may fire.
        val stateless = record.state == null
        val from = record.state ?: workflow.definition.initialState()?.name
        val mayUpdate = currentUser.permittedObjects(user, Actions.UPDATE).allows(definition.obj.id)

        return workflow.definition.transitions
            .filter { it.from == from }
            .map { transition ->
                val reason =
                    when {
                        stateless -> "record has no workflow state yet"
                        !mayUpdate -> "requires permission ${Actions.UPDATE}"
                        !holdsRole(user, transition) -> "requires role ${transition.roles.joinToString(" or ")}"
                        else -> null
                    }
                AvailableTransition(
                    name = transition.name,
                    label = transition.label,
                    to = transition.to,
                    toLabel = workflow.definition.state(transition.to)?.label ?: transition.to,
                    allowed = reason == null,
                    reason = reason
                )
            }
    }

    @Transactional
    suspend fun apply(
        objectName: String,
        id: UUID,
        transitionName: String
    ): RecordResponse = apply(objectName, id, transitionName, null)

    // reason: why, stored on the transition's audit row; requiresReason asks for it (ADR-041)
    @Transactional
    suspend fun apply(
        objectName: String,
        id: UUID,
        transitionName: String,
        reason: String?
    ): RecordResponse = apply(objectName, id, transitionName, reason, expectedUpdatedAt = null)

    /**
     * [apply], only while the record still carries one of [expectedUpdatedAt] (ADR-051): the versions an
     * If-Match accepts, or the `updatedAt` the caller read. Compared in the transition's own UPDATE: a record
     * that moved on is a 412 and nothing is stored, audited or told. null: no check.
     */
    @Transactional
    suspend fun apply(
        objectName: String,
        id: UUID,
        transitionName: String,
        reason: String?,
        expectedUpdatedAt: List<Instant>?
    ): RecordResponse {
        val changeReason = ChangeReason.normalize(reason)
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.UPDATE, definition.obj.id)
        val workflow =
            workflows.findByObject(user.organizationId, definition.obj.id)
                ?: throw NotFoundException("Object '${definition.obj.name}' has no workflow")
        if (!workflow.enabled) {
            throw ConflictException("Workflow '${workflow.name}' is disabled")
        }
        val transition =
            workflow.definition.transitions.firstOrNull { it.name == transitionName }
                ?: throw NotFoundException("Workflow '${workflow.name}' has no transition '$transitionName'")

        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        // read whole: a guard judges the record, not the fields this caller may see (ADR-040). in the
        // caller's read scope: out of it, the record looks missing (ADR-048)
        val record =
            store.findById(definition, user.organizationId, id, access.ownerFilter(user), true, readScopes.criteria(user, definition))
                ?: throw NotFoundException("Record $id does not exist")

        if (!holdsRole(user, transition)) {
            throw ForbiddenException(
                "Transition '$transitionName' requires role ${transition.roles.joinToString(" or ")}"
            )
        }
        if (record.state != transition.from) {
            throw ConflictException(
                "Transition '$transitionName' leaves '${transition.from}', " +
                    "but record $id is in state '${record.state ?: "none"}'"
            )
        }

        // a transition moves the state: an UPDATE, so appendOnly refuses it and every guard sees it (ADR-040)
        guards.beforeWrite(
            definition,
            RecordWrite(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.TRANSITIONED,
                before = record.attributes,
                transition = transition.name,
                reason = changeReason
            )
        )
        val moved =
            if (expectedUpdatedAt == null) {
                store.transitionState(visible, user.organizationId, user.userId, id, transition.from, transition.to)
            } else {
                store.transitionStateIfUnchanged(visible, user.organizationId, user.userId, id, transition.from, transition.to, expectedUpdatedAt)
            } ?: throw missedTransition(definition, user, id, transition, expectedUpdatedAt)

        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = definition.obj.name,
            recordId = id,
            operation = AuditOperation.UPDATE,
            before = mapOf("state" to transition.from),
            after = mapOf("state" to transition.to),
            reason = changeReason
        )
        // read unprojected: an automation must judge the whole record, not the fields this
        // caller may see. the transition itself already happened.
        val full = store.findById(definition, user.organizationId, id, null, true)
        val change =
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.TRANSITIONED,
                before = full?.attributes,
                after = full?.attributes,
                state = transition.to,
                transition = transition.name
            )
        // every listener, in @Order, inside this transaction (P1 R9)
        changes.forEach { it.recordChanged(change) }
        return masks.row(user, user.organizationId, definition, visible, moved, full?.attributes).toResponse()
    }

    // the guarded UPDATE matched no row: gone or out of reach (404), stale against If-Match (412, ADR-051), or
    // the state moved on (409). the re-read narrows like the first one, so nothing out of reach is told apart
    private suspend fun missedTransition(
        definition: ObjectDefinition,
        user: AuthenticatedUser,
        id: UUID,
        transition: WorkflowTransition,
        expectedUpdatedAt: List<Instant>?
    ): Exception {
        if (expectedUpdatedAt != null) {
            val now =
                store.findById(definition, user.organizationId, id, access.ownerFilter(user), true, readScopes.criteria(user, definition))
                    ?: return NotFoundException("Record $id does not exist")
            if (now.updatedAt !in expectedUpdatedAt) return RecordETag.stale(id)
        }
        return ConflictException("Record $id left state '${transition.from}' before the transition ran")
    }

    // empty roles means anyone with UPDATE on the object. ADMIN always may.
    private fun holdsRole(
        user: AuthenticatedUser,
        transition: WorkflowTransition
    ): Boolean = transition.roles.isEmpty() || user.isAdmin || transition.roles.any { it in user.roles }

    private fun validate(
        request: WorkflowDefinitionRequest,
        roleNames: Set<String>
    ): WorkflowDefinition {
        if (request.states.isEmpty()) {
            throw ValidationException("Workflow has no states", "states", "at least one state is required")
        }

        val states =
            request.states.map { state ->
                val name = requireValidName(state.name?.trim()?.lowercase(), "states", "state")
                // half a position is no position: the client would place the box anyway
                val placed = state.x != null && state.y != null
                WorkflowState(
                    name = name,
                    label = state.label?.trim()?.ifBlank { null } ?: name,
                    type = parseStateType(state.type),
                    x = if (placed) state.x else null,
                    y = if (placed) state.y else null
                )
            }

        val duplicate = states.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw ValidationException("Duplicate state '${duplicate.key}'", "states", "state names must be unique")
        }
        val initial = states.filter { it.type == StateType.INITIAL }
        if (initial.size != 1) {
            throw ValidationException(
                "Workflow has ${initial.size} initial states",
                "states",
                "exactly one state must be INITIAL"
            )
        }

        val byName = states.associateBy { it.name }
        val transitions =
            request.transitions.map { transition ->
                val name = requireValidName(transition.name?.trim()?.lowercase(), "transitions", "transition")
                val from = requireValidName(transition.from?.trim()?.lowercase(), "transitions", "state")
                val to = requireValidName(transition.to?.trim()?.lowercase(), "transitions", "state")
                val source =
                    byName[from]
                        ?: throw ValidationException("Unknown state '$from'", "transitions", "no state is named '$from'")
                if (to !in byName) {
                    throw ValidationException("Unknown state '$to'", "transitions", "no state is named '$to'")
                }
                // a final state is final. a way out of it makes the type a lie.
                if (source.type == StateType.FINAL) {
                    throw ValidationException(
                        "Transition '$name' leaves the final state '$from'",
                        "transitions",
                        "a FINAL state has no outgoing transitions"
                    )
                }
                val roles = transition.roles.map { it.trim().uppercase() }.filter { it.isNotBlank() }
                roles.firstOrNull { it !in roleNames }?.let {
                    throw ValidationException("Unknown role '$it'", "transitions", "no role '$it' in this organization")
                }
                WorkflowTransition(
                    name = name,
                    label = transition.label?.trim()?.ifBlank { null } ?: name,
                    from = from,
                    to = to,
                    roles = roles
                )
            }

        val duplicateTransition = transitions.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateTransition != null) {
            throw ValidationException(
                "Duplicate transition '${duplicateTransition.key}'",
                "transitions",
                "transition names must be unique"
            )
        }

        return WorkflowDefinition(states, transitions)
    }

    private fun parseStateType(raw: String?): StateType {
        if (raw.isNullOrBlank()) return StateType.INTERMEDIATE
        return StateType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw ValidationException(
                "Unknown state type '$raw'",
                "states",
                "must be one of ${StateType.entries.joinToString(", ") { it.name }}"
            )
    }

    private fun requireValidName(
        name: String?,
        field: String,
        kind: String
    ): String {
        if (name.isNullOrBlank()) {
            throw ValidationException("Missing $kind name", field, "name is required")
        }
        if (!VALID_NAME.matches(name)) {
            throw ValidationException("Invalid $kind name '$name'", field, "must match ${VALID_NAME.pattern}")
        }
        return name
    }

    companion object {
        // like an object name, but hyphens read better in a url
        private val VALID_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")
    }
}
