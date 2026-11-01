package wasichai.core.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import wasichai.core.common.Actions
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Lets a [RecordReadScope] narrow by another object in the same statement (issue 147, ADR-075): a lesson is
 * read when its unit is one of a version the caller is enrolled in. [select] reads that other object as
 * [RecordService] would for the same caller: its table and columns from metadata, its organization, `READ` on
 * it (none: no row), `own_records_only`, and its own read scopes. Every value bound.
 *
 * Not applied, as for a criterion: field rules (it may name a field the caller cannot read) and read masks
 * (nothing of the rows reaches the caller, only which ids match).
 *
 * `suspend`, so the caller's rules are looked up here, inside [RecordReadScope.criterion]; what it returns
 * only renders SQL. A cycle of scopes through subqueries throws `IllegalStateException`.
 */
class RecordSubqueries(
    private val metadata: MetadataService,
    private val schemas: WasichaiSchemas,
    private val currentUser: CurrentUser,
    private val access: AccessPolicy,
    private val types: FieldTypeRegistry,
    // lazy: an app's scope takes this bean, and RecordReadScopes takes every scope
    readScopes: () -> RecordReadScopes
) {
    private val readScopes by lazy(readScopes)

    /**
     * The values of [select] (`id` or a field name) of the records of [objectName] that [caller] reads, among
     * those matching every entry of [where]: a field name (or `id`) to a value, `null` (IS NULL) or another
     * [RecordSubquery] (IN). A value goes through its field type, as a list filter's does. An unknown object
     * is a `NotFoundException`; an unknown field an `IllegalArgumentException`.
     */
    suspend fun select(
        caller: AuthenticatedUser,
        objectName: String,
        select: String = ID,
        where: Map<String, Any?> = emptyMap()
    ): RecordSubquery {
        val definition = metadata.loadDefinition(caller.organizationId, objectName)
        val selected = definition.column(select)
        val conditions = where.map { (name, value) -> condition(definition, name, value) }
        val table = schemas.dataTable(definition.obj.physicalTable)
        // no READ, nothing: the scopes of an object the caller cannot read are never asked
        if (!currentUser.hasPermission(caller, Actions.READ, definition.obj.id)) {
            return RecordSubquery { "SELECT $selected FROM $table WHERE false" }
        }
        val owner = access.ownerFilter(caller)
        val scopes = scopesOf(caller, definition)
        return RecordSubquery { bind ->
            buildString {
                append("SELECT $selected FROM $table WHERE organization_id = ${bind(caller.organizationId)}")
                owner?.let { append(" AND created_by = ${bind(it)}") }
                conditions.forEach { append(" AND ${it(bind)}") }
                // the target's own read scopes, each in parens as RecordCriterion.term has it
                scopes.forEach { scope ->
                    val condition = scope.condition(definition, bind)
                    check(condition.isNotBlank()) { "a RecordCriterion for '${definition.obj.name}' returned a blank condition" }
                    append(" AND ($condition)")
                }
            }
        }
    }

    // the target's own scopes, with it marked as being resolved: a scope that comes back to it is a cycle
    private suspend fun scopesOf(
        caller: AuthenticatedUser,
        definition: ObjectDefinition
    ): List<RecordCriterion> {
        val resolving = currentCoroutineContext()[Resolving]?.objects.orEmpty()
        val name = definition.obj.name
        check(name !in resolving) { "read scopes reach '$name' again through subqueries: ${(resolving + name).joinToString(" -> ")}" }
        return withContext(Resolving(resolving + name)) { readScopes.criteria(caller, definition) }
    }

    // values converted now, so a bad one throws where the scope is asked, not mid-read
    private fun condition(
        definition: ObjectDefinition,
        name: String,
        value: Any?
    ): (bind: (Any) -> String) -> String {
        val column = definition.column(name)
        if (value == null) return { _ -> "$column IS NULL" }
        if (value is RecordSubquery) return { bind -> "$column IN (${value.sql(bind)})" }
        val stored = if (name == ID) uuidOf(value) else storedValue(definition, name, value)
        return { bind -> "$column = ${bind(stored)}" }
    }

    private fun storedValue(
        definition: ObjectDefinition,
        name: String,
        value: Any
    ): Any {
        val field = definition.fields.first { it.name == name }
        return types.handler(field.type).toDatabase(field.copy(required = false), value)
            ?: throw IllegalArgumentException("'$name' of '${definition.obj.name}' has no stored value for $value: use null for IS NULL")
    }

    private fun uuidOf(value: Any): UUID =
        when (value) {
            is UUID -> value
            is String -> UUID.fromString(value)
            else -> throw IllegalArgumentException("id must be a UUID, not $value")
        }

    // the objects whose scopes are being asked on this coroutine, outermost first
    private class Resolving(
        val objects: List<String>
    ) : AbstractCoroutineContextElement(Resolving) {
        companion object Key : CoroutineContext.Key<Resolving>
    }

    companion object {
        const val ID = "id"
    }
}

// a subquery [RecordSubqueries.select] prepared. renders plain SQL, its values through [bind] as a RecordCriterion's
fun interface RecordSubquery {
    fun sql(bind: (Any) -> String): String

    // the common case: [field] (or `id`) of the object being read is one of this subquery's values
    fun criterion(field: String): RecordCriterion = RecordCriterion { definition, bind -> "${definition.column(field)} IN (${sql(bind)})" }
}

/**
 * The quoted column of [name] (`id` or a field) in this object's table, for a [RecordCriterion]: the app never
 * names a physical column. An unknown field is an `IllegalArgumentException`.
 */
fun ObjectDefinition.column(name: String): String {
    if (name == RecordSubqueries.ID) return RecordSubqueries.ID
    val field =
        fields.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("'${obj.name}' has no field '$name'")
    return SqlIdentifier.quote(field.columnName)
}
