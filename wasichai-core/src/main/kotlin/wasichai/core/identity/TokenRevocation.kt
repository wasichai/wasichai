package wasichai.core.identity

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// per-user "tokens valid after" marker (ADR-059). written on every change that must sign a user out, whether
// revocation is on or not, so turning it on honours what happened before. only read when it is on.
// the marker is the next whole second after the change: iat has second precision, so a token of that same
// second cannot be told apart and goes too. a login in that second gets iat = the marker (JwtService), so a
// token issued after the change always works. app clock on both sides, never the database's.
class TokenRevocation(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val enabled: Boolean,
    private val cacheTtl: Duration,
    private val clock: Clock = Clock.systemUTC()
) {
    // exists = false: no such user in that tenant any more (deleted), every token refused
    private class Entry(
        val organizationId: UUID,
        val exists: Boolean,
        val validAfter: Instant?,
        val loadedAt: Instant
    )

    private val cache = ConcurrentHashMap<UUID, Entry>()

    // every token of the user issued until now stops working. answers the stored marker, null when no such user.
    // this node's cache takes it at once; others within cacheTtl. a rolled back change leaves the cached marker
    // until it expires: tokens refused a little early, never accepted late.
    suspend fun revoke(
        organizationId: UUID,
        userId: UUID
    ): Instant? {
        val marker = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
        // strictly after every token issued so far: those have iat <= max(now, old marker). a marker already at or
        // past the next second (a change in this same second) moves one more second, else a token issued with
        // iat = that marker would survive this change
        val stored =
            db
                .sql(
                    """
                    UPDATE ${schemas.metadata}.users
                    SET tokens_valid_after = CASE
                        WHEN tokens_valid_after IS NULL OR tokens_valid_after < :marker THEN :marker
                        ELSE tokens_valid_after + interval '1 second'
                    END
                    WHERE id = :id AND organization_id = :organizationId
                    RETURNING tokens_valid_after
                    """.trimIndent()
                ).bind("marker", marker.atOffset(ZoneOffset.UTC))
                .bind("id", userId)
                .bind("organizationId", organizationId)
                .map { row, _ -> Marker(Rows.instantOrNull(row, "tokens_valid_after")) }
                .one()
                .awaitFirstOrNull()
                ?.validAfter
        remember(Entry(organizationId, stored != null, stored, Instant.now(clock)), userId)
        return stored
    }

    // the user row is gone (deleted, or the service account behind it): refused here at once
    fun forget(
        organizationId: UUID,
        userId: UUID
    ) = remember(Entry(organizationId, false, null, Instant.now(clock)), userId)

    // true when the token was issued at or after its user's marker, and the user still exists in its tenant
    suspend fun accepts(jwt: Jwt): Boolean {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return false
        val organizationId = runCatching { UUID.fromString(jwt.getClaimAsString(JwtService.CLAIM_ORGANIZATION)) }.getOrNull() ?: return false
        val issuedAt = jwt.issuedAt ?: return false
        val entry = cached(userId, organizationId) ?: load(organizationId, userId)
        if (!entry.exists) return false
        return entry.validAfter == null || !issuedAt.isBefore(entry.validAfter)
    }

    private fun cached(
        userId: UUID,
        organizationId: UUID
    ): Entry? {
        val entry = cache[userId] ?: return null
        val fresh = entry.organizationId == organizationId && Instant.now(clock).isBefore(entry.loadedAt.plus(cacheTtl))
        return if (fresh) entry else null
    }

    // tenant filter: a token's user is looked up in the token's own tenant only
    private suspend fun load(
        organizationId: UUID,
        userId: UUID
    ): Entry {
        val marker =
            db
                .sql("SELECT tokens_valid_after FROM ${schemas.metadata}.users WHERE id = :id AND organization_id = :organizationId")
                .bind("id", userId)
                .bind("organizationId", organizationId)
                .map { row, _ -> Marker(Rows.instantOrNull(row, "tokens_valid_after")) }
                .one()
                .awaitFirstOrNull()
        val entry = Entry(organizationId, marker != null, marker?.validAfter, Instant.now(clock))
        remember(entry, userId)
        return entry
    }

    // nothing cached while off, or with a zero ttl. expired entries swept once the map grows, so it stays
    // about as big as the users seen within one ttl
    private fun remember(
        entry: Entry,
        userId: UUID
    ) {
        if (!enabled || cacheTtl.isZero) return
        if (cache.size >= SWEEP_AT) {
            val now = Instant.now(clock)
            cache.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(cacheTtl)) }
        }
        cache[userId] = entry
    }

    private class Marker(
        val validAfter: Instant?
    )

    private companion object {
        const val SWEEP_AT = 10_000
    }
}

// the app's decoder, plus the marker check. a revoked token is a BadJwtException: the resource server answers
// 401 invalid_token, as for an expired one
class RevocationCheckingJwtDecoder(
    private val delegate: ReactiveJwtDecoder,
    private val revocation: TokenRevocation
) : ReactiveJwtDecoder {
    override fun decode(token: String): Mono<Jwt> =
        delegate.decode(token).flatMap { jwt ->
            mono {
                if (!revocation.accepts(jwt)) throw BadJwtException("Token has been revoked")
                jwt
            }
        }
}
