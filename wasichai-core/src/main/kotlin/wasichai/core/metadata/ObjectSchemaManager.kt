package wasichai.core.metadata

import io.r2dbc.postgresql.api.PostgresqlException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import wasichai.core.common.ValidationException
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// the only place that runs DDL. metadata in, physical table out. ADR-004.
@Component
class ObjectSchemaManager(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val types: FieldTypeRegistry
) {
    suspend fun createTable(
        obj: CustomObject,
        fields: List<CustomField>,
        relationTables: Map<UUID, String> = emptyMap()
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        val columns =
            buildList {
                add("id uuid PRIMARY KEY DEFAULT gen_random_uuid()")
                add("organization_id uuid NOT NULL")
                add("created_at timestamptz NOT NULL DEFAULT now()")
                add("updated_at timestamptz NOT NULL DEFAULT now()")
                add("created_by uuid")
                add("updated_by uuid")
                fields.forEach { add(columnDefinition(it, relationTables)) }
            }

        execute("CREATE TABLE $table (\n    ${columns.joinToString(",\n    ")}\n)")
        execute("CREATE INDEX ${SqlIdentifier.quote(obj.physicalTable + "_org_idx")} ON $table (organization_id)")
        fields.forEach { field -> indexStatements(obj, field).forEach { execute(it) } }
        declaredIndexes(ObjectDefinition(obj, fields)).forEach { execute(createIndexStatement(obj, it)) }
        uniqueSets(ObjectDefinition(obj, fields)).forEach { execute(addUniqueStatement(obj, it)) }
    }

    suspend fun addColumn(
        obj: CustomObject,
        field: CustomField,
        relationTables: Map<UUID, String> = emptyMap()
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        execute("ALTER TABLE $table ADD COLUMN ${columnDefinition(field, relationTables)}")
        indexStatements(obj, field).forEach { execute(it) }
        // only the new column's own index: no declared set can name a field that did not exist
        declaredIndexes(ObjectDefinition(obj.copy(indexes = emptyList()), listOf(field))).forEach { execute(createIndexStatement(obj, it)) }
    }

    // metadata moved from `before` to `after`: drop the declared indexes it no longer wants, build the new ones
    suspend fun syncIndexes(
        before: ObjectDefinition,
        after: ObjectDefinition
    ) {
        indexChanges(before, after).forEach { execute(it) }
    }

    // same for the declared composite uniques. an added one the data already breaks fails here,
    // inside the caller's transaction, so the metadata write goes back with it (ADR-037)
    suspend fun syncUniqueConstraints(
        before: ObjectDefinition,
        after: ObjectDefinition
    ) {
        uniqueConstraintChanges(before, after).forEach { execute(it) }
    }

    // the fields a violated data-table unique covers, in its column order.
    // the driver names the constraint, the catalog says its columns: no parsing of a message that
    // changes with lc_messages. empty for anything else. column name = field name (MetadataService).
    suspend fun uniqueFields(violation: DuplicateKeyException): List<String> {
        if (!DRIVER_PRESENT) return emptyList()
        val details =
            generateSequence<Throwable>(violation) { it.cause }.filterIsInstance<PostgresqlException>().firstOrNull()?.errorDetails
                ?: return emptyList()
        if (details.schemaName.orElse(null) != schemas.data) return emptyList()
        val table = details.tableName.orElse(null) ?: return emptyList()
        val constraint = details.constraintName.orElse(null) ?: return emptyList()
        return db
            .sql(
                """
                SELECT a.attname
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname = :schema AND t.relname = :table AND c.conname = :constraint AND c.contype = :type
                ORDER BY k.ord
                """.trimIndent()
            ).bind("schema", schemas.data)
            .bind("table", table)
            .bind("constraint", constraint)
            .bind("type", UNIQUE_CONSTRAINT)
            .map { row, _ -> row.get("attname", String::class.java)!! }
            .all()
            .asFlow()
            .toList()
    }

    // startup reconciliation: build what the metadata declares and the catalog lacks. never drops.
    suspend fun ensureIndexes(
        definition: ObjectDefinition,
        existing: Set<String>
    ): Int {
        val missing = declaredIndexes(definition).filter { declaredIndexName(definition.obj, it) !in existing }
        missing.forEach { execute(createIndexStatement(definition.obj, it)) }
        return missing.size
    }

    // every index name in the data schema, for ensureIndexes to skip what is already there
    suspend fun indexNames(): Set<String> =
        db
            .sql("SELECT indexname FROM pg_indexes WHERE schemaname = :schema")
            .bind("schema", schemas.data)
            .map { row, _ -> row.get("indexname", String::class.java)!! }
            .all()
            .asFlow()
            .toList()
            .toSet()

    // a module that gives an object's records a state calls this (ADR-013). nullable on purpose:
    // records that predate it keep no state. never dropped: the state history must survive.
    suspend fun addStateColumn(obj: CustomObject) {
        val table = schemas.dataTable(obj.physicalTable)
        val column = SqlIdentifier.quote(STATE_COLUMN)
        execute("ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column text")
        // the state is filtered and sorted on, so it is worth an index from day one
        execute("CREATE INDEX IF NOT EXISTS ${SqlIdentifier.quote(obj.physicalTable + "_state_idx")} ON $table ($column)")
    }

    suspend fun dropTable(obj: CustomObject) {
        execute("DROP TABLE IF EXISTS ${schemas.dataTable(obj.physicalTable)} CASCADE")
    }

    suspend fun dropColumn(
        obj: CustomObject,
        field: CustomField
    ) {
        execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} DROP COLUMN IF EXISTS ${SqlIdentifier.quote(field.columnName)}")
    }

    suspend fun setRequired(
        obj: CustomObject,
        field: CustomField,
        required: Boolean
    ) {
        val action = if (required) "SET NOT NULL" else "DROP NOT NULL"
        execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ALTER COLUMN ${SqlIdentifier.quote(field.columnName)} $action")
    }

    suspend fun setUnique(
        obj: CustomObject,
        field: CustomField,
        unique: Boolean
    ) {
        dropConstraints(obj, field, UNIQUE_CONSTRAINT)
        if (unique) execute("ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ADD UNIQUE (${SqlIdentifier.quote(field.columnName)})")
    }

    suspend fun replaceEnumCheck(
        obj: CustomObject,
        field: CustomField,
        options: List<String>
    ) {
        val column = SqlIdentifier.quote(field.columnName)
        dropConstraints(obj, field, CHECK_CONSTRAINT)
        if (options.isNotEmpty()) {
            execute(
                "ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ADD CHECK ($column IN (${options.joinToString(", ") { SqlIdentifier.literal(it) }}))"
            )
        }
    }

    suspend fun createJoinTable(
        joinTable: String,
        source: CustomObject,
        target: CustomObject
    ) {
        val table = schemas.dataTable(joinTable)
        execute(
            """
            CREATE TABLE $table (
                organization_id uuid NOT NULL,
                source_id uuid NOT NULL REFERENCES ${schemas.dataTable(source.physicalTable)} (id) ON DELETE CASCADE,
                target_id uuid NOT NULL REFERENCES ${schemas.dataTable(target.physicalTable)} (id) ON DELETE CASCADE,
                created_at timestamptz NOT NULL DEFAULT now(),
                PRIMARY KEY (source_id, target_id)
            )
            """.trimIndent()
        )
        execute("CREATE INDEX ${SqlIdentifier.quote(joinTable + "_target_idx")} ON $table (target_id)")
    }

    suspend fun dropJoinTable(joinTable: String) {
        execute("DROP TABLE IF EXISTS ${schemas.dataTable(joinTable)}")
    }

    // the column as DDL. the type comes from its handler; enum and relation keep their core constraints.
    internal fun columnDefinition(
        field: CustomField,
        relationTables: Map<UUID, String>
    ): String {
        val column = SqlIdentifier.quote(field.columnName)
        val definition = StringBuilder("$column ${types.handler(field.type).columnType(field)}")
        if (field.required) definition.append(" NOT NULL")
        if (field.unique) definition.append(" UNIQUE")
        when (field.type) {
            FieldType.ENUM -> {
                val options = field.enumOptions.orEmpty()
                if (options.isEmpty()) {
                    throw ValidationException("Enum field '${field.name}' has no options", field.name, "requires enumOptions")
                }
                definition.append(" CHECK ($column IN (${options.joinToString(", ") { SqlIdentifier.literal(it) }}))")
            }
            FieldType.RELATION -> {
                val target =
                    field.relationTargetObjectId?.let { relationTables[it] }
                        ?: throw ValidationException(
                            "Relation field '${field.name}' has no resolvable target",
                            field.name,
                            "target object does not exist"
                        )
                definition.append(" REFERENCES ${schemas.dataTable(target)} (id) ON DELETE SET NULL")
            }
            else -> Unit
        }
        return definition.toString()
    }

    // whatever the type asks for once its column exists (a spatial index, say)
    internal fun indexStatements(
        obj: CustomObject,
        field: CustomField
    ): List<String> = types.handler(field.type).indexes(obj, schemas.dataTable(obj.physicalTable), field)

    // what the table should carry besides org/state/type indexes, as column lists: every field declared
    // indexed, every relation column (postgres does not index a foreign key), every declared set. a
    // unique column already has the unique constraint's index, so it gets no second one. ADR-036.
    fun declaredIndexes(definition: ObjectDefinition): List<List<String>> {
        val columns = definition.fields.associate { it.name to it.columnName }
        val single =
            definition.fields
                .filter { (it.indexed || it.type == FieldType.RELATION) && !it.unique }
                .map { listOf(it.columnName) }
        val sets = definition.obj.indexes.mapNotNull { set -> set.map { columns[it] ?: return@mapNotNull null } }
        return (single + sets).distinct()
    }

    // DDL only: what syncIndexes runs, tested without a database
    internal fun indexChanges(
        before: ObjectDefinition,
        after: ObjectDefinition
    ): List<String> {
        val was = declaredIndexes(before)
        val now = declaredIndexes(after)
        return (was - now.toSet()).map { dropIndexStatement(after.obj, it) } + (now - was.toSet()).map { createIndexStatement(after.obj, it) }
    }

    // the declared composite uniques as column lists (ADR-037)
    fun uniqueSets(definition: ObjectDefinition): List<List<String>> {
        val columns = definition.fields.associate { it.name to it.columnName }
        return definition.obj.uniqueConstraints.mapNotNull { set -> set.map { columns[it] ?: return@mapNotNull null } }
    }

    // DDL only: what syncUniqueConstraints runs. drops first, so a set moved to new columns never meets its old self.
    internal fun uniqueConstraintChanges(
        before: ObjectDefinition,
        after: ObjectDefinition
    ): List<String> {
        val was = uniqueSets(before)
        val now = uniqueSets(after)
        return (was - now.toSet()).map { dropUniqueStatement(after.obj, it) } + (now - was.toSet()).map { addUniqueStatement(after.obj, it) }
    }

    // the table is per organization, so the set alone is unique per organization, like a single-field
    // unique. the name is derived, so dropping needs no catalog read.
    internal fun addUniqueStatement(
        obj: CustomObject,
        columns: List<String>
    ): String =
        "ALTER TABLE ${schemas.dataTable(obj.physicalTable)} ADD CONSTRAINT ${SqlIdentifier.quote(uniqueConstraintName(obj, columns))} " +
            "UNIQUE (${columns.joinToString(", ") { SqlIdentifier.quote(it) }})"

    internal fun dropUniqueStatement(
        obj: CustomObject,
        columns: List<String>
    ): String = "ALTER TABLE ${schemas.dataTable(obj.physicalTable)} DROP CONSTRAINT IF EXISTS ${SqlIdentifier.quote(uniqueConstraintName(obj, columns))}"

    private fun uniqueConstraintName(
        obj: CustomObject,
        columns: List<String>
    ): String = SqlIdentifier.fieldSetName(obj.physicalTable, columns, DECLARED_UNIQUE)

    internal fun declaredIndexName(
        obj: CustomObject,
        columns: List<String>
    ): String = SqlIdentifier.fieldSetName(obj.physicalTable, columns, DECLARED_INDEX)

    // IF NOT EXISTS: applying the same metadata twice, or reconciling at every start, is a no-op
    internal fun createIndexStatement(
        obj: CustomObject,
        columns: List<String>
    ): String =
        "CREATE INDEX IF NOT EXISTS ${SqlIdentifier.quote(declaredIndexName(obj, columns))} " +
            "ON ${schemas.dataTable(obj.physicalTable)} (${columns.joinToString(", ") { SqlIdentifier.quote(it) }})"

    internal fun dropIndexStatement(
        obj: CustomObject,
        columns: List<String>
    ): String = "DROP INDEX IF EXISTS ${SqlIdentifier.qualify(schemas.data, declaredIndexName(obj, columns))}"

    // constraint names are generated by postgres, so drop what the catalog actually has. only the
    // column's own one-column constraints: a declared composite unique naming it is not this field's (ADR-037)
    private suspend fun dropConstraints(
        obj: CustomObject,
        field: CustomField,
        type: String
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        constraintNames(obj.physicalTable, field.columnName, type).forEach { name ->
            execute("ALTER TABLE $table DROP CONSTRAINT ${SqlIdentifier.quote(name)}")
        }
    }

    private suspend fun constraintNames(
        table: String,
        column: String,
        type: String
    ): List<String> =
        db
            .sql(
                """
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
                WHERE n.nspname = :schema AND t.relname = :table AND a.attname = :column AND c.contype = :type
                  AND cardinality(c.conkey) = 1
                """.trimIndent()
            ).bind("schema", schemas.data)
            .bind("table", table)
            .bind("column", column)
            .bind("type", type)
            .map { row, _ -> row.get("conname", String::class.java)!! }
            .all()
            .asFlow()
            .toList()

    private suspend fun execute(sql: String) {
        db
            .sql(sql)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    companion object {
        // the record-state column (ADR-013). the name is kept so existing tables need no rename.
        const val STATE_COLUMN = "workflow_state"
        private const val UNIQUE_CONSTRAINT = "u"
        private const val CHECK_CONSTRAINT = "c"

        // "<table>_ix_<hash>": declared indexes, told apart from the org/state/type ones
        private const val DECLARED_INDEX = "ix"

        // "<table>_uq_<hash>": declared composite uniques (ADR-037)
        private const val DECLARED_UNIQUE = "uq"

        // the driver is compile only: an app without it never reaches a postgres error anyway
        private val DRIVER_PRESENT = ClassUtils.isPresent("io.r2dbc.postgresql.api.PostgresqlException", ObjectSchemaManager::class.java.classLoader)
    }
}
