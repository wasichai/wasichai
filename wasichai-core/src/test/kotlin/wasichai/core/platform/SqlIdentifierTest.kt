package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class SqlIdentifierTest {
    @Test
    fun `accepts a plain lower case name`() {
        assertThat(SqlIdentifier.requireValidObjectName("predio")).isEqualTo("predio")
    }

    @Test
    fun `rejects reserved SQL keywords`() {
        assertThatThrownBy { SqlIdentifier.requireValidObjectName("select") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("select")
    }

    @Test
    fun `rejects names that are not identifier safe`() {
        listOf("Predio", "mi objeto", "1predio", "predio;drop", "predio'--").forEach { name ->
            assertThatThrownBy { SqlIdentifier.requireValidObjectName(name) }
                .describedAs(name)
                .isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `rejects field names that collide with platform columns`() {
        val reserved = setOf("id", "created_at")
        reserved.forEach { column ->
            assertThatThrownBy { SqlIdentifier.requireValidFieldName(column, reserved) }
                .describedAs(column)
                .isInstanceOf(ValidationException::class.java)
        }
        assertThat(SqlIdentifier.requireValidFieldName("codigo", reserved)).isEqualTo("codigo")
    }

    @Test
    fun `rejects object names too long to fit a physical table name`() {
        assertThatThrownBy { SqlIdentifier.requireValidObjectName("a".repeat(40)) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `an index name that fits is the plain one`() {
        assertThat(SqlIdentifier.indexName("predio__00000000", "lote", "gix")).isEqualTo("predio__00000000_lote_gix")
    }

    // postgres would truncate at 63 on its own, and two long names would land on the same one
    @Test
    fun `a name too long for postgres is cut on purpose and stays unique`() {
        val table = "a".repeat(40)
        val first = SqlIdentifier.indexName(table, "b".repeat(40), "gix")
        val second = SqlIdentifier.indexName(table, "b".repeat(39) + "c", "gix")

        assertThat(first).hasSizeLessThanOrEqualTo(63)
        assertThat(second).hasSizeLessThanOrEqualTo(63)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `a truncated index name is still a quotable identifier`() {
        val name = SqlIdentifier.indexName("a".repeat(40), "b".repeat(40), "gix")
        assertThat(SqlIdentifier.quote(name)).isEqualTo("\"$name\"")
    }

    @Test
    fun `quote refuses anything unsafe`() {
        assertThat(SqlIdentifier.quote("predio__00000000")).isEqualTo("\"predio__00000000\"")
        assertThatThrownBy { SqlIdentifier.quote("predio\"; DROP TABLE x --") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `literal doubles embedded quotes`() {
        assertThat(SqlIdentifier.literal("O'Brien")).isEqualTo("'O''Brien'")
    }
}
