package wasichai.core.metadata

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException

// issue 155: per-language texts of objects, fields and relationships (ADR-078)
class LocalizedTextsTest {
    @Test
    fun `keeps language tags, trims texts and drops a blank one`() {
        val texts = LocalizedTexts.checked(mapOf("es" to " Código ", "en" to "Code", "pt-BR" to "Código", "fr" to "  ", "de" to null), "labels")

        assertThat(texts).isEqualTo(mapOf("es" to "Código", "en" to "Code", "pt-BR" to "Código"))
    }

    @Test
    fun `no map is no texts`() {
        assertThat(LocalizedTexts.checked(null, "labels")).isEmpty()
    }

    @Test
    fun `a key that is not a language tag is a 400 on its property`() {
        listOf("EN", "english", "e", "es_PE", "").forEach { tag ->
            val error = assertThrows<ValidationException> { LocalizedTexts.checked(mapOf(tag to "x"), "pluralLabels") }
            assertThat(error.violations.single().field).isEqualTo("pluralLabels")
        }
    }

    @Test
    fun `option texts need an option of the field, and an option left with no text is dropped`() {
        val options = listOf("LECTURA", "VIDEO")
        val texts = LocalizedTexts.checkedOptions(mapOf("LECTURA" to mapOf("en" to "Reading"), "VIDEO" to mapOf("en" to " ")), options, "enumOptionLabels")

        assertThat(texts).isEqualTo(mapOf("LECTURA" to mapOf("en" to "Reading")))

        val unknown = assertThrows<ValidationException> { LocalizedTexts.checkedOptions(mapOf("AUDIO" to mapOf("en" to "Audio")), options, "enumOptionLabels") }
        assertThat(unknown.violations.single().field).isEqualTo("enumOptionLabels")
        val bad = assertThrows<ValidationException> { LocalizedTexts.checkedOptions(mapOf("VIDEO" to mapOf("EN" to "Video")), options, "enumOptionLabels") }
        assertThat(bad.violations.single().field).isEqualTo("enumOptionLabels")
    }

    @Test
    fun `a field with no options takes no option texts`() {
        assertThrows<ValidationException> { LocalizedTexts.checkedOptions(mapOf("A" to mapOf("en" to "A")), null, "enumOptionLabels") }
        assertThat(LocalizedTexts.checkedOptions(emptyMap(), null, "enumOptionLabels")).isEmpty()
    }

    @Test
    fun `the column stores only what someone wrote and reads back the same, a row stored before reads empty`() {
        val column = TranslationsColumn(JsonMapper())
        val json =
            column.write(
                mapOf(
                    "labels" to mapOf("en" to "Code"),
                    "descriptions" to emptyMap(),
                    "enumOptions" to mapOf("LECTURA" to mapOf("en" to "Reading"))
                )
            )

        assertThat(json).doesNotContain("descriptions")
        val read = column.read(json)
        assertThat(read.texts("labels")).isEqualTo(mapOf("en" to "Code"))
        assertThat(read.texts("descriptions")).isEmpty()
        assertThat(read.options("enumOptions")).isEqualTo(mapOf("LECTURA" to mapOf("en" to "Reading")))
        assertThat(column.read("{}").texts("labels")).isEmpty()
        assertThat(column.read(null).options("enumOptions")).isEmpty()
    }
}
