package wasichai.core.platform

import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Every request gets its own [RequestMemo] (ADR-066): the role answers it looks up once live as long as it does, and
 * the next request asks again, so a permission changed in between is seen at once.
 */
class RequestMemoWebFilter :
    WebFilter,
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> = chain.filter(exchange).contextWrite { RequestMemo.open(it) }

    companion object {
        // right after the correlation id, ahead of the security chain (order -100)
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 1
    }
}
