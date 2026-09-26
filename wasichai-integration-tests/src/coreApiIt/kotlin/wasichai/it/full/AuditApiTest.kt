package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

// the history is a read of the record: what the caller cannot see on the record it cannot see here.
class AuditApiTest : FullAppIntegrationTest() {
    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = uniqueName("audited")
        createObject(objectName)
    }

    @Test
    fun `an update reports one change per changed field and leaves the rest out`() {
        val id = createRecord(admin, "R-1", 10)
        updateRecord(admin, id, "R-2", 20)

        val body =
            history(admin, objectName, id)
                .jsonPath("$[0].operation")
                .isEqualTo("UPDATE")
                .jsonPath("$[0].changes.length()")
                .isEqualTo(2)
                .jsonPath("$[0].changes[?(@.field == 'codigo')].before")
                .isEqualTo("R-1")
                .jsonPath("$[0].changes[?(@.field == 'codigo')].after")
                .isEqualTo("R-2")
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(body).contains("\"field\":\"valor\"")

        // a second update touching one field only reports that field
        updateRecord(admin, id, "R-3", 20)
        history(admin, objectName, id)
            .jsonPath("$[0].changes.length()")
            .isEqualTo(1)
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("codigo")
            .jsonPath("$[0].changes[0].after")
            .isEqualTo("R-3")
    }

    @Test
    fun `create and delete carry no changes`() {
        val id = createRecord(admin, "R-10", 1)
        deleteRecord(admin, id)

        history(admin, objectName, id)
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].operation")
            .isEqualTo("DELETE")
            .jsonPath("$[0].changes.length()")
            .isEqualTo(0)
            .jsonPath("$[1].operation")
            .isEqualTo("CREATE")
            .jsonPath("$[1].changes.length()")
            .isEqualTo(0)
    }

    @Test
    fun `record history is newest first and holds only that record`() {
        val mine = createRecord(admin, "M-1", 1)
        val other = createRecord(admin, "O-1", 2)
        updateRecord(admin, other, "O-2", 2)
        updateRecord(admin, mine, "M-2", 1)

        history(admin, objectName, mine)
            .jsonPath("$.length()")
            .isEqualTo(2)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[1].operation")
            .isEqualTo("CREATE")
            .jsonPath("$[0].recordId")
            .isEqualTo(mine)
            .jsonPath("$[1].recordId")
            .isEqualTo(mine)
            .jsonPath("$[0].userEmail")
            .isEqualTo("admin@wasichai.local")
            .jsonPath("$[0].changes[0].after")
            .isEqualTo("M-2")
    }

    @Test
    fun `the audit list filters by object and by operation`() {
        val id = createRecord(admin, "F-1", 3)
        updateRecord(admin, id, "F-2", 3)

        audit(admin, "/api/audit?objectName=$objectName")
            .jsonPath("$.length()")
            .isEqualTo(2)

        audit(admin, "/api/audit?objectName=$objectName&operation=UPDATE")
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].objectName")
            .isEqualTo(objectName)
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("codigo")

        // another object's rows never show up under this filter
        val other = uniqueName("otheraudited")
        createObject(other)
        createRecord(admin, "X-1", 9, other)
        audit(admin, "/api/audit?objectName=$objectName")
            .jsonPath("$.length()")
            .isEqualTo(2)
        audit(admin, "/api/audit?objectName=$other&recordId=$id")
            .jsonPath("$.length()")
            .isEqualTo(0)
    }

    @Test
    fun `a field the role cannot read never appears in the history`() {
        val role = newRole("AuditBlind")
        grant(role, listOf("READ"))
        restrictField(role, "valor", read = false, write = false)
        val token = newUserToken(role)

        val id = createRecord(admin, "S-1", 5)
        updateRecord(admin, id, "S-2", 50)

        val hidden =
            history(token, objectName, id)
                .jsonPath("$[0].changes.length()")
                .isEqualTo(1)
                .jsonPath("$[0].changes[0].field")
                .isEqualTo("codigo")
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(hidden).doesNotContain("valor")

        // the tenant-wide list is filtered the same way
        val listed =
            audit(token, "/api/audit?objectName=$objectName&operation=UPDATE")
                .jsonPath("$[0].changes.length()")
                .isEqualTo(1)
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(listed).doesNotContain("valor")

        // the administrator still sees both fields
        val full =
            history(admin, objectName, id)
                .jsonPath("$[0].changes.length()")
                .isEqualTo(2)
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(full).contains("valor")
    }

    @Test
    fun `a grant on one object does not open another object's history`() {
        val other = uniqueName("forbidden")
        createObject(other)
        val id = createRecord(admin, "P-1", 1, other)

        val role = newRole("AuditScoped")
        grantOn(role, objectName, listOf("READ"))
        val token = newUserToken(role)

        val mine = createRecord(admin, "P-2", 2)
        client
            .get()
            .uri("/api/objects/$objectName/records/$mine/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

        client
            .get()
            .uri("/api/objects/$other/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden

        // the object grant is not the organization-wide read the audit list asks for
        client
            .get()
            .uri("/api/audit?objectName=$objectName")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    // ------------------------------------------------------------------ helpers

    private fun history(
        token: String,
        target: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$target/records/$id/history")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun audit(
        token: String,
        uri: String
    ) = client
        .get()
        .uri(uri)
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Audited",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "valor", "type" to "DECIMAL")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        codigo: String,
        valor: Int,
        target: String = objectName
    ): String =
        client
            .post()
            .uri("/api/objects/$target/records")
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

    private fun updateRecord(
        token: String,
        id: String,
        codigo: String,
        valor: Int
    ) {
        client
            .put()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to codigo, "valor" to valor)))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun deleteRecord(
        token: String,
        id: String
    ) {
        client
            .delete()
            .uri("/api/objects/$objectName/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent
    }

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

    private fun grantOn(
        role: String,
        target: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "permissions" to actions.map { mapOf("objectName" to target, "action" to it, "allowed" to true) }
                )
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

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("auditor")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Auditor",
                    "password" to "supersecret",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
