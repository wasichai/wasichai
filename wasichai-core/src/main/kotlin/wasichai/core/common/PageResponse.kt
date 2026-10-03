package wasichai.core.common

import com.fasterxml.jackson.annotation.JsonInclude
import kotlin.math.ceil

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    // null when the caller skipped the count (?count=false): no COUNT(*) ran, so nothing to say
    val totalElements: Long?,
    val totalPages: Int?,
    // where the next keyset read starts (?after=). left out of the json when nothing follows,
    // so a default list reads exactly as before (ADR-036).
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val nextCursor: String? = null
) {
    fun <R> map(transform: (T) -> R): PageResponse<R> = PageResponse(content.map(transform), page, size, totalElements, totalPages, nextCursor)

    companion object {
        fun <T> of(
            content: List<T>,
            page: Int,
            size: Int,
            totalElements: Long?,
            nextCursor: String? = null
        ): PageResponse<T> =
            PageResponse(
                content = content,
                page = page,
                size = size,
                totalElements = totalElements,
                totalPages = totalElements?.let { if (size <= 0) 0 else ceil(it.toDouble() / size).toInt() },
                nextCursor = nextCursor
            )
    }
}

// page/size guard. keeps a runaway ?size=100000 from hitting the db.
data class PageRequest(
    val page: Int,
    val size: Int
) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val MAX_SIZE = 200

        fun of(
            page: Int?,
            size: Int?
        ): PageRequest =
            PageRequest(
                page = (page ?: 0).coerceAtLeast(0),
                size = (size ?: 25).coerceIn(1, MAX_SIZE)
            )
    }
}
