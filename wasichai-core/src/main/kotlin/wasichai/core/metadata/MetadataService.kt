package wasichai.core.metadata

import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.SystemColumns
import java.util.UUID

@Service
class MetadataService(
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val relationships: RelationshipRepository,
    private val schema: ObjectSchemaManager,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val types: FieldTypeRegistry,
    private val systemColumns: SystemColumns,
    private val usages: List<FieldUsage>,
    private val removals: List<ObjectRemovalListener>
) {
    // objects you may read, not "every object if you may read something"
    suspend fun listObjects(): List<CustomObject> {
        val user = currentUser.require()
        val all = objects.findAll(user.organizationId)
        if (user.isAdmin) return all
        val permitted = currentUser.permittedObjects(user, Actions.READ)
        return all.filter { permitted.allows(it.id) }
    }

    // the same list with its fields attached, in one extra query. installed field types answer from the fields.
    suspend fun listDefinitions(): List<ObjectDefinition> {
        val listed = listObjects()
        val byObject = fields.findByObjects(listed.map { it.id })
        return listed.map { ObjectDefinition(it, byObject[it.id].orEmpty()) }
    }

    // what the caller is allowed to see. fields they cannot read are not listed at all.
    suspend fun definitionOf(name: String): ObjectDefinition {
        val user = currentUser.require()
        val obj =
            objects.findByName(user.organizationId, name)
                ?: throw NotFoundException("Object '$name' does not exist")
        currentUser.requirePermission(user, Actions.READ, obj.id)
        val definition = ObjectDefinition(obj, fields.findByObject(obj.id))
        return definition.readableBy(access.fieldAccess(user, obj.id))
    }

    // no permission check: callers that already checked a record-level action use this
    suspend fun loadDefinition(
        organizationId: UUID,
        name: String
    ): ObjectDefinition {
        val obj =
            objects.findByName(organizationId, name)
                ?: throw NotFoundException("Object '$name' does not exist")
        return ObjectDefinition(obj, fields.findByObject(obj.id))
    }

    // same, for callers that only kept the id
    suspend fun loadDefinitionById(
        organizationId: UUID,
        id: UUID
    ): ObjectDefinition {
        val obj =
            objects.findById(organizationId, id)
                ?: throw NotFoundException("Object does not exist")
        return ObjectDefinition(obj, fields.findByObject(obj.id))
    }

    @Transactional
    suspend fun createObject(request: CreateObjectRequest): ObjectDefinition {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val name = SqlIdentifier.requireValidObjectName(request.name.trim().lowercase())

        if (objects.findByName(user.organizationId, name) != null) {
            throw ConflictException("Object '$name' already exists")
        }

        val obj =
            CustomObject(
                id = UUID.randomUUID(),
                organizationId = user.organizationId,
                name = name,
                label = request.label.trim(),
                pluralLabel =
                    request.pluralLabel
                        ?.trim()
                        .orEmpty()
                        .ifBlank { request.label.trim() },
                description = request.description?.trim(),
                enabled = true,
                physicalTable = physicalTableName(name, user.organizationId),
                createdAt = null,
                updatedAt = null,
                appendOnly = request.appendOnly,
                apiOnly = request.apiOnly
            )

        var stored = objects.insert(obj)
        val storedFields =
            request.fields.mapIndexed { index, field ->
                fields.insert(buildField(stored.id, field, index, user.organizationId))
            }
        // the sets name fields, so they are checked once the fields exist (a relation may point at this object)
        val indexes = FieldSets.normalize(INDEXES, request.indexes, storedFields, types)
        val uniques = FieldSets.normalize(UNIQUE_CONSTRAINTS, request.uniqueConstraints, storedFields, types, minFields = 2)
        if (indexes.isNotEmpty() || uniques.isNotEmpty()) stored = objects.update(stored.copy(indexes = indexes, uniqueConstraints = uniques))
        schema.createTable(stored, storedFields, relationTables(storedFields, user.organizationId))
        return ObjectDefinition(stored, storedFields)
    }

    @Transactional
    suspend fun addField(
        objectName: String,
        request: FieldRequest
    ): CustomField {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        if (fields.findByObject(obj.id).any { it.name == request.name.trim().lowercase() }) {
            throw ConflictException("Field '${request.name}' already exists on '$objectName'")
        }
        val position = fields.maxPosition(obj.id) + 1
        val stored = fields.insert(buildField(obj.id, request, position, user.organizationId))
        schema.addColumn(obj, stored, relationTables(listOf(stored), user.organizationId))
        return stored
    }

    @Transactional
    suspend fun updateField(
        objectName: String,
        fieldName: String,
        request: UpdateFieldRequest
    ): CustomField {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        val existing =
            fields.findByName(obj.id, fieldName)
                ?: throw NotFoundException("Field '$fieldName' does not exist on '$objectName'")

        // layouts and rules store field names, so a rename would break them in silence
        if (request.name != null && request.name.trim().lowercase() != existing.name) {
            throw ValidationException(
                "Fields cannot be renamed",
                "name",
                "layouts and rules refer to the field by name; add a new field instead"
            )
        }
        if (request.type != null && request.type.trim().uppercase() != existing.type.name) {
            throw ValidationException(
                "Field types cannot change",
                "type",
                "converting a column may lose data; add a new field instead"
            )
        }

        // the type's own rules (a module type may refuse unique)
        types.handler(existing.type).checkUpdate(existing, request)
        if (request.indexed == true) FieldSets.requireIndexable(existing, "indexed", types)

        val enumOptions =
            request.enumOptions?.map { it.trim() }?.filter { it.isNotEmpty() }?.also { options ->
                if (existing.type != FieldType.ENUM) {
                    throw ValidationException("Not an enum field", "enumOptions", "only ENUM fields have options")
                }
                if (options.isEmpty()) {
                    throw ValidationException("Enum needs options", "enumOptions", "must not be empty")
                }
                options.forEach {
                    if (!ENUM_OPTION.matches(it)) {
                        throw ValidationException("Invalid option '$it'", "enumOptions", "may contain letters, digits, space, _ . -")
                    }
                }
            } ?: existing.enumOptions

        val updated =
            existing.copy(
                label = request.label?.trim()?.ifBlank { existing.label } ?: existing.label,
                required = request.required ?: existing.required,
                unique = request.unique ?: existing.unique,
                description = request.description?.trim() ?: existing.description,
                position = request.position ?: existing.position,
                enumOptions = enumOptions,
                visible = request.visible ?: existing.visible,
                editable = request.editable ?: existing.editable,
                indexed = request.indexed ?: existing.indexed
            )

        // metadata and table move together, in one transaction
        if (updated.required != existing.required) schema.setRequired(obj, existing, updated.required)
        if (updated.unique != existing.unique) {
            refuseRepeats("unique", "Field '$fieldName' has repeated values; it cannot be made unique") {
                schema.setUnique(obj, existing, updated.unique)
            }
        }
        // unique counts too: a unique column's constraint is its index, so a plain one comes or goes with it
        if (updated.indexed != existing.indexed || updated.unique != existing.unique) {
            val all = fields.findByObject(obj.id)
            schema.syncIndexes(ObjectDefinition(obj, all), ObjectDefinition(obj, all.map { if (it.id == existing.id) updated else it }))
        }
        if (updated.enumOptions != existing.enumOptions && existing.type == FieldType.ENUM) {
            schema.replaceEnumCheck(obj, existing, updated.enumOptions.orEmpty())
        }
        return fields.update(updated)
    }

    @Transactional
    suspend fun deleteField(
        objectName: String,
        fieldName: String
    ) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj =
            objects.findByName(user.organizationId, objectName)
                ?: throw NotFoundException("Object '$objectName' does not exist")
        val field =
            fields.findByName(obj.id, fieldName)
                ?: throw NotFoundException("Field '$fieldName' does not exist on '$objectName'")
        rejectAppendOnly(obj, "its field '$fieldName'")

        // a relationship owns its field. dropping it here would leave the relationship half deleted.
        if (field.type == FieldType.RELATION) {
            val owner = relationships.findForObject(user.organizationId, obj.id).firstOrNull { it.relationFieldId == field.id }
            if (owner != null) {
                throw ConflictException(
                    "Field '$fieldName' belongs to relationship '${owner.name}'. Delete the relationship instead."
                )
            }
        }

        // postgres would drop a composite index or unique with the column, and the metadata would still list it
        FieldSets.blocking(field.name, obj)?.let { set ->
            throw ConflictException("Field '$fieldName' is part of $set. Remove it from the object's ${set.property} first.")
        }

        val users = usages.flatMap { it.whoUses(obj, field.name) }
        if (users.isNotEmpty()) {
            throw ConflictException("Field '$fieldName' is used by ${users.joinToString(", ")}. Change or delete them first.")
        }

        schema.dropColumn(obj, field)
        fields.delete(field.id)
    }

    @Transactional
    suspend fun updateObject(
        name: String,
        request: UpdateObjectRequest
    ): ObjectDefinition {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj =
            objects.findByName(user.organizationId, name)
                ?: throw NotFoundException("Object '$name' does not exist")
        // the name is the API path and the physical table. accepting it silently would be a lie.
        if (request.name != null && request.name.trim().lowercase() != obj.name) {
            throw ValidationException(
                "Objects cannot be renamed",
                "name",
                "the name backs the table and the API path; create a new object instead"
            )
        }
        val objectFields = fields.findByObject(obj.id)
        val indexes = request.indexes?.let { FieldSets.normalize(INDEXES, it, objectFields, types) } ?: obj.indexes
        val uniques =
            request.uniqueConstraints?.let { FieldSets.normalize(UNIQUE_CONSTRAINTS, it, objectFields, types, minFields = 2) }
                ?: obj.uniqueConstraints
        val updated =
            objects.update(
                obj.copy(
                    label = request.label.trim(),
                    pluralLabel =
                        request.pluralLabel
                            ?.trim()
                            .orEmpty()
                            .ifBlank { request.label.trim() },
                    description = request.description?.trim(),
                    enabled = request.enabled,
                    indexes = indexes,
                    uniqueConstraints = uniques,
                    appendOnly = request.appendOnly ?: obj.appendOnly,
                    apiOnly = request.apiOnly ?: obj.apiOnly
                )
            )
        // the same sets again change nothing: applying a model twice is a no-op
        if (indexes != obj.indexes) schema.syncIndexes(ObjectDefinition(obj, objectFields), ObjectDefinition(updated, objectFields))
        if (uniques != obj.uniqueConstraints) {
            refuseRepeats(UNIQUE_CONSTRAINTS, "Records of '$name' repeat a unique constraint's values; it cannot be added") {
                schema.syncUniqueConstraints(ObjectDefinition(obj, objectFields), ObjectDefinition(updated, objectFields))
            }
        }
        return ObjectDefinition(updated, objectFields)
    }

    @Transactional
    suspend fun deleteObject(name: String) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val obj =
            objects.findByName(user.organizationId, name)
                ?: throw NotFoundException("Object '$name' does not exist")
        rejectAppendOnly(obj, "it")

        // another object's RELATION column points here. dropping the table would strip its
        // foreign key without telling anyone, so refuse and name what is in the way.
        val pointing = fields.findByRelationTarget(obj.id).filter { it.objectId != obj.id }
        if (pointing.isNotEmpty()) {
            val owners =
                pointing.mapNotNull { field ->
                    objects.findById(user.organizationId, field.objectId)?.let { "${it.name}.${field.name}" }
                }
            throw ConflictException("Object '$name' is referenced by ${owners.joinToString(", ")}. Remove those fields first.")
        }

        // metadata rows cascade, join tables do not: drop them here or they outlive the object
        relationships.findForObject(user.organizationId, obj.id).forEach { relationship ->
            relationship.joinTable?.let { schema.dropJoinTable(it) }
        }
        removals.forEach { it.objectRemoved(obj) }
        schema.dropTable(obj)
        objects.delete(user.organizationId, obj.id)
    }

    // a unique the data already breaks: the caller's 409, not a server error. thrown inside the
    // transaction, so the metadata written before it rolls back with the DDL (ADR-037)
    private suspend fun refuseRepeats(
        property: String,
        message: String,
        ddl: suspend () -> Unit
    ) {
        try {
            ddl()
        } catch (e: DuplicateKeyException) {
            throw ConflictException(message, listOf(FieldViolation(property, "existing records repeat these values")))
        }
    }

    // relation targets are resolved to physical tables so DDL can add the FK
    private suspend fun relationTables(
        storedFields: List<CustomField>,
        organizationId: UUID
    ): Map<UUID, String> =
        storedFields
            .mapNotNull { it.relationTargetObjectId }
            .distinct()
            .mapNotNull { targetId -> objects.findById(organizationId, targetId)?.let { targetId to it.physicalTable } }
            .toMap()

    private suspend fun buildField(
        objectId: UUID,
        request: FieldRequest,
        position: Int,
        organizationId: UUID
    ): CustomField {
        val name = systemColumns.requireValidFieldName(request.name.trim().lowercase())
        val type = types.parse(request.type)
        // the type's own rules and stored attributes, checked before anything generic, as before
        val attributes = types.handler(type).attributesOf(name, request)
        val enumOptions = request.enumOptions?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (type == FieldType.ENUM) {
            if (enumOptions.isNullOrEmpty()) {
                throw ValidationException("Enum field '$name' has no options", "enumOptions", "must not be empty")
            }
            enumOptions.forEach {
                if (!ENUM_OPTION.matches(it)) {
                    throw ValidationException("Invalid option '$it'", "enumOptions", "may contain letters, digits, space, _ . -")
                }
            }
        }
        val relationTarget =
            request.relationTarget?.let { targetName ->
                objects.findByName(organizationId, targetName.trim().lowercase())?.id
                    ?: throw ValidationException(
                        "Unknown relation target '$targetName'",
                        "relationTarget",
                        "object does not exist"
                    )
            }
        if (type == FieldType.RELATION && relationTarget == null) {
            throw ValidationException("Relation field '$name' has no target", "relationTarget", "is required")
        }

        val field =
            CustomField(
                id = UUID.randomUUID(),
                objectId = objectId,
                name = name,
                label =
                    request.label
                        ?.trim()
                        .orEmpty()
                        .ifBlank { name },
                type = type,
                columnName = name,
                required = request.required,
                unique = request.unique,
                defaultValue = request.defaultValue,
                description = request.description?.trim(),
                position = position,
                enumOptions = enumOptions,
                relationTargetObjectId = relationTarget,
                attributes = attributes,
                visible = request.visible,
                editable = request.editable,
                indexed = request.indexed
            )
        if (field.indexed) FieldSets.requireIndexable(field, "indexed", types)
        return field
    }

    // "<name>__<first 8 of org uuid>": readable, unique per tenant, fits an identifier
    private fun physicalTableName(
        name: String,
        organizationId: UUID
    ): String = "${name}__${organizationId.toString().replace("-", "").take(8)}"

    companion object {
        private const val INDEXES = "indexes"
        private const val UNIQUE_CONSTRAINTS = "uniqueConstraints"
        private val ENUM_OPTION = Regex("^[\\p{L}0-9 _.-]{1,64}$")
    }
}

// dropping a table or a column deletes stored values: an appendOnly object keeps them until someone
// switches the rule off first, a deliberate and visible step (ADR-040)
internal fun rejectAppendOnly(
    obj: CustomObject,
    what: String
) {
    if (obj.appendOnly) {
        throw ConflictException("Object '${obj.name}' is append-only: switch appendOnly off before deleting $what")
    }
}
