package wasichai.notifications

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.boot.autoconfigure.AutoConfigurations
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import wasichai.notifications.autoconfigure.WasichaiNotificationsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.time.Duration
import java.util.UUID

class NotificationListenerTest {
    private val org = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val ana = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `a payload with the organization only signals everyone of it`() {
        assertThat(Signal.parse("""{"o":"$org"}""")).isEqualTo(Signal(org, null))
    }

    @Test
    fun `a payload with a user signals that person only`() {
        assertThat(Signal.parse("""{"o":"$org","u":"$ana"}""")).isEqualTo(Signal(org, ana))
        assertThat(Signal.parse(NotificationChannel.payload(org, ana))).isEqualTo(Signal(org, ana))
        assertThat(Signal.parse(NotificationChannel.payload(org))).isEqualTo(Signal(org, null))
    }

    @Test
    fun `a payload that is not ours is ignored`() {
        listOf(
            null,
            "",
            "hello",
            "{",
            "[]",
            "{}",
            """{"u":"$ana"}""",
            """{"o":"not-a-uuid"}""",
            """{"o":42}""",
            """{"o":"$org","u":"nope"}""",
            """{"o":"$org","u":7}"""
        ).forEach { assertThat(Signal.parse(it)).describedAs(it).isNull() }
    }

    @Test
    fun `a signal reaches its organization, and its person when it names one`() {
        val bob = UUID.randomUUID()
        assertThat(Signal(org, null).reaches(org, ana)).isTrue()
        assertThat(Signal(org, ana).reaches(org, ana)).isTrue()
        assertThat(Signal(org, bob).reaches(org, ana)).isFalse()
        assertThat(Signal(UUID.randomUUID(), null).reaches(org, ana)).isFalse()
        assertThat(Signal.WILDCARD.reaches(org, ana)).isTrue()
    }

    @Test
    fun `the hub hands a signal to every open stream`() {
        val hub = NotificationSignals()
        StepVerifier
            .create(hub.signals().take(1))
            .then { hub.emit(Signal(org, ana)) }
            .expectNext(Signal(org, ana))
            .verifyComplete()
        // nobody listening: dropped, never thrown
        hub.emit(Signal.WILDCARD)
    }

    @Test
    fun `a connection that is not PostgreSQL's turns the listener off, once`() {
        val connection = mock(Connection::class.java)
        doReturn(Mono.empty<Void>()).`when`(connection).close()
        val factory = mock(ConnectionFactory::class.java)
        doReturn(Mono.just(connection)).`when`(factory).create()
        val listener =
            NotificationListener(
                connections = { factory },
                channel = "wasichai_notifications",
                signals = NotificationSignals(),
                firstBackoff = Duration.ofMillis(10),
                maxBackoff = Duration.ofMillis(10)
            )

        listener.start()
        Thread.sleep(200)

        assertThat(listener.state).isEqualTo(NotificationListener.State.OFF)
        // no retry: another connection would be the same driver
        verify(factory, times(1)).create()
        verify(connection).close()
        listener.stop()
        assertThat(listener.state).isEqualTo(NotificationListener.State.STOPPED)
        assertThat(listener.isRunning).isFalse()
    }

    @Test
    fun `a failing connect is retried`() {
        val factory = mock(ConnectionFactory::class.java)
        doReturn(Mono.error<Connection>(IllegalStateException("refused"))).`when`(factory).create()
        val listener =
            NotificationListener(
                { factory },
                "wasichai_notifications",
                NotificationSignals(),
                firstBackoff = Duration.ofMillis(5),
                maxBackoff = Duration.ofMillis(5)
            )

        listener.start()
        Thread.sleep(300)
        listener.stop()

        assertThat(mockingDetails(factory).invocations.size).isGreaterThan(2)
    }

    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiNotificationsAutoConfiguration::class.java))

    @Test
    fun `the hub, the stream route and the listener are wired, and listen=false leaves the listener out`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(NotificationSignals::class.java)
            assertThat(context).hasSingleBean(NotificationStreamController::class.java)
            assertThat(context).hasSingleBean(NotificationListener::class.java)
        }
        runner.withPropertyValues("wasichai.notifications.listen=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(NotificationSignals::class.java)
            assertThat(context).hasSingleBean(NotificationStreamController::class.java)
            assertThat(context).doesNotHaveBean(NotificationListener::class.java)
        }
    }
}
