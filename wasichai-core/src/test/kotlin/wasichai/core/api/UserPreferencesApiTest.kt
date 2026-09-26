package wasichai.core.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.test.WasichaiIntegrationTest

class UserPreferencesApiTest : WasichaiIntegrationTest() {
    // shared database: leave the admin's row as the seed has it (no preferences)
    @AfterEach
    fun reset() {
        put(mapOf("theme" to "system", "locale" to null)).expectStatus().isOk
    }

    private fun put(body: Map<String, Any?>) =
        client
            .put()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(body)
            .exchange()

    private fun get() =
        client
            .get()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()

    @Test
    fun `reads the defaults before anything is stored`() {
        get()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("system")
            .jsonPath("$.locale")
            .isEmpty
    }

    @Test
    fun `stores theme and locale`() {
        put(mapOf("theme" to "dark", "locale" to "en"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("dark")
        get()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("dark")
            .jsonPath("$.locale")
            .isEqualTo("en")
    }

    @Test
    fun `partial PUT keeps what it leaves out and null clears the locale`() {
        put(mapOf("theme" to "dark", "locale" to "en")).expectStatus().isOk
        put(mapOf("theme" to "high-contrast")).expectStatus().isOk
        get()
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("high-contrast")
            .jsonPath("$.locale")
            .isEqualTo("en")
        put(mapOf("locale" to null)).expectStatus().isOk
        get()
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("high-contrast")
            .jsonPath("$.locale")
            .isEmpty
    }

    @Test
    fun `refuses an invalid theme, locale or field`() {
        put(mapOf("theme" to "Dark Mode"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("theme")
        put(mapOf("theme" to null)).expectStatus().isBadRequest
        put(mapOf("locale" to "not a tag"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("locale")
        put(mapOf("colour" to "red"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("colour")
    }

    @Test
    fun `needs a token`() {
        client
            .get()
            .uri("/api/auth/me/preferences")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .put()
            .uri("/api/auth/me/preferences")
            .bodyValue(mapOf("theme" to "dark"))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
