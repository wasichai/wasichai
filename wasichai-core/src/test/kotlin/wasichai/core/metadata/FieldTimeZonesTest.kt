package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException

class FieldTimeZonesTest {
    @Test
    fun `an IANA name on a DATETIME field is kept, trimmed`() {
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, " America/Lima ")).isEqualTo("America/Lima")
    }

    @Test
    fun `left out or blank is no zone, on any type`() {
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, null)).isNull()
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, "  ")).isNull()
        assertThat(FieldTimeZones.checked(FieldType.TEXT, "")).isNull()
    }

    @Test
    fun `a zone on any other type is refused on timeZone`() {
        listOf(FieldType.TEXT, FieldType.DATE).forEach { type ->
            assertThatThrownBy { FieldTimeZones.checked(type, "America/Lima") }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("DATETIME")
        }
    }

    @Test
    fun `an unknown name, a fixed offset or the wrong case is refused`() {
        listOf("America/Limaa", "+05:00", "GMT+5", "america/lima").forEach { name ->
            assertThatThrownBy { FieldTimeZones.checked(FieldType.DATETIME, name) }
                .describedAs(name)
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Unknown time zone")
        }
    }
}
