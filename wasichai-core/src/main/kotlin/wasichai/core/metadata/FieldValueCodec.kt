package wasichai.core.metadata

import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// json value -> db value for the core types, with validation
object FieldValueCodec {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun javaType(type: FieldType): Class<*> =
        when (type) {
            FieldType.INTEGER -> java.lang.Long::class.java
            FieldType.DECIMAL -> BigDecimal::class.java
            FieldType.BOOLEAN -> java.lang.Boolean::class.java
            FieldType.DATE -> LocalDate::class.java
            FieldType.DATETIME -> OffsetDateTime::class.java
            FieldType.UUID, FieldType.RELATION -> UUID::class.java
            else -> String::class.java
        }

    fun toDatabase(
        field: CustomField,
        value: Any?
    ): Any? {
        if (value == null) {
            if (field.required) throw ValidationException("Missing value", field.name, "is required")
            return null
        }
        return when (field.type) {
            FieldType.TEXT, FieldType.LONG_TEXT -> text(field, value)
            FieldType.EMAIL ->
                text(field, value).also {
                    if (!EMAIL.matches(it)) throw ValidationException("Invalid email", field.name, "is not an email")
                }
            FieldType.URL ->
                text(field, value).also {
                    if (!it.startsWith("http://") && !it.startsWith("https://")) {
                        throw ValidationException("Invalid URL", field.name, "must start with http:// or https://")
                    }
                }
            FieldType.ENUM ->
                text(field, value).also {
                    val options = field.enumOptions.orEmpty()
                    if (it !in options) {
                        throw ValidationException("Invalid option", field.name, "must be one of ${options.joinToString(", ")}")
                    }
                }
            FieldType.INTEGER ->
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: reject(field, "is not an integer")
                    else -> reject(field, "is not an integer")
                }
            FieldType.DECIMAL ->
                when (value) {
                    is BigDecimal -> value
                    is Number -> BigDecimal(value.toString())
                    is String -> value.toBigDecimalOrNull() ?: reject(field, "is not a number")
                    else -> reject(field, "is not a number")
                }
            FieldType.BOOLEAN ->
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: reject(field, "is not a boolean")
                    else -> reject(field, "is not a boolean")
                }
            FieldType.DATE -> runCatching { LocalDate.parse(text(field, value)) }.getOrElse { reject(field, "is not an ISO date") }
            FieldType.DATETIME ->
                runCatching { OffsetDateTime.parse(text(field, value)) }
                    .getOrElse { reject(field, "is not an ISO date-time") }
            FieldType.UUID, FieldType.RELATION ->
                runCatching { UUID.fromString(text(field, value)) }.getOrElse { reject(field, "is not a UUID") }
            // a module type goes through its own handler, never here
            else -> reject(field, "is not a core field type")
        }
    }

    // db value -> json friendly. dates as iso strings so output does not depend on mapper config.
    fun fromDatabase(value: Any?): Any? =
        when (value) {
            null -> null
            is LocalDate -> value.toString()
            is OffsetDateTime -> value.toInstant().toString()
            is UUID -> value.toString()
            else -> value
        }

    private fun text(
        field: CustomField,
        value: Any?
    ): String = value as? String ?: reject(field, "must be a string")

    private fun reject(
        field: CustomField,
        reason: String
    ): Nothing = throw ValidationException("Invalid value for '${field.name}'", field.name, reason)
}
