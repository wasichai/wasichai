package wasichai.core.admin

import wasichai.core.metadata.FieldPermissionEntry
import wasichai.core.metadata.PermissionEntry

/**
 * A role's grants as the role methods store them, in one place: [AdminService] stores what this answers, and
 * [DeclaredRoles] compares a declaration with the stored role after the same pass, so what a run writes is what the
 * next run reads and idempotency cannot drift (ADR-085). Names are trimmed and cased, the first entry of a repeated
 * grant or rule is kept, and no field rule writes what it cannot read.
 */
internal object RoleGrants {
    fun name(raw: String): String = raw.trim().uppercase()

    fun label(raw: String): String = raw.trim()

    fun grants(entries: List<PermissionEntry>): List<PermissionEntry> =
        entries
            .map {
                PermissionEntry(
                    it.objectName
                        ?.trim()
                        ?.lowercase()
                        ?.ifBlank { null },
                    it.action.trim().uppercase(),
                    it.allowed
                )
            }.distinctBy { it.objectName to it.action }

    // cannot write what you cannot read: a blind overwrite is not an edit
    fun rules(entries: List<FieldPermissionEntry>): List<FieldPermissionEntry> =
        entries
            .map { FieldPermissionEntry(it.objectName.trim().lowercase(), it.fieldName.trim().lowercase(), it.read, it.write && it.read) }
            .distinctBy { it.objectName to it.fieldName }
}
