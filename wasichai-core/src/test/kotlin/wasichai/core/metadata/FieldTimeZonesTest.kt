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

    // issue 99
    @Test
    fun `a region name in any case is answered in its canonical spelling`() {
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, "america/lima")).isEqualTo("America/Lima")
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, "AMERICA/ARGENTINA/BUENOS_AIRES")).isEqualTo("America/Argentina/Buenos_Aires")
        assertThat(FieldTimeZones.checked(FieldType.DATETIME, "utc")).isEqualTo("UTC")
    }

    @Test
    fun `a fixed offset is answered as plus or minus HH colon MM`() {
        mapOf(
            "-0500" to "-05:00",
            "+05:00" to "+05:00",
            "-05" to "-05:00",
            "+0530" to "+05:30",
            "+18:00" to "+18:00",
            "Z" to "+00:00",
            "z" to "+00:00",
            "+00:00" to "+00:00",
            "-00:00" to "+00:00"
        ).forEach { (sent, stored) ->
            assertThat(FieldTimeZones.checked(FieldType.DATETIME, sent)).describedAs(sent).isEqualTo(stored)
        }
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
            listOf("America/Lima", "-05:00").forEach { zone ->
                assertThatThrownBy { FieldTimeZones.checked(type, zone) }
                    .isInstanceOf(ValidationException::class.java)
                    .hasMessageContaining("DATETIME")
            }
        }
    }

    @Test
    fun `a typo, a prefixed offset, seconds or an offset out of range is refused`() {
        listOf("America/Limaa", "GMT+5", "UTC-5", "+05:00:30", "+050030", "+5", "+5:00", "+19:00", "+05:60", "05:00").forEach { name ->
            assertThatThrownBy { FieldTimeZones.checked(FieldType.DATETIME, name) }
                .describedAs(name)
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Unknown time zone")
        }
    }
}
