package wasichai.notifications

import wasichai.core.common.FieldViolation
import java.time.Clock
import java.util.UUID

/**
 * Publishes notifications from an app's own code: the module's main Kotlin API (ADR-046).
 *
 * ```
 * notifications.publish(
 *     organizationId,
 *     "caja.pagos",
 *     NotificationDraft(
 *         kind = NotificationKind.ACTION,
 *         title = "Pago cobrado sin registrar",
 *         audience = listOf(Audience.Role("SUPERVISOR_CAJA"), Audience.Email(responsable)),
 *         link = NotificationLink.Route("caja:pagos-sin-entregar"),
 *         key = "pago-$pagoId"
 *     )
 * )
 * // later, when someone explained it
 * notifications.resolve(organizationId, "caja.pagos", "pago-$pagoId")
 * ```
 *
 * - **Transactions.** Every call joins the caller's transaction (ADR-038), or runs in its own. A rollback takes the
 *   notification with it; the live signal (`pg_notify`) goes out only with the commit.
 * - **Source.** `source` names the producer, `^[a-z][a-z0-9_.:-]{1,80}$`. `manual` and `rule:…` are the module's own
 *   and refused. A notification of a source is not edited, deleted or (an ACTION) dismissed by hand: the app resolves it.
 * - **Key.** With [NotificationDraft.key], `publish` is an upsert on (organization, source, key): the same content writes
 *   nothing, new content updates in place and keeps who read it (unless the kind changed), a resolved one reopens as new.
 *   Without a key every call inserts a new notification.
 * - **Lenient about people.** An unknown user, email, role or unit is dropped with a WARN, so a person who left never
 *   rolls back business work; when nobody is left, `publish` writes nothing and answers `null`.
 * - **Lenient about length.** A title over 200 characters or a body over 4000 is cut, ending in "…".
 * - **Strict about format.** A malformed draft (a bad key, route, url or tab, an expiry before the publication, a
 *   `RECORD` link to an object the organization does not have) is an [IllegalArgumentException]: a bug in the app.
 */
class Notifications(
    private val preparer: NotificationPreparer,
    private val writer: NotificationWriter,
    private val clock: Clock
) {
    /** The notification's id, or `null` when no recipient is left (each unknown one dropped with a WARN). */
    suspend fun publish(
        organizationId: UUID,
        source: String,
        draft: NotificationDraft
    ): UUID? {
        val prepared = preparer.prepare(organizationId, source, draft, clock.instant(), strict = false) ?: return null
        return writer.publish(organizationId, source, prepared, createdBy = null).id
    }

    /** Resolves the open notification of [source] with [key]; `false` when there is none. */
    suspend fun resolve(
        organizationId: UUID,
        source: String,
        key: String
    ): Boolean {
        requireSource(source)
        val violations = NotificationValidation.checkKey(key)
        require(violations.isEmpty()) { describe(violations) }
        return writer.resolve(organizationId, source, key)
    }

    /** Resolves every open notification of [source]; answers how many. */
    suspend fun resolveAll(
        organizationId: UUID,
        source: String
    ): Int {
        requireSource(source)
        return writer.resolveAll(organizationId, source)
    }

    private fun requireSource(source: String) {
        val violations = NotificationValidation.checkSource(source)
        require(violations.isEmpty()) { describe(violations) }
    }

    private fun describe(violations: List<FieldViolation>): String = DraftCheck.MESSAGE + ": " + violations.joinToString("; ") { "${it.field} ${it.message}" }
}
