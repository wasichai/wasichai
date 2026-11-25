package wasichai.core.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.ErrorResponse
import java.net.URI

class ProblemInstanceTest {
    private val request = MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/users/a%20b?page=2")).build()

    @Test
    fun `a problem without instance gets the request path, encoded, without the query`() {
        val problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN)
        ProblemInstance().handleError(problem, null, request)
        assertThat(problem.instance).isEqualTo(URI.create("/api/users/a%20b"))
    }

    @Test
    fun `an instance already set is kept`() {
        val problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN).apply { instance = URI.create("/elsewhere") }
        ProblemInstance().handleError(problem, null, request)
        assertThat(problem.instance).isEqualTo(URI.create("/elsewhere"))
    }

    @Test
    fun `the registration adds it to the handlers' interceptors`() {
        val interceptors = mutableListOf<ErrorResponse.Interceptor>()
        ProblemInstance.Registration().addErrorResponseInterceptors(interceptors)
        assertThat(interceptors).singleElement().isInstanceOf(ProblemInstance::class.java)
    }
}
