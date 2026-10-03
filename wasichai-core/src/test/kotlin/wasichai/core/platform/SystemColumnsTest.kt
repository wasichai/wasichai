package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class SystemColumnsTest {
    private val core = SystemColumns(emptyList())

    @Test
    fun `core alone reserves the six always-present columns and version`() {
        assertThat(core.all.map { it.name })
            .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "version", "count", "after")
        assertThat(core.names).doesNotContain("workflow_state")
    }

    @Test
    fun `a contributed column sits between the core ones and the reserved ones`() {
        val columns = SystemColumns(listOf(SystemColumnContributor { listOf(SystemColumn("workflow_state", "TEXT", "WORKFLOW")) }))
        assertThat(columns.all.map { it.name })
            .containsExactly("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "workflow_state", "version", "count", "after")
        assertThatThrownBy { columns.requireValidFieldName("workflow_state") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `only the reserved name nothing creates has no type`() {
        assertThat(core.all.filter { it.type == null }).allMatch { it.scope == SystemColumnScope.RESERVED }
    }

    @Test
    fun `a column declared twice fails at boot`() {
        assertThatThrownBy { SystemColumns(listOf(SystemColumnContributor { listOf(SystemColumn("id", "UUID", "X")) })) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("id")
    }
}
