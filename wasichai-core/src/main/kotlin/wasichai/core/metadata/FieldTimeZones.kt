package wasichai.core.metadata

import wasichai.core.common.ValidationException
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * A `DATETIME` field's own zone (issue 91, ADR-063, ADR-064): an IANA region name such as `America/Lima`, in any
 * case, or a fixed UTC offset. Stored and answered in one spelling, so clients compare and show the same text.
 * The value stays an instant; the zone only says whose wall clock it is read in. Nothing converts.
 */
object FieldTimeZones {
    private const val PROPERTY = "timeZone"

    // ±HH, ±HHMM, ±HH:MM. no seconds, no GMT/UTC prefix: one shape, the one Intl.DateTimeFormat takes
    private val OFFSET = Regex("^[+-]\\d{2}(:?\\d{2})?$")

    // lowercase -> canonical. the tzdb ids have no two that differ only in case
    private val REGIONS: Map<String, String> by lazy { ZoneId.getAvailableZoneIds().associateBy { it.lowercase(Locale.ROOT) } }

    // blank is no zone
    fun checked(
        type: FieldType,
        raw: String?
    ): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (type != FieldType.DATETIME) {
            throw ValidationException("Only DATETIME fields have a time zone", PROPERTY, "a ${type.name} field has no time zone")
        }
        return offset(name) ?: REGIONS[name.lowercase(Locale.ROOT)]
            ?: throw ValidationException(
                "Unknown time zone '$name'",
                PROPERTY,
                "must be an IANA time zone name, e.g. America/Lima, or a UTC offset, e.g. -05:00"
            )
    }

    // "-0500" -> "-05:00". zero is "+00:00", never "Z", so every offset reads ±HH:MM
    private fun offset(name: String): String? {
        if (name.equals("Z", ignoreCase = true)) return "+00:00"
        if (!OFFSET.matches(name)) return null
        val offset =
            try {
                ZoneOffset.of(name)
            } catch (_: DateTimeException) {
                return null
            }
        return if (offset == ZoneOffset.UTC) "+00:00" else offset.id
    }
}
