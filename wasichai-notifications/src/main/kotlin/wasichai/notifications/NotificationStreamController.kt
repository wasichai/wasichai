package wasichai.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.OrgUnitDirectory
import wasichai.notifications.autoconfigure.NotificationsProperties
import java.time.Clock

// spec D, ADR-047: the caller's summary, live. the token comes in the Authorization header like any route
// (never the URL), and the stream ends at its exp: the UI reconnects with the token it holds then.
@RestController
@RequestMapping("/api/auth/me/notifications")
class NotificationStreamController(
    private val currentUser: CurrentUser,
    private val units: OrgUnitDirectory,
    // its summary drops a RECORD link the reader may not READ (spec B): one place for that rule
    private val inbox: InboxService,
    private val signals: NotificationSignals,
    private val properties: NotificationsProperties,
    private val clock: Clock
) {
    // who reads is settled before the Flux exists: a 401 or 403 is a plain answer, not a broken stream.
    // roles are the token's for the stream's life (exp bounds it); every summary re-reads the units.
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(): ResponseEntity<Flux<ServerSentEvent<Any>>> {
        val user = currentUser.require()
        if (user.serviceAccount != null) throw ForbiddenException("A service account has no notifications")
        val reader = InboxReader(user.organizationId, user.userId, user.roles, units.closureOf(user.organizationId, user.userId))
        val expiresAt =
            (
                ReactiveSecurityContextHolder
                    .getContext()
                    .awaitFirstOrNull()
                    ?.authentication
                    ?.principal as? Jwt
            )?.expiresAt
        val events =
            NotificationStream.stream(
                reader = reader,
                signals = signals.signals(),
                summary = { mono { inbox.summary(user) } },
                refresh = properties.streamRefresh,
                heartbeat = properties.streamHeartbeat,
                debounce = properties.streamDebounce,
                expiresAt = expiresAt,
                clock = clock
            )
        // nginx buffers a response unless told: a buffered stream arrives all at once, at the end
        return ResponseEntity.ok().header(ACCEL_BUFFERING, "no").body(events)
    }

    companion object {
        const val ACCEL_BUFFERING = "X-Accel-Buffering"
    }
}
