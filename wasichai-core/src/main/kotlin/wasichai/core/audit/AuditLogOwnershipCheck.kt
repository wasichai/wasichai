package wasichai.core.audit

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas

/** Who owns audit_log, as the role wasichai runs as sees it: [owner] is the table's owner, [runtime] that role. */
data class AuditLogOwner(
    val owner: String,
    val runtime: String
)

// audit_log's triggers refuse UPDATE, DELETE and TRUNCATE (ADR-054), but an owner can drop or disable them. at every
// start: a WARN when the role wasichai runs as can act as the owner (is it, is a member of it, or is a superuser).
// never fails startup: a check that cannot run is an INFO line. runs once the app is ready, like
// DeclaredIndexReconciler, so a context without a database never reaches it.
class AuditLogOwnershipCheck internal constructor(
    private val probe: suspend () -> AuditLogOwner?
) : ApplicationListener<ApplicationReadyEvent> {
    constructor(db: DatabaseClient, schemas: WasichaiSchemas) : this({ ownerActingAsRuntime(db, schemas) })

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        runBlocking { check() }
    }

    /** The warning it logged, or null when there was nothing to warn about or the check could not run. */
    suspend fun check(): String? {
        val found =
            try {
                withTimeout(TIMEOUT_MS) { probe() }
            } catch (e: Exception) {
                log.info("could not check who owns audit_log: {}", e.message)
                return null
            } ?: return null
        val warning =
            "audit_log is append-only through triggers (ADR-054), but the role wasichai runs as ('${found.runtime}') can act as " +
                "its owner ('${found.owner}') and so drop or disable them. run migrations as a separate owner role and connect " +
                "as a role that only has SELECT and INSERT on it (docs/guides/build-your-app.md, two database roles)"
        log.warn(warning)
        return warning
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L

        // MEMBER: the owner itself, a member of it (SET ROLE away) or a superuser. no row: not one of them, or no table
        internal val OWNER_QUERY =
            """
            SELECT pg_get_userbyid(c.relowner)::text AS owner, current_user::text AS runtime
            FROM pg_class c
            WHERE c.oid = to_regclass(:table) AND pg_has_role(current_user, c.relowner, 'MEMBER')
            """.trimIndent()

        private suspend fun ownerActingAsRuntime(
            db: DatabaseClient,
            schemas: WasichaiSchemas
        ): AuditLogOwner? =
            db
                .sql(OWNER_QUERY)
                .bind("table", "${schemas.metadata}.audit_log")
                .map { row, _ -> AuditLogOwner(row.get("owner", String::class.java)!!, row.get("runtime", String::class.java)!!) }
                .one()
                .awaitFirstOrNull()
    }
}
