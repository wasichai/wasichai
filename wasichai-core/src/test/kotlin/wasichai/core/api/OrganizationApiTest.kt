package wasichai.core.api

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.test.WasichaiIntegrationTest

class OrganizationApiTest : WasichaiIntegrationTest() {
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

    // issue 56 (ADR-055): switch off, the administrator provisions as before, yet hands MANAGE_TENANTS to nobody,
    // so no tenant holds it by the time the switch goes on
    @Test
    fun `by default MANAGE_TENANTS is the administrator's, but only a holder of the grant hands it on`() {
        val token = bearer()
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to role, "label" to "Operator"))
            .exchange()
            .expectStatus()
            .isCreated

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "MANAGE_TENANTS", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "MANAGE_EVERYTHING", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.capabilities[2]")
            .isEqualTo("MANAGE_TENANTS")
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
