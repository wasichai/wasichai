package wasichai.core.common

import kotlin.math.ceil

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun <T> of(
            content: List<T>,
            page: Int,
            size: Int,
            totalElements: Long
        ): PageResponse<T> =
            PageResponse(
                content = content,
                page = page,
                size = size,
                totalElements = totalElements,
                totalPages = if (size <= 0) 0 else ceil(totalElements.toDouble() / size).toInt()
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
