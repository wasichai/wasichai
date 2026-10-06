package wasichai.notifications

import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.util.retry.Retry
import wasichai.core.platform.SqlIdentifier
import java.time.Duration

// spec D, ADR-047: one LISTEN connection per replica, outside the pool (Connections.unpooled), feeding the hub.
// lost: reconnect with backoff. back: a wildcard, so a gap costs every stream one recompute and loses nothing.
// the factory is read on each connect: a mocked database (context tests) only fails a retry, never the boot.
class NotificationListener(
    private val connections: () -> ConnectionFactory,
    private val channel: String,
    private val signals: NotificationSignals,
    private val healthCheck: Duration = HEALTH_CHECK,
    private val firstBackoff: Duration = FIRST_BACKOFF,
    private val maxBackoff: Duration = MAX_BACKOFF,
    private val scheduler: Scheduler = Schedulers.parallel()
) : SmartLifecycle {
    enum class State { STOPPED, CONNECTING, LISTENING, OFF }

    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    @Volatile
    var state: State = State.STOPPED
        private set

    @Synchronized
    override fun start() {
        if (subscription != null) return
        state = State.CONNECTING
        subscription =
            listen()
                .retryWhen(
                    // transient: a session that got to LISTEN starts the backoff over at 1 s
                    Retry
                        .backoff(Long.MAX_VALUE, firstBackoff)
                        .maxBackoff(maxBackoff)
                        .transientErrors(true)
                        .filter { it !is NotPostgresql }
                        .doBeforeRetry {
                            state = State.CONNECTING
                            log.warn("LISTEN {} lost ({}), reconnecting", channel, it.failure().toString())
                        }
                ).subscribe({}, ::off)
    }

    @Synchronized
    override fun stop() {
        subscription?.dispose()
        subscription = null
        state = State.STOPPED
    }

    override fun isRunning(): Boolean = subscription != null

    // the connection is closed whatever ends the session: error, completion or stop()
    private fun listen(): Flux<Unit> =
        Flux.usingWhen(
            Mono.defer { Mono.from(connections().create()) },
            ::session,
            { connection -> connection.close() }
        )

    private fun session(connection: Connection): Flux<Unit> {
        val pg = connection as? PostgresqlConnection ?: return Flux.error(NotPostgresql(connection.javaClass.name))
        // the channel is <metadata schema>_notifications, a checked identifier: quoted, never bound (LISTEN takes no parameter)
        val listening =
            Flux
                .from(pg.createStatement("LISTEN ${SqlIdentifier.quote(channel)}").execute())
                .flatMap { it.rowsUpdated }
                .then(
                    Mono.fromCallable {
                        state = State.LISTENING
                        log.info("LISTEN {}: notification streams are live", channel)
                        signals.emit(Signal.WILDCARD)
                    }
                )
        val heard = pg.notifications.doOnNext { notification -> Signal.parse(notification.parameter)?.let(signals::emit) }
        // a half-open socket hears nothing and says nothing: ask it something
        val health =
            Flux
                .interval(healthCheck, scheduler)
                .onBackpressureDrop()
                .concatMap {
                    Flux
                        .from(pg.createStatement("SELECT 1").execute())
                        .flatMap { it.rowsUpdated }
                        .then()
                        .timeout(HEALTH_TIMEOUT, scheduler)
                }
        // listening emits once: that resets the backoff. a session never ends well, only stop() ends it quietly
        return listening.concatWith(
            Flux
                .merge(heard.then(), health.then())
                .then(Mono.error<Unit>(IllegalStateException("LISTEN connection closed")))
        )
    }

    private fun off(error: Throwable) {
        state = State.OFF
        if (error is NotPostgresql) {
            log.warn("notifications LISTEN off: {}. streams refresh every stream-refresh instead", error.message)
        } else {
            log.error("notifications LISTEN stopped. streams refresh every stream-refresh instead", error)
        }
    }

    private class NotPostgresql(
        type: String
    ) : RuntimeException("the connection is $type, not PostgreSQL's r2dbc driver")

    companion object {
        val HEALTH_CHECK: Duration = Duration.ofSeconds(60)
        val FIRST_BACKOFF: Duration = Duration.ofSeconds(1)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        private val HEALTH_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
