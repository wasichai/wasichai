package wasichai.core.platform

import wasichai.core.common.ValidationException

// every runtime-built identifier passes here. validate, then quote. values always bound, never inlined.
object SqlIdentifier {
    private val VALID_NAME = Regex("^[a-z][a-z0-9_]{0,48}$")

    // generated identifiers (physical tables, indexes) may run to the postgres limit
    private val SAFE_IDENTIFIER = Regex("^[a-z][a-z0-9_]{0,62}$")

    // object names stay short so "<name>__<org8>" still fits a quotable identifier
    const val MAX_OBJECT_NAME = 39

    private const val MAX_IDENTIFIER = 63
    private const val HASH_LENGTH = 8

    private val SQL_KEYWORDS: Set<String> =
        setOf(
            "all",
            "alter",
            "and",
            "any",
            "array",
            "as",
            "asc",
            "begin",
            "between",
            "by",
            "case",
            "cast",
            "check",
            "column",
            "commit",
            "constraint",
            "create",
            "cross",
            "current_date",
            "current_time",
            "current_timestamp",
            "current_user",
            "default",
            "delete",
            "desc",
            "distinct",
            "do",
            "drop",
            "else",
            "end",
            "except",
            "exists",
            "false",
            "fetch",
            "for",
            "foreign",
            "from",
            "full",
            "grant",
            "group",
            "having",
            "in",
            "index",
            "inner",
            "insert",
            "intersect",
            "into",
            "is",
            "join",
            "key",
            "left",
            "like",
            "limit",
            "not",
            "null",
            "offset",
            "on",
            "or",
            "order",
            "outer",
            "primary",
            "references",
            "returning",
            "right",
            "rollback",
            "select",
            "session_user",
            "set",
            "some",
            "table",
            "then",
            "to",
            "true",
            "union",
            "unique",
            "update",
            "user",
            "using",
            "values",
            "view",
            "when",
            "where",
            "with"
        )

    fun requireValidName(
        name: String,
        kind: String,
        field: String = "name",
        maxLength: Int = 49
    ): String {
        if (name.length > maxLength) {
            throw ValidationException("Invalid $kind name '$name'", field, "must be at most $maxLength characters")
        }
        if (!VALID_NAME.matches(name)) {
            throw ValidationException(
                "Invalid $kind name '$name'",
                field,
                "must match ^[a-z][a-z0-9_]{0,48}$ (lower case, starting with a letter)"
            )
        }
        if (name in SQL_KEYWORDS) {
            throw ValidationException("Invalid $kind name '$name'", field, "is a reserved SQL keyword")
        }
        return name
    }

    fun requireValidObjectName(
        name: String,
        field: String = "name"
    ): String = requireValidName(name, "object", field, MAX_OBJECT_NAME)

    // reserved = SystemColumns.names: core's plus whatever the installed modules own
    fun requireValidFieldName(
        name: String,
        reserved: Set<String>,
        field: String = "name"
    ): String {
        requireValidName(name, "field", field)
        if (name in reserved) {
            throw ValidationException("Invalid field name '$name'", field, "is reserved by the platform")
        }
        return name
    }

    // postgres truncates an index name at 63 and a silent truncation can collide with its neighbour.
    // truncate on purpose, and when it bites, end on a hash of what was cut.
    fun indexName(
        table: String,
        column: String,
        suffix: String
    ): String {
        val full = "${table}_${column}_$suffix"
        if (full.length <= MAX_IDENTIFIER) return full
        val hash = Integer.toHexString(full.hashCode()).takeLast(HASH_LENGTH).padStart(HASH_LENGTH, '0')
        return full.take(MAX_IDENTIFIER - HASH_LENGTH - 1) + "_" + hash
    }

    fun quote(identifier: String): String {
        require(SAFE_IDENTIFIER.matches(identifier)) { "Unsafe SQL identifier: '$identifier'" }
        return "\"$identifier\""
    }

    // single quotes for DDL literals (enum CHECK values). doubled, never concatenated raw.
    fun literal(value: String): String = "'" + value.replace("'", "''") + "'"

    fun qualify(
        schema: String,
        table: String
    ): String = "${quote(schema)}.${quote(table)}"
}
