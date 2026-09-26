package wasichai.views

import wasichai.core.common.ValidationException
import java.util.UUID

enum class SortDirection {
    ASC,
    DESC;

    companion object {
        fun parse(raw: String?): SortDirection {
            if (raw.isNullOrBlank()) return ASC
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown sort direction '$raw'",
                    "sort",
                    "must be one of ${entries.joinToString(", ") { it.name }}"
                )
        }
    }
}

data class ViewSort(
    val field: String,
    val direction: SortDirection
)

// what the list screen needs: which columns, which rows, in which order, how many at a time.
data class ViewDefinition(
    val columns: List<String> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
    val sort: ViewSort? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE
)

data class View(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val name: String,
    val label: String,
    val isDefault: Boolean,
    val definition: ViewDefinition
)

const val DEFAULT_PAGE_SIZE = 25

// sorting by the row's own bookkeeping columns is fine, they are on every table
val SORTABLE_SYSTEM_COLUMNS = setOf("id", "created_at", "updated_at")
