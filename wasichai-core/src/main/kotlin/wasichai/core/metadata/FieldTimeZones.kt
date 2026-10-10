package wasichai.core.metadata

import wasichai.core.common.ValidationException
import java.time.ZoneId

/**
 * A `DATETIME` field's own zone (issue 91, ADR-063): an IANA region name such as `America/Lima`.
 * The value stays an instant; the zone only says whose wall clock it is read in. Nothing converts.
 */
object FieldTimeZones {
    private const val PROPERTY = "timeZone"

    // blank is no zone. a region only: "+05:00" or "GMT+5" carry no rules, so they are not a place's zone
    fun checked(
        type: FieldType,
        raw: String?
    ): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (type != FieldType.DATETIME) {
            throw ValidationException("Only DATETIME fields have a time zone", PROPERTY, "a ${type.name} field has no time zone")
        }
        if (name !in ZoneId.getAvailableZoneIds()) {
            throw ValidationException("Unknown time zone '$name'", PROPERTY, "must be an IANA time zone name, e.g. America/Lima")
        }
        return name
    }
}
