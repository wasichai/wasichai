package wasichai.core.data

import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.metadata.ObjectDefinition

// a condition a module adds to a record query. values go through `bind`, which returns the
// placeholder to write: nothing from the request is ever interpolated.
fun interface RecordCriterion {
    fun condition(
        definition: ObjectDefinition,
        bind: (Any) -> String
    ): String
}

// one WHERE term: the condition in parens, its values bound into [bindings] as c<n> (R7, ADR-048). unparenthesized,
// an "x OR y" would AND in loosely enough to escape organization_id (and createdBy) in front of it - a tenancy leak.
internal fun RecordCriterion.term(
    definition: ObjectDefinition,
    bindings: MutableMap<String, Any>
): String {
    val condition =
        condition(definition) { value ->
            val name = "c${bindings.size}"
            bindings[name] = value
            ":$name"
        }
    check(condition.isNotBlank()) {
        "a RecordCriterion for '${definition.obj.name}' returned a blank condition"
    }
    return "($condition)"
}

// a module that owns query-string parameters turns them into criteria (R7)
interface RecordQueryContributor {
    // never read as field filters, whether sent or not
    val parameters: Set<String>

    // null when none of its parameters was sent. a bad value throws ValidationException.
    fun parse(params: Map<String, String>): RecordCriterion?
}

// query string -> RecordQuery. anything not reserved is an equality filter on a field.
class RecordQueryParser(
    private val contributors: List<RecordQueryContributor>
) {
    private val reserved: Set<String> = CORE_PARAMETERS + contributors.flatMap { it.parameters }

    fun parse(params: Map<String, String>): RecordQuery =
        RecordQuery(
            page = PageRequest.of(params["page"]?.toIntOrNull(), params["size"]?.toIntOrNull()),
            sort = params["sort"],
            descending = params["dir"].equals("desc", ignoreCase = true),
            search = params["q"],
            filters = params.filterKeys { it !in reserved },
            criteria = contributors.mapNotNull { it.parse(params) },
            count = count(params["count"]),
            after = params["after"]?.takeIf { it.isNotBlank() }
        )

    // strict: a typo must not silently run the count the caller meant to skip
    private fun count(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            null, "true" -> true
            "false" -> false
            else -> throw ValidationException("Invalid count '$raw'", "count", "must be true or false")
        }

    companion object {
        val CORE_PARAMETERS = setOf("page", "size", "sort", "dir", "q", "limit", "count", "after")
    }
}
