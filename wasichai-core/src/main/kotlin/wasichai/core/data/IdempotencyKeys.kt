package wasichai.core.data

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnprocessableContentException
import wasichai.core.common.ValidationException
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.WasichaiIdempotencyProperties
import wasichai.core.platform.WasichaiSchemas
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The `idempotency_keys` store (ADR-058): one row per caller and key, holding the answer the first request
 * got. Written in the transaction of the write it answers for, so a key never exists without its record,
 * nor a record without its key.
 *
 * In flight is a transaction advisory lock on (organization, caller, key), taken without waiting: a second
 * request with the key while the first still runs gets 409 and `Retry-After`, never a second write. A row
 * means the first one committed; a first one that failed left nothing, so the key is free again.
 *
 * [transactions] is resolved on first use, as in ClusterLock.
 */
class IdempotencyKeys(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val json: JsonMapper,
    private val properties: WasichaiIdempotencyProperties,
    transactions: () -> TransactionalOperator
) {
    private val operator by lazy(transactions)

    // the first request's answer: status and the exact json it was sent
    class Answer(
        val status: Int,
        val body: String
    )

    class Outcome(
        val answer: Answer,
        val replayed: Boolean
    )

    /**
     * Runs [first] once per ([organizationId], [userId], [key]) within the ttl, and stores its answer with
     * it. A later call with the same [requestHash] gets that answer back, replayed, and runs nothing; one
     * with another hash is a 422. Joins the caller's transaction (ADR-038), opens one otherwise: the lock,
     * the write and the row commit or roll back together.
     */
    suspend fun once(
        organizationId: UUID,
        userId: UUID?,
        key: String,
        requestHash: String,
        first: suspend () -> Answer
    ): Outcome =
        if (inTransaction()) {
            claimed(organizationId, userId, key, requestHash, first)
        } else {
            operator.executeAndAwait { claimed(organizationId, userId, key, requestHash, first) }
        }

    private suspend fun claimed(
        organizationId: UUID,
        userId: UUID?,
        key: String,
        requestHash: String,
        first: suspend () -> Answer
    ): Outcome {
        // never waits: a request that waited would hold a pooled connection for as long as the first one runs
        val locked =
            db
                .sql("SELECT pg_try_advisory_xact_lock(:id) AS locked")
                .bind("id", ClusterLock.lockId("wasichai.idempotency/$organizationId/${userId ?: "platform"}/$key"))
                .map { row, _ -> row.get("locked", java.lang.Boolean::class.java)?.booleanValue() == true }
                .one()
                .awaitSingle()
        if (!locked) {
            throw RetryLaterException(
                "A request with this $HEADER is still being processed",
                RETRY_AFTER,
                listOf(FieldViolation(HEADER, "a request with this key is in flight; send it again later"))
            )
        }
        // past the ttl a key is gone, purged or not yet: the ttl is exact whatever the purge's pace
        byCaller(
            db.sql("DELETE FROM ${schemas.metadata}.idempotency_keys WHERE ${match(userId)} AND created_at < now() - make_interval(secs => :ttl)"),
            organizationId,
            userId,
            key
        ).bind("ttl", ttlSeconds())
            .then()
            .awaitFirstOrNull()
        val stored =
            byCaller(
                db.sql("SELECT request_hash, response_status, response_body FROM ${schemas.metadata}.idempotency_keys WHERE ${match(userId)}"),
                organizationId,
                userId,
                key
            ).map { row, _ ->
                Triple(
                    row.get("request_hash", String::class.java)!!,
                    row.get("response_status", Integer::class.java)!!.toInt(),
                    row.get("response_body", String::class.java)!!
                )
            }.one()
                .awaitFirstOrNull()
        if (stored != null) {
            if (stored.first != requestHash) {
                throw UnprocessableContentException(
                    "This $HEADER was already used for another request",
                    listOf(FieldViolation(HEADER, "was sent before with another method, path or body; use a new key"))
                )
            }
            return Outcome(Answer(stored.second, stored.third), replayed = true)
        }
        val answer = first()
        byCaller(
            db.sql(
                "INSERT INTO ${schemas.metadata}.idempotency_keys (organization_id, user_id, key, request_hash, response_status, response_body) " +
                    "VALUES (:organizationId, :userId, :key, :hash, :status, :body)"
            ),
            organizationId,
            userId,
            key,
            insert = true
        ).bind("hash", requestHash)
            .bind("status", answer.status)
            .bind("body", answer.body)
            .then()
            .awaitFirstOrNull()
        return Outcome(answer, replayed = false)
    }

    /** Deletes every key past the ttl, in every organization. Answers how many. */
    suspend fun purgeExpired(): Long =
        db
            .sql("DELETE FROM ${schemas.metadata}.idempotency_keys WHERE created_at < now() - make_interval(secs => :ttl)")
            .bind("ttl", ttlSeconds())
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /**
     * What makes two requests the same: method, path and body. The body as parsed, keys sorted at every
     * level, so key order and whitespace do not count; a value's json does.
     */
    fun requestHash(
        method: String,
        path: String,
        body: Any?
    ): String {
        val canonical = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(body)
        val digest = MessageDigest.getInstance("SHA-256").digest("$method $path\n$canonical".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // the record's json as the api sends it
    fun write(record: RecordResponse): String = json.writeValueAsString(record)

    /**
     * A stored answer back as a record, for an in-process replay. Values are what its json holds: a date
     * is its ISO string, a decimal a BigDecimal, as a read of the json would give them.
     */
    fun readRecord(body: String): RecordResponse {
        val tree: Map<String, Any?> =
            json
                .readerFor(Map::class.java)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(body)
        return RecordResponse(
            id = tree["id"].toString(),
            createdAt = (tree["createdAt"] as? String)?.let(Instant::parse),
            updatedAt = (tree["updatedAt"] as? String)?.let(Instant::parse),
            attributes = section(tree["attributes"]),
            state = tree["state"] as? String,
            sections = tree.filterKeys { it !in RECORD_KEYS }.mapValues { (_, value) -> section(value) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>).orEmpty()

    // binds the caller's columns; the platform (no user) is user_id IS NULL
    private fun byCaller(
        spec: DatabaseClient.GenericExecuteSpec,
        organizationId: UUID,
        userId: UUID?,
        key: String,
        insert: Boolean = false
    ): DatabaseClient.GenericExecuteSpec {
        val bound = spec.bind("organizationId", organizationId).bind("key", key)
        return when {
            userId != null -> bound.bind("userId", userId)
            insert -> bound.bindNull("userId", UUID::class.java)
            else -> bound
        }
    }

    private fun match(userId: UUID?): String =
        "organization_id = :organizationId AND key = :key AND " + if (userId == null) "user_id IS NULL" else "user_id = :userId"

    private fun ttlSeconds(): Double = properties.ttl.toMillis() / 1000.0

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAYED = "Idempotent-Replayed"
        const val MAX_LENGTH = 128

        // a first request takes milliseconds; one second is soon enough to ask again
        val RETRY_AFTER: Duration = Duration.ofSeconds(1)

        private val RECORD_KEYS = setOf("id", "createdAt", "updatedAt", "attributes", "state")

        /** 1 to 128 printable ASCII characters, not all blank; 400 on the header otherwise. */
        fun requireValid(key: String) {
            val valid = key.length in 1..MAX_LENGTH && key.isNotBlank() && key.all { it.code in 0x20..0x7E }
            if (!valid) throw ValidationException("Malformed $HEADER header", HEADER, "send 1 to $MAX_LENGTH printable ASCII characters")
        }
    }
}

// whether a reactive transaction is active here: then a write joins it (ADR-038). no transaction context at all counts as none
internal suspend fun inTransaction(): Boolean =
    try {
        TransactionSynchronizationManager
            .forCurrentTransaction()
            .map { it.isActualTransactionActive }
            .awaitFirstOrNull() == true
    } catch (_: NoTransactionException) {
        false
    }
