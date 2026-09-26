package wasichai.agent

import wasichai.core.common.ValidationException
import java.util.UUID

// the model may ask for a thousand rows. it gets fifty.
const val MAX_TOOL_LIMIT = 50
const val DEFAULT_TOOL_LIMIT = 20

// tool arguments arrive as a parsed json object. read them as values; never string-match the payload.
object AgentToolInput {
    fun string(
        input: Map<String, Any?>,
        name: String
    ): String =
        optionalString(input, name)
            ?: throw ValidationException("Tool argument '$name' is required", name, "is required")

    fun optionalString(
        input: Map<String, Any?>,
        name: String
    ): String? =
        when (val raw = input[name]) {
            null -> null
            is String -> raw.trim().ifEmpty { null }
            is Number, is Boolean -> raw.toString()
            else -> null
        }

    fun uuid(
        input: Map<String, Any?>,
        name: String
    ): UUID {
        val raw = string(input, name)
        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("Tool argument '$name' is not a record id", name, "must be a uuid")
        }
    }

    // whatever the model asks for, the cap wins
    fun limit(input: Map<String, Any?>): Int {
        val raw = input["limit"]
        val asked =
            when (raw) {
                null -> DEFAULT_TOOL_LIMIT
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: DEFAULT_TOOL_LIMIT
                else -> DEFAULT_TOOL_LIMIT
            }
        return asked.coerceIn(1, MAX_TOOL_LIMIT)
    }

    fun descending(input: Map<String, Any?>): Boolean = optionalString(input, "direction").equals("desc", ignoreCase = true)

    // {"filters": {"estado": "activo"}} -> equality filters, exactly what the REST api accepts
    fun filters(input: Map<String, Any?>): Map<String, String> {
        val raw = input["filters"] as? Map<*, *> ?: return emptyMap()
        return raw
            .mapNotNull { (key, value) ->
                val name = (key as? String)?.trim().orEmpty()
                if (name.isEmpty() || value == null) null else name to value.toString()
            }.toMap()
    }

    // passed along as sent: the gis module's query contributor parses it, and refuses a broken one
    fun bbox(input: Map<String, Any?>): String? = optionalString(input, "bbox")
}
