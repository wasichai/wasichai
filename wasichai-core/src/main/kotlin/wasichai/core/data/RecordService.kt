package wasichai.core.data

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.stereotype.Service
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.FieldAccess
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.readableBy
import wasichai.core.metadata.readableNames
import wasichai.core.metadata.writableBy
import java.time.Instant
import java.util.UUID

data class RecordRequest(
    val attributes: Map<String, Any?> = emptyMap()
) {
    /**
     * Payload sections of installed field types, by section then field. Left out is left alone, null
     * clears. Sections are deliberately excluded from audit diffs and [RecordChange]: only
     * `attributes` is compared, stored as `before`/`after`, and handed to listeners, so a section
     * field never appears in a record's history or reaches an automation. The first module to add a
     * section field keeps it out of both for the same reason (ADR-0019).
     */
    @get:JsonIgnore
    val sections: MutableMap<String, Map<String, Any?>> = linkedMapOf()

    // anything that is not an object cannot be a section, so it is ignored like any unknown property
    @JsonAnySetter
    fun section(
        name: String,
        value: Any?
    ) {
        if (value is Map<*, *>) sections[name] = value.entries.associate { (key, item) -> key.toString() to item }
    }
}

data class RecordResponse(
    val id: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val attributes: Map<String, Any?>,
    // the record's state. null when nothing gives the object one.
    val state: String? = null,
    // one key per installed section (R5). kept out of the json as itself, written flat below.
    @get:JsonIgnore val sections: Map<String, Map<String, Any?>> = emptyMap()
) {
    @JsonAnyGetter
    fun flattened(): Map<String, Map<String, Any?>> = sections
}

@Service
class RecordService(
    private val metadata: MetadataService,
    private val store: RecordStore,
    private val audit: AuditService,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val workflows: WorkflowStates,
    private val types: FieldTypeRegistry,
    private val changes: List<RecordChangeListener>
) {
    suspend fun list(
        objectName: String,
        query: RecordQuery
    ): PageResponse<RecordResponse> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        // unreadable columns are never selected, so they cannot leak by accident
        val visible = definition.readableBy(fieldAccess)
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val page =
            store.query(
                visible,
                user.organizationId,
                query.copy(createdBy = access.ownerFilter(user), withState = workflow.attached)
            )
        return PageResponse(
            content = page.content.map { it.toResponse() },
            page = page.page,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages
        )
    }

    suspend fun get(
        objectName: String,
        id: UUID
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        return store
            .findById(visible, user.organizationId, id, access.ownerFilter(user), workflow.attached)
            ?.toResponse()
            ?: throw NotFoundException("Record $id does not exist")
    }

    suspend fun create(
        objectName: String,
        request: RecordRequest
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.CREATE, definition.obj.id)
        rejectDisabled(definition)
        val sections = installed(request.sections)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        rejectUnwritableRequired(definition, fieldAccess)
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val created =
            store.insert(
                definition.writableBy(fieldAccess),
                user.organizationId,
                user.userId,
                request.attributes,
                sections,
                workflow
            )
        // audit and listeners judge the record as stored, every field (ADR-0025): the port promises
        // nothing about what insert hands back, and a locked field left out would read as cleared
        val stored = storedRow(definition, user, created, workflow.attached)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = created.id,
            operation = AuditOperation.CREATE,
            after = stored.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = created.id,
                kind = RecordChangeKind.CREATED,
                after = stored.attributes,
                state = stored.state
            )
        )
        return created.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest
    ): RecordResponse {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.UPDATE, definition.obj.id)
        rejectDisabled(definition)
        val sections = installed(request.sections)
        val fieldAccess = access.fieldAccess(user, definition.obj.id)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        val workflow = workflows.stateOf(user.organizationId, definition.obj.id)
        val before =
            store.findById(definition, user.organizationId, id, access.ownerFilter(user), workflow.attached)
                ?: throw NotFoundException("Record $id does not exist")
        // locked fields keep their stored value: a full-replace PUT must not blank them.
        // the state is untouched here: it only moves through a transition.
        val updated =
            store.update(
                definition.writableBy(fieldAccess),
                user.organizationId,
                user.userId,
                id,
                request.attributes,
                sections,
                workflow.attached
            )
        // before is a full read; after must be one too, or every locked field reads as cleared (ADR-0025)
        val stored = storedRow(definition, user, updated, workflow.attached)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = id,
            operation = AuditOperation.UPDATE,
            before = before.attributes,
            after = stored.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.UPDATED,
                before = before.attributes,
                after = stored.attributes,
                state = stored.state
            )
        )
        return updated.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun delete(
        objectName: String,
        id: UUID
    ) {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.DELETE, definition.obj.id)
        rejectDisabled(definition)
        val before =
            store.findById(definition, user.organizationId, id, access.ownerFilter(user))
                ?: throw NotFoundException("Record $id does not exist")
        store.delete(definition, user.organizationId, id)
        audit.record(
            organizationId = user.organizationId,
            userId = user.userId,
            objectName = objectName,
            recordId = id,
            operation = AuditOperation.DELETE,
            before = before.attributes
        )
        notify(
            RecordChange(
                organizationId = user.organizationId,
                userId = user.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = id,
                kind = RecordChangeKind.DELETED,
                before = before.attributes,
                state = before.state
            )
        )
    }

    // rows rather than pages, for modules that render records their own way
    suspend fun rows(
        objectName: String,
        query: RecordQuery
    ): Pair<ObjectDefinition, List<RecordRow>> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val visible = definition.readableBy(access.fieldAccess(user, definition.obj.id))
        return visible to
            store
                .query(visible, user.organizationId, query.copy(createdBy = access.ownerFilter(user)))
                .content
    }

    /**
     * The row as stored, every field, for audit and listeners (ADR-0025). Listeners get this, not the
     * caller's projection: automations judge the whole record and act as the platform (ADR-016), and
     * nothing here is ever sent back to the caller.
     *
     * The write's own RETURNING row wins when it already carries every field: it is atomic with the
     * write. The re-read is non-transactional (this service opens no transaction), best-effort, and
     * only used when RETURNING was projected: a concurrent write could land in between.
     */
    private suspend fun storedRow(
        definition: ObjectDefinition,
        user: AuthenticatedUser,
        written: RecordRow,
        withState: Boolean
    ): RecordRow {
        if (carriesEveryField(definition, written)) return written
        return store.findById(definition, user.organizationId, written.id, null, withState) ?: written
    }

    // a section field sits in its own section, every other field in attributes
    private fun carriesEveryField(
        definition: ObjectDefinition,
        row: RecordRow
    ): Boolean =
        definition.fields.all { field ->
            val section = types.handler(field.type).section
            if (section == null) field.name in row.attributes else row.sections[section]?.containsKey(field.name) == true
        }

    private suspend fun notify(change: RecordChange) {
        changes.forEach { it.recordChanged(change) }
    }

    // sections no installed type owns are ignored, like any unknown property
    private fun installed(sections: Map<String, Map<String, Any?>>) = sections.filterKeys { it in types.sections }

    // dropping the value silently would let the caller believe the edit landed. a section field is
    // a field, so its write permission is the field's: checking only `attributes` would let a locked
    // one in through the other door.
    private fun rejectUnwritable(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>
    ) {
        if (fieldAccess.unrestricted) return
        val denied =
            definition.fields.firstOrNull { field ->
                // a field is "sent" through attributes, or through its OWN section - never any other
                // section: a name that only coincides with a key in an unrelated section is not a write.
                val section = types.handler(field.type).section
                val sent = attributes.containsKey(field.name) || (section != null && sections[section]?.containsKey(field.name) == true)
                sent && !fieldAccess.canWrite(field.id)
            }
        if (denied != null) {
            throw ValidationException(
                "Field '${denied.name}' is not writable for you",
                denied.name,
                "your roles may not write this field"
            )
        }
    }

    // a required field nobody may write would fail on NOT NULL: say so instead of a 500
    private fun rejectUnwritableRequired(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess
    ) {
        if (fieldAccess.unrestricted) return
        val blocked =
            definition.fields.firstOrNull { it.required && it.defaultValue == null && !fieldAccess.canWrite(it.id) }
                ?: return
        throw ForbiddenException(
            "Field '${blocked.name}' is required but your roles may not write it, so you cannot create ${definition.obj.name}"
        )
    }

    private fun RecordRow.onlyReadable(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess
    ): RecordRow {
        if (fieldAccess.unrestricted) return this
        val allowed = definition.readableNames(fieldAccess)
        return copy(
            attributes = attributes.filterKeys { it in allowed },
            sections = sections.mapValues { (_, entries) -> entries.filterKeys { it in allowed } }
        )
    }
}

// a disabled object is retired, not gone: its data stays readable, nothing new lands on it.
// package-level so RelatedRecordService's other end can be held to the same rule (P2 final-review).
internal fun rejectDisabled(definition: ObjectDefinition) {
    if (!definition.obj.enabled) {
        throw ConflictException("Object '${definition.obj.name}' is disabled and accepts no changes")
    }
}

fun RecordRow.toResponse(): RecordResponse =
    RecordResponse(
        id = id.toString(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        attributes = attributes,
        state = state,
        sections = sections
    )
