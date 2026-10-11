package wasichai.core.platform

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.util.context.Context
import reactor.util.context.ContextView

// issue 105 (ADR-066): an answer is remembered for one request, and only inside one
class RequestMemoTest {
    private var loads = 0

    private suspend fun answer(): String = RequestMemo.remember("key") { "answer ${++loads}" }

    @Test
    fun `inside a memo an answer is loaded once`() =
        runTest {
            withContext(RequestMemo.open(Context.empty()).asCoroutineContext()) {
                assertThat(answer()).isEqualTo("answer 1")
                assertThat(answer()).isEqualTo("answer 1")
            }
            assertThat(loads).isEqualTo(1)
        }

    @Test
    fun `without one every call loads, as before`() =
        runTest {
            answer()
            answer()
            RequestMemo.put("key", "kept nowhere")
            RequestMemo.clear()

            assertThat(loads).isEqualTo(2)
        }

    @Test
    fun `clear forgets, and put answers without a load`() =
        runTest {
            withContext(RequestMemo.open(Context.empty()).asCoroutineContext()) {
                answer()
                RequestMemo.clear()
                answer()
                RequestMemo.put("key", "learned elsewhere")
                assertThat(answer()).isEqualTo("learned elsewhere")
            }
            assertThat(loads).isEqualTo(2)
        }

    @Test
    fun `two requests never share a memo`() =
        runTest {
            withContext(RequestMemo.open(Context.empty()).asCoroutineContext()) { answer() }
            withContext(RequestMemo.open(Context.empty()).asCoroutineContext()) { answer() }

            assertThat(loads).isEqualTo(2)
        }

    @Test
    fun `the filter opens a fresh memo for each request`() {
        val filter = RequestMemoWebFilter()
        val seen = mutableListOf<ContextView>()
        val chain =
            WebFilterChain { _ ->
                Mono.deferContextual {
                    seen += it
                    Mono.empty()
                }
            }

        repeat(2) { filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/objects")), chain).block() }

        assertThat(seen).hasSize(2)
        assertThat(seen[0].size()).isEqualTo(1)
        assertThat(
            seen[0]
                .stream()
                .findFirst()
                .get()
                .value
        ).isNotSameAs(
            seen[1]
                .stream()
                .findFirst()
                .get()
                .value
        )
    }
}
