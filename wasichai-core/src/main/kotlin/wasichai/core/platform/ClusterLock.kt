package wasichai.core.platform

import io.r2dbc.spi.Connection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.r2dbc.connection.SingleConnectionFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One holder per database, whatever the number of replicas, over PostgreSQL advisory locks (ADR-039).
 * A key is any non-blank string; [lockId] turns it into the same bigint everywhere.
 *
 * [transactions] is resolved on first use: an app with no transaction manager can still [tryLock].
 */
class ClusterLock(
    private val db: DatabaseClient,
    transactions: () -> TransactionalOperator
) {
    private val operator by lazy(transactions)

    /**
     * Session lock, without waiting. Null when another session holds [key]. Held on a connection of its
     * own, outside the pool, until [Lease.release] unlocks and closes it, so a lock can never go back to
     * the pool still held. Use it as `tryLock("outbox")?.use { publish() }`.
     */
    suspend fun tryLock(key: String): Lease? {
        val id = lockId(key)
        // a pooled connection would outlive the lease and keep the lock: go under the pool
        val factory = Connections.unpooled(db.connectionFactory)
        val connection = Mono.from(factory.create()).awaitSingle()
        val session = DatabaseClient.create(SingleConnectionFactory(connection, factory.metadata, true))
        val acquired =
            try {
                session
                    .sql("SELECT pg_try_advisory_lock(:id) AS acquired")
                    .bind("id", id)
                    .map { row, _ -> row.get("acquired", java.lang.Boolean::class.java)?.booleanValue() == true }
                    .one()
                    .awaitSingle()
            } catch (e: Throwable) {
                close(connection)
                throw e
            }
        if (!acquired) {
            close(connection)
            return null
        }
        return Lease(key, id, connection, session)
    }

    /**
     * Transaction lock, waiting for it: [block] runs while this transaction holds [key], and the lock
     * goes with the commit or rollback. Joins the caller's transaction when there is one (ADR-038), so
     * the lock lasts until that one ends; opens one otherwise.
     */
    suspend fun <T> withXactLock(
        key: String,
        block: suspend () -> T
    ): T {
        val id = lockId(key)
        return operator.executeAndAwait {
            db
                .sql("SELECT pg_advisory_xact_lock(:id)")
                .bind("id", id)
                .then()
                .awaitFirstOrNull()
            block()
        }
    }

    /** A held session lock. Release it once; a second release does nothing. */
    class Lease internal constructor(
        val key: String,
        private val id: Long,
        private val connection: Connection,
        private val session: DatabaseClient
    ) {
        private val released = AtomicBoolean(false)

        // unlock first: the driver closes without waiting for the backend to exit, so the lock could
        // outlive close() for a moment. best effort: close frees it anyway when the unlock fails.
        suspend fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                withContext(NonCancellable) {
                    session
                        .sql("SELECT pg_advisory_unlock(:id)")
                        .bind("id", id)
                        .then()
                        .awaitFirstOrNull()
                }
            } catch (_: Exception) {
                // the session is gone or broken: closing it is what is left
            } finally {
                close(connection)
            }
        }

        // releases even when the block throws or is cancelled
        suspend fun <T> use(block: suspend () -> T): T =
            try {
                block()
            } finally {
                release()
            }
    }

    companion object {
        /** First eight bytes of the key's SHA-256: stable across JVMs, replicas and PostgreSQL versions. */
        fun lockId(key: String): Long {
            require(key.isNotBlank()) { "A cluster lock key must not be blank" }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
        }

        // non-cancellable: a cancelled caller must still give the session, and its lock, back
        private suspend fun close(connection: Connection) {
            withContext(NonCancellable) { Mono.from(connection.close()).awaitFirstOrNull() }
        }
    }
}
