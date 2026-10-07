package wasichai.core.data

import io.r2dbc.spi.R2dbcException
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
import wasichai.core.platform.bindNullable
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
        userId: UUID?,
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
                .bindNullable("userId", userId)
        spec = bindValues(spec, "p", values)
        spec = bindValues(spec, "s", sectionValues)
        if (workflow.initialState != null) spec = spec.bind("recordState", workflow.initialState)

        return spec.map { row, _ -> mapRow(definition, row, workflow.attached) }.one().awaitSingle()
    }

    override suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID?,
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
                .bindNullable("userId", userId)
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
        withState: Boolean,
        criteria: List<RecordCriterion>
    ): RecordRow? {
        val (where, bindings) = byIdClause(definition, organizationId, id, createdBy, criteria)
        var spec = db.sql("SELECT ${selectList(definition, withState)} FROM ${tableOf(definition)} WHERE $where")
        bindings.forEach { (name, value) -> spec = spec.bind(name, value) }
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
        val cursor =
            query.after?.let { raw ->
                if (query.page.page > 0) {
                    throw ValidationException("after cannot be combined with page", "after", "a keyset read starts at its cursor; leave page out")
                }
                RecordCursor.decode(raw)
            }
        // ANY(:ids) on an empty array matches nothing anyway; skip the round trip and say so directly
        query.ids?.let {
            if (it.isEmpty()) return PageResponse.of(emptyList(), query.page.page, query.page.size, if (query.count) 0 else null)
        }

        val table = tableOf(definition)
        val (where, bindings) = whereClause(definition, organizationId, query)
        val key = sortKey(definition, query)
        val order = orderBy(definition, query)

        // the total counts every match, not what is left after the cursor
        val total =
            if (query.count) {
                var countSpec = db.sql("SELECT COUNT(*) AS total FROM $table WHERE $where")
                bindings.forEach { (name, value) -> countSpec = countSpec.bind(name, value) }
                countSpec.map { row, _ -> Rows.long(row, "total") }.one().awaitSingle()
            } else {
                null
            }

        val (keyset, keysetBindings) = cursor?.let { keysetCondition(definition, query, it) } ?: ("" to emptyMap())
        val rowsWhere = if (cursor == null) where else "$where AND $keyset"
        // one row past the page says whether another page follows, without counting anything
        var rowsSpec =
            db.sql(
                "SELECT ${selectList(definition, query.withState)}, CAST(${key.column} AS text) AS $SORT_VALUE " +
                    "FROM $table WHERE $rowsWhere $order LIMIT :limit OFFSET :offset"
            )
        (bindings + keysetBindings).forEach { (name, value) -> rowsSpec = rowsSpec.bind(name, value) }
        val rows =
            try {
                rowsSpec
                    .bind("limit", query.page.size + 1)
                    .bind("offset", if (cursor == null) query.page.offset else 0L)
                    .map { row, _ -> mapRow(definition, row, query.withState) to Rows.stringOrNull(row, SORT_VALUE) }
                    .all()
                    .asFlow()
                    .toList()
            } catch (e: Exception) {
                // a cursor that decodes but whose value postgres cannot cast back (class 22: data exception) was
                // not one this list returned: the caller's mistake, not ours
                if (cursor != null && isDataException(e)) throw invalidCursorValue()
                throw e
            }

        val content = rows.take(query.page.size)
        val next =
            if (rows.size > query.page.size) {
                content.last().let { (row, value) -> RecordCursor(key.name, query.descending, value, row.id).encode() }
            } else {
                null
            }
        return PageResponse.of(content.map { it.first }, query.page.page, query.page.size, total, next)
    }

    // the sort a list runs: the name a cursor carries, the sql column, its postgres type (a cursor
    // value comes back as text and is cast to it) and whether nulls have to be stepped around
    internal data class SortKey(
        val name: String,
        val column: String,
        val sqlType: String,
        val nullable: Boolean
    )

    internal fun sortKey(
        definition: ObjectDefinition,
        query: RecordQuery
    ): SortKey {
        val requested = query.sort?.trim()?.lowercase()
        return when {
            requested.isNullOrBlank() -> SortKey("created_at", "created_at", "timestamptz", false)
            requested == "id" -> SortKey("id", "id", "uuid", false)
            requested == "created_at" || requested == "updated_at" -> SortKey(requested, requested, "timestamptz", false)
            else -> {
                val field = fieldOrFail(definition, requested)
                // NOT NULL is kept in step with required (ObjectSchemaManager.setRequired)
                SortKey(field.name, SqlIdentifier.quote(field.columnName), types.handler(field.type).columnType(field), !field.required)
            }
        }
    }

    // rows strictly after the cursor in ORDER BY order: (sort value, id), the same pair orderBy ends
    // on, so rows tied on the sort value are split by id and none is read twice or skipped. postgres
    // puts nulls last ascending and first descending; a nullable key steps around them the same way.
    internal fun keysetCondition(
        definition: ObjectDefinition,
        query: RecordQuery,
        cursor: RecordCursor
    ): Pair<String, Map<String, Any>> {
        val key = sortKey(definition, query)
        if (cursor.sort != key.name || cursor.descending != query.descending) {
            throw ValidationException(
                "The cursor belongs to another sort",
                "after",
                "was returned for sort=${cursor.sort}&dir=${if (cursor.descending) "desc" else "asc"}; keep that sort, or start again without after"
            )
        }
        val op = if (query.descending) "<" else ">"
        val id = mapOf<String, Any>("afterId" to cursor.id)
        if (key.name == "id") return "id $op :afterId" to id

        val c = key.column
        val v = "CAST(:afterValue AS ${key.sqlType})"
        val value = cursor.value
        return when {
            // a NOT NULL key is a plain row comparison, which an index on (key, id) serves directly
            !key.nullable -> "($c, id) $op ($v, :afterId)" to id + ("afterValue" to (value ?: throw invalidCursorValue()))
            value == null && !query.descending -> "($c IS NULL AND id > :afterId)" to id
            value == null -> "(($c IS NULL AND id < :afterId) OR $c IS NOT NULL)" to id
            !query.descending -> "($c > $v OR ($c = $v AND id > :afterId) OR $c IS NULL)" to id + ("afterValue" to value)
            else -> "($c < $v OR ($c = $v AND id < :afterId))" to id + ("afterValue" to value)
        }
    }

    internal fun isDataException(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is R2dbcException && it.sqlState?.startsWith(DATA_EXCEPTION) == true }

    private fun invalidCursorValue() = ValidationException("Invalid cursor", "after", "is not a nextCursor a record list returned")

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

        // module conditions and the app's read scope, each parenthesized (R7, ADR-048)
        query.criteria.forEach { conditions += it.term(definition, bindings) }

        return conditions.joinToString(" AND ") to bindings
    }

    // pure, so it is tested directly: findById's WHERE. the same order as whereClause: tenant first,
    // then the owner, then every criterion in parens (ADR-048)
    internal fun byIdClause(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID,
        createdBy: UUID?,
        criteria: List<RecordCriterion>
    ): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("id = :id", "organization_id = :organizationId")
        val bindings = mutableMapOf<String, Any>("id" to id, "organizationId" to organizationId)
        createdBy?.let { owner ->
            conditions += "created_by = :createdBy"
            bindings["createdBy"] = owner
        }
        criteria.forEach { conditions += it.term(definition, bindings) }
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
        val column = sortKey(definition, query).column
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
            current = current.bindNullable(name, value, types.handler(field.type).javaType(field))
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

    companion object {
        // the sort value as text, for the next cursor. a field name starts with a letter, so this alias never collides.
        private const val SORT_VALUE = "__sort_value"

        // sqlstate class 22: invalid_text_representation, invalid_datetime_format, numeric_value_out_of_range…
        private const val DATA_EXCEPTION = "22"
    }
}
