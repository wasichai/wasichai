package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class AdminApiTest : FullAppIntegrationTest() {
    @Test
    fun `creates a user, lists it, changes its roles and disables it`() {
        val token = bearer()
        val first = newRole(token, "First")
        val second = newRole(token, "Second")
        val email = "${uniqueName("user")}@wasichai.local"

        val id = createUser(token, email, "supersecret", listOf(first))

        val listed =
            client
                .get()
                .uri("/api/users")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(listed).contains(email).contains(first)
        // a hash must never travel back
        assertThat(listed).doesNotContain("passwordHash").doesNotContain("bcrypt")

        // the new user can sign in before we disable them
        bearer(email, "supersecret")

        client
            .put()
            .uri("/api/users/$id/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("roles" to listOf(first, second)))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.roles.length()")
            .isEqualTo(2)

        client
            .put()
            .uri("/api/users/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("displayName" to "Renamed", "enabled" to false))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.displayName")
            .isEqualTo("Renamed")
            .jsonPath("$.enabled")
            .isEqualTo(false)

        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to email, "password" to "supersecret"))
            .exchange()
            .expectStatus()
            .isUnauthorized

        client
            .delete()
            .uri("/api/users/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        deleteRole(token, first)
        deleteRole(token, second)
    }

    @Test
    fun `refuses to delete or disable the caller's own account`() {
        val token = bearer()
        val me =
            client
                .get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"userId\":\"")
                .substringBefore("\"")

        client
            .put()
            .uri("/api/users/$me")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("enabled" to false))
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .delete()
            .uri("/api/users/$me")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `refuses to delete the ADMIN role`() {
        client
            .delete()
            .uri("/api/roles/ADMIN")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { assertThat(it).contains("ADMIN") }
    }

    @Test
    fun `rejects a duplicate role name and a malformed one`() {
        val token = bearer()
        val name = newRole(token, "Duplicated")

        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Again", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isEqualTo(409)

        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "bad name!", "label" to "Bad", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("name")

        deleteRole(token, name)
    }

    @Test
    fun `rejects a short password on create and on update`() {
        val token = bearer()
        val email = "${uniqueName("short")}@wasichai.local"

        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("email" to email, "displayName" to "Short", "password" to "abc", "roles" to emptyList<String>()))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("password")

        val id = createUser(token, email, "supersecret", emptyList())

        client
            .put()
            .uri("/api/users/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("password" to "abc"))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .delete()
            .uri("/api/users/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    @Test
    fun `replaces the action and field rules of a role`() {
        val token = bearer()
        val role = newRole(token, "Rules")
        val objectName = createObject(token)

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "permissions" to
                        listOf(
                            mapOf("objectName" to null, "action" to "READ", "allowed" to true),
                            mapOf("objectName" to objectName, "action" to "UPDATE", "allowed" to true)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.permissions.length()")
            .isEqualTo(2)

        // a second call replaces, it does not append
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.permissions.length()")
            .isEqualTo(1)

        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "fields" to
                        listOf(mapOf("objectName" to objectName, "fieldName" to "valor", "read" to true, "write" to false))
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.fieldPermissions[0].fieldName")
            .isEqualTo("valor")
            .jsonPath("$.fieldPermissions[0].write")
            .isEqualTo(false)

        client
            .put()
            .uri("/api/roles/$role")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Renamed rules", "ownRecordsOnly" to true))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Renamed rules")
            .jsonPath("$.ownRecordsOnly")
            .isEqualTo(true)

        deleteRole(token, role)
    }

    @Test
    fun `rejects an unknown object, field or action in a rule payload`() {
        val token = bearer()
        val role = newRole(token, "Unknown")
        val objectName = createObject(token)

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "FLY", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to "nope", "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isBadRequest

        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf("fields" to listOf(mapOf("objectName" to objectName, "fieldName" to "nope", "read" to false, "write" to false)))
            ).exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("fieldName")

        deleteRole(token, role)
    }

    // ------------------------------------------------------------------ helpers

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

    private fun deleteRole(
        token: String,
        name: String
    ) {
        client
            .delete()
            .uri("/api/roles/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
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

    private fun createObject(token: String): String {
        val name = uniqueName("admin")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Admin object",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "valor", "type" to "DECIMAL")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }
}
