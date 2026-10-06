package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

/**
 * A tenant of its own for one notifications test class. The database is shared and an audience of
 * ALL means everyone in the organization, so each class provisions a fresh organization: what it
 * counts in an inbox is only what it published.
 *
 * Provision it once per class, with the context's beans:
 * ```
 * @TestInstance(TestInstance.Lifecycle.PER_CLASS)
 * class InboxTest : FullAppIntegrationTest() {
 *     @Autowired private lateinit var db: DatabaseClient
 *     @Autowired private lateinit var schemas: WasichaiSchemas
 *     private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }
 *
 *     @Test fun `...`() {
 *         val role = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ")))
 *         val unit = tenant.createUnit("OBRAS")
 *         val ana = tenant.createUser(roles = listOf(role), units = listOf(unit.code))
 *         client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, ana.token)...
 *     }
 * }
 * ```
 * Without PER_CLASS, JUnit makes an instance per test and the lazy tenant is provisioned per test:
 * correct, only slower.
 */
class NotificationsTenant private constructor(
    private val client: WebTestClient,
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    val organizationId: UUID,
    val slug: String,
    val adminEmail: String,
    // "Bearer ..." of the tenant's ADMIN: provisioning gives the organization one
    val admin: String,
    val adminId: UUID
) {
    /** A user of this tenant, signed in: [token] is the "Bearer ..." header value. */
    data class User(
        val id: UUID,
        val email: String,
        val token: String
    )

    /** An organizational unit: notifications address a unit by code, rows reference it by id. */
    data class OrgUnit(
        val id: UUID,
        val code: String
    )

    /** One grant of a role: [objectName] null means every object of the organization. */
    data class Grant(
        val action: String,
        val objectName: String? = null,
        val allowed: Boolean = true
    )

    /** A fresh role holding [permissions]; returns its name, which is what users and audiences carry. */
    fun createRole(
        name: String = "R" + unique().uppercase(),
        permissions: List<Grant> = emptyList(),
        ownRecordsOnly: Boolean = false
    ): String {
        exchange("POST", "/api/roles", mapOf("name" to name, "label" to name, "ownRecordsOnly" to ownRecordsOnly), HttpStatus.CREATED)
        if (permissions.isNotEmpty()) {
            val entries = permissions.map { mapOf("objectName" to it.objectName, "action" to it.action, "allowed" to it.allowed) }
            exchange("PUT", "/api/roles/$name/permissions", mapOf("permissions" to entries), HttpStatus.OK)
        }
        return name
    }

    /** A fresh user with [roles], sitting in the units [units] (codes), signed in. */
    fun createUser(
        roles: List<String> = emptyList(),
        units: List<String> = emptyList(),
        displayName: String = "User ${unique().take(6)}"
    ): User {
        val email = "${unique("user")}@$slug.local"
        val created =
            exchange(
                "POST",
                "/api/users",
                mapOf("email" to email, "displayName" to displayName, "password" to PASSWORD, "roles" to roles),
                HttpStatus.CREATED
            )
        val id = UUID.fromString(created.get("id").asString())
        if (units.isNotEmpty()) addToUnits(id, *units.toTypedArray())
        return User(id, email, login(email))
    }

    // units by sql: POST /api/org-units comes with the admin task; the tables are core's (V9) either way

    /** A unit under [parent] (a code), or a root one. [code] must match ^[A-Z][A-Z0-9_]{1,48}$. */
    fun createUnit(
        code: String = "U" + unique().uppercase(),
        label: String = code,
        parent: String? = null
    ): OrgUnit {
        val parentId = parent?.let { unitId(it) }
        val id =
            runBlocking {
                db
                    .sql(
                        "INSERT INTO ${schemas.metadata}.org_units (organization_id, parent_id, code, label) " +
                            "VALUES (:org, :parent, :code, :label) RETURNING id"
                    ).bind("org", organizationId)
                    .let { if (parentId == null) it.bindNull("parent", UUID::class.java) else it.bind("parent", parentId) }
                    .bind("code", code)
                    .bind("label", label)
                    .map { row, _ -> row.get("id", UUID::class.java)!! }
                    .one()
                    .awaitSingle()
            }
        return OrgUnit(id, code)
    }

    /** Puts [userId] in the units [codes] of this tenant, keeping the ones it has. */
    fun addToUnits(
        userId: UUID,
        vararg codes: String
    ) {
        codes.forEach { code ->
            runBlocking {
                db
                    .sql("INSERT INTO ${schemas.metadata}.user_org_units (user_id, unit_id) VALUES (:user, :unit) ON CONFLICT DO NOTHING")
                    .bind("user", userId)
                    .bind("unit", unitId(code))
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
            }
        }
    }

    /** A custom object of this tenant with [fields] (name to type, e.g. "vence" to "DATE"); returns its name. */
    fun createObject(
        fields: Map<String, String> = mapOf("codigo" to "TEXT"),
        name: String = unique("obj")
    ): String {
        exchange(
            "POST",
            "/api/objects",
            mapOf("name" to name, "label" to name, "fields" to fields.map { (field, type) -> mapOf("name" to field, "type" to type) }),
            HttpStatus.CREATED
        )
        return name
    }

    /** A record of [objectName] created by the tenant's admin; returns its id. */
    fun createRecord(
        objectName: String,
        attributes: Map<String, Any?>
    ): UUID = UUID.fromString(exchange("POST", "/api/objects/$objectName/records", mapOf("attributes" to attributes), HttpStatus.CREATED).get("id").asString())

    /** "Bearer ..." for [email]; every user this fixture makes has [PASSWORD]. */
    fun login(
        email: String,
        password: String = PASSWORD
    ): String = login(client, email, password)

    private fun unitId(code: String): UUID =
        runBlocking {
            db
                .sql("SELECT id FROM ${schemas.metadata}.org_units WHERE organization_id = :org AND code = :code")
                .bind("org", organizationId)
                .bind("code", code)
                .map { row, _ -> row.get("id", UUID::class.java)!! }
                .one()
                .awaitSingle()
        }

    // as the tenant's admin; fails with what the server said, then the parsed body
    private fun exchange(
        method: String,
        uri: String,
        body: Any,
        expected: HttpStatus
    ): JsonNode = call(client, method, uri, admin, body, expected)

    companion object {
        const val PASSWORD = "supersecret"

        private val json = JsonMapper.builder().build()

        /** A new organization and its ADMIN, created by the seeded platform administrator. */
        fun provision(
            client: WebTestClient,
            db: DatabaseClient,
            schemas: WasichaiSchemas
        ): NotificationsTenant {
            val platform = login(client, WasichaiIntegrationTest.ADMIN_EMAIL, WasichaiIntegrationTest.ADMIN_PASSWORD)
            val slug = "notif-" + unique().take(10)
            val adminEmail = "admin@$slug.local"
            val organization =
                call(
                    client,
                    "POST",
                    "/api/organizations",
                    platform,
                    mapOf("name" to "Notifications $slug", "slug" to slug, "adminEmail" to adminEmail, "adminPassword" to PASSWORD),
                    HttpStatus.CREATED
                )
            val admin = login(client, adminEmail, PASSWORD)
            val me = call(client, "GET", "/api/auth/me", admin, null, HttpStatus.OK)
            return NotificationsTenant(
                client = client,
                db = db,
                schemas = schemas,
                organizationId = UUID.fromString(organization.get("id").asString()),
                slug = slug,
                adminEmail = adminEmail,
                admin = admin,
                adminId = UUID.fromString(me.get("userId").asString())
            )
        }

        private fun unique(prefix: String = ""): String =
            prefix +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)

        private fun login(
            client: WebTestClient,
            email: String,
            password: String
        ): String =
            "Bearer " + call(client, "POST", "/api/auth/login", null, mapOf("email" to email, "password" to password), HttpStatus.OK).get("token").asString()

        private fun call(
            client: WebTestClient,
            method: String,
            uri: String,
            token: String?,
            body: Any?,
            expected: HttpStatus
        ): JsonNode {
            val request =
                client
                    .method(HttpMethod.valueOf(method))
                    .uri(uri)
                    .headers { headers -> token?.let { headers.set(HttpHeaders.AUTHORIZATION, it) } }
            val result = (if (body == null) request else request.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
            // never the request body: a login carries the password
            if (result.status.value() != expected.value()) {
                throw AssertionError("$method $uri answered ${result.status}, expected $expected\nresponse body: ${result.responseBody}")
            }
            return json.readTree(result.responseBody ?: "null")
        }
    }
}
