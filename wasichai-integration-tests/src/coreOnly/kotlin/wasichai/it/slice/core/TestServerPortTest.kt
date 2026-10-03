package wasichai.it.slice.core

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import wasichai.test.WasichaiIntegrationTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// #34: on macOS a 127.0.0.1 listener may share the port of a wildcard one, and it gets every localhost
// request. any other process doing that turned the suite's login into somebody else's 404.
class TestServerPortTest : WasichaiIntegrationTest() {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `another listener on the test server's loopback port never takes its requests`() {
        // the squatter: any http server on 127.0.0.1, answering 404 to every path
        val squatter = runCatching { HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0).also { it.start() } }
        try {
            // a fresh connection, not one the test client already pooled
            val login =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}"""))
                    .build()
            val status = HttpClient.newHttpClient().use { it.send(login, HttpResponse.BodyHandlers.discarding()).statusCode() }
            assertThat(status).describedAs("login on localhost:$port, squatter bound: ${squatter.isSuccess}").isEqualTo(200)
        } finally {
            squatter.getOrNull()?.stop(0)
        }
    }
}
