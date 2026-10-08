package wasichai.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

// audit_log is append-only in the database (ADR-054). purgeRole: the one database role whose own login may purge it,
// after SET LOCAL wasichai.audit.purge = 'on'. null: nobody. read by the migration (R__audit_purge_role.sql), so it
// takes effect where migrations run, under the migration role. blank counts as null.
@ConfigurationProperties(prefix = "wasichai.audit")
data class WasichaiAuditProperties(
    val purgeRole: String? = null
) {
    init {
        // goes into the function body as a literal: a plain unquoted role name and nothing else
        require(purgeRole.isNullOrBlank() || ROLE.matches(purgeRole)) {
            "wasichai.audit.purge-role '$purgeRole' must be an unquoted PostgreSQL role name matching ${ROLE.pattern}"
        }
    }

    // what the migration writes: "" for none
    val purgeRolePlaceholder: String get() = purgeRole?.takeIf { it.isNotBlank() }.orEmpty()

    companion object {
        val ROLE = Regex("^[a-z_][a-z0-9_]{0,62}$")
    }
}
