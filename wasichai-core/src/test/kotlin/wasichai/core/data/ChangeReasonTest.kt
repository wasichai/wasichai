package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

// ADR-041: a change reason is trimmed, blank is absent, and it has a length cap
class ChangeReasonTest {
    @Test
    fun `trimmed, blank is absent`() {
        assertThat(ChangeReason.normalize("  corrección de monto  ")).isEqualTo("corrección de monto")
        assertThat(ChangeReason.normalize("   ")).isNull()
        assertThat(ChangeReason.normalize("")).isNull()
        assertThat(ChangeReason.normalize(null)).isNull()
    }

    @Test
    fun `longer than the cap is refused on reason`() {
        assertThat(ChangeReason.normalize("x".repeat(ChangeReason.MAX_LENGTH))).hasSize(ChangeReason.MAX_LENGTH)
        assertThatThrownBy { ChangeReason.normalize("x".repeat(ChangeReason.MAX_LENGTH + 1)) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.violations.single().field).isEqualTo("reason") }
    }

    @Test
    fun `the cap counts characters, not utf-16 units`() {
        val emoji = "📝"
        assertThat(ChangeReason.normalize(emoji.repeat(ChangeReason.MAX_LENGTH))).isNotNull()
    }

    @Test
    fun `a header value is taken as is, or decoded from the rfc 8187 utf-8 form`() {
        assertThat(ChangeReason.fromHeader(" 10% de descuento ")).isEqualTo("10% de descuento")
        assertThat(ChangeReason.fromHeader("UTF-8''A%C3%B1o%20%E2%80%94%20cierre")).isEqualTo("Año — cierre")
        assertThat(ChangeReason.fromHeader("utf-8''%20%20")).isNull()
        assertThat(ChangeReason.fromHeader(null)).isNull()
    }

    @Test
    fun `a malformed rfc 8187 value is refused on reason`() {
        assertThatThrownBy { ChangeReason.fromHeader("UTF-8''%E2%8") }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.violations.single().field).isEqualTo("reason") }
        assertThatThrownBy { ChangeReason.fromHeader("UTF-8''%FF") }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.violations.single().field).isEqualTo("reason") }
    }
}
