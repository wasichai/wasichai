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
    val fieldName: String? = null
)

// null means "leave as it is". type, source and target are accepted only to be refused: see update.
data class UpdateRelationshipRequest(
    val label: String? = null,
    val inverseLabel: String? = null,
    val type: String? = null,
    val source: String? = null,
    val target: String? = null
)

// one relationship, seen from the object you are standing on
data class RelatedSide(
    val relationship: Relationship,
    val otherObject: CustomObject,
    val label: String,
    val many: Boolean
)

// every change leaves one admin:relationship entry; the column or join table behind it is part of it (ADR-049)
@Service
class RelationshipService(
    private val relationships: RelationshipRepository,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val metadata: MetadataService,
    private val schema: ObjectSchemaManager,
    private val currentUser: CurrentUser,
    private val audit: AdminAudit
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
        return relationships.findForObject(user.organizationId, obj.id).map { side(it, obj) }
    }

    @Transactional
    suspend fun create(request: CreateRelationshipRequest): Relationship {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val name = SqlIdentifier.requireValidName(request.name.trim().lowercase(), "relationship", "name", MAX_NAME)
        if (relationships.findByName(user.organizationId, name) != null) {
            throw ConflictException("Relationship '$name' already exists")
        }

        val type = RelationshipType.parse(request.type)
        val source = objectOrFail(user.organizationId, request.source, "source")
        val target = objectOrFail(user.organizationId, request.target, "target")

        var relationFieldId: UUID? = null
        var joinTable: String? = null

        if (type.usesJoinTable) {
            joinTable = "rel_${name}__${user.organizationId.toString().replace("-", "").take(8)}"
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
                    organizationId = user.organizationId,
                    name = name,
                    label = request.label.trim(),
                    inverseLabel = request.inverseLabel?.trim(),
                    type = type,
                    sourceObjectId = source.id,
                    targetObjectId = target.id,
                    relationFieldId = relationFieldId,
                    joinTable = joinTable
                )
            )
        audit.record(user, AdminEntity.RELATIONSHIP, created.id, AdminOperation.CREATE, null, snapshot(created))
        return created
    }

    // the labels, and only the labels. moving the key means moving a column between tables, or
    // swapping it for a join table, and nothing carries the stored links across.
    @Transactional
    suspend fun update(
        name: String,
        request: UpdateRelationshipRequest
    ): Relationship {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val relationship =
            relationships.findByName(user.organizationId, name)
                ?: throw NotFoundException("Relationship '$name' does not exist")

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
                    inverseLabel = if (request.inverseLabel != null) inverseLabel else relationship.inverseLabel
                )
            )
        audit.record(user, AdminEntity.RELATIONSHIP, relationship.id, AdminOperation.UPDATE, snapshot(relationship), snapshot(updated))
        return updated
    }

    @Transactional
    suspend fun delete(name: String) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val relationship =
            relationships.findByName(user.organizationId, name)
                ?: throw NotFoundException("Relationship '$name' does not exist")
        // its join table holds links of both ends, its field sits on one: either way stored values go
        val holders =
            if (relationship.joinTable != null) {
                listOf(relationship.sourceObjectId, relationship.targetObjectId)
            } else {
                listOfNotNull(relationship.relationFieldId?.let { fields.findById(it)?.objectId })
            }
        holders
            .mapNotNull { objects.findById(user.organizationId, it) }
            .forEach { rejectAppendOnly(it, "relationship '$name'") }

        val column =
            relationship.relationFieldId?.let { fieldId ->
                fields.findById(fieldId)?.let { field -> objects.findById(user.organizationId, field.objectId)?.let { owner -> owner to field } }
            }
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
        relationship.joinTable?.let { schema.dropJoinTable(it) }
        column?.let { (owner, field) ->
            schema.dropColumn(owner, field)
            fields.delete(field.id)
        }
        relationships.delete(relationship.id)
        audit.record(user, AdminEntity.RELATIONSHIP, relationship.id, AdminOperation.DELETE, before, null)
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
        )

    // one relationship seen from one object. data walks records with it.
    suspend fun side(
        relationship: Relationship,
        obj: CustomObject
    ): RelatedSide {
        val fromSource = obj.id == relationship.sourceObjectId
        if (!fromSource && obj.id != relationship.targetObjectId) {
            throw ValidationException(
                "Relationship '${relationship.name}' does not involve '${obj.name}'",
                "relationship",
                "wrong object"
            )
        }
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
        return RelatedSide(relationship, other, label, many)
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
