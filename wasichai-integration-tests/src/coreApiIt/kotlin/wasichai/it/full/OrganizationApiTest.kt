package wasichai.it.full

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class OrganizationApiTest : FullAppIntegrationTest() {
    @Test
    fun `returns and renames the caller's organization`() {
        val token = bearer()

        client
            .get()
            .uri("/api/organizations/current")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.slug")
            .isEqualTo("demo")

        client
            .put()
            .uri("/api/organizations/current")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "Demo renamed"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo("Demo renamed")

        // put it back so other tests see the seed as it was
        client
            .put()
            .uri("/api/organizations/current")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "Demo"))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `provisions a tenant whose administrator can sign in`() {
        val slug = "tenant-" + uniqueName("").take(8)

        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .bodyValue(
                mapOf(
                    "name" to "Tenant",
                    "slug" to slug,
                    "adminEmail" to "$slug@wasichai.local",
                    "adminPassword" to "supersecret"
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.slug")
            .isEqualTo(slug)

        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to "$slug@wasichai.local", "password" to "supersecret"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.user.roles[0]")
            .isEqualTo("ADMIN")
    }

    @Test
    fun `rejects a short administrator password and a bad slug`() {
        val token = bearer()

        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to "Tenant",
                    "slug" to "Bad Slug",
                    "adminEmail" to "a@b.com",
                    "adminPassword" to "supersecret"
                )
            ).exchange()
            .expectStatus()
            .isBadRequest

        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to "Tenant",
                    "slug" to "another-tenant",
                    "adminEmail" to "a@b.com",
                    "adminPassword" to "short"
                )
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("adminPassword")
    }
}
