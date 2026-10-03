package wasichai.core.metadata

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

private const val OBJECT_COLUMNS =
    "id, organization_id, name, label, plural_label, description, enabled, " +
        "physical_table, created_at, updated_at, indexes::text AS indexes, unique_constraints::text AS unique_constraints"

@Repository
class CustomObjectRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val objectMapper: ObjectMapper
) {
    suspend fun insert(obj: CustomObject): CustomObject =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.custom_objects
                    (id, organization_id, name, label, plural_label, description, enabled, physical_table, indexes,
                     unique_constraints)
                VALUES (:id, :organizationId, :name, :label, :pluralLabel, :description, :enabled, :physicalTable,
                        CAST(:indexes AS jsonb), CAST(:uniqueConstraints AS jsonb))
                RETURNING $OBJECT_COLUMNS
                """.trimIndent()
            ).bind("id", obj.id)
            .bind("indexes", objectMapper.writeValueAsString(obj.indexes))
            .bind("uniqueConstraints", objectMapper.writeValueAsString(obj.uniqueConstraints))
            .bind("organizationId", obj.organizationId)
            .bind("name", obj.name)
            .bind("label", obj.label)
            .bind("pluralLabel", obj.pluralLabel)
            .bindNullable("description", obj.description)
            .bind("enabled", obj.enabled)
            .bind("physicalTable", obj.physicalTable)
            .map(::mapObject)
            .one()
            .awaitSingle()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): CustomObject? =
        db
            .sql("SELECT $OBJECT_COLUMNS FROM ${schemas.metadata}.custom_objects WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map(::mapObject)
            .one()
            .awaitFirstOrNull()

    suspend fun findById(
        organizationId: UUID,
        id: UUID
    ): CustomObject? =
        db
            .sql("SELECT $OBJECT_COLUMNS FROM ${schemas.metadata}.custom_objects WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map(::mapObject)
            .one()
            .awaitFirstOrNull()

    suspend fun findAll(organizationId: UUID): List<CustomObject> =
        db
            .sql("SELECT $OBJECT_COLUMNS FROM ${schemas.metadata}.custom_objects WHERE organization_id = :org ORDER BY label")
            .bind("org", organizationId)
            .map(::mapObject)
            .all()
            .asFlow()
            .toList()

    // every organization's objects, for the platform's own startup work (index reconciliation). no
    // caller, no tenant: never reachable from a request.
    suspend fun findAllOrganizations(): List<CustomObject> =
        db
            .sql("SELECT $OBJECT_COLUMNS FROM ${schemas.metadata}.custom_objects ORDER BY organization_id, name")
            .map(::mapObject)
            .all()
            .asFlow()
            .toList()

    suspend fun update(obj: CustomObject): CustomObject =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.custom_objects
                SET label = :label, plural_label = :pluralLabel, description = :description,
                    enabled = :enabled, indexes = CAST(:indexes AS jsonb),
                    unique_constraints = CAST(:uniqueConstraints AS jsonb), updated_at = now()
                WHERE id = :id AND organization_id = :organizationId
                RETURNING $OBJECT_COLUMNS
                """.trimIndent()
            ).bind("id", obj.id)
            .bind("indexes", objectMapper.writeValueAsString(obj.indexes))
            .bind("uniqueConstraints", objectMapper.writeValueAsString(obj.uniqueConstraints))
            .bind("organizationId", obj.organizationId)
            .bind("label", obj.label)
            .bind("pluralLabel", obj.pluralLabel)
            .bindNullable("description", obj.description)
            .bind("enabled", obj.enabled)
            .map(::mapObject)
            .one()
            .awaitSingle()

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ) {
        db
            .sql("DELETE FROM ${schemas.metadata}.custom_objects WHERE id = :id AND organization_id = :org")
            .bind("id", id)
            .bind("org", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun mapObject(
        row: Row,
        metadata: RowMetadata
    ): CustomObject =
        CustomObject(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            pluralLabel = Rows.string(row, "plural_label"),
            description = Rows.stringOrNull(row, "description"),
            enabled = Rows.bool(row, "enabled"),
            physicalTable = Rows.string(row, "physical_table"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            updatedAt = Rows.instantOrNull(row, "updated_at"),
            indexes = fieldSets(row, "indexes"),
            uniqueConstraints = fieldSets(row, "unique_constraints")
        )

    private fun fieldSets(
        row: Row,
        column: String
    ): List<List<String>> = Rows.stringOrNull(row, column)?.let { objectMapper.readValue(it, object : TypeReference<List<List<String>>>() {}) } ?: emptyList()
}

private const val FIELD_COLUMNS =
    "id, object_id, name, label, type, column_name, required, is_unique, default_value, " +
        "description, position, enum_options::text AS enum_options, relation_target_object_id, visible, editable, indexed"

@Repository
class CustomFieldRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas,
    private val types: FieldTypeRegistry
) {
    // core columns, then the ones installed field types own (R3). read into CustomField.attributes.
    private val attributeColumns: List<String> = types.attributeColumns.keys.toList()
    private val selectColumns: String = FIELD_COLUMNS + attributeColumns.joinToString("") { ", " + SqlIdentifier.quote(it) }
    private val table: String = "${schemas.metadata}.custom_fields"

    suspend fun insert(field: CustomField): CustomField {
        val extraColumns = attributeColumns.joinToString("") { ", " + SqlIdentifier.quote(it) }
        val extraValues = attributeColumns.indices.joinToString("") { ", :a$it" }
        var spec =
            db
                .sql(
                    """
                    INSERT INTO $table
                        (id, object_id, name, label, type, column_name, required, is_unique, default_value,
                         description, position, enum_options, relation_target_object_id, visible, editable, indexed$extraColumns)
                    VALUES (:id, :objectId, :name, :label, :type, :columnName, :required, :unique, :defaultValue,
                            :description, :position, CAST(:enumOptions AS jsonb), :relationTarget, :visible, :editable, :indexed$extraValues)
                    RETURNING $selectColumns
                    """.trimIndent()
                ).bind("id", field.id)
                .bind("objectId", field.objectId)
                .bind("name", field.name)
                .bind("label", field.label)
                .bind("type", field.type.name)
                .bind("columnName", field.columnName)
                .bind("required", field.required)
                .bind("unique", field.unique)
                .bindNullable("defaultValue", field.defaultValue)
                .bindNullable("description", field.description)
                .bind("position", field.position)
                .bindNullable("enumOptions", field.enumOptions?.let { objectMapper.writeValueAsString(it) })
                .bindNullable("relationTarget", field.relationTargetObjectId)
                .bind("visible", field.visible)
                .bind("editable", field.editable)
                .bind("indexed", field.indexed)
        attributeColumns.forEachIndexed { index, column ->
            val value = field.attributes[column]
            spec = if (value == null) spec.bindNull("a$index", types.attributeColumns.getValue(column)) else spec.bind("a$index", value)
        }
        return spec.map(::mapField).one().awaitSingle()
    }

    suspend fun findByObject(objectId: UUID): List<CustomField> =
        db
            .sql("SELECT $selectColumns FROM $table WHERE object_id = :objectId ORDER BY position, name")
            .bind("objectId", objectId)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()

    // one query for a whole listing
    suspend fun findByObjects(objectIds: List<UUID>): Map<UUID, List<CustomField>> {
        if (objectIds.isEmpty()) return emptyMap()
        return db
            .sql("SELECT $selectColumns FROM $table WHERE object_id IN (:objectIds) ORDER BY position, name")
            .bind("objectIds", objectIds)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()
            .groupBy { it.objectId }
    }

    suspend fun findByName(
        objectId: UUID,
        name: String
    ): CustomField? =
        db
            .sql("SELECT $selectColumns FROM $table WHERE object_id = :objectId AND name = :name")
            .bind("objectId", objectId)
            .bind("name", name)
            .map(::mapField)
            .one()
            .awaitFirstOrNull()

    suspend fun findById(id: UUID): CustomField? =
        db
            .sql("SELECT $selectColumns FROM $table WHERE id = :id")
            .bind("id", id)
            .map(::mapField)
            .one()
            .awaitFirstOrNull()

    // attributes are fixed at creation, like the type: an update never touches them
    suspend fun update(field: CustomField): CustomField =
        db
            .sql(
                """
                UPDATE $table
                SET label = :label, required = :required, is_unique = :unique, description = :description,
                    position = :position, enum_options = CAST(:enumOptions AS jsonb), visible = :visible,
                    editable = :editable, indexed = :indexed, updated_at = now()
                WHERE id = :id
                RETURNING $selectColumns
                """.trimIndent()
            ).bind("id", field.id)
            .bind("label", field.label)
            .bind("required", field.required)
            .bind("unique", field.unique)
            .bindNullable("description", field.description)
            .bind("position", field.position)
            .bindNullable("enumOptions", field.enumOptions?.let { objectMapper.writeValueAsString(it) })
            .bind("visible", field.visible)
            .bind("editable", field.editable)
            .bind("indexed", field.indexed)
            .map(::mapField)
            .one()
            .awaitSingle()

    suspend fun delete(id: UUID) {
        db
            .sql("DELETE FROM $table WHERE id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // who points at this object. deleting it would break their FK, so we refuse instead.
    suspend fun findByRelationTarget(targetObjectId: UUID): List<CustomField> =
        db
            .sql("SELECT $selectColumns FROM $table WHERE relation_target_object_id = :targetId")
            .bind("targetId", targetObjectId)
            .map(::mapField)
            .all()
            .asFlow()
            .toList()

    suspend fun maxPosition(objectId: UUID): Int =
        db
            .sql("SELECT COALESCE(MAX(position), -1) AS max_position FROM $table WHERE object_id = :objectId")
            .bind("objectId", objectId)
            .map { row, _ -> Rows.int(row, "max_position") }
            .one()
            .awaitSingle()

    private fun mapField(
        row: Row,
        metadata: RowMetadata
    ): CustomField =
        CustomField(
            id = Rows.uuid(row, "id"),
            objectId = Rows.uuid(row, "object_id"),
            name = Rows.string(row, "name"),
            label = Rows.string(row, "label"),
            // not parsed: a stored type whose module is gone must still list (FieldTypeRegistry.handler says 409)
            type = FieldType(Rows.string(row, "type")),
            columnName = Rows.string(row, "column_name"),
            required = Rows.bool(row, "required"),
            unique = Rows.bool(row, "is_unique"),
            defaultValue = Rows.stringOrNull(row, "default_value"),
            description = Rows.stringOrNull(row, "description"),
            position = Rows.int(row, "position"),
            enumOptions =
                Rows.stringOrNull(row, "enum_options")?.let {
                    objectMapper.readValue(it, object : TypeReference<List<String>>() {})
                },
            relationTargetObjectId = Rows.uuidOrNull(row, "relation_target_object_id"),
            attributes = attributeColumns.associateWith { row.get(it) },
            visible = Rows.bool(row, "visible"),
            editable = Rows.bool(row, "editable"),
            indexed = Rows.bool(row, "indexed")
        )
}

// r2dbc rejects bind(null); nullable binds must declare the type.
internal inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, T::class.java) else bind(name, value)
