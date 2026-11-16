package wasichai.it.full

import org.junit.jupiter.api.Test

// how the ui signs people in, asked before anyone has a token
class AuthConfigApiTest : FullAppIntegrationTest() {
    @Test
    fun `the config says local`() {
        client
            .get()
            .uri("/api/auth/config")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"mode":"local"}""", true)
    }
}
