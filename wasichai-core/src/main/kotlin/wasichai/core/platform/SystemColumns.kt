package wasichai.core.platform

// when a system column exists on a record table. a module may name its own scope ("WORKFLOW").
object SystemColumnScope {
    const val ALWAYS = "ALWAYS"
    const val RESERVED = "RESERVED"
}

// a name the platform keeps for itself. type is null when no column is created for it.
data class SystemColumn(
    val name: String,
    val type: String?,
    val scope: String
)

// a module that owns a column on record tables names it here, so no user field can take it
fun interface SystemColumnContributor {
    fun systemColumns(): List<SystemColumn>
}

// every name user fields must not take: core's, then the modules', then the reserved ones
class SystemColumns(
    contributors: List<SystemColumnContributor>
) {
    val all: List<SystemColumn> = CORE + contributors.flatMap { it.systemColumns() } + RESERVED

    val names: Set<String> = all.map { it.name }.toSet()

    init {
        val twice = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "system column declared twice: ${twice.joinToString(", ")}" }
    }

    fun requireValidFieldName(
        name: String,
        field: String = "name"
    ): String = SqlIdentifier.requireValidFieldName(name, names, field)

    companion object {
        val CORE: List<SystemColumn> =
            listOf(
                SystemColumn("id", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("organization_id", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("created_at", "DATETIME", SystemColumnScope.ALWAYS),
                SystemColumn("updated_at", "DATETIME", SystemColumnScope.ALWAYS),
                SystemColumn("created_by", "UUID", SystemColumnScope.ALWAYS),
                SystemColumn("updated_by", "UUID", SystemColumnScope.ALWAYS)
            )

        // reserved, never created. version: optimistic locking rides on updated_at (ADR-051), the name is kept free
        // for a counter should one ever be needed. count and after: record
        // list parameters (ADR-036), so a field of that name could never be filtered on.
        val RESERVED: List<SystemColumn> =
            listOf(
                SystemColumn("version", null, SystemColumnScope.RESERVED),
                SystemColumn("count", null, SystemColumnScope.RESERVED),
                SystemColumn("after", null, SystemColumnScope.RESERVED)
            )
    }
}
