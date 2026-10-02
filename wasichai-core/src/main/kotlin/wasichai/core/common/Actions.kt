package wasichai.core.common

// the built-in actions permissions are granted on. mirrors the permissions CHECK constraint.
// an object may declare more of its own (ADR-042): see ObjectActionService.
object Actions {
    const val READ = "READ"
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val MANAGE_METADATA = "MANAGE_METADATA"
    const val MANAGE_ORGANIZATION = "MANAGE_ORGANIZATION"

    val BUILT_IN = setOf(READ, CREATE, UPDATE, DELETE, MANAGE_METADATA, MANAGE_ORGANIZATION)
}
