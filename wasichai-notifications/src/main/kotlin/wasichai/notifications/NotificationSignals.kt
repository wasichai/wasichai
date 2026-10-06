package wasichai.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// spec D: one channel per metadata schema, so two apps on one database never hear each other
object NotificationChannel {
    const val SUFFIX = "_notifications"

    // postgres truncates identifiers past 63 bytes: LISTEN would then wait on another name
    private const val MAX_LENGTH = 63

    fun name(schemas: WasichaiSchemas): String {
        val name = schemas.metadata + SUFFIX
        require(name.length <= MAX_LENGTH) { "notification channel '$name' is longer than $MAX_LENGTH characters: shorten wasichai.database.metadata-schema" }
        return name
    }

    // ids only: a listener recomputes, it never trusts the payload's content
    fun payload(
        organizationId: UUID,
        userId: UUID? = null
    ): String = if (userId == null) """{"o":"$organizationId"}""" else """{"o":"$organizationId","u":"$userId"}"""
}

// inside the caller's transaction: delivered on commit, dropped on rollback.
// postgres folds identical payloads of one transaction, so a batch costs one signal.
internal suspend fun DatabaseClient.pgNotify(
    schemas: WasichaiSchemas,
    organizationId: UUID,
    userId: UUID? = null
) {
    sql("SELECT pg_notify(:channel, :payload)")
        .bind("channel", NotificationChannel.name(schemas))
        .bind("payload", NotificationChannel.payload(organizationId, userId))
        .then()
        .awaitFirstOrNull()
}
