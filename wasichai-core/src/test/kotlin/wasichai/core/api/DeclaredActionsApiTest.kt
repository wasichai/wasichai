package wasichai.core.api

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomObjectRepository
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// app-declared actions (ADR-042): declared on an object, granted like CRUD, checked by CurrentUser, listed for the caller
class DeclaredActionsApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var currentUser: CurrentUser

    @Autowired
    private lateinit var objects: CustomObjectRepository

    private lateinit var admin: String
    private lateinit var objectName: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        objectName = createObject(uniqueName("recibo"))
    }

    @Test
    fun `an object declares an action, lists it and refuses a bad or repeated name`() {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "anular_ajeno", "label" to "Anular recibo ajeno"))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo("ANULAR_AJENO")
            .jsonPath("$.label")
            .isEqualTo("Anular recibo ajeno")

        client
            .get()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo("ANULAR_AJENO")

        assertThat(statusOf("ANULAR_AJENO")).isEqualTo(409)
        // a built-in action is not the app's to declare
        assertThat(statusOf("delete")).isEqualTo(400)
        assertThat(statusOf("MANAGE_METADATA")).isEqualTo(400)
        assertThat(statusOf("ANULAR AJENO")).isEqualTo(400)
        assertThat(statusOf("9LIVES")).isEqualTo(400)

        client
            .post()
            .uri("/api/metadata/objects/${uniqueName("missing")}/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "COBRAR"))
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `declaring an action needs MANAGE_METADATA`() {
        val role = newRole()
        grantOn(role, listOf(objectName to "READ"))
        val token = newUserToken(role)

        client
            .post()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "COBRAR"))
            .exchange()
            .expectStatus()
            .isForbidden

        // reading what the object declares only needs READ on it
        client
            .get()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a declared action is granted on its object, an undeclared one is still unknown`() {
        declare("ANULAR_AJENO")
        val role = newRole()

        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(permissions(listOf(objectName to "READ", objectName to "anular_ajeno")))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.permissions[?(@.action == 'ANULAR_AJENO')].objectName")
            .isEqualTo(listOf(objectName))

        unknownAction(role, objectName, "REIMPRIMIR")
        // declared on one object, so it means nothing tenant-wide
        unknownAction(role, null, "ANULAR_AJENO")
        // nor on another object
        unknownAction(role, createObject(uniqueName("cierre")), "ANULAR_AJENO")
    }

    @Test
    fun `requirePermission checks a declared action, and the administrator holds it`() {
        declare("ANULAR_AJENO")
        val holder = newRole()
        grantOn(holder, listOf(objectName to "READ", objectName to "ANULAR_AJENO"))
        val other = newRole()
        grantOn(other, listOf(objectName to "READ"))
        val objectId = objectId()

        runBlocking {
            currentUser.requirePermission(userWith(holder), "ANULAR_AJENO", objectId)
            currentUser.requirePermission(userWith(AuthenticatedUser.ADMIN_ROLE), "ANULAR_AJENO", objectId)
        }
        assertThatThrownBy { runBlocking { currentUser.requirePermission(userWith(other), "ANULAR_AJENO", objectId) } }
            .isInstanceOf(ForbiddenException::class.java)
        // granted on this object only
        val elsewhere = createObject(uniqueName("cierre")).let { name -> runBlocking { objects.findByName(organizationId(), name)!!.id } }
        assertThatThrownBy { runBlocking { currentUser.requirePermission(userWith(holder), "ANULAR_AJENO", elsewhere) } }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `the caller's permissions list the declared actions they hold`() {
        declare("ANULAR_AJENO")
        declare("COBRAR")
        val role = newRole()
        grantOn(role, listOf(objectName to "READ", objectName to "CREATE", objectName to "COBRAR"))
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
            .isEqualTo(listOf("READ", "CREATE", "COBRAR"))

        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ", "CREATE", "UPDATE", "DELETE", "ANULAR_AJENO", "COBRAR"))
    }

    @Test
    fun `removing a declared action removes its grants`() {
        declare("ANULAR_AJENO")
        val role = newRole()
        grantOn(role, listOf(objectName to "READ", objectName to "ANULAR_AJENO"))
        val token = newUserToken(role)

        client
            .delete()
            .uri("/api/metadata/objects/$objectName/actions/anular_ajeno")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent

        client
            .delete()
            .uri("/api/metadata/objects/$objectName/actions/ANULAR_AJENO")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound

        client
            .get()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(0)

        val roles =
            client
                .get()
                .uri("/api/roles")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(roles.substringAfter("\"name\":\"$role\"").substringBefore("\"fieldPermissions\"")).doesNotContain("ANULAR_AJENO")

        client
            .get()
            .uri("/api/auth/me/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.objects.$objectName")
            .isEqualTo(listOf("READ"))

        assertThatThrownBy { runBlocking { currentUser.requirePermission(userWith(role), "ANULAR_AJENO", objectId()) } }
            .isInstanceOf(ForbiddenException::class.java)

        // declared again, it starts with no grants
        declare("ANULAR_AJENO")
        assertThatThrownBy { runBlocking { currentUser.requirePermission(userWith(role), "ANULAR_AJENO", objectId()) } }
            .isInstanceOf(ForbiddenException::class.java)
    }

    // ------------------------------------------------------------------ helpers

    private fun createObject(name: String): String {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Recibo", "fields" to listOf(mapOf("name" to "numero", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun declare(action: String) {
        client
            .post()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to action))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun statusOf(action: String): Int =
        client
            .post()
            .uri("/api/metadata/objects/$objectName/actions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to action))
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    private fun unknownAction(
        role: String,
        target: String?,
        action: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(permissions(listOf(target to action)))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown action '$action'")
    }

    private fun permissions(entries: List<Pair<String?, String>>) =
        mapOf("permissions" to entries.map { (target, action) -> mapOf("objectName" to target, "action" to action, "allowed" to true) })

    private fun grantOn(
        role: String,
        entries: List<Pair<String?, String>>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(permissions(entries))
            .exchange()
            .expectStatus()
            .isOk
    }

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Cajero", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("cajero")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Cajero", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }

    private fun organizationId(): UUID =
        UUID.fromString(
            client
                .get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .substringAfter("\"organizationId\":\"")
                .substringBefore("\"")
        )

    private fun objectId(): UUID = runBlocking { objects.findByName(organizationId(), objectName)!!.id }

    // requirePermission takes the caller as given: roles are what the token would carry
    private fun userWith(role: String) = AuthenticatedUser(UUID.randomUUID(), organizationId(), "cajero@wasichai.local", listOf(role))
}
