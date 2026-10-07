package wasichai.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import tools.jackson.databind.json.JsonMapper
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

// what a stream hears: recompute for this organization (and only this user, when set).
// organizationId null is the wildcard: everyone recomputes (after a LISTEN reconnect, a gap may hide anything).
data class Signal(
    val organizationId: UUID?,
    val userId: UUID?
) {
    fun reaches(
        organizationId: UUID,
        userId: UUID
    ): Boolean = this.organizationId == null || (this.organizationId == organizationId && (this.userId == null || this.userId == userId))

    companion object {
        val WILDCARD = Signal(null, null)

        private val log = LoggerFactory.getLogger(Signal::class.java)
        private val json = JsonMapper.builder().build()

        // anyone may pg_notify the channel: a payload that is not ours is dropped, never thrown
        fun parse(payload: String?): Signal? {
            val parsed =
                runCatching {
                    val node = json.readTree(payload ?: "")
                    val org = node.get("o")?.takeIf { it.isString }?.let { UUID.fromString(it.asString()) }
                    val user = node.get("u")?.let { if (it.isNull) null else UUID.fromString(it.asString()) }
                    org?.let { Signal(it, user) }
                }.getOrNull()
            if (parsed == null) log.debug("notification signal ignored, bad payload: {}", payload?.take(MAX_LOGGED))
            return parsed
        }

        private const val MAX_LOGGED = 200
    }
}

// the hub between the LISTEN connection and every open stream of this replica.
// best effort: a stream that cannot keep up misses a signal, and its refresh floor catches up.
class NotificationSignals {
    private val sink = Sinks.many().multicast().directBestEffort<Signal>()

    // a sink takes one emitter at a time
    @Synchronized
    fun emit(signal: Signal) {
        sink.tryEmitNext(signal)
    }

    fun signals(): Flux<Signal> = sink.asFlux()
}
