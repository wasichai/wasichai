package wasichai.notifications

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

// ---- the public contract (spec B). apps build these. ----

enum class NotificationKind { INFO, WARNING, ACTION }

sealed interface Audience {
    data object All : Audience

    data class User(
        val id: UUID
    ) : Audience

    // caja: a cashier is an email on its records
    data class Email(
        val email: String
    ) : Audience

    data class Role(
        val name: String
    ) : Audience

    // reaches the unit's whole subtree
    data class Unit(
        val code: String
    ) : Audience
}

sealed interface NotificationLink {
    data class Record(
        val objectName: String,
        val recordId: UUID,
        val tab: String? = null
    ) : NotificationLink

    data class Route(
        val route: String,
        val params: Map<String, String> = emptyMap(),
        val tab: String? = null
    ) : NotificationLink

    data class Url(
        val url: String
    ) : NotificationLink
}

data class NotificationDraft(
    val kind: NotificationKind,
    val title: String,
    val audience: List<Audience>,
    val body: String? = null,
    val link: NotificationLink? = null,
    // stable id inside the source; with one, publish is an upsert
    val key: String? = null,
    val publishAt: Instant? = null,
    val expiresAt: Instant? = null,
    val dueAt: Instant? = null
)

// sources the module writes itself. an app's source never takes these names.
object Sources {
    const val MANUAL = "manual"
    const val RULE_PREFIX = "rule:"

    fun rule(name: String): String = RULE_PREFIX + name

    // an automation's NOTIFY action (ADR-060)
    const val AUTOMATION_PREFIX = "automation:"

    fun automation(name: String): String = AUTOMATION_PREFIX + name

    // the loop's own run keys (notification_source_runs, its locks): not a source, and nobody publishes under them
    const val PURGE = "purge"
    const val RULES = "rules"
    const val DELIVERIES = "deliveries"

    fun isOwnedByModule(source: String): Boolean = source == MANUAL || source.startsWith(RULE_PREFIX) || source.startsWith(AUTOMATION_PREFIX)

    fun isLoopKey(source: String): Boolean = source == PURGE || source == RULES || source == DELIVERIES

    // anything else is "of a source": not edited or deleted over REST, its ACTIONs not dismissed (the app resolves them)
    fun isManual(source: String): Boolean = source == MANUAL
}

// ---- what is stored ----

enum class TargetType { ALL, USER, ROLE, UNIT }

// a resolved audience: one row of notification_targets. EMAIL is already a USER here.
data class StoredTarget(
    val type: TargetType,
    val userId: UUID?,
    val roleName: String?,
    val unitId: UUID?
) : Comparable<StoredTarget> {
    init {
        // same shape as the table's check constraint
        require((type == TargetType.USER) == (userId != null)) { "a $type target has a user id only when it is USER" }
        require((type == TargetType.ROLE) == (roleName != null)) { "a $type target has a role name only when it is ROLE" }
        require((type == TargetType.UNIT) == (unitId != null)) { "a $type target has a unit id only when it is UNIT" }
    }

    // stable order: fingerprint must not depend on audience order
    override fun compareTo(other: StoredTarget): Int = ORDER.compare(this, other)

    companion object {
        private val ORDER =
            compareBy<StoredTarget>({ it.type.name }, { it.userId?.toString() }, { it.roleName }, { it.unitId?.toString() })

        fun all(): StoredTarget = StoredTarget(TargetType.ALL, null, null, null)

        fun user(id: UUID): StoredTarget = StoredTarget(TargetType.USER, id, null, null)

        fun role(name: String): StoredTarget = StoredTarget(TargetType.ROLE, null, name, null)

        fun unit(id: UUID): StoredTarget = StoredTarget(TargetType.UNIT, null, null, id)
    }
}

// a validated, normalised draft with its recipients resolved: ready to insert or compare
data class PreparedNotification(
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    val link: NotificationLink?,
    // the object a RECORD link opens (link_object_id)
    val linkObjectId: UUID?,
    // sorted, distinct
    val targets: List<StoredTarget>,
    val key: String?,
    val publishAt: Instant?,
    val expiresAt: Instant?,
    val dueAt: Instant?,
    val fingerprint: String
)

// ---- wire shapes (REST and the link jsonb) ----

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LinkJson(
    val type: String,
    @param:JsonProperty("object") @get:JsonProperty("object") val objectName: String? = null,
    val recordId: UUID? = null,
    val tab: String? = null,
    val route: String? = null,
    val params: Map<String, String>? = null,
    val url: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AudienceJson(
    val type: String,
    val value: String? = null
)

fun NotificationLink.toJson(): LinkJson =
    when (this) {
        is NotificationLink.Record -> LinkJson(LINK_RECORD, objectName = objectName, recordId = recordId, tab = tab)
        is NotificationLink.Route -> LinkJson(LINK_ROUTE, route = route, params = params, tab = tab)
        is NotificationLink.Url -> LinkJson(LINK_URL, url = url)
    }

// throws ValidationException naming link.<part>
fun LinkJson.toLink(field: String = "link"): NotificationLink {
    val violations = mutableListOf<FieldViolation>()
    return toLink(field, violations) ?: throw ValidationException("Invalid notification", violations)
}

// null with the reason in violations: REST collects every problem of a body at once
fun LinkJson.toLink(
    field: String,
    violations: MutableList<FieldViolation>
): NotificationLink? {
    fun missing(part: String): Nothing? {
        violations += FieldViolation("$field.$part", "is required for a $type link")
        return null
    }
    return when (type) {
        LINK_RECORD -> {
            if (objectName == null) return missing("object")
            if (recordId == null) return missing("recordId")
            NotificationLink.Record(objectName, recordId, tab)
        }
        LINK_ROUTE -> route?.let { NotificationLink.Route(it, params.orEmpty(), tab) } ?: missing("route")
        LINK_URL -> url?.let { NotificationLink.Url(it) } ?: missing("url")
        else -> {
            violations += FieldViolation("$field.type", "must be one of $LINK_RECORD, $LINK_ROUTE, $LINK_URL")
            null
        }
    }
}

fun Audience.toJson(): AudienceJson =
    when (this) {
        Audience.All -> AudienceJson(AUDIENCE_ALL)
        is Audience.User -> AudienceJson(AUDIENCE_USER, id.toString())
        is Audience.Email -> AudienceJson(AUDIENCE_EMAIL, email)
        is Audience.Role -> AudienceJson(AUDIENCE_ROLE, name)
        is Audience.Unit -> AudienceJson(AUDIENCE_UNIT, code)
    }

// throws ValidationException naming the field
fun AudienceJson.toAudience(field: String = "audience"): Audience {
    val violations = mutableListOf<FieldViolation>()
    return toAudience(field, violations) ?: throw ValidationException("Invalid notification", violations)
}

fun AudienceJson.toAudience(
    field: String,
    violations: MutableList<FieldViolation>
): Audience? {
    fun refuse(reason: String): Audience? {
        violations += FieldViolation(field, reason)
        return null
    }
    if (type == AUDIENCE_ALL) return Audience.All
    if (type !in AUDIENCE_TYPES) return refuse("type must be one of ${AUDIENCE_TYPES.joinToString()}")
    val v = value
    if (v.isNullOrBlank()) return refuse("a $type audience needs a value")
    return when (type) {
        AUDIENCE_USER -> runCatching { UUID.fromString(v.trim()) }.getOrNull()?.let { Audience.User(it) } ?: refuse("value must be a user id")
        AUDIENCE_EMAIL -> Audience.Email(v)
        AUDIENCE_ROLE -> Audience.Role(v)
        else -> Audience.Unit(v)
    }
}

// a REST body's audience. every bad entry is named audience[i].
fun List<AudienceJson>.toAudiences(field: String = "audience"): List<Audience> {
    val violations = mutableListOf<FieldViolation>()
    val audience = mapIndexedNotNull { i, json -> json.toAudience("$field[$i]", violations) }
    if (violations.isNotEmpty()) throw ValidationException("Invalid notification", violations)
    return audience
}

// the inbox drops a RECORD link its reader may not open
internal const val LINK_RECORD = "RECORD"
private const val LINK_ROUTE = "ROUTE"
private const val LINK_URL = "URL"

private const val AUDIENCE_ALL = "ALL"
private const val AUDIENCE_USER = "USER"
private const val AUDIENCE_EMAIL = "EMAIL"
private const val AUDIENCE_ROLE = "ROLE"
private const val AUDIENCE_UNIT = "UNIT"
private val AUDIENCE_TYPES = listOf(AUDIENCE_ALL, AUDIENCE_USER, AUDIENCE_EMAIL, AUDIENCE_ROLE, AUDIENCE_UNIT)

// ---- what a person reads ----

data class KindCount(
    val active: Long,
    val unread: Long,
    val overdue: Long
) {
    companion object {
        val ZERO = KindCount(0, 0, 0)
    }
}

data class LatestNotification(
    val id: UUID,
    val kind: NotificationKind,
    val title: String,
    val publishAt: Instant,
    // so the UI can link a toast. READ filtering is the caller's, as for inbox items
    val link: LinkJson? = null,
    // what that filter needs; never on the wire
    @get:JsonIgnore val linkObjectId: UUID? = null
)

data class NotificationSummary(
    // every kind, in enum order: the UI reads kinds.ACTION without a null check
    val kinds: Map<NotificationKind, KindCount>,
    val latest: LatestNotification?
) {
    init {
        require(kinds.keys == NotificationKind.entries.toSet()) { "a summary counts every kind" }
    }

    companion object {
        // missing kinds count zero
        fun of(
            counts: Map<NotificationKind, KindCount>,
            latest: LatestNotification?
        ): NotificationSummary = NotificationSummary(NotificationKind.entries.associateWith { counts[it] ?: KindCount.ZERO }, latest)
    }
}

data class InboxItem(
    val id: UUID,
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    // null too when the reader may not open the object
    val link: LinkJson?,
    val publishAt: Instant,
    val expiresAt: Instant?,
    val dueAt: Instant?,
    val overdue: Boolean,
    val source: String,
    val read: Boolean,
    val snoozedUntil: Instant?,
    val dismissible: Boolean
)
