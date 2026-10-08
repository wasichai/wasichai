package wasichai.core.platform

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Every request gets a correlation id (ADR-050): the client's `X-Correlation-Id` when it is one well-formed value,
 * a fresh one otherwise. It is echoed on every response and put in the Reactor context (and from there the MDC)
 * with the source `api`, so each audit row the request writes carries both.
 *
 * First of all filters: before Spring Security's chain (order -100), so a 401 or 403 it answers carries the id too.
 */
class CorrelationIdWebFilter :
    WebFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        // two headers are no single id: replaced, like a malformed one
        val sent = exchange.request.headers[ChangeOrigin.HEADER]?.singleOrNull()
        val id = ChangeOrigin.acceptCorrelationId(sent) ?: ChangeOrigin.newCorrelationId()
        // right before commit: an error handler may reset the headers on its way to the answer
        exchange.response.beforeCommit {
            exchange.response.headers.set(ChangeOrigin.HEADER, id)
            Mono.empty()
        }
        return chain.filter(exchange).contextWrite { ChangeOrigin.label(it, ChangeOrigin.API, id) }
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE
    }
}
