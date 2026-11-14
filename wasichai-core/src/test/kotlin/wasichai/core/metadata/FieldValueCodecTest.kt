package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

class FieldValueCodecTest {
    private fun field(
        type: FieldType,
        required: Boolean = false,
        enumOptions: List<String>? = null
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = UUID.randomUUID(),
        name = "campo",
        label = "Campo",
        type = type,
        columnName = "campo",
        required = required,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = enumOptions,
        relationTargetObjectId = null,
        visible = true,
        editable = true
    )

    @Test
    fun `converts decimals from numbers and strings alike`() {
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DECIMAL), 850.5))
            .isEqualTo(BigDecimal("850.5"))
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DECIMAL), "1250.75"))
            .isEqualTo(BigDecimal("1250.75"))
    }

    @Test
    fun `rejects a decimal that is not a number`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.DECIMAL), "mucho") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `enforces enum options`() {
        val uso = field(FieldType.ENUM, enumOptions = listOf("RESIDENCIAL", "COMERCIAL"))
        assertThat(FieldValueCodec.toDatabase(uso, "COMERCIAL")).isEqualTo("COMERCIAL")
        assertThatThrownBy { FieldValueCodec.toDatabase(uso, "INDUSTRIAL") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `required fields reject null`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.TEXT, required = true), null) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.TEXT), null)).isNull()
    }

    @Test
    fun `validates emails and urls`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.EMAIL), "nope") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.URL), "ftp://x") }
            .isInstanceOf(ValidationException::class.java)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.EMAIL), "a@b.com")).isEqualTo("a@b.com")
    }

    // a type core does not know never reaches the codec. if it does, it is refused, not guessed.
    @Test
    fun `a type core does not know is refused`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType("SHAPE")), "{}") }
            .isInstanceOf(ValidationException::class.java)
    }

    // server code builds values as kotlin objects: the stored type itself is taken as is (issue 167)
    @Test
    fun `a UUID or RELATION field takes a java UUID as well as its text`() {
        val id = UUID.randomUUID()
        assertThat(FieldValueCodec.toDatabase(field(FieldType.UUID), id)).isEqualTo(id)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.RELATION), id)).isEqualTo(id)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.RELATION), id.toString())).isEqualTo(id)
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.RELATION), 42) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("campo")
    }

    @Test
    fun `a DATE field takes a LocalDate as well as its ISO text`() {
        val day = LocalDate.of(2026, 11, 13)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DATE), day)).isEqualTo(day)
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DATE), "2026-11-13")).isEqualTo(day)
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.DATE), Instant.parse("2026-11-13T00:00:00Z")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a DATETIME field takes an OffsetDateTime, a ZonedDateTime or an Instant`() {
        val offset = OffsetDateTime.of(2026, 11, 13, 9, 30, 0, 0, ZoneOffset.ofHours(-5))
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DATETIME), offset)).isEqualTo(offset)
        val zoned = ZonedDateTime.of(2026, 11, 13, 9, 30, 0, 0, ZoneId.of("America/Lima"))
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DATETIME), zoned)).isEqualTo(zoned.toOffsetDateTime())
        val instant = Instant.parse("2026-11-13T14:30:00Z")
        val stored = FieldValueCodec.toDatabase(field(FieldType.DATETIME), instant) as OffsetDateTime
        assertThat(stored.toInstant()).isEqualTo(instant)
        // read back as the instant it is, the same text a string write gives
        assertThat(FieldValueCodec.fromDatabase(stored)).isEqualTo("2026-11-13T14:30:00Z")
        assertThat(FieldValueCodec.toDatabase(field(FieldType.DATETIME), "2026-11-13T09:30:00-05:00")).isEqualTo(offset)
    }

    // a wall time with no offset names no instant: guessing a zone would store the wrong moment
    @Test
    fun `a DATETIME field refuses a LocalDateTime`() {
        assertThatThrownBy { FieldValueCodec.toDatabase(field(FieldType.DATETIME), LocalDateTime.of(2026, 11, 13, 9, 30)) }
            .isInstanceOf(ValidationException::class.java)
    }
}
