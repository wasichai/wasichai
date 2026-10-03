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
