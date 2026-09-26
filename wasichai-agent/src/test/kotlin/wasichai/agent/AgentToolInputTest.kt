package wasichai.agent

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.util.UUID

class AgentToolInputTest {
    @Test
    fun `a missing required argument names itself`() {
        assertThatThrownBy { AgentToolInput.string(emptyMap(), "object") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("object")

        // blank is missing too: "   " is not an object name
        assertThatThrownBy { AgentToolInput.string(mapOf("object" to "   "), "object") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `strings are trimmed and numbers are accepted`() {
        assertThat(AgentToolInput.string(mapOf("object" to "  predio "), "object")).isEqualTo("predio")
        assertThat(AgentToolInput.optionalString(mapOf("sort" to 3), "sort")).isEqualTo("3")
        assertThat(AgentToolInput.optionalString(emptyMap(), "sort")).isNull()
    }

    @Test
    fun `a record id that is not a uuid is a validation error, not a crash`() {
        assertThatThrownBy { AgentToolInput.uuid(mapOf("id" to "not-a-uuid"), "id") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("id")

        val id = UUID.randomUUID()
        assertThat(AgentToolInput.uuid(mapOf("id" to id.toString()), "id")).isEqualTo(id)
    }

    @Test
    fun `a limit above the cap is clamped`() {
        assertThat(AgentToolInput.limit(mapOf("limit" to 5000))).isEqualTo(MAX_TOOL_LIMIT)
        assertThat(AgentToolInput.limit(mapOf("limit" to "9999"))).isEqualTo(MAX_TOOL_LIMIT)
        assertThat(AgentToolInput.limit(mapOf("limit" to 0))).isEqualTo(1)
        assertThat(AgentToolInput.limit(mapOf("limit" to -10))).isEqualTo(1)
        assertThat(AgentToolInput.limit(mapOf("limit" to 7))).isEqualTo(7)
        assertThat(AgentToolInput.limit(emptyMap())).isEqualTo(DEFAULT_TOOL_LIMIT)
        assertThat(AgentToolInput.limit(mapOf("limit" to "many"))).isEqualTo(DEFAULT_TOOL_LIMIT)
    }

    @Test
    fun `filters become string equality pairs and nulls are dropped`() {
        val filters = AgentToolInput.filters(mapOf("filters" to mapOf("estado" to "activo", "valor" to 12, "blank" to null, "" to "x")))
        assertThat(filters).containsExactlyInAnyOrderEntriesOf(mapOf("estado" to "activo", "valor" to "12"))
        assertThat(AgentToolInput.filters(mapOf("filters" to "nope"))).isEmpty()
        assertThat(AgentToolInput.filters(emptyMap())).isEmpty()
    }

    @Test
    fun `direction only reverses on desc`() {
        assertThat(AgentToolInput.descending(mapOf("direction" to "DESC"))).isTrue()
        assertThat(AgentToolInput.descending(mapOf("direction" to "asc"))).isFalse()
        assertThat(AgentToolInput.descending(emptyMap())).isFalse()
    }

    // BboxQuery (wasichai-gis) parses it; a core-only app answers the record api's 400 instead
    @Test
    fun `bbox is passed along as sent, trimmed`() {
        assertThat(AgentToolInput.bbox(mapOf("bbox" to " -1,-2,3,4 "))).isEqualTo("-1,-2,3,4")
        assertThat(AgentToolInput.bbox(emptyMap())).isNull()
    }
}
