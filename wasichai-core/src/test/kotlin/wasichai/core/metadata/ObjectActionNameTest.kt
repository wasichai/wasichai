package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class ObjectActionNameTest {
    @Test
    fun `an upper snake name is kept, any case is upper-cased`() {
        assertThat(ObjectActionService.requireValidName("ANULAR_AJENO")).isEqualTo("ANULAR_AJENO")
        assertThat(ObjectActionService.requireValidName(" anular_ajeno ")).isEqualTo("ANULAR_AJENO")
    }

    @Test
    fun `a built-in action cannot be declared again`() {
        listOf("READ", "create", "Update", "DELETE", "MANAGE_METADATA", "manage_organization").forEach { name ->
            assertThatThrownBy { ObjectActionService.requireValidName(name) }
                .describedAs(name)
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("built in")
        }
    }

    // issue 56 (ADR-055)
    @Test
    fun `MANAGE_TENANTS is built in too, so no object declares it`() {
        assertThatThrownBy { ObjectActionService.requireValidName("manage_tenants") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("built in")
    }

    @Test
    fun `a name that is not upper snake is refused`() {
        listOf("ANULAR AJENO", "9LIVES", "_X", "A", "ANULAR-AJENO", "A".repeat(50)).forEach { name ->
            assertThatThrownBy { ObjectActionService.requireValidName(name) }
                .describedAs(name)
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Invalid action name")
        }
    }
}
