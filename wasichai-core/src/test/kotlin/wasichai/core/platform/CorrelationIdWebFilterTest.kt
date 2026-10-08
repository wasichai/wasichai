package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Hooks
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.util.context.ContextView
import java.util.UUID

// ADR-050: every request has a correlation id, the client's when well formed, and every response says which
class CorrelationIdWebFilterTest {
    private val filter = CorrelationIdWebFilter()

    private var seen: ContextView? = null

    @AfterEach
    fun propagationOff() {
        Hooks.disableAutomaticContextPropagation()
    }

    // what a handler or the security chain does: answer with a status and complete
    private fun answering(status: HttpStatus) =
        WebFilterChain { exchange ->
            Mono.deferContextual { context ->
                seen = context
                exchange.response.setStatusCode(status)
                exchange.response.setComplete()
            }
        }

    private fun exchange(vararg sent: String): MockServerWebExchange {
        val request = MockServerHttpRequest.post("/api/objects/predio/records")
        if (sent.isNotEmpty()) request.header(ChangeOrigin.HEADER, *sent)
        return MockServerWebExchange.from(request)
    }

    private fun run(
        exchange: MockServerWebExchange,
        status: HttpStatus = HttpStatus.CREATED
    ): String? {
        filter.filter(exchange, answering(status)).block()
        return exchange.response.headers.getFirst(ChangeOrigin.HEADER)
    }

    @Test
    fun `a well-formed id is kept, echoed and handed on with the source api`() {
        val echoed = run(exchange("req-42.A_b"))

        assertThat(echoed).isEqualTo("req-42.A_b")
        assertThat(ChangeOrigin.correlationIdOf(seen!!)).isEqualTo("req-42.A_b")
        assertThat(ChangeOrigin.sourceOf(seen!!)).isEqualTo(ChangeOrigin.API)
    }

    @Test
    fun `sixty-four characters is the longest id kept`() {
        val longest = "a".repeat(64)

        assertThat(run(exchange(longest))).isEqualTo(longest)
    }

    @Test
    fun `no header gets a generated id, the same in the response and the context`() {
        val echoed = run(exchange())

        assertThat(UUID.fromString(echoed)).isNotNull()
        assertThat(ChangeOrigin.correlationIdOf(seen!!)).isEqualTo(echoed)
    }

    @Test
    fun `a malformed id is replaced, never echoed`() {
        listOf("has space", "<script>", "a;b", "a/b", "", "café").forEach { bad ->
            val echoed = run(exchange(bad))

            assertThat(echoed).describedAs(bad).isNotEqualTo(bad)
            assertThat(UUID.fromString(echoed)).isNotNull()
            assertThat(ChangeOrigin.correlationIdOf(seen!!)).isEqualTo(echoed)
        }
    }

    @Test
    fun `an oversized id is replaced, never echoed`() {
        val oversized = "a".repeat(65)

        val echoed = run(exchange(oversized))

        assertThat(echoed).isNotEqualTo(oversized)
        assertThat(UUID.fromString(echoed)).isNotNull()
    }

    @Test
    fun `two ids are no single id`() {
        val echoed = run(exchange("first", "second"))

        assertThat(echoed).isNotIn("first", "second")
        assertThat(UUID.fromString(echoed)).isNotNull()
    }

    @Test
    fun `a 401 carries the id too`() {
        val exchange = exchange("unauthorized-1")

        val echoed = run(exchange, HttpStatus.UNAUTHORIZED)

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(echoed).isEqualTo("unauthorized-1")
    }

    @Test
    fun `the id is set right before commit, so a handler resetting the headers does not lose it`() {
        val exchange = exchange("kept-1")
        val clearing =
            WebFilterChain { ex ->
                ex.response.headers.clear()
                ex.response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR)
                ex.response.setComplete()
            }

        filter.filter(exchange, clearing).block()

        assertThat(exchange.response.headers.getFirst(ChangeOrigin.HEADER)).isEqualTo("kept-1")
    }

    // WebFluxSecurityConfiguration.WEB_FILTER_CHAIN_FILTER_ORDER, package-private there. the real chain's 401 is
    // checked end to end in AuditOriginApiTest
    @Test
    fun `it runs before spring security's chain`() {
        assertThat(filter.order).isLessThan(-100)
    }

    @Test
    fun `with context propagation on, a log line on another thread has the id in its MDC`() {
        Hooks.enableAutomaticContextPropagation()
        var logged: String? = null
        val exchange = exchange("logged-1")
        val logging =
            WebFilterChain { ex ->
                Mono
                    .just(1)
                    .publishOn(Schedulers.parallel())
                    .doOnNext { logged = MDC.get(ChangeOrigin.MDC_KEY) }
                    .then(ex.response.setComplete())
            }

        filter.filter(exchange, logging).block()

        assertThat(logged).isEqualTo("logged-1")
    }
}
