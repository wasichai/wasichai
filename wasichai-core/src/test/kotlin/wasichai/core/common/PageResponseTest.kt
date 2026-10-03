package wasichai.core.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageResponseTest {
    @Test
    fun `a counted page has its totals`() {
        val page = PageResponse.of(listOf("a"), 0, 10, 21)

        assertThat(page.totalElements).isEqualTo(21)
        assertThat(page.totalPages).isEqualTo(3)
        assertThat(page.nextCursor).isNull()
    }

    // issue 21: ?count=false runs no COUNT(*), so there is nothing to say about the total
    @Test
    fun `an uncounted page has no totals, and mapping keeps the cursor`() {
        val page = PageResponse.of(listOf(1, 2), 0, 2, null, nextCursor = "c")

        assertThat(page.totalElements).isNull()
        assertThat(page.totalPages).isNull()
        val mapped = page.map { it.toString() }
        assertThat(mapped.content).containsExactly("1", "2")
        assertThat(mapped.nextCursor).isEqualTo("c")
        assertThat(mapped.totalElements).isNull()
    }
}
