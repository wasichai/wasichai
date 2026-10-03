package wasichai.core.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.Relationship
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.metadata.RelationshipService
import wasichai.core.metadata.readableBy
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// records on the other side of a relationship. metadata owns the relationship; walking its
// records is a record matter, so it lives here.
@Service
class RelatedRecordService(
    private val relationships: RelationshipRepository,
    private val relationshipService: RelationshipService,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val metadata: MetadataService,
    private val store: RecordStore,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val audit: AuditService,
    private val guards: RecordWriteGuards
) {
    // records on the other side of a relationship, from one record
    suspend fun relatedRecords(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        query: RecordQuery
    ): Pair<ObjectDefinition, PageResponse<RecordRow>> {
        val user = currentUser.require()
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        currentUser.requirePermission(user, Actions.READ, obj.id)
        return relatedRows(
            user.organizationId,
            objectName,
            recordId,
            relationshipName,
            query.copy(createdBy = access.ownerFilter(user)),
            narrow = { definition -> definition.readableBy(access.fieldAccess(user, definition.obj.id)) }
        )
    }

    // the same walk with no caller behind it: a module acting as the platform (ADR-016) has no user
    // to check. callers that DO have a user pass `narrow` to put their field rules back.
    suspend fun relatedRows(
        organizationId: UUID,
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        query: RecordQuery,
        narrow: suspend (ObjectDefinition) -> ObjectDefinition = { it }
    ): Pair<ObjectDefinition, PageResponse<RecordRow>> {
        val obj =
            objects.findByName(organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        val relationship =
            relationships.findByName(organizationId, relationshipName)
                ?: throw NotFoundException("Relationship '$relationshipName' does not exist")
        val view = relationshipService.side(relationship, obj)
        val otherDefinition = narrow(metadata.loadDefinition(organizationId, view.otherObject.name))

        val resolved =
            when {
                relationship.usesJoinTableFor() -> {
                    val ids = linkedIds(relationship, obj, recordId)
                    store.query(otherDefinition, organizationId, query.copy(ids = ids))
                }
                fkIsOn(relationship, obj) -> {
                    // this record carries the foreign key: follow it to a single record
                    val definition = metadata.loadDefinition(organizationId, obj.name)
                    val field = relationFieldOrFail(relationship)
                    val value = store.findById(definition, organizationId, recordId)?.attributes?.get(field.name)
                    val targetId = (value as? String)?.let(UUID::fromString)
                    store.query(otherDefinition, organizationId, query.copy(ids = listOfNotNull(targetId)))
                }
                else -> {
                    // the other side points back at this record
                    val field = relationFieldOrFail(relationship)
                    store.query(otherDefinition, organizationId, query.copy(filters = query.filters + (field.name to recordId.toString())))
                }
            }
        return otherDefinition to resolved
    }

    @Transactional
    suspend fun link(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) = link(objectName, recordId, relationshipName, otherId, viaApi = false)

    // viaApi: the generic related-record route, which an apiOnly end refuses (ADR-040)
    @Transactional
    internal suspend fun link(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID,
        viaApi: Boolean
    ) {
        val ends = checkedEnds(objectName, recordId, relationshipName, otherId, viaApi)
        guard(ends, linked = true)
        val inserted =
            db
                .sql(
                    """
                    INSERT INTO ${schemas.dataTable(ends.relationship.joinTable!!)}
                        (organization_id, source_id, target_id)
                    VALUES (:organizationId, :sourceId, :targetId)
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).bind("organizationId", ends.relationship.organizationId)
                .bind("sourceId", ends.sourceId)
                .bind("targetId", ends.targetId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        // already linked: nothing changed, nothing to record
        if (inserted > 0) auditLink(ends, linked = true)
    }

    @Transactional
    suspend fun unlink(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID
    ) = unlink(objectName, recordId, relationshipName, otherId, viaApi = false)

    @Transactional
    internal suspend fun unlink(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID,
        viaApi: Boolean
    ) {
        val ends = checkedEnds(objectName, recordId, relationshipName, otherId, viaApi)
        guard(ends, linked = false)
        val deleted =
            db
                .sql(
                    "DELETE FROM ${schemas.dataTable(ends.relationship.joinTable!!)} " +
                        "WHERE source_id = :sourceId AND target_id = :targetId AND organization_id = :organizationId"
                ).bind("sourceId", ends.sourceId)
                .bind("targetId", ends.targetId)
                .bind("organizationId", ends.relationship.organizationId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (deleted > 0) auditLink(ends, linked = false)
    }

    // both records of one link, as the caller may see them
    private data class LinkEnds(
        val user: AuthenticatedUser,
        val relationship: Relationship,
        val definition: ObjectDefinition,
        val recordId: UUID,
        val record: RecordRow,
        val otherDefinition: ObjectDefinition,
        val otherId: UUID,
        val other: RecordRow
    ) {
        private val fromSource get() = definition.obj.id == relationship.sourceObjectId
        val sourceId: UUID get() = if (fromSource) recordId else otherId
        val targetId: UUID get() = if (fromSource) otherId else recordId
    }

    // a link writes to both records, so both are held to what the record api holds them to (ADR-0025):
    // same tenant, and only the caller's own when own_records_only. missing, foreign and not-yours all
    // look the same -- a 404 -- exactly like GET/PUT/DELETE on a record. the titular side already
    // needs UPDATE to reach here (manyToManyOrFail); the other end must clear the same bar, or a link
    // would let UPDATE on A alone write a history row onto a B the caller may not touch.
    private suspend fun checkedEnds(
        objectName: String,
        recordId: UUID,
        relationshipName: String,
        otherId: UUID,
        viaApi: Boolean
    ): LinkEnds {
        val user = currentUser.require()
        val (relationship, obj) = manyToManyOrFail(user, objectName, relationshipName)
        val otherObjectId = if (obj.id == relationship.sourceObjectId) relationship.targetObjectId else relationship.sourceObjectId
        currentUser.requirePermission(user, Actions.UPDATE, otherObjectId)
        val definition = metadata.loadDefinitionById(user.organizationId, obj.id)
        val otherDefinition = metadata.loadDefinitionById(user.organizationId, otherObjectId)
        rejectDisabled(otherDefinition)
        if (viaApi) {
            rejectApiOnly(definition)
            rejectApiOnly(otherDefinition)
        }
        val owner = access.ownerFilter(user)
        val record =
            store.findById(definition, user.organizationId, recordId, owner)
                ?: throw NotFoundException("Record $recordId does not exist")
        val other =
            store.findById(otherDefinition, user.organizationId, otherId, owner)
                ?: throw NotFoundException("Record $otherId does not exist")
        return LinkEnds(user, relationship, definition, recordId, record, otherDefinition, otherId, other)
    }

    // a link is an UPDATE of both records (audit says so too): appendOnly and every guard judge each end
    // before the join table is touched (ADR-040)
    private suspend fun guard(
        ends: LinkEnds,
        linked: Boolean
    ) {
        listOf(
            Triple(ends.definition, ends.record, ends.otherId),
            Triple(ends.otherDefinition, ends.other, ends.recordId)
        ).forEach { (definition, row, other) ->
            guards.beforeWrite(
                definition,
                RecordWrite(
                    organizationId = ends.user.organizationId,
                    userId = ends.user.userId,
                    objectId = definition.obj.id,
                    objectName = definition.obj.name,
                    recordId = row.id,
                    kind = RecordChangeKind.UPDATED,
                    before = row.attributes,
                    attributes = linkChange(ends.relationship.name, other, linked).second
                )
            )
        }
    }

    // one UPDATE on each record's history. UPDATE, not a new operation: the audit CHECK stays the one
    // core and documents define (ADR-0025).
    private suspend fun auditLink(
        ends: LinkEnds,
        linked: Boolean
    ) {
        listOf(
            Triple(ends.definition, ends.recordId, ends.otherId),
            Triple(ends.otherDefinition, ends.otherId, ends.recordId)
        ).forEach { (definition, id, other) ->
            val (before, after) = linkChange(ends.relationship.name, other, linked)
            audit.record(
                organizationId = ends.user.organizationId,
                userId = ends.user.userId,
                objectName = definition.obj.name,
                recordId = id,
                operation = AuditOperation.UPDATE,
                before = before,
                after = after
            )
        }
    }

    private suspend fun manyToManyOrFail(
        user: AuthenticatedUser,
        objectName: String,
        relationshipName: String
    ): Pair<Relationship, CustomObject> {
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        currentUser.requirePermission(user, Actions.UPDATE, obj.id)
        val relationship =
            relationships.findByName(user.organizationId, relationshipName)
                ?: throw NotFoundException("Relationship '$relationshipName' does not exist")
        if (!relationship.type.usesJoinTable) {
            throw ValidationException(
                "Not a many-to-many relationship",
                "relationship",
                "link and unlink only apply to MANY_TO_MANY; update the field instead"
            )
        }
        return relationship to obj
    }

    private suspend fun linkedIds(
        relationship: Relationship,
        obj: CustomObject,
        recordId: UUID
    ): List<UUID> {
        val fromSource = obj.id == relationship.sourceObjectId
        val selected = if (fromSource) "target_id" else "source_id"
        val matched = if (fromSource) "source_id" else "target_id"
        return db
            .sql("SELECT $selected AS other_id FROM ${schemas.dataTable(relationship.joinTable!!)} WHERE $matched = :recordId")
            .bind("recordId", recordId)
            .map { row, _ -> row.get("other_id", UUID::class.java)!! }
            .all()
            .asFlow()
            .toList()
    }

    private suspend fun relationFieldOrFail(relationship: Relationship): CustomField =
        relationship.relationFieldId?.let { fields.findById(it) }
            ?: throw NotFoundException("Relationship '${relationship.name}' has no field")

    private fun Relationship.usesJoinTableFor(): Boolean = type.usesJoinTable && joinTable != null

    // true when the record we start from owns the foreign key column
    private fun fkIsOn(
        relationship: Relationship,
        obj: CustomObject
    ): Boolean =
        (relationship.type.fkOnSource && obj.id == relationship.sourceObjectId) ||
            (relationship.type.fkOnTarget && obj.id == relationship.targetObjectId)
}

// what a link or unlink looks like in a record's history: the relationship as the "field", the other
// record's id appearing or going away. `rel:` keeps it apart from a real field of the same name.
internal fun linkChange(
    relationship: String,
    otherId: UUID,
    linked: Boolean
): Pair<Map<String, Any?>, Map<String, Any?>> {
    val key = linkAuditKey(relationship)
    val absent = mapOf<String, Any?>(key to null)
    val present = mapOf<String, Any?>(key to otherId.toString())
    return if (linked) absent to present else present to absent
}

internal fun linkAuditKey(relationship: String): String = "rel:$relationship"
