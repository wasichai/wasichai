package wasichai.core.data

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.Rows
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// one physical table per object (ADR-004). every column goes through its type's handler, so a
// module type brings its own select and bind sql and core never learns what it stores.
@Component
class PhysicalTableRecordStore(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val types: FieldTypeRegistry
) : RecordStore {
    override suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState
    ): RecordRow {
        val writable = attributeFields(definition).filter { it.editable }
        val values = writable.associateWith { types.handler(it.type).toDatabase(it, attributes[it.name]) }
        val sectionValues = sectionValues(definition, sections)

        val columns = mutableListOf("organization_id", "created_by", "updated_by")
        val placeholders = mutableListOf(":organizationId", ":userId", ":userId")
        values.keys.forEachIndexed { index, field ->
            columns += SqlIdentifier.quote(field.columnName)
            placeholders += types.handler(field.type).bindExpression(field, "p$index")
        }
        sectionValues.keys.forEachIndexed { index, field ->
            columns += SqlIdentifier.quote(field.columnName)
            placeholders += types.handler(field.type).bindExpression(field, "s$index")
        }
        // a state machine that is enabled means a new record is born in its initial state
        if (workflow.initialState != null) {
            columns += SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
            placeholders += ":recordState"
        }

        var spec =
            db
                .sql(
                    """
                    INSERT INTO ${tableOf(definition)} (${columns.joinToString(", ")})
                    VALUES (${placeholders.joinToString(", ")})
                    RETURNING ${selectList(definition, workflow.attached)}
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .bind("userId", userId)
        spec = bindValues(spec, "p", values)
        spec = bindValues(spec, "s", sectionValues)
        if (workflow.initialState != null) spec = spec.bind("recordState", workflow.initialState)

        return spec.map { row, _ -> mapRow(definition, row, workflow.attached) }.one().awaitSingle()
    }

    override suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean
    ): RecordRow {
        val writable = attributeFields(definition).filter { it.editable }
        val values = writable.associateWith { types.handler(it.type).toDatabase(it, attributes[it.name]) }
        val sectionValues = sectionValues(definition, sections)

        val assignments = mutableListOf("updated_at = now()", "updated_by = :userId")
        values.keys.forEachIndexed { index, field ->
            assignments += "${SqlIdentifier.quote(field.columnName)} = ${types.handler(field.type).bindExpression(field, "p$index")}"
        }
        sectionValues.keys.forEachIndexed { index, field ->
            assignments += "${SqlIdentifier.quote(field.columnName)} = ${types.handler(field.type).bindExpression(field, "s$index")}"
        }

        var spec =
            db
                .sql(
                    """
                    UPDATE ${tableOf(definition)}
                    SET ${assignments.joinToString(", ")}
                    WHERE id = :id AND organization_id = :organizationId
                    RETURNING ${selectList(definition, withState)}
                    """.trimIndent()
                ).bind("id", id)
                .bind("organizationId", organizationId)
                .bind("userId", userId)
        spec = bindValues(spec, "p", values)
        spec = bindValues(spec, "s", sectionValues)

        return spec.map { row, _ -> mapRow(definition, row, withState) }.one().awaitFirstOrNull()
            ?: throw NotFoundException("Record $id does not exist")
    }

    // guarded by the current state in the WHERE, so a racing caller loses instead of overwriting
    override suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow? {
        val column = SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
        val guard = if (from == null) "$column IS NULL" else "$column = :from"
        var spec =
            db
                .sql(
                    """
                    UPDATE ${tableOf(definition)}
                    SET $column = :to, updated_at = now(), updated_by = :userId
                    WHERE id = :id AND organization_id = :organizationId AND $guard
                    RETURNING ${selectList(definition, true)}
                    """.trimIndent()
                ).bind("id", id)
                .bind("organizationId", organizationId)
                .bind("userId", userId)
                .bind("to", to)
        if (from != null) spec = spec.bind("from", from)
        return spec.map { row, _ -> mapRow(definition, row, true) }.one().awaitFirstOrNull()
    }

    override suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM ${tableOf(definition)} WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // a record the caller may not see must look missing, not forbidden: no existence leak
    override suspend fun findById(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID,
        createdBy: UUID?,
        withState: Boolean
    ): RecordRow? {
        val owner = if (createdBy == null) "" else " AND created_by = :createdBy"
        var spec =
            db
                .sql(
                    "SELECT ${selectList(definition, withState)} FROM ${tableOf(definition)} " +
                        "WHERE id = :id AND organization_id = :organizationId$owner"
                ).bind("id", id)
                .bind("organizationId", organizationId)
        if (createdBy != null) spec = spec.bind("createdBy", createdBy)
        return spec
            .map { row, _ -> mapRow(definition, row, withState) }
            .one()
            .awaitFirstOrNull()
    }

    override suspend fun query(
        definition: ObjectDefinition,
        organizationId: UUID,
        query: RecordQuery
    ): PageResponse<RecordRow> {
        // ANY(:ids) on an empty array matches nothing anyway; skip the round trip and say so directly
        query.ids?.let { if (it.isEmpty()) return PageResponse.of(emptyList(), query.page.page, query.page.size, 0) }

        val table = tableOf(definition)
        val (where, bindings) = whereClause(definition, organizationId, query)
        val order = orderBy(definition, query)

        var countSpec = db.sql("SELECT COUNT(*) AS total FROM $table WHERE $where")
        bindings.forEach { (name, value) -> countSpec = countSpec.bind(name, value) }
        val total = countSpec.map { row, _ -> Rows.long(row, "total") }.one().awaitSingle()

        var rowsSpec =
            db.sql(
                "SELECT ${selectList(definition, query.withState)} FROM $table WHERE $where $order " +
                    "LIMIT :limit OFFSET :offset"
            )
        bindings.forEach { (name, value) -> rowsSpec = rowsSpec.bind(name, value) }
        val rows =
            rowsSpec
                .bind("limit", query.page.size)
                .bind("offset", query.page.offset)
                .map { row, _ -> mapRow(definition, row, query.withState) }
                .all()
                .asFlow()
                .toList()

        return PageResponse.of(rows, query.page.page, query.page.size, total)
    }

    // pure, so it is tested directly: the WHERE and its bindings for one query. organization_id
    // always leads, unparenthesized; every module condition is wrapped in parens, so an "x OR y"
    // criterion ANDs as one term instead of loosening the tenancy guard in front of it.
    internal fun whereClause(
        definition: ObjectDefinition,
        organizationId: UUID,
        query: RecordQuery
    ): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("organization_id = :organizationId")
        val bindings = mutableMapOf<String, Any>("organizationId" to organizationId)

        query.filters.entries.forEachIndexed { index, (name, raw) ->
            val field = fieldOrFail(definition, name)
            val value =
                types.handler(field.type).toDatabase(field.copy(required = false), raw)
                    ?: return@forEachIndexed
            conditions += "${SqlIdentifier.quote(field.columnName)} = :f$index"
            bindings["f$index"] = value
        }

        query.search?.takeIf { it.isNotBlank() }?.let { term ->
            val textColumns = definition.fields.filter { types.handler(it.type).textLike }
            if (textColumns.isNotEmpty()) {
                conditions +=
                    "(" +
                    textColumns.joinToString(" OR ") { "${SqlIdentifier.quote(it.columnName)} ILIKE :search" } +
                    ")"
                bindings["search"] = "%$term%"
            }
        }

        query.createdBy?.let { owner ->
            conditions += "created_by = :createdBy"
            bindings["createdBy"] = owner
        }

        // an empty id list already returned from query(); only "some ids" reaches this point
        query.ids?.let { ids ->
            conditions += "id = ANY(:ids)"
            bindings["ids"] = ids.toTypedArray()
        }

        // module conditions, parenthesized (R7): unparenthesized, an "x OR y" would AND in loosely
        // enough to escape organization_id (and createdBy) above it - a tenancy leak.
        query.criteria.forEach { criterion ->
            val condition =
                criterion.condition(definition) { value ->
                    val name = "c${bindings.size}"
                    bindings[name] = value
                    ":$name"
                }
            check(condition.isNotBlank()) {
                "a RecordCriterion for '${definition.obj.name}' returned a blank condition"
            }
            conditions += "($condition)"
        }

        return conditions.joinToString(" AND ") to bindings
    }

    internal fun selectList(
        definition: ObjectDefinition,
        withState: Boolean
    ): String {
        val columns = mutableListOf("id", "created_at", "updated_at")
        definition.fields.forEach { field ->
            columns += types.handler(field.type).select(field, SqlIdentifier.quote(field.columnName))
        }
        if (withState) columns += SqlIdentifier.quote(ObjectSchemaManager.STATE_COLUMN)
        return columns.joinToString(", ")
    }

    private fun tableOf(definition: ObjectDefinition) = schemas.dataTable(definition.obj.physicalTable)

    private fun sectionOf(field: CustomField): String? = types.handler(field.type).section

    // fields whose value travels in "attributes"
    private fun attributeFields(definition: ObjectDefinition) = definition.fields.filter { sectionOf(it) == null }

    // only the section entries the caller sent. one left out is left alone; one sent as null is cleared.
    internal fun sectionValues(
        definition: ObjectDefinition,
        sections: Map<String, Map<String, Any?>>
    ): Map<CustomField, Any?> {
        val values = linkedMapOf<CustomField, Any?>()
        sections.forEach { (section, entries) ->
            if (section !in types.sections) return@forEach
            entries.forEach { (name, value) ->
                val field =
                    definition.fields.firstOrNull { it.name == name && sectionOf(it) == section }
                        ?: throw types.sectionOwner(section).unknownSectionKey(name, definition)
                values[field] = value?.let { types.handler(field.type).toDatabase(field, it) }
            }
        }
        return values
    }

    internal fun orderBy(
        definition: ObjectDefinition,
        query: RecordQuery
    ): String {
        val direction = if (query.descending) "DESC" else "ASC"
        val requested = query.sort?.trim()?.lowercase()
        val column =
            when {
                requested.isNullOrBlank() -> "created_at"
                requested in setOf("id", "created_at", "updated_at") -> requested
                else -> SqlIdentifier.quote(fieldOrFail(definition, requested).columnName)
            }
        // id last: rows of one transaction tie on created_at, and OFFSET over a tie can repeat or skip rows
        return if (column == "id") "ORDER BY id $direction" else "ORDER BY $column $direction, id $direction"
    }

    private fun fieldOrFail(
        definition: ObjectDefinition,
        name: String
    ): CustomField {
        val field =
            definition.fields.firstOrNull { it.name == name }
                ?: throw ValidationException("Unknown field '$name'", name, "is not a field of '${definition.obj.name}'")
        types.handler(field.type).rejectFilterOrSort(field)?.let { throw it }
        return field
    }

    internal fun bindValues(
        spec: DatabaseClient.GenericExecuteSpec,
        prefix: String,
        values: Map<CustomField, Any?>
    ): DatabaseClient.GenericExecuteSpec {
        var current = spec
        values.entries.forEachIndexed { index, (field, value) ->
            val name = "$prefix$index"
            current = if (value == null) current.bindNull(name, types.handler(field.type).javaType(field)) else current.bind(name, value)
        }
        return current
    }

    private fun read(
        field: CustomField,
        row: Row
    ): Any? {
        val handler = types.handler(field.type)
        return handler.fromDatabase(field, row.get(handler.readName(field)))
    }

    private fun mapRow(
        definition: ObjectDefinition,
        row: Row,
        withState: Boolean
    ): RecordRow =
        RecordRow(
            id = Rows.uuid(row, "id"),
            createdAt = Rows.instantOrNull(row, "created_at"),
            updatedAt = Rows.instantOrNull(row, "updated_at"),
            attributes = attributeFields(definition).associate { it.name to read(it, row) },
            // every field of every installed section is listed, null included: the caller should not
            // have to guess whether a missing key means "empty" or "not a field of this object"
            sections =
                types.sections.associateWith { section ->
                    definition.fields.filter { sectionOf(it) == section }.associate { it.name to read(it, row) }
                },
            state = if (withState) Rows.stringOrNull(row, ObjectSchemaManager.STATE_COLUMN) else null
        )
}
