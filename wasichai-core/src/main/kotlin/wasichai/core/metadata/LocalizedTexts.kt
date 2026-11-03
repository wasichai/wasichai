package wasichai.core.metadata

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import wasichai.core.common.ValidationException

// a text per language tag beside a single-language one ({es: "Código", en: "Code"}). the server keeps the maps and
// hands them out; the client picks its language's, else the single text (ADR-070, ADR-078)
typealias LocalizedText = Map<String, String>

object LocalizedTexts {
    // a BCP 47 language tag, as config.languages and the user's locale preference hold it: es, en, pt-BR
    val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    // a request's map: keys checked (400 on [property]), texts trimmed, a blank text drops its language
    fun checked(
        raw: Map<String, String?>?,
        property: String
    ): LocalizedText =
        raw
            .orEmpty()
            .mapNotNull { (tag, text) ->
                val language = tag.trim()
                if (!LANGUAGE_TAG.matches(language)) {
                    throw ValidationException("Invalid language '$tag'", property, "language keys must match ${LANGUAGE_TAG.pattern}")
                }
                text?.trim()?.ifBlank { null }?.let { language to it }
            }.toMap()

    // an enum field's texts per option: every key an option of the field, each map checked like any other. an option
    // left with no text is dropped, so the stored map only holds what someone wrote
    fun checkedOptions(
        raw: Map<String, Map<String, String?>?>?,
        options: List<String>?,
        property: String
    ): Map<String, LocalizedText> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val known = options.orEmpty().toSet()
        return raw
            .mapNotNull { (option, texts) ->
                val value = option.trim()
                if (value !in known) {
                    throw ValidationException("Unknown option '$option'", property, "keys must be options of the field")
                }
                checked(texts, property).takeIf { it.isNotEmpty() }?.let { value to it }
            }.toMap()
    }
}

// what a metadata row keeps in its translations column: property -> language -> text, and for enum options
// option -> language -> text. empty maps are left out, so a row with none stores {}
internal class TranslationsColumn(
    private val objectMapper: ObjectMapper
) {
    fun write(texts: Map<String, Map<String, Any>>): String = objectMapper.writeValueAsString(texts.filterValues { it.isNotEmpty() })

    fun read(json: String?): Translations =
        Translations(json?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Map<String, Any?>>>() {}) } ?: emptyMap())
}

internal class Translations(
    private val raw: Map<String, Map<String, Any?>>
) {
    fun texts(property: String): LocalizedText = textsOf(raw[property])

    fun options(property: String): Map<String, LocalizedText> =
        raw[property].orEmpty().mapValues { (_, texts) -> textsOf(texts as? Map<*, *>) }.filterValues { it.isNotEmpty() }

    private fun textsOf(map: Map<*, *>?): LocalizedText =
        map
            .orEmpty()
            .entries
            .mapNotNull { (language, text) -> if (language is String && text is String) language to text else null }
            .toMap()
}
