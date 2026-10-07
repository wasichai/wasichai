package wasichai.notifications

import java.security.MessageDigest
import java.time.Instant

// SHA-256 hex over a canonical rendering: same content, same hash; no write when nothing changed.
// every value is length-prefixed, so "ab"+"c" never equals "a"+"bc"; null is its own token, not "".
// targets and route params are sorted: audience order means nothing.
internal fun fingerprint(
    kind: NotificationKind,
    title: String,
    body: String?,
    link: NotificationLink?,
    publishAt: Instant?,
    expiresAt: Instant?,
    dueAt: Instant?,
    targets: Collection<StoredTarget>
): String {
    val out = Canonical()
    out.put("kind", kind.name)
    out.put("title", title)
    out.put("body", body)
    when (link) {
        null -> out.put("link", null)
        is NotificationLink.Record -> {
            out.put("link", "RECORD")
            out.put("object", link.objectName)
            out.put("recordId", link.recordId.toString())
            out.put("tab", link.tab)
        }
        is NotificationLink.Route -> {
            out.put("link", "ROUTE")
            out.put("route", link.route)
            out.put("params", link.params.size.toString())
            link.params.toSortedMap().forEach { (key, value) ->
                out.put("param", key)
                out.put("value", value)
            }
            out.put("tab", link.tab)
        }
        is NotificationLink.Url -> {
            out.put("link", "URL")
            out.put("url", link.url)
        }
    }
    // the draft's own publishAt: null stays null, so the hash does not move with the clock
    out.put("publishAt", publishAt?.toString())
    out.put("expiresAt", expiresAt?.toString())
    out.put("dueAt", dueAt?.toString())
    val sorted = targets.distinct().sorted()
    out.put("targets", sorted.size.toString())
    sorted.forEach { target ->
        out.put("type", target.type.name)
        out.put("userId", target.userId?.toString())
        out.put("roleName", target.roleName)
        out.put("unitId", target.unitId?.toString())
    }
    return out.sha256()
}

private class Canonical {
    private val text = StringBuilder()

    fun put(
        name: String,
        value: String?
    ) {
        text.append(name).append('=')
        if (value == null) text.append('~') else text.append(value.length).append(':').append(value)
        text.append(';')
    }

    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8)).toHexString()
}
