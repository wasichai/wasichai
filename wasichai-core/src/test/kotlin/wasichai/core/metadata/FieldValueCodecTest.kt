package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.math.BigDecimal
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
}
