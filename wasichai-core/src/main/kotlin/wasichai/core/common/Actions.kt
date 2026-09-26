package wasichai.core.common

// the actions permissions are granted on. mirrors the CHECK constraint on the permissions table.
object Actions {
    const val READ = "READ"
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val MANAGE_METADATA = "MANAGE_METADATA"
    const val MANAGE_ORGANIZATION = "MANAGE_ORGANIZATION"
}
