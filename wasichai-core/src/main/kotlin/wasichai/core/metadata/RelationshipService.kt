package wasichai.core.metadata

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AdminEntity
import wasichai.core.identity.AdminOperation
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.SqlIdentifier
import java.util.UUID

data class CreateRelationshipRequest(
    val name: String,
    val label: String,
    val inverseLabel: String? = null,
    val type: String,
    val source: String,
    val target: String,
    // field that carries the FK; defaults to the referenced object's name
    val fieldName: String? = null,
    // per language tag (ADR-078): a blank text drops its language
    val labels: Map<String, String?>? = null,
    val inverseLabels: Map<String, String?>? = null
)

// null means "leave as it is". type, source and target are accepted only to be refused: see update.
data class UpdateRelationshipRequest(
    val label: String? = null,
    val inverseLabel: String? = null,
    val type: String? = null,
    val source: String? = null,
    val target: String? = null,
    // null leaves them, a map replaces them, {} clears them (ADR-078)
    val labels: Map<String, String?>? = null,
    val inverseLabels: Map<String, String?>? = null
)

// one relationship, seen from the object you are standing on. direction: which way a relationship
// from an object to itself is walked, null for any other (the object already says which end).
data class RelatedSide(
    val relationship: Relationship,
    val otherObject: CustomObject,
    val label: String,
    val many: Boolean,
    val direction: RelationshipDirection? = null,
    // label's texts per language: the relationship's, its inverse's, or the other object's plural ones (ADR-078)
    val labels: LocalizedText = emptyMap()
) {
    // the end this side stands on is the source. on a self-relationship the object is both ends,
    // so the direction decides.
    val fromSource: Boolean
        get() = direction?.fromSource(relationship.type) ?: (otherObject.id == relationship.targetObjectId)
}

// every change leaves one admin:relationship entry; the column or join table behind it is part of it (ADR-049)
@Service
class RelationshipService(
    private val relationships: RelationshipRepository,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val metadata: MetadataService,
    private val schema: ObjectSchemaManager,
    private val currentUser: CurrentUser,
    private val audit: AdminAudit,
    // who hears of a change; the definitions it drops (ADR-066). defaulted: uncached, as before
    private val changes: MetadataChanges = MetadataChanges.uncached()
) {
    suspend fun list(): List<Relationship> {
        val user = currentUser.requireWithPermission(Actions.READ)
        return relationships.findAll(user.organizationId)
    }

    suspend fun forObject(objectName: String): List<RelatedSide> {
        val user = currentUser.require()
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        currentUser.requirePermission(user, Actions.READ, obj.id)
        // a self-relationship is both ends of this object: one side per direction, forward first
        return relationships.findForObject(user.organizationId, obj.id).flatMap { relationship ->
            if (relationship.selfReferencing) {
                RelationshipDirection.entries.map { side(relationship, obj, it) }
            } else {
                listOf(side(relationship, obj))
            }
        }
    }

    @Transactional
    suspend fun create(request: CreateRelationshipRequest): Relationship {
        val actor = ModelOwner.actor(currentUser)
        val name = SqlIdentifier.requireValidName(request.name.trim().lowercase(), "relationship", "name", MAX_NAME)
        if (relationships.findByName(actor.organizationId, name) != null) {
            throw ConflictException("Relationship '$name' already exists")
        }

        val type = RelationshipType.parse(request.type)
        // checked before any DDL: a bad key must not leave a column behind
        val labels = LocalizedTexts.checked(request.labels, "labels")
        val inverseLabels = LocalizedTexts.checked(request.inverseLabels, "inverseLabels")
        val source = objectOrFail(actor.organizationId, request.source, "source")
        val target = objectOrFail(actor.organizationId, request.target, "target")

        var relationFieldId: UUID? = null
        var joinTable: String? = null

        if (type.usesJoinTable) {
            joinTable = "rel_${name}__${actor.organizationId.toString().replace("-", "").take(8)}"
            if (relationships.tableExists(joinTable)) {
                throw ConflictException("Join table '$joinTable' already exists")
            }
            schema.createJoinTable(joinTable, source, target)
        } else {
            // the FK lives with the "many" side for MANY_TO_ONE, and with the target for ONE_TO_MANY
            val owner = if (type.fkOnSource) source else target
            val referenced = if (type.fkOnSource) target else source
            val fieldName =
                request.fieldName
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                    .ifBlank { referenced.name }
            val field =
                metadata.addRelationField(
                    owner.name,
                    FieldRequest(
                        name = fieldName,
                        label = request.label.trim(),
                        type = FieldType.RELATION.name,
                        unique = type == RelationshipType.ONE_TO_ONE,
                        relationTarget = referenced.name
                    )
                )
            relationFieldId = field.id
        }

        val created =
            relationships.insert(
                Relationship(
                    id = UUID.randomUUID(),
                    organizationId = actor.organizationId,
                    name = name,
                    label = request.label.trim(),
                    inverseLabel = request.inverseLabel?.trim(),
                    type = type,
                    sourceObjectId = source.id,
                    targetObjectId = target.id,
                    relationFieldId = relationFieldId,
                    joinTable = joinTable,
                    labels = labels,
                    inverseLabels = inverseLabels,
                    // the platform ensuring a declared model owns what it makes (ADR-081)
                    declaredBy = actor.model
                )
            )
        actor.record(audit, AdminEntity.RELATIONSHIP, created.id, AdminOperation.CREATE, null, snapshot(created))
        changes.changed(MetadataChange(actor.organizationId, MetadataChangeKind.RELATIONSHIP, AdminOperation.CREATE, source.id, source.name, name))
        return created
    }

    // the labels, and only the labels. moving the key means moving a column between tables, or
    // swapping it for a join table, and nothing carries the stored links across.
    @Transactional
    suspend fun update(
        name: String,
        request: UpdateRelationshipRequest
    ): Relationship {
        val actor = ModelOwner.actor(currentUser)
        val relationship =
            relationships.findByName(actor.organizationId, name)
                ?: throw NotFoundException("Relationship '$name' does not exist")
        actor.requireMayChange(relationship.declaredBy, "Relationship '$name'")

        listOf("type" to request.type, "source" to request.source, "target" to request.target)
            .firstOrNull { it.second != null }
            ?.let { (field, _) ->
                throw ValidationException(
                    "A relationship cannot be reshaped",
                    field,
                    "the column or join table behind it would have to move, and the links stored in it " +
                        "cannot follow; delete the relationship and create it again"
                )
            }

        val label = request.label?.trim()
        if (label != null && label.isBlank()) {
            throw ValidationException("Relationship '$name' has no label", "label", "must not be blank")
        }
        // a blank inverse label is no inverse label: side() then falls back to the other object's plural
        val inverseLabel = request.inverseLabel?.trim()?.ifBlank { null }

        val updated =
            relationships.update(
                relationship.copy(
                    label = label ?: relationship.label,
                    inverseLabel = if (request.inverseLabel != null) inverseLabel else relationship.inverseLabel,
                    labels = request.labels?.let { LocalizedTexts.checked(it, "labels") } ?: relationship.labels,
                    inverseLabels = request.inverseLabels?.let { LocalizedTexts.checked(it, "inverseLabels") } ?: relationship.inverseLabels,
                    // a declared model takes over a relationship a person made, once it matches (ADR-081)
                    declaredBy = actor.model ?: relationship.declaredBy
                )
            )
        actor.record(audit, AdminEntity.RELATIONSHIP, relationship.id, AdminOperation.UPDATE, snapshot(relationship), snapshot(updated))
        changes.changed(relationshipChange(relationship, AdminOperation.UPDATE))
        return updated
    }

    @Transactional
    suspend fun delete(name: String) {
        val actor = ModelOwner.actor(currentUser)
        val relationship =
            relationships.findByName(actor.organizationId, name)
                ?: throw NotFoundException("Relationship '$name' does not exist")
        actor.requireMayChange(relationship.declaredBy, "Relationship '$name'")
        // its join table holds links of both ends, its field sits on one: either way stored values go
        val holders =
            if (relationship.joinTable != null) {
                listOf(relationship.sourceObjectId, relationship.targetObjectId)
            } else {
                listOfNotNull(relationship.relationFieldId?.let { fields.findById(it)?.objectId })
            }
        holders
            .mapNotNull { objects.findById(actor.organizationId, it) }
            .forEach { rejectAppendOnly(it, "relationship '$name'") }

        val column =
            relationship.relationFieldId?.let { fieldId ->
                fields.findById(fieldId)?.let { field -> objects.findById(actor.organizationId, field.objectId)?.let { owner -> owner to field } }
            }
        // the column sits on its object's table: a declared one keeps its fields (ADR-081)
        column?.let { (owner, _) -> actor.requireMayChange(owner.declaredBy, "Object '${owner.name}'") }
        // postgres would drop a composite index or unique with the column while the metadata still lists it (ADR-036, ADR-037)
        column?.let { (owner, field) ->
            FieldSets.blocking(field.name, owner)?.let { set ->
                throw ConflictException(
                    "Field '${field.name}' of relationship '$name' is part of $set of '${owner.name}'. " +
                        "Remove it from the object's ${set.property} first."
                )
            }
        }

        val before = snapshot(relationship)
        val change = relationshipChange(relationship, AdminOperation.DELETE)
        relationship.joinTable?.let { schema.dropJoinTable(it) }
        column?.let { (owner, field) ->
            schema.dropColumn(owner, field)
            fields.delete(field.id)
        }
        relationships.delete(relationship.id)
        actor.record(audit, AdminEntity.RELATIONSHIP, relationship.id, AdminOperation.DELETE, before, null)
        changes.changed(change)
    }

    // the admin trail's view of it (ADR-049): ends and column by name, as the api speaks them
    private suspend fun snapshot(relationship: Relationship): Map<String, Any?> =
        linkedMapOf(
            "name" to relationship.name,
            "label" to relationship.label,
            "inverseLabel" to relationship.inverseLabel,
            "type" to relationship.type.name,
            "source" to objects.findById(relationship.organizationId, relationship.sourceObjectId)?.name,
            "target" to objects.findById(relationship.organizationId, relationship.targetObjectId)?.name,
            "fieldName" to relationship.relationFieldId?.let { fields.findById(it)?.name },
            "joinTable" to relationship.joinTable
        ) + translations("labels" to relationship.labels, "inverseLabels" to relationship.inverseLabels) +
            // only when a declared model owns it, so an entry of a person's reads as before (ADR-081)
            (relationship.declaredBy?.let { mapOf("declaredBy" to it) } ?: emptyMap())

    // a relationship's change, told by its source object (ADR-066)
    private suspend fun relationshipChange(
        relationship: Relationship,
        operation: AdminOperation
    ): MetadataChange =
        MetadataChange(
            relationship.organizationId,
            MetadataChangeKind.RELATIONSHIP,
            operation,
            relationship.sourceObjectId,
            objects.findById(relationship.organizationId, relationship.sourceObjectId)?.name,
            relationship.name
        )

    // one relationship seen from one object. data walks records with it. on a self-relationship
    // inverse walks the end forward does not; any other relationship has no inverse to walk.
    suspend fun side(
        relationship: Relationship,
        obj: CustomObject,
        direction: RelationshipDirection = RelationshipDirection.FORWARD
    ): RelatedSide {
        if (obj.id != relationship.sourceObjectId && obj.id != relationship.targetObjectId) {
            throw ValidationException(
                "Relationship '${relationship.name}' does not involve '${obj.name}'",
                "relationship",
                "wrong object"
            )
        }
        if (direction == RelationshipDirection.INVERSE && !relationship.selfReferencing) {
            throw ValidationException(
                "Relationship '${relationship.name}' joins two objects",
                RelationshipDirection.PARAMETER,
                "inverse only applies to a relationship from an object to itself; read it from the other object"
            )
        }
        val fromSource = if (relationship.selfReferencing) direction.fromSource(relationship.type) else obj.id == relationship.sourceObjectId
        val otherId = if (fromSource) relationship.targetObjectId else relationship.sourceObjectId
        val other =
            objects.findById(relationship.organizationId, otherId)
                ?: throw NotFoundException("Related object no longer exists")
        val many =
            when (relationship.type) {
                RelationshipType.MANY_TO_MANY -> true
                RelationshipType.ONE_TO_ONE -> false
                RelationshipType.MANY_TO_ONE -> !fromSource
                RelationshipType.ONE_TO_MANY -> fromSource
            }
        val label =
            if (fromSource) relationship.label else relationship.inverseLabel ?: other.pluralLabel
        val labels =
            when {
                fromSource -> relationship.labels
                relationship.inverseLabel != null || relationship.inverseLabels.isNotEmpty() -> relationship.inverseLabels
                else -> other.pluralLabels
            }
        return RelatedSide(relationship, other, label, many, direction.takeIf { relationship.selfReferencing }, labels)
    }

    private suspend fun objectOrFail(
        organizationId: UUID,
        name: String,
        field: String
    ): CustomObject =
        objects.findByName(organizationId, name.trim().lowercase())
            ?: throw ValidationException("Unknown object '$name'", field, "object does not exist")

    companion object {
        private const val MAX_NAME = 35
    }
}
