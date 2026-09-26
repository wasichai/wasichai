package wasichai.it.full

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class UserPreferencesIsolationTest : FullAppIntegrationTest() {
    @Test
    fun `each caller reads and writes only their own preferences`() {
        val admin = bearer()
        val email = "${uniqueName("prefs")}@wasichai.local"
        createUser(admin, email, "supersecret", listOf(newRole(admin, "Prefs")))
        val other = bearer(email, "supersecret")

        client
            .put()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, other)
            .bodyValue(mapOf("theme" to "dark"))
            .exchange()
            .expectStatus()
            .isOk

        client
            .get()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("system")
        client
            .get()
            .uri("/api/auth/me/preferences")
            .header(HttpHeaders.AUTHORIZATION, other)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.theme")
            .isEqualTo("dark")
    }

    // ------------------------------------------------------------------ helpers (copied from AdminApiTest: private there)

    private fun newRole(
        token: String,
        label: String
    ): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to label, "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo(name)
        return name
    }

    private fun createUser(
        token: String,
        email: String,
        password: String,
        roles: List<String>
    ): String {
        val body =
            client
                .post()
                .uri("/api/users")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(
                    mapOf("email" to email, "displayName" to "Tester", "password" to password, "roles" to roles)
                ).exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return body.substringAfter("\"id\":\"").substringBefore("\"")
    }
}
