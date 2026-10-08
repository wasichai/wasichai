package wasichai.core.data

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
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
import wasichai.core.metadata.FieldDefaults
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.readableBy
import wasichai.core.metadata.readableNames
import wasichai.core.metadata.writableBy
import wasichai.core.platform.Background
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.TenantDirectory
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
    private val changes: List<RecordChangeListener>,
    private val guards: RecordWriteGuards,
    private val references: AppendOnlyReferences,
    private val readScopes: RecordReadScopes,
    private val tenants: TenantDirectory
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs [block] as the platform for [organizationId] (ADR-039): every call it makes to this service
     * acts in that organization, with no user. No permission or field rule applies, as for ADMIN; the
     * writes leave `created_by`, `updated_by` and the audit `user_id` null, as an automation does
     * (ADR-016). Composes with the caller's transaction either way round (ADR-038).
     *
     * Background work only. Inside a request, with a token or without one, it throws: nothing a
     * request carries can become the platform.
     *
     * Its audit rows say `platform` (ADR-050); the overload below names the work instead.
     */
    suspend fun <T> asPlatform(
        organizationId: UUID,
        block: suspend () -> T
    ): T = PlatformCaller.run(organizationId, ChangeOrigin.PLATFORM, block)

    /**
     * [asPlatform], with the app's own label on the block's audit rows instead of `platform`
     * (ADR-050): `job:retention`, `import:42`. It must match `^[A-Za-z0-9._:-]{1,64}$`, or the call
     * throws `IllegalArgumentException` before anything runs. An overload, not a default: code
     * compiled against the two-argument call keeps working.
     */
    suspend fun <T> asPlatform(
        organizationId: UUID,
        source: String,
        block: suspend () -> T
    ): T = PlatformCaller.run(organizationId, source, block)

    /**
     * Runs [block] once per organization of the [TenantDirectory] (ADR-057), each inside
     * [asPlatform] for that organization with [source] on its audit rows. With [objectName], only the
     * organizations that define that object. In id order, one at a time.
     *
     * One organization's failure is logged and the loop goes on; the block handles what must not be
     * lost. Background work only: inside a request it throws before any block runs, as [asPlatform]
     * does. A bad [source] throws `IllegalArgumentException` before anything runs.
     */
    suspend fun forEachOrganization(
        objectName: String? = null,
        source: String = ChangeOrigin.PLATFORM,
        block: suspend (organizationId: UUID) -> Unit
    ) {
        // before the directory: a replaced one may lack the tripwire, and asPlatform's own throw would be swallowed below
        Background.require("RecordService.forEachOrganization", "never becomes the platform")
        ChangeOrigin.requireValidSource(source)
        val organizations = if (objectName == null) tenants.organizations() else tenants.organizationsWithObject(objectName)
        organizations.forEach { tenant ->
            try {
                asPlatform(tenant.id, source) { block(tenant.id) }
            } catch (e: CancellationException) {
                // our own cancellation stops the loop; a block's own timeout is a failure like any other
                currentCoroutineContext().ensureActive()
                log.warn("{} failed for organization {} ({}): {}", source, tenant.slug, tenant.id, e.message, e)
            } catch (e: Exception) {
                log.warn("{} failed for organization {} ({}): {}", source, tenant.slug, tenant.id, e.message, e)
            }
        }
    }

    suspend fun list(
        objectName: String,
        query: RecordQuery
    ): PageResponse<RecordResponse> {
        val caller = caller()
        val definition = metadata.loadDefinition(caller.organizationId, objectName)
        caller.requirePermission(Actions.READ, definition.obj.id)
        val fieldAccess = caller.fieldAccess(definition.obj.id)
        // unreadable columns are never selected, so they cannot leak by accident
        val visible = definition.readableBy(fieldAccess)
        val workflow = workflows.stateOf(caller.organizationId, definition.obj.id)
        val page =
            store.query(
                visible,
                caller.organizationId,
                query.copy(createdBy = caller.ownerFilter(), withState = workflow.attached, criteria = query.criteria + caller.scope(definition))
            )
        return page.map { it.toResponse() }
    }

    suspend fun get(
        objectName: String,
        id: UUID
    ): RecordResponse {
        val caller = caller()
        val definition = metadata.loadDefinition(caller.organizationId, objectName)
        caller.requirePermission(Actions.READ, definition.obj.id)
        val visible = definition.readableBy(caller.fieldAccess(definition.obj.id))
        val workflow = workflows.stateOf(caller.organizationId, definition.obj.id)
        return store
            .findById(visible, caller.organizationId, id, caller.ownerFilter(), workflow.attached, caller.scope(definition))
            ?.toResponse()
            ?: throw NotFoundException("Record $id does not exist")
    }

    suspend fun create(
        objectName: String,
        request: RecordRequest
    ): RecordResponse = create(objectName, request, reason = null, viaApi = false)

    /**
     * [reason]: why, as the writer says it (ADR-041). Trimmed, blank is none, stored on the write's
     * audit row. An object with `requiresReason` refuses the write without one (400 on `reason`).
     */
    suspend fun create(
        objectName: String,
        request: RecordRequest,
        reason: String?
    ): RecordResponse = create(objectName, request, reason, viaApi = false)

    // viaApi: the generic record api calls, which an apiOnly object refuses (ADR-040)
    internal suspend fun create(
        objectName: String,
        request: RecordRequest,
        reason: String?,
        viaApi: Boolean
    ): RecordResponse {
        val write = open(objectName, Actions.CREATE, reason, viaApi)
        val caller = write.caller
        val definition = write.definition
        val sections = installed(request.sections)
        val fieldAccess = caller.fieldAccess(definition.obj.id)
        // only what the caller sent is theirs to be refused; a default is the field's own value (issue 60)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        rejectUnwritableRequired(definition, fieldAccess)
        val (target, attributes) = FieldDefaults.applied(definition.writableBy(fieldAccess), request.attributes, types)
        val workflow = workflows.stateOf(caller.organizationId, definition.obj.id)
        // a guard judges what the record will hold, defaults included: a RELATION default is checked like a sent value
        write.guard(RecordChangeKind.CREATED, recordId = null, attributes = attributes, reader = caller.user)
        val created =
            store.insert(
                target,
                caller.organizationId,
                caller.userId,
                attributes,
                sections,
                workflow
            )
        // audit and listeners judge the record as stored, every field (ADR-0025): the port promises
        // nothing about what insert hands back, and a locked field left out would read as cleared
        val stored = storedRow(definition, caller.organizationId, created, workflow.attached)
        write.recorded(RecordChangeKind.CREATED, created.id, after = stored.attributes, state = stored.state)
        return created.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest
    ): RecordResponse = update(objectName, id, request, reason = null, viaApi = false)

    // reason: as for create (ADR-041)
    suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String?
    ): RecordResponse = update(objectName, id, request, reason, viaApi = false)

    /**
     * [update], only while the record still carries [expectedUpdatedAt], the `updatedAt` the caller read
     * (ADR-051). The compare is part of the UPDATE itself, so it holds inside the caller's transaction
     * (ADR-038) and against any concurrent writer: a record that moved on throws
     * [wasichai.core.common.PreconditionFailedException] (412) and nothing is stored, audited or told.
     * null: no check, as the overloads above. An overload, not a default: code compiled against them keeps working.
     */
    suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String?,
        expectedUpdatedAt: Instant?
    ): RecordResponse = update(objectName, id, request, reason, viaApi = false, expectedUpdatedAt = expectedUpdatedAt?.let(::listOf))

    // expectedUpdatedAt: what If-Match accepts, null when it sent none or * (ADR-051)
    internal suspend fun update(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String?,
        viaApi: Boolean,
        expectedUpdatedAt: List<Instant>? = null
    ): RecordResponse = write(objectName, id, request, reason, viaApi, expectedUpdatedAt, partial = false)

    /**
     * A partial update (ADR-051): JSON merge on `attributes`. Only the keys sent are written, `null` clears
     * one, every other field keeps its stored value, also against a concurrent write of another field. A
     * key that is no attribute of the object is a 400 and a read-only field (`editable: false`) a 403;
     * everything else is [update]'s: permissions, field rules, write rules, guards, audit and listeners.
     * Sections merge as they always do. [expectedUpdatedAt] as for [update].
     */
    suspend fun patch(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String? = null,
        expectedUpdatedAt: Instant? = null
    ): RecordResponse = patch(objectName, id, request, reason, viaApi = false, expectedUpdatedAt = expectedUpdatedAt?.let(::listOf))

    internal suspend fun patch(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String?,
        viaApi: Boolean,
        expectedUpdatedAt: List<Instant>?
    ): RecordResponse = write(objectName, id, request, reason, viaApi, expectedUpdatedAt, partial = true)

    // PUT and PATCH: one path, so the two can never drift apart on a rule
    private suspend fun write(
        objectName: String,
        id: UUID,
        request: RecordRequest,
        reason: String?,
        viaApi: Boolean,
        expectedUpdatedAt: List<Instant>?,
        partial: Boolean
    ): RecordResponse {
        val write = open(objectName, Actions.UPDATE, reason, viaApi)
        val caller = write.caller
        val definition = write.definition
        val sections = installed(request.sections)
        val fieldAccess = caller.fieldAccess(definition.obj.id)
        // a PUT ignores what it cannot write by metadata; a PATCH names only what it means to change, so it says
        if (partial) rejectUnknownOrReadOnly(definition, request.attributes)
        rejectUnwritable(definition, fieldAccess, request.attributes, sections)
        val workflow = workflows.stateOf(caller.organizationId, definition.obj.id)
        val before =
            store.findById(definition, caller.organizationId, id, caller.ownerFilter(), workflow.attached, caller.scope(definition))
                ?: throw NotFoundException("Record $id does not exist")
        // a guard judges what the record will hold: a PATCH shows it as the PUT of the same change would
        val sent = if (partial) before.attributes + request.attributes else request.attributes
        write.guard(RecordChangeKind.UPDATED, id, before = before.attributes, attributes = sent, reader = caller.user)
        // locked fields keep their stored value: a full-replace PUT must not blank them. a PATCH locks every
        // field it did not send. the state is untouched here: it only moves through a transition.
        val target = definition.writableBy(fieldAccess).let { if (partial) it.lockedBut(request.attributes.keys) else it }
        val updated =
            if (expectedUpdatedAt == null) {
                store.update(target, caller.organizationId, caller.userId, id, request.attributes, sections, workflow.attached)
            } else {
                store.updateIfUnchanged(target, caller.organizationId, caller.userId, id, request.attributes, sections, workflow.attached, expectedUpdatedAt)
                    ?: throw caller.staleOrMissing(definition, id)
            }
        // before is a full read; after must be one too, or every locked field reads as cleared (ADR-0025)
        val stored = storedRow(definition, caller.organizationId, updated, workflow.attached)
        write.recorded(RecordChangeKind.UPDATED, id, before = before.attributes, after = stored.attributes, state = stored.state)
        return updated.onlyReadable(definition, fieldAccess).toResponse()
    }

    suspend fun delete(
        objectName: String,
        id: UUID
    ) = delete(objectName, id, reason = null, viaApi = false)

    // reason: as for create (ADR-041)
    suspend fun delete(
        objectName: String,
        id: UUID,
        reason: String?
    ) = delete(objectName, id, reason, viaApi = false)

    // [expectedUpdatedAt]: as for update (ADR-051). a record that moved on stays, 412
    suspend fun delete(
        objectName: String,
        id: UUID,
        reason: String?,
        expectedUpdatedAt: Instant?
    ) = delete(objectName, id, reason, viaApi = false, expectedUpdatedAt = expectedUpdatedAt?.let(::listOf))

    internal suspend fun delete(
        objectName: String,
        id: UUID,
        reason: String?,
        viaApi: Boolean,
        expectedUpdatedAt: List<Instant>? = null
    ) {
        val write = open(objectName, Actions.DELETE, reason, viaApi)
        val caller = write.caller
        val definition = write.definition
        val before =
            store.findById(definition, caller.organizationId, id, caller.ownerFilter(), criteria = caller.scope(definition))
                ?: throw NotFoundException("Record $id does not exist")
        // postgres would null or drop what append-only records hold of this one (ADR-040). checked
        // again under a row lock, with the delete, when anything append-only can point here (ADR-044).
        // the version compare rides in the DELETE itself, under that lock too (ADR-051)
        references.deleting(caller.organizationId, definition, id, guard = { write.guard(RecordChangeKind.DELETED, id, before = before.attributes) }) {
            val deleted =
                if (expectedUpdatedAt == null) {
                    store.delete(definition, caller.organizationId, id)
                } else {
                    store.deleteIfUnchanged(definition, caller.organizationId, id, expectedUpdatedAt)
                }
            // without a precondition a row gone meanwhile is the old answer: deleted all the same
            if (!deleted && expectedUpdatedAt != null) throw caller.staleOrMissing(definition, id)
        }
        write.recorded(RecordChangeKind.DELETED, id, before = before.attributes, state = before.state)
    }

    // rows rather than pages, for modules that render records their own way
    suspend fun rows(
        objectName: String,
        query: RecordQuery
    ): Pair<ObjectDefinition, List<RecordRow>> {
        val caller = caller()
        val definition = metadata.loadDefinition(caller.organizationId, objectName)
        caller.requirePermission(Actions.READ, definition.obj.id)
        val visible = definition.readableBy(caller.fieldAccess(definition.obj.id))
        return visible to
            store
                .query(visible, caller.organizationId, query.copy(createdBy = caller.ownerFilter(), criteria = query.criteria + caller.scope(definition)))
                .content
    }

    // who is calling: the token's user, or the platform inside asPlatform. user null = the platform.
    private class Caller(
        val organizationId: UUID,
        val user: AuthenticatedUser?
    ) {
        val userId: UUID? get() = user?.userId
    }

    private suspend fun caller(): Caller {
        PlatformCaller.current()?.let { return Caller(it, null) }
        val user = currentUser.require()
        return Caller(user.organizationId, user)
    }

    // the platform passes every check, as ADMIN does: no role, no field rule, no owner filter, no read scope
    private suspend fun Caller.requirePermission(
        action: String,
        objectId: UUID
    ) {
        if (user != null) currentUser.requirePermission(user, action, objectId)
    }

    private suspend fun Caller.fieldAccess(objectId: UUID): FieldAccess = if (user == null) FieldAccess.FULL else access.fieldAccess(user, objectId)

    private suspend fun Caller.ownerFilter(): UUID? = if (user == null) null else access.ownerFilter(user)

    // the app's read scope, next to the owner filter (ADR-048). the full definition, not the caller's projection
    private suspend fun Caller.scope(definition: ObjectDefinition): List<RecordCriterion> = readScopes.criteria(user, definition)

    // a compare-and-write matched no row (ADR-051): stale when the caller still reads the record, else it is
    // gone or out of reach meanwhile, and answers as a missing one (ADR-048)
    private suspend fun Caller.staleOrMissing(
        definition: ObjectDefinition,
        id: UUID
    ): Exception =
        if (store.findById(definition, organizationId, id, ownerFilter(), criteria = scope(definition)) != null) {
            RecordETag.stale(id)
        } else {
            NotFoundException("Record $id does not exist")
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
        organizationId: UUID,
        written: RecordRow,
        withState: Boolean
    ): RecordRow {
        if (carriesEveryField(definition, written)) return written
        return store.findById(definition, organizationId, written.id, null, withState) ?: written
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

    // one write: who writes, on which object, and why (ADR-041). guards, the audit row and listeners
    // are all told about it from here, so the three always describe the same write.
    private class Write(
        val caller: Caller,
        val definition: ObjectDefinition,
        val reason: String?
    )

    // the gate in front of every write, in this order: the reason's form, the caller, the object, the
    // api-only door (ADR-040), the action, a disabled object. a refusal here stores nothing.
    private suspend fun open(
        objectName: String,
        action: String,
        reason: String?,
        viaApi: Boolean
    ): Write {
        val changeReason = ChangeReason.normalize(reason)
        val caller = caller()
        val definition = metadata.loadDefinition(caller.organizationId, objectName)
        if (viaApi) rejectApiOnly(definition)
        caller.requirePermission(action, definition.obj.id)
        rejectDisabled(definition)
        return Write(caller, definition, changeReason)
    }

    // [reader]: whose read scope the relation values must be in (D30). a delete sets none, so names nobody
    private suspend fun Write.guard(
        kind: RecordChangeKind,
        recordId: UUID?,
        before: Map<String, Any?>? = null,
        attributes: Map<String, Any?>? = null,
        reader: AuthenticatedUser? = null
    ) {
        guards.beforeWrite(
            definition,
            RecordWrite(
                organizationId = caller.organizationId,
                userId = caller.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = recordId,
                kind = kind,
                before = before,
                attributes = attributes,
                reason = reason
            ),
            reader
        )
    }

    // what happened, once stored: the audit row, keyed by the object's name as history reads it, then
    // every listener (ADR-0025)
    private suspend fun Write.recorded(
        kind: RecordChangeKind,
        recordId: UUID,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
        state: String? = null
    ) {
        audit.record(
            organizationId = caller.organizationId,
            userId = caller.userId,
            objectName = definition.obj.name,
            recordId = recordId,
            operation = kind.auditOperation(),
            before = before,
            after = after,
            reason = reason
        )
        notify(
            RecordChange(
                organizationId = caller.organizationId,
                userId = caller.userId,
                objectId = definition.obj.id,
                objectName = definition.obj.name,
                recordId = recordId,
                kind = kind,
                before = before,
                after = after,
                state = state
            )
        )
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

    // a PATCH key must be an attribute of the object, and one its metadata lets anyone write (ADR-051)
    private fun rejectUnknownOrReadOnly(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>
    ) {
        attributes.keys.forEach { name ->
            val field =
                definition.fields.firstOrNull { it.name == name && types.handler(it.type).section == null }
                    ?: throw ValidationException("Unknown field '$name'", name, "is not an attribute of '${definition.obj.name}'")
            if (!field.editable) throw ForbiddenException("Field '$name' is read-only")
        }
    }

    // a required field nobody may write would fail on NOT NULL: say so instead of a 500. one with a
    // default gets it on create, so it passes
    private fun rejectUnwritableRequired(
        definition: ObjectDefinition,
        fieldAccess: FieldAccess
    ) {
        if (fieldAccess.unrestricted) return
        val blocked =
            definition.fields.firstOrNull { it.required && !FieldDefaults.appliesTo(it, types) && !fieldAccess.canWrite(it.id) }
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

// every attribute field not [sent] locked, so the store leaves it as stored. sections keep their own rule.
private fun ObjectDefinition.lockedBut(sent: Set<String>): ObjectDefinition {
    val locked = fields.map { field -> if (field.name in sent) field else field.copy(editable = false) }
    return copy(fields = locked)
}

// a disabled object is retired, not gone: its data stays readable, nothing new lands on it.
// package-level so RelatedRecordService's other end can be held to the same rule (P2 final-review).
internal fun rejectDisabled(definition: ObjectDefinition) {
    if (!definition.obj.enabled) {
        throw ConflictException("Object '${definition.obj.name}' is disabled and accepts no changes")
    }
}

// a transition is audited as the UPDATE it is: the audit CHECK knows no other operation
private fun RecordChangeKind.auditOperation(): AuditOperation =
    when (this) {
        RecordChangeKind.CREATED -> AuditOperation.CREATE
        RecordChangeKind.UPDATED, RecordChangeKind.TRANSITIONED -> AuditOperation.UPDATE
        RecordChangeKind.DELETED -> AuditOperation.DELETE
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
