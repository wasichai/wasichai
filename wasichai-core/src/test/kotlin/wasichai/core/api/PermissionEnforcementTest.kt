package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.test.WasichaiIntegrationTest

// the administrator bypasses every rule, so each test also checks what the admin still sees.
class PermissionEnforcementTest : WasichaiIntegrationTest() {
    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("secured")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to objectName,
                    "label" to "Secured",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "valor", "type" to "DECIMAL"),
                            mapOf("name" to "zona", "type" to "TEXT")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a role with READ only can list records but cannot create one`() {
        val role = newRole("Reader")
        grant(role, listOf("READ"))
        val token = newUserToken(role)

        createRecord(admin, "R-1", 10)

        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-2")))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `hides an unreadable field from metadata and from record reads`() {
        val role = newRole("Blind")
        grant(role, listOf("READ"))
        restrictField(role, "valor", read = false, write = false)
        val token = newUserToken(role)

        val id = createRecord(admin, "R-10", 42)

        val definition =
            client
                .get()
                .uri("/api/metadata/objects/$objectName")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(definition).contains("codigo").doesNotContain("valor")

        client
            .get()
            .uri("/api/metadata/objects/$objectName/fields")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            // codigo and zona; valor is the one this role may not read
            .jsonPath("$.length()")
            .isEqualTo(2)

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("R-10")
            .jsonPath("$.attributes.valor")
            .doesNotExist()

        // the administrator is untouched by the rule
        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.valor")
            .exists()
    }

    @Test
    fun `rejects a write to a field the role cannot write and names it`() {
        val role = newRole("Half")
        grant(role, listOf("READ", "CREATE", "UPDATE"))
        restrictField(role, "valor", read = true, write = false)
        val token = newUserToken(role)

        val fields =
            client
                .get()
                .uri("/api/metadata/objects/$objectName/fields")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(fields.substringAfter("\"name\":\"valor\"")).contains("\"editable\":false")

        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-20", "valor" to 7)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("valor")

        // without the locked attribute the write goes through, and the stored value survives it
        val id = createRecord(admin, "R-21", 99)
        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "R-21b")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("R-21b")
            .jsonPath("$.attributes.valor")
            .exists()
    }

    @Test
    fun `own records only limits the caller to the records they created`() {
        val role = newRole("Owner")
        grant(role, listOf("READ", "CREATE", "UPDATE", "DELETE"))
        ownRecordsOnly(role)
        val token = newUserToken(role)

        val mine = createRecord(token, "MINE", 1)
        val theirs = createRecord(admin, "THEIRS", 2)

        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].attributes.codigo")
            .isEqualTo("MINE")

        // another user's record must look missing, never forbidden
        client
            .get()
            .uri("/api/objects/$objectName/records/$theirs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .put()
            .uri("/api/objects/$objectName/records/$theirs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "STOLEN")))
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .delete()
            .uri("/api/objects/$objectName/records/$theirs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .get()
            .uri("/api/objects/$objectName/records/$mine")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(2)
    }

    @Test
    fun `another organization cannot see this one's object or records`() {
        val id = createRecord(admin, "R-30", 5)
        val slug = "tenant-" + uniqueName("").take(8)

        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to "Other tenant",
                    "slug" to slug,
                    "adminEmail" to "$slug@wasichai.local",
                    "adminPassword" to "supersecret"
                )
            ).exchange()
            .expectStatus()
            .isCreated

        val stranger = bearer("$slug@wasichai.local", "supersecret")

        client
            .get()
            .uri("/api/metadata/objects/$objectName")
            .header(HttpHeaders.AUTHORIZATION, stranger)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, stranger)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .get()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, stranger)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .get()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, stranger)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `a grant naming one object does not reach another object`() {
        val other = uniqueName("other")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to other,
                    "label" to "Other",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))
                )
            ).exchange()
            .expectStatus()
            .isCreated

        val role = newRole("Scoped")
        grantOn(role, objectName, listOf("READ", "CREATE"))
        val token = newUserToken(role)

        createRecord(admin, "S-1", 1)

        // the object it was granted on
        client
            .get()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

        // the object it was not
        client
            .get()
            .uri("/api/objects/$other/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .post()
            .uri("/api/objects/$other/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "X-1")))
            .exchange()
            .expectStatus()
            .isForbidden

        client
            .get()
            .uri("/api/metadata/objects/$other")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `the object list shows only what the caller may read`() {
        val hidden = uniqueName("hidden")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to hidden, "label" to "Hidden"))
            .exchange()
            .expectStatus()
            .isCreated

        val role = newRole("Narrow")
        grantOn(role, objectName, listOf("READ"))
        val token = newUserToken(role)

        val visible =
            client
                .get()
                .uri("/api/objects")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(visible).contains(objectName).doesNotContain(hidden)

        // the admin still reaches the one the member cannot see. asking for it directly keeps the
        // assertion off the tenant-wide list, which the shared test database makes enormous.
        client
            .get()
            .uri("/api/metadata/objects/$hidden")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `the caller's permissions list the actions granted on each readable object`() {
        val role = newRole("Clerk")
        grant(role, listOf("READ", "CREATE"))
        val token = newUserToken(role)

        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.admin")
            .isEqualTo(false)
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ", "CREATE"))
    }

    @Test
    fun `the caller's permissions leave out objects they cannot read`() {
        val other = uniqueName("other")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to other, "label" to "Other"))
            .exchange()
            .expectStatus()
            .isCreated

        val role = newRole("Scoped")
        // one PUT: it replaces every row the role has. create without read keeps `other` out,
        // as it does from GET /api/objects.
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "permissions" to
                        listOf(
                            mapOf("objectName" to objectName, "action" to "READ", "allowed" to true),
                            mapOf("objectName" to objectName, "action" to "UPDATE", "allowed" to true),
                            mapOf("objectName" to other, "action" to "CREATE", "allowed" to true)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
        val token = newUserToken(role)

        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ", "UPDATE"))
            .jsonPath("$.objects.$other")
            .doesNotExist()
    }

    @Test
    fun `the administrator may do every record action on every object`() {
        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.admin")
            .isEqualTo(true)
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ", "CREATE", "UPDATE", "DELETE"))
    }

    // issue 53 (ADR-053): tenant-wide rights are reported too, and agree with what the services enforce
    @Test
    fun `an org-wide MANAGE_METADATA grant is reported as a capability and lets the caller create an object`() {
        val role = newRole("Modeler")
        grant(role, listOf("MANAGE_METADATA"))
        val token = newUserToken(role)

        // no READ anywhere, so no object: the whole answer is known
        assertThat(myPermissions(token)).isEqualTo("""{"admin":false,"capabilities":["MANAGE_METADATA"],"objects":{}}""")

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to uniqueName("modeled"), "label" to "Modeled"))
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `object-level grants give no capability, and the object's actions are the record ones they always were`() {
        val role = newRole("ObjectModeler")
        grantOn(role, objectName, listOf("READ", "UPDATE", "MANAGE_METADATA"))
        val token = newUserToken(role)

        assertThat(myPermissions(token)).isEqualTo(
            """{"admin":false,"capabilities":[],"objects":{"$objectName":["READ","UPDATE"]}}"""
        )
    }

    // MANAGE_TENANTS too: switch off, it is MANAGE_ORGANIZATION's (ADR-055)
    @Test
    fun `the administrator holds every capability, admin and objects unchanged`() {
        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.admin")
            .isEqualTo(true)
            .jsonPath("$.capabilities")
            .isEqualTo(listOf("MANAGE_METADATA", "MANAGE_ORGANIZATION", "MANAGE_TENANTS"))
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ", "CREATE", "UPDATE", "DELETE"))
    }

    @Test
    fun `the caller's permissions need a token`() {
        client
            .get()
            .uri("/api/auth/me/permissions")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `an object grant does not authorise platform-wide metadata management`() {
        val role = newRole("ObjectAdmin")
        grantOn(role, objectName, listOf("READ", "MANAGE_METADATA"))
        val token = newUserToken(role)

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to uniqueName("sneaky"), "label" to "Sneaky"))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    private fun grantOn(
        role: String,
        targetObject: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "permissions" to
                        actions.map { mapOf("objectName" to targetObject, "action" to it, "allowed" to true) }
                )
            ).exchange()
            .expectStatus()
            .isOk
    }

    // the raw body: what is asserted is the bytes a client gets
    private fun myPermissions(token: String): String =
        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun newRole(label: String): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to label, "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun grant(
        role: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) })
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun restrictField(
        role: String,
        fieldName: String,
        read: Boolean,
        write: Boolean
    ) {
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "fields" to
                        listOf(
                            mapOf("objectName" to objectName, "fieldName" to fieldName, "read" to read, "write" to write)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun ownRecordsOnly(role: String) {
        client
            .put()
            .uri("/api/roles/$role")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("ownRecordsOnly" to true))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Member",
                    "password" to "supersecret",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    private fun createRecord(
        token: String,
        codigo: String,
        valor: Int
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo, "valor" to valor)))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")
}
