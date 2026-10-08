package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest

// issue 56 (ADR-055): with separate provisioning, creating and deleting tenants takes MANAGE_TENANTS, granted to a
// role, never implied by ADMIN or MANAGE_ORGANIZATION, and never a service account's. renaming stays MANAGE_ORGANIZATION.
// the demo tenant plays the operator: a role of its own holds the action, bootstrapped by SQL as the guide says.
// nothing here deletes the demo tenant: every DELETE runs in a tenant the test provisioned.
@TestPropertySource(properties = ["wasichai.organizations.separate-provisioning=true"])
class SeparateProvisioningApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val mapper = JsonMapper.builder().build()

    // the seeded ADMIN of the demo tenant: MANAGE_ORGANIZATION and the ADMIN role, no MANAGE_TENANTS row
    private lateinit var admin: String

    // a person of the demo tenant whose role holds MANAGE_ORGANIZATION and, by SQL, MANAGE_TENANTS
    private lateinit var operator: String
    private lateinit var operatorEmail: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        val role = createRole(admin)
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "MANAGE_ORGANIZATION"), token = admin)
        bootstrapTenantsGrant(DEMO, role)
        operatorEmail = "${uniqueName("operator")}@wasichai.local"
        operator = newUser(admin, role, operatorEmail)
    }

    @Test
    fun `the ADMIN role and MANAGE_ORGANIZATION are not enough to create or delete a tenant, renaming still is`() {
        assertThat(provisionStatus(admin)).isEqualTo(403)

        val tenant = provision(operator)
        assertThat(provisionStatus(tenant.admin)).isEqualTo(403)
        send(HttpMethod.DELETE, "/api/organizations/current", status = HttpStatus.FORBIDDEN, token = tenant.admin)
        send(HttpMethod.PUT, "/api/organizations/current", mapOf("name" to "Renamed"), token = tenant.admin)

        // refused, so nothing went: the tenant and its administrator are still there
        assertThat(json(send(HttpMethod.GET, "/api/organizations/current", token = tenant.admin))["name"]).isEqualTo("Renamed")
    }

    @Test
    fun `a role holding MANAGE_TENANTS creates a tenant and deletes its own, each in the admin trail`() {
        val tenant = provision(operator)

        // the provisioner's act, in the provisioner's trail, as before (ADR-049)
        val created = list("/api/audit?objectName=admin:organization&recordId=${tenant.id}&limit=50", operator)
        assertThat(created.map { it["operation"] }).containsExactly("CREATE")
        assertThat(created.single()["userEmail"]).isEqualTo(operatorEmail)

        // the tenant's own operator: its ADMIN role granted the action out of band
        bootstrapTenantsGrant(tenant.slug, "ADMIN")
        send(HttpMethod.DELETE, "/api/organizations/current", status = HttpStatus.NO_CONTENT, token = tenant.admin)

        client
            .post()
            .uri("/api/auth/login")
            .bodyValue(mapOf("email" to tenant.adminEmail, "password" to TENANT_PASSWORD))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a tenant provisioned with the switch on has an ADMIN role without MANAGE_TENANTS`() {
        val tenant = provision(operator)

        val adminRole = list("/api/roles", tenant.admin).single { it["name"] == "ADMIN" }
        assertThat(actions(adminRole)).containsExactlyInAnyOrder(
            "READ",
            "CREATE",
            "UPDATE",
            "DELETE",
            "MANAGE_METADATA",
            "MANAGE_ORGANIZATION"
        )
        val mine = json(send(HttpMethod.GET, "/api/auth/me/permissions", token = tenant.admin))
        assertThat(mine["admin"]).isEqualTo(true)
        assertThat(mine["capabilities"]).isEqualTo(listOf("MANAGE_METADATA", "MANAGE_ORGANIZATION"))
    }

    @Test
    fun `MANAGE_TENANTS is granted through the roles API, by a holder only, with no object`() {
        val role = createRole(operator)
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "MANAGE_TENANTS"), token = operator)
        assertThat(actions(list("/api/roles", operator).single { it["name"] == role })).containsExactly("MANAGE_TENANTS")

        val granted = newUser(operator, role)
        assertThat(json(send(HttpMethod.GET, "/api/auth/me/permissions", token = granted))["capabilities"]).isEqualTo(listOf("MANAGE_TENANTS"))
        provision(granted)
        assertThat(json(send(HttpMethod.GET, "/api/auth/me/permissions", token = operator))["capabilities"])
            .isEqualTo(listOf("MANAGE_ORGANIZATION", "MANAGE_TENANTS"))

        // the tenant's administrator can neither hand it on nor take it away: it would grant itself the operator's power
        val other = createRole(admin)
        send(HttpMethod.PUT, "/api/roles/$other/permissions", permissions(null to "MANAGE_TENANTS"), HttpStatus.FORBIDDEN, admin)
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(), HttpStatus.FORBIDDEN, admin)
        // leaving it as it is stays the administrator's call
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "MANAGE_TENANTS", null to "READ"), token = admin)

        // tenant-wide only, and an unknown action is still a 400
        val objectScoped = send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions("anything" to "MANAGE_TENANTS"), HttpStatus.BAD_REQUEST, operator)
        assertThat(objectScoped).contains("objectName")
        val unknown = send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "MANAGE_EVERYTHING"), HttpStatus.BAD_REQUEST, operator)
        assertThat(unknown).contains("Unknown action 'MANAGE_EVERYTHING'")
    }

    @Test
    fun `a service account is refused tenant routes, even with MANAGE_TENANTS on its role`() {
        // in a tenant of its own, so a wrong answer could only delete that one
        val tenant = provision(operator)
        val role = createRole(tenant.admin)
        bootstrapTenantsGrant(tenant.slug, role)
        val account =
            json(
                send(HttpMethod.POST, "/api/service-accounts", mapOf("name" to uniqueName("rentas"), "roles" to listOf(role)), HttpStatus.CREATED, tenant.admin)
            )
        val issued =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to account["clientId"], "clientSecret" to account["clientSecret"]))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sa = "Bearer ${json(issued)["token"]}"

        assertThat(provisionStatus(sa)).isEqualTo(403)
        send(HttpMethod.DELETE, "/api/organizations/current", status = HttpStatus.FORBIDDEN, token = sa)
        assertThat(json(send(HttpMethod.GET, "/api/auth/me/permissions", token = sa))["capabilities"]).isEqualTo(emptyList<String>())
    }

    // ------------------------------------------------------------------ helpers

    private class Tenant(
        val id: String,
        val slug: String,
        val adminEmail: String,
        val admin: String
    )

    private fun provision(token: String): Tenant {
        val slug = "tenant-" + uniqueName("").take(8)
        val email = "$slug@wasichai.local"
        val created =
            json(
                send(
                    HttpMethod.POST,
                    "/api/organizations",
                    mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to email, "adminPassword" to TENANT_PASSWORD),
                    HttpStatus.CREATED,
                    token
                )
            )
        return Tenant(created["id"] as String, slug, email, bearer(email, TENANT_PASSWORD))
    }

    private fun provisionStatus(token: String): Int {
        val slug = "refused-" + uniqueName("").take(8)
        return client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to "Refused", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to TENANT_PASSWORD))
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()
    }

    // the documented bootstrap (docs/guides/build-your-app.md): one row, written by the operator, not through the API
    private fun bootstrapTenantsGrant(
        slug: String,
        role: String
    ) = runBlocking {
        val inserted =
            db
                .sql(
                    """
                    INSERT INTO ${schemas.metadata}.permissions (role_id, object_id, action)
                    SELECT r.id, NULL, 'MANAGE_TENANTS'
                    FROM ${schemas.metadata}.roles r
                    JOIN ${schemas.metadata}.organizations o ON o.id = r.organization_id
                    WHERE o.slug = :slug AND r.name = :role
                    """.trimIndent()
                ).bind("slug", slug)
                .bind("role", role)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        assertThat(inserted).isEqualTo(1L)
    }

    private fun createRole(token: String): String {
        val name = "R" + uniqueName("").uppercase()
        send(HttpMethod.POST, "/api/roles", mapOf("name" to name, "label" to "Tenants"), HttpStatus.CREATED, token)
        return name
    }

    private fun newUser(
        token: String,
        role: String,
        email: String = "${uniqueName("caller")}@wasichai.local"
    ): String {
        send(
            HttpMethod.POST,
            "/api/users",
            mapOf("email" to email, "displayName" to "Caller", "password" to "caller-password", "roles" to listOf(role)),
            HttpStatus.CREATED,
            token
        )
        return bearer(email, "caller-password")
    }

    private fun permissions(vararg grants: Pair<String?, String>) =
        mapOf("permissions" to grants.map { (obj, action) -> mapOf("objectName" to obj, "action" to action, "allowed" to true) })

    @Suppress("UNCHECKED_CAST")
    private fun actions(role: Map<String, Any?>): List<String> = (role["permissions"] as List<Map<String, Any?>>).map { it["action"] as String }

    private fun send(
        method: HttpMethod,
        uri: String,
        body: Any? = null,
        status: HttpStatus = HttpStatus.OK,
        token: String
    ): String {
        val request = client.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, token)
        val spec = if (body != null) request.bodyValue(body) else request
        return spec
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: ""
    }

    private fun json(body: String): Map<String, Any?> = mapper.readValue(body, object : TypeReference<Map<String, Any?>>() {})

    private fun list(
        uri: String,
        token: String
    ): List<Map<String, Any?>> = mapper.readValue(send(HttpMethod.GET, uri, token = token), object : TypeReference<List<Map<String, Any?>>>() {})

    private companion object {
        const val DEMO = "demo"
        const val TENANT_PASSWORD = "supersecret"
    }
}
