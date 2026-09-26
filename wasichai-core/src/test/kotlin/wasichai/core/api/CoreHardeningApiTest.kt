package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-0025 known gaps, closed: links obey own_records_only and leave history, and history never
// reads a locked field as cleared. the one deliberate behaviour change against the original in P2.
class CoreHardeningApiTest : WasichaiIntegrationTest() {
    private lateinit var admin: String
    private lateinit var predio: String
    private lateinit var titular: String
    private lateinit var relationship: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        predio = uniqueName("predio")
        titular = uniqueName("titular")
        relationship = uniqueName("rel").take(30)
        createSchema(admin)
    }

    // both objects and the relationship between them, in the token's organization
    private fun createSchema(token: String) {
        createObject(token, predio, listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "valor", "type" to "DECIMAL")))
        createObject(token, titular, listOf(mapOf("name" to "nombre", "type" to "TEXT")))
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to relationship,
                    "label" to "Titulares",
                    "inverseLabel" to "Predios",
                    "type" to "MANY_TO_MANY",
                    "source" to predio,
                    "target" to titular
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `an own-records-only user cannot link or unlink someone else's record`() {
        val token = newUserToken(ownRecordsRole())
        val mine = createRecord(token, predio, mapOf("codigo" to "MINE"))
        val myOwner = createRecord(token, titular, mapOf("nombre" to "Yo"))
        val theirs = createRecord(admin, predio, mapOf("codigo" to "THEIRS"))
        val theirOwner = createRecord(admin, titular, mapOf("nombre" to "Otro"))

        // someone else's record on either end looks missing, as on GET/PUT/DELETE
        link(token, predio, mine, theirOwner).expectStatus().isNotFound
        link(token, predio, theirs, myOwner).expectStatus().isNotFound
        link(admin, predio, theirs, theirOwner).expectStatus().isNoContent
        unlink(token, predio, theirs, theirOwner).expectStatus().isNotFound
        related(admin, predio, theirs).jsonPath("$.totalElements").isEqualTo(1)

        // their own pair still works
        link(token, predio, mine, myOwner).expectStatus().isNoContent
        related(token, predio, mine).jsonPath("$.totalElements").isEqualTo(1)
    }

    @Test
    fun `a record that does not exist in this organization cannot be linked`() {
        val plot = createRecord(admin, predio, mapOf("codigo" to "P-1"))
        val nobody = UUID.randomUUID().toString()

        link(admin, predio, plot, nobody)
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Record $nobody does not exist")
        unlink(admin, predio, plot, nobody).expectStatus().isNotFound
    }

    @Test
    fun `link and unlink leave an UPDATE on both histories, and a repeat leaves nothing`() {
        val plot = createRecord(admin, predio, mapOf("codigo" to "P-1"))
        val owner = createRecord(admin, titular, mapOf("nombre" to "Marta"))

        link(admin, predio, plot, owner).expectStatus().isNoContent
        link(admin, predio, plot, owner).expectStatus().isNoContent
        unlink(admin, predio, plot, owner).expectStatus().isNoContent

        history(admin, predio, plot)
            // newest first: unlink, link, create. the repeated link wrote nothing.
            .jsonPath("$.length()")
            .isEqualTo(3)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("rel:$relationship")
            .jsonPath("$[0].changes[0].before")
            .isEqualTo(owner)
            .jsonPath("$[0].changes[0].after")
            .doesNotExist()
            .jsonPath("$[1].changes[0].field")
            .isEqualTo("rel:$relationship")
            .jsonPath("$[1].changes[0].after")
            .isEqualTo(owner)
        // the other end carries the same two rows, pointing back
        history(admin, titular, owner)
            .jsonPath("$.length()")
            .isEqualTo(3)
            .jsonPath("$[0].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[0].changes[0].field")
            .isEqualTo("rel:$relationship")
            .jsonPath("$[0].changes[0].before")
            .isEqualTo(plot)
            .jsonPath("$[0].changes[0].after")
            .doesNotExist()
            .jsonPath("$[1].operation")
            .isEqualTo("UPDATE")
            .jsonPath("$[1].changes[0].after")
            .isEqualTo(plot)
    }

    @Test
    fun `a missing record on the calling side cannot be linked or unlinked`() {
        val owner = createRecord(admin, titular, mapOf("nombre" to "Marta"))
        val nobody = UUID.randomUUID().toString()

        link(admin, predio, nobody, owner)
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Record $nobody does not exist")
        unlink(admin, predio, nobody, owner).expectStatus().isNotFound
        history(admin, titular, owner).jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `a record of another organization cannot be linked from either side`() {
        val slug = "tenant-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Other tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .exchange()
            .expectStatus()
            .isCreated
        val stranger = bearer("$slug@wasichai.local", "supersecret")
        // same names over there, so only the record ids cross the tenant line
        createSchema(stranger)
        val strangerPlot = createRecord(stranger, predio, mapOf("codigo" to "THEIRS"))
        val strangerOwner = createRecord(stranger, titular, mapOf("nombre" to "Otro"))
        val plot = createRecord(admin, predio, mapOf("codigo" to "OURS"))
        val owner = createRecord(admin, titular, mapOf("nombre" to "Marta"))

        link(admin, predio, plot, strangerOwner).expectStatus().isNotFound
        link(admin, predio, strangerPlot, owner).expectStatus().isNotFound
        link(stranger, predio, strangerPlot, owner).expectStatus().isNotFound
        unlink(stranger, predio, strangerPlot, owner).expectStatus().isNotFound
        related(stranger, predio, strangerPlot).jsonPath("$.totalElements").isEqualTo(0)
        history(admin, titular, owner).jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `UPDATE on one end is not enough -- the other end needs it too`() {
        val role = newRole()
        // per-object grants only (no wildcard "objectName": null row, which would apply to every
        // object and defeat the point): UPDATE on predio, but titular stops at CREATE.
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "permissions" to
                        listOf(
                            mapOf("objectName" to predio, "action" to "READ", "allowed" to true),
                            mapOf("objectName" to predio, "action" to "CREATE", "allowed" to true),
                            mapOf("objectName" to predio, "action" to "UPDATE", "allowed" to true),
                            mapOf("objectName" to titular, "action" to "READ", "allowed" to true),
                            mapOf("objectName" to titular, "action" to "CREATE", "allowed" to true)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
        val token = newUserToken(role)
        val plot = createRecord(token, predio, mapOf("codigo" to "S-1"))
        val owner = createRecord(token, titular, mapOf("nombre" to "Sin permiso"))

        link(token, predio, plot, owner)
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(403)
            .jsonPath("$.type")
            .isEqualTo("https://wasichai.dev/problems/403")

        // no history row landed on the other end either
        history(admin, titular, owner).jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `a disabled other object is refused the same way RecordService refuses it`() {
        val plot = createRecord(admin, predio, mapOf("codigo" to "S-2"))
        val owner = createRecord(admin, titular, mapOf("nombre" to "Marta"))
        client
            .put()
            .uri("/api/objects/$titular")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("label" to titular, "enabled" to false))
            .exchange()
            .expectStatus()
            .isOk

        link(admin, predio, plot, owner)
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Object '$titular' is disabled and accepts no changes")

        history(admin, predio, plot).jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `a field the caller could not write is not reported as cleared`() {
        val role = newRole()
        grant(role, listOf("READ", "CREATE", "UPDATE"))
        lockField(role, predio, "valor")
        val token = newUserToken(role)
        val plot = createRecord(admin, predio, mapOf("codigo" to "S-1", "valor" to 5))

        client
            .put()
            .uri("/api/objects/$predio/records/$plot")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "S-2")))
            .exchange()
            .expectStatus()
            .isOk

        val body =
            history(admin, predio, plot)
                .jsonPath("$[0].changes.length()")
                .isEqualTo(1)
                .jsonPath("$[0].changes[0].field")
                .isEqualTo("codigo")
                .returnResult()
                .responseBody!!
                .decodeToString()
        assertThat(body).doesNotContain("\"valor\"")
    }

    // ---- helpers ----

    private fun createObject(
        token: String,
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        target: String,
        attributes: Map<String, Any>
    ): String =
        client
            .post()
            .uri("/api/objects/$target/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun link(
        token: String,
        target: String,
        id: String,
        otherId: String
    ) = client
        .post()
        .uri("/api/objects/$target/records/$id/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, token)
        .bodyValue(mapOf("otherId" to otherId))
        .exchange()

    private fun unlink(
        token: String,
        target: String,
        id: String,
        otherId: String
    ) = client
        .delete()
        .uri("/api/objects/$target/records/$id/related/$relationship/$otherId")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()

    private fun related(
        token: String,
        target: String,
        id: String
    ) = client
        .get()
        .uri("/api/objects/$target/records/$id/related/$relationship")
        .header(HttpHeaders.AUTHORIZATION, token)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

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

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Hardening", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun ownRecordsRole(): String {
        val role = newRole()
        grant(role, listOf("READ", "CREATE", "UPDATE"))
        client
            .put()
            .uri("/api/roles/$role")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("ownRecordsOnly" to true))
            .exchange()
            .expectStatus()
            .isOk
        return role
    }

    private fun grant(
        role: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    // readable, not writable
    private fun lockField(
        role: String,
        target: String,
        fieldName: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to target, "fieldName" to fieldName, "read" to true, "write" to false))))
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
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
