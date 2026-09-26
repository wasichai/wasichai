package wasichai.core.metadata

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
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
    }

    suspend fun addColumn(
        obj: CustomObject,
        field: CustomField,
        relationTables: Map<UUID, String> = emptyMap()
    ) {
        val table = schemas.dataTable(obj.physicalTable)
        execute("ALTER TABLE $table ADD COLUMN ${columnDefinition(field, relationTables)}")
        indexStatements(obj, field).forEach { execute(it) }
    }

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

    // constraint names are generated by postgres, so drop what the catalog actually has
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
    }
}
