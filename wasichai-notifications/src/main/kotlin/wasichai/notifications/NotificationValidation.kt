package wasichai.notifications

import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// a draft that passed every format rule (spec B, Validation), normalised.
// recipients and the link's object still need the database (AudienceResolver, MetadataService).
internal data class NormalizedDraft(
    val kind: NotificationKind,
    val title: String,
    val body: String?,
    val link: NotificationLink?,
    val audience: List<Audience>,
    val key: String?,
    val publishAt: Instant?,
    val expiresAt: Instant?,
    val dueAt: Instant?
) {
    fun prepare(
        targets: Collection<StoredTarget>,
        linkObjectId: UUID?
    ): PreparedNotification {
        val sorted = targets.distinct().sorted()
        return PreparedNotification(
            kind = kind,
            title = title,
            body = body,
            link = link,
            linkObjectId = linkObjectId,
            targets = sorted,
            key = key,
            publishAt = publishAt,
            expiresAt = expiresAt,
            dueAt = dueAt,
            fingerprint = fingerprint(kind, title, body, link, publishAt, expiresAt, dueAt, sorted)
        )
    }
}

internal data class DraftCheck(
    // null when anything is wrong
    val draft: NormalizedDraft?,
    val violations: List<FieldViolation>
) {
    // REST: 400 naming every field
    fun orThrow(): NormalizedDraft = draft ?: throw ValidationException(MESSAGE, violations)

    // Kotlin: a malformed draft is a bug in the app
    fun orThrowIllegalArgument(): NormalizedDraft =
        draft ?: throw IllegalArgumentException("$MESSAGE: " + violations.joinToString("; ") { "${it.field} ${it.message}" })

    companion object {
        const val MESSAGE = "Invalid notification"
    }
}

internal object NotificationValidation {
    const val TITLE_MAX = 200
    const val BODY_MAX = 4000
    const val ROUTE_PARAMS_MAX = 10
    const val ROUTE_PARAM_VALUE_MAX = 200
    const val URL_MAX = 2000
    private const val ELLIPSIS = "\u2026"

    private val SOURCE = Regex("^[a-z][a-z0-9_.:-]{1,80}$")
    private val KEY = Regex("^[A-Za-z0-9_.:/-]{1,200}$")

    // core's role name format (AdminService) and the org_units code check
    private val ROLE = Regex("^[A-Z][A-Z0-9_]{1,48}$")
    private val UNIT = Regex("^[A-Z][A-Z0-9_]{1,48}$")
    private val ROUTE = Regex("^[a-z][a-z0-9-]*:[A-Za-z0-9_.-]+$")
    private val PARAM_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")

    // the TAB key format (spec E)
    private val TAB = Regex("^[A-Z][A-Z0-9_]{0,39}$")

    // source and draft together. allowReservedSource: the module writing its own manual or rule: rows.
    // cutLongText: Kotlin publish. wording never aborts a business transaction, so a long title or body is cut.
    fun check(
        source: String,
        draft: NotificationDraft,
        now: Instant,
        allowReservedSource: Boolean = false,
        cutLongText: Boolean = false
    ): DraftCheck {
        val sourceViolations = checkSource(source, allowReservedSource)
        val result = checkDraft(draft, now, cutLongText)
        if (sourceViolations.isEmpty()) return result
        return DraftCheck(null, sourceViolations + result.violations)
    }

    fun checkSource(
        source: String,
        allowReserved: Boolean = false
    ): List<FieldViolation> =
        when {
            !SOURCE.matches(source) -> listOf(FieldViolation("source", "must match ${SOURCE.pattern}"))
            !allowReserved && Sources.isOwnedByModule(source) ->
                listOf(
                    FieldViolation(
                        "source",
                        "'${Sources.MANUAL}', '${Sources.RULE_PREFIX}…' and '${Sources.AUTOMATION_PREFIX}…' belong to the notifications module"
                    )
                )
            // reserved even for the module: a notification under a loop key would share its run row and lock
            Sources.isLoopKey(source) ->
                listOf(
                    FieldViolation("source", "'${Sources.PURGE}', '${Sources.RULES}' and '${Sources.DELIVERIES}' are the notifications loop's own keys")
                )
            else -> emptyList()
        }

    fun checkKey(key: String): List<FieldViolation> = if (KEY.matches(key)) emptyList() else listOf(FieldViolation("key", "must match ${KEY.pattern}"))

    fun checkDraft(
        draft: NotificationDraft,
        now: Instant,
        cutLongText: Boolean = false
    ): DraftCheck {
        val violations = mutableListOf<FieldViolation>()

        val title = draft.title.trim().let { if (cutLongText) it.cut(TITLE_MAX) else it }
        if (title.charCount() !in 1..TITLE_MAX) violations += FieldViolation("title", "must be 1 to $TITLE_MAX characters")

        val body = draft.body?.let { if (cutLongText) it.cut(BODY_MAX) else it }
        if (body != null && body.charCount() > BODY_MAX) violations += FieldViolation("body", "must be at most $BODY_MAX characters")

        if (draft.key != null) violations += checkKey(draft.key)

        val audience = normalizeAudience(draft.audience, violations)
        val link = draft.link?.let { normalizeLink(it, violations) }

        // postgres keeps microseconds: a stored time then equals the draft's, fingerprint included
        val publishAt = draft.publishAt?.truncatedTo(ChronoUnit.MICROS)
        val expiresAt = draft.expiresAt?.truncatedTo(ChronoUnit.MICROS)
        val dueAt = draft.dueAt?.truncatedTo(ChronoUnit.MICROS)
        if (expiresAt != null && expiresAt <= (publishAt ?: now)) {
            violations += FieldViolation("expiresAt", if (publishAt == null) "must be in the future" else "must be after publishAt")
        }

        if (violations.isNotEmpty()) return DraftCheck(null, violations)
        return DraftCheck(NormalizedDraft(draft.kind, title, body, link, audience, draft.key, publishAt, expiresAt, dueAt), emptyList())
    }

    // roles and units upper case, emails lower case. bad entries named audience[i].
    fun normalizeAudience(
        audience: List<Audience>,
        violations: MutableList<FieldViolation>
    ): List<Audience> {
        if (audience.isEmpty()) {
            violations += FieldViolation("audience", "must not be empty")
            return emptyList()
        }
        return audience.mapIndexedNotNull { i, entry ->
            val field = "audience[$i]"
            when (entry) {
                Audience.All, is Audience.User -> entry
                is Audience.Email ->
                    entry.email
                        .trim()
                        .lowercase()
                        .takeIf { it.isNotEmpty() }
                        ?.let { Audience.Email(it) }
                        .also { if (it == null) violations += FieldViolation(field, "email must not be blank") }
                is Audience.Role ->
                    entry.name
                        .trim()
                        .uppercase()
                        .takeIf { ROLE.matches(it) }
                        ?.let { Audience.Role(it) }
                        .also { if (it == null) violations += FieldViolation(field, "role name must match ${ROLE.pattern}") }
                is Audience.Unit ->
                    entry.code
                        .trim()
                        .uppercase()
                        .takeIf { UNIT.matches(it) }
                        ?.let { Audience.Unit(it) }
                        .also { if (it == null) violations += FieldViolation(field, "unit code must match ${UNIT.pattern}") }
            }
        }
    }

    // format only. whether a RECORD's object exists is the caller's (it needs the organization).
    fun normalizeLink(
        link: NotificationLink,
        violations: MutableList<FieldViolation>
    ): NotificationLink? {
        val before = violations.size
        val normalized =
            when (link) {
                is NotificationLink.Record -> {
                    val objectName = link.objectName.trim()
                    if (objectName.isEmpty()) violations += FieldViolation("link.object", "must not be blank")
                    link.copy(objectName = objectName, tab = normalizeTab(link.tab, violations))
                }
                is NotificationLink.Route -> {
                    checkRoute(link, violations)
                    link.copy(tab = normalizeTab(link.tab, violations))
                }
                is NotificationLink.Url -> {
                    checkUrl(link.url, violations)
                    link
                }
            }
        return normalized.takeIf { violations.size == before }
    }

    // trimmed, upper-cased. blank means no tab.
    fun normalizeTab(
        tab: String?,
        violations: MutableList<FieldViolation>
    ): String? {
        val normalized = tab?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (!TAB.matches(normalized)) violations += FieldViolation("link.tab", "must match ${TAB.pattern}")
        return normalized
    }

    private fun checkRoute(
        link: NotificationLink.Route,
        violations: MutableList<FieldViolation>
    ) {
        if (!ROUTE.matches(link.route)) violations += FieldViolation("link.route", "must match ${ROUTE.pattern}")
        if (link.params.size > ROUTE_PARAMS_MAX) {
            violations += FieldViolation("link.params", "at most $ROUTE_PARAMS_MAX params")
            return
        }
        link.params.forEach { (key, value) ->
            when {
                !PARAM_KEY.matches(key) -> violations += FieldViolation("link.params.$key", "key must match ${PARAM_KEY.pattern}")
                value.charCount() > ROUTE_PARAM_VALUE_MAX ->
                    violations +=
                        FieldViolation("link.params.$key", "must be at most $ROUTE_PARAM_VALUE_MAX characters")
            }
        }
    }

    private fun checkUrl(
        url: String,
        violations: MutableList<FieldViolation>
    ) {
        if (url.length > URL_MAX) {
            violations += FieldViolation("link.url", "must be at most $URL_MAX characters")
            return
        }
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        // no host: "https:///x" or a relative path would open inside the app
        if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
            violations += FieldViolation("link.url", "must be an absolute http or https url")
        }
    }

    // characters as postgres counts them (code points), not UTF-16 units
    private fun String.charCount(): Int = codePointCount(0, length)

    // at most max code points, the last one "…". never splits a surrogate pair
    private fun String.cut(max: Int): String {
        if (charCount() <= max) return this
        return substring(0, offsetByCodePoints(0, max - 1)) + ELLIPSIS
    }
}
