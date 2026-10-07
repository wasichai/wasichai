package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.admin.AdminService
import wasichai.core.admin.CreateRoleRequest
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 49 (ADR-049): changes to users, roles, permissions, service accounts, units, the model and the tenant are in
// the audit log, under admin:* names, for MANAGE_ORGANIZATION only, and never with a secret in them
class AdminAuditApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var adminService: AdminService

    @Autowired
    private lateinit var transactions: TransactionalOperator

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private val mapper = JsonMapper.builder().build()
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    // ------------------------------------------------------------------ writers

    @Test
    fun `each user change is one entry with the actor, the user and what changed`() {
        val role = createRole("Clerk")
        val email = "${uniqueName("audited")}@wasichai.local"
        val id = createUser(email, "first-password", listOf(role))

        send(HttpMethod.PUT, "/api/users/$id", mapOf("displayName" to "Renamed", "password" to "second-password"))
        send(HttpMethod.PUT, "/api/users/$id/roles", mapOf("roles" to emptyList<String>()))
        send(HttpMethod.DELETE, "/api/users/$id", status = HttpStatus.NO_CONTENT)

        val entries = entries("admin:user", id)
        assertThat(entries.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "UPDATE", "CREATE")
        assertThat(entries).allSatisfy { entry ->
            assertThat(entry["userEmail"]).isEqualTo(ADMIN_EMAIL)
            assertThat(entry["recordId"]).isEqualTo(id)
            assertThat(entry["objectName"]).isEqualTo("admin:user")
        }
        assertThat(changes(entries[3])).containsEntry("email", null to email)
        assertThat(changes(entries[2])).containsOnlyKeys("displayName", "passwordChanged")
        assertThat(changes(entries[2])["passwordChanged"]).isEqualTo(null to true)
        assertThat(changes(entries[1])).containsOnlyKeys("roles")
        assertThat(changes(entries[1])["roles"]).isEqualTo(listOf(role) to emptyList<String>())
        assertThat(changes(entries[0])).containsEntry("email", email to null)
    }

    @Test
    fun `each role change is one entry, and a replaced permission set diffs to the grants that changed`() {
        val obj = createObject()
        val role = createRole("Before")
        val roleId = roleId(role)

        send(HttpMethod.PUT, "/api/roles/$role", mapOf("label" to "After"))
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "READ", obj to "UPDATE", obj to "DELETE"))
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "READ", obj to "UPDATE", null to "CREATE"))
        send(HttpMethod.PUT, "/api/roles/$role/field-permissions", fieldPermissions(obj, "codigo", read = true, write = false))
        send(HttpMethod.DELETE, "/api/roles/$role", status = HttpStatus.NO_CONTENT)

        val roleEntries = entries("admin:role", roleId)
        assertThat(roleEntries.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "CREATE")
        assertThat(changes(roleEntries[1])).containsOnlyKeys("label")
        // a deleted role's entry says what it could do
        assertThat((changes(roleEntries[0])["permissions"]!!.first as Map<*, *>).keys).containsExactlyInAnyOrder("*.READ", "$obj.UPDATE", "*.CREATE")

        val permissionEntries = entries("admin:permission", roleId)
        assertThat(permissionEntries.map { it["operation"] }).containsExactly("UPDATE", "UPDATE", "UPDATE")
        // the second replace kept READ and UPDATE: only the dropped DELETE and the new CREATE show
        assertThat(changes(permissionEntries[1])).isEqualTo(mapOf("$obj.DELETE" to (true to null), "*.CREATE" to (null to true)))
        assertThat(changes(permissionEntries[2])).isEqualTo(
            mapOf("*.READ" to (null to true), "$obj.UPDATE" to (null to true), "$obj.DELETE" to (null to true))
        )
        assertThat(changes(permissionEntries[0])).isEqualTo(mapOf("$obj.codigo" to (null to mapOf("read" to true, "write" to false))))
        // both whole sets are stored
        val stored = states("admin:permission", roleId)[1]
        assertThat(stored.first!!.keys).containsExactlyInAnyOrder("role", "*.READ", "$obj.UPDATE", "$obj.DELETE")
        assertThat(stored.second!!.keys).containsExactlyInAnyOrder("role", "*.READ", "$obj.UPDATE", "*.CREATE")
    }

    @Test
    fun `service account changes are audited, a rotation says only that it happened`() {
        val role = createRole("Sync")
        val created = json(send(HttpMethod.POST, "/api/service-accounts", mapOf("name" to uniqueName("sync"), "roles" to listOf(role)), HttpStatus.CREATED))
        val id = created["id"] as String

        send(HttpMethod.PUT, "/api/service-accounts/$id", mapOf("enabled" to false))
        send(HttpMethod.POST, "/api/service-accounts/$id/secret")
        send(HttpMethod.DELETE, "/api/service-accounts/$id", status = HttpStatus.NO_CONTENT)

        val entries = entries("admin:service-account", id)
        assertThat(entries.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "UPDATE", "CREATE")
        assertThat(changes(entries[1])).isEqualTo(mapOf("secretRotated" to (null to true)))
        assertThat(changes(entries[2])).isEqualTo(mapOf("enabled" to (true to false)))
        assertThat(changes(entries[3])).containsEntry("roles", null to listOf(role))
    }

    @Test
    fun `organizational units and who sits in them are audited`() {
        val code = "U" + uniqueName("").uppercase()
        send(HttpMethod.POST, "/api/org-units", mapOf("code" to code, "label" to "Area"), HttpStatus.CREATED)
        val unitId = idOf("org_units", "code", code)
        val userId = createUser("${uniqueName("member")}@wasichai.local", "member-password", emptyList())

        send(HttpMethod.PUT, "/api/org-units/$code", mapOf("label" to "Area renamed"))
        send(HttpMethod.PUT, "/api/users/$userId/org-units", mapOf("units" to listOf(code)))
        send(HttpMethod.PUT, "/api/users/$userId/org-units", mapOf("units" to emptyList<String>()))
        send(HttpMethod.DELETE, "/api/org-units/$code", status = HttpStatus.NO_CONTENT)

        val units = entries("admin:org-unit", unitId)
        assertThat(units.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "CREATE")
        assertThat(changes(units[1])).isEqualTo(mapOf("label" to ("Area" to "Area renamed")))
        val memberships = entries("admin:user", userId).filter { it["operation"] == "UPDATE" }
        assertThat(memberships.map { changes(it) }).containsExactly(
            mapOf("orgUnits" to (listOf(code) to emptyList<String>())),
            mapOf("orgUnits" to (emptyList<String>() to listOf(code)))
        )
    }

    @Test
    fun `objects, fields, flags, declared actions and relationships are audited, one entry per call`() {
        val obj = createObject()
        val objectId = objectId(obj)
        val target = createObject()

        val fieldId = json(send(HttpMethod.POST, "/api/metadata/objects/$obj/fields", mapOf("name" to "valor", "type" to "DECIMAL"), HttpStatus.CREATED))["id"]
        send(HttpMethod.PUT, "/api/metadata/objects/$obj/fields/valor", mapOf("label" to "Valor total"))
        send(HttpMethod.PUT, "/api/objects/$obj", mapOf("label" to "Audited", "requiresReason" to true))
        send(HttpMethod.POST, "/api/metadata/objects/$obj/actions", mapOf("name" to "ANULAR", "label" to "Anular"), HttpStatus.CREATED)
        send(HttpMethod.DELETE, "/api/metadata/objects/$obj/actions/ANULAR", status = HttpStatus.NO_CONTENT)
        send(HttpMethod.DELETE, "/api/metadata/objects/$obj/fields/valor", status = HttpStatus.NO_CONTENT)

        val rel = uniqueName("rel")
        val relationship =
            json(
                send(
                    HttpMethod.POST,
                    "/api/relationships",
                    mapOf("name" to rel, "label" to "Destino", "type" to "MANY_TO_ONE", "source" to obj, "target" to target),
                    HttpStatus.CREATED
                )
            )
        val relationshipId = relationship["id"] as String
        send(HttpMethod.PUT, "/api/relationships/$rel", mapOf("label" to "Destino final"))
        send(HttpMethod.DELETE, "/api/relationships/$rel", status = HttpStatus.NO_CONTENT)
        send(HttpMethod.DELETE, "/api/objects/$obj", status = HttpStatus.NO_CONTENT)

        val objects = entries("admin:object", objectId)
        assertThat(objects.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "UPDATE", "UPDATE", "CREATE")
        assertThat(changes(objects[3])).isEqualTo(mapOf("requiresReason" to (false to true)))
        assertThat(changes(objects[2])).isEqualTo(mapOf("actions" to (emptyMap<String, Any?>() to mapOf("ANULAR" to "Anular"))))
        assertThat(changes(objects[1])).isEqualTo(mapOf("actions" to (mapOf("ANULAR" to "Anular") to emptyMap<String, Any?>())))
        assertThat(changes(objects[4])).containsEntry("name", null to obj).containsKey("fields")
        assertThat(changes(objects[0])).containsEntry("name", obj to null).containsKey("physicalTable")

        val fields = entries("admin:field", fieldId as String)
        assertThat(fields.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "CREATE")
        assertThat(changes(fields[1])).isEqualTo(mapOf("label" to ("valor" to "Valor total")))

        val relationships = entries("admin:relationship", relationshipId)
        assertThat(relationships.map { it["operation"] }).containsExactly("DELETE", "UPDATE", "CREATE")
        assertThat(changes(relationships[2])).containsEntry("fieldName", null to target).containsEntry("target", null to target)
        // the relationship's own column is its entry's, not a field entry of its own
        assertThat(count("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = 'admin:field' AND after_state->>'object' = :o", obj))
            .isEqualTo(2)
    }

    @Test
    fun `provisioning, renaming and deleting a tenant are audited, and the deleted tenant's entries stay`() {
        val slug = "tenant-" + uniqueName("").take(8)
        val created =
            json(
                send(
                    HttpMethod.POST,
                    "/api/organizations",
                    mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "tenant-password"),
                    HttpStatus.CREATED
                )
            )
        val organizationId = created["id"] as String

        // the provisioner's act, in the provisioner's trail
        val provisioned = entries("admin:organization", organizationId).single()
        assertThat(provisioned["operation"]).isEqualTo("CREATE")
        assertThat(changes(provisioned)).containsEntry("slug", null to slug).containsEntry("adminEmail", null to "$slug@wasichai.local")

        val tenantAdmin = bearer("$slug@wasichai.local", "tenant-password")
        send(HttpMethod.PUT, "/api/organizations/current", mapOf("name" to "Tenant renamed"), token = tenantAdmin)
        assertThat(entries("admin:organization", organizationId, tenantAdmin).map { changes(it) })
            .containsExactly(mapOf("name" to ("Tenant" to "Tenant renamed")))
        send(HttpMethod.DELETE, "/api/organizations/current", status = HttpStatus.NO_CONTENT, token = tenantAdmin)

        val kept =
            runBlocking {
                db
                    .sql(
                        """
                        SELECT operation FROM ${schemas.metadata}.audit_log
                        WHERE organization_id = :organizationId AND object_name = 'admin:organization' ORDER BY occurred_at, id
                        """.trimIndent()
                    ).bind("organizationId", UUID.fromString(organizationId))
                    .map { row, _ -> row.get("operation", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitSingle()
            }
        assertThat(kept).containsExactly("UPDATE", "DELETE")
    }

    @Test
    fun `no audit row nor audit answer holds a password, a hash or a client secret`() {
        val email = "${uniqueName("secret")}@wasichai.local"
        val firstPassword = "first-" + uniqueName("pw")
        val secondPassword = "second-" + uniqueName("pw")
        val userId = createUser(email, firstPassword, emptyList())
        send(HttpMethod.PUT, "/api/users/$userId", mapOf("password" to secondPassword))

        val account = json(send(HttpMethod.POST, "/api/service-accounts", mapOf("name" to uniqueName("sa")), HttpStatus.CREATED))
        val accountId = account["id"] as String
        val rotated = json(send(HttpMethod.POST, "/api/service-accounts/$accountId/secret"))

        val slug = "tenant-" + uniqueName("").take(8)
        val tenantPassword = "tenant-" + uniqueName("pw")
        val tenant =
            json(
                send(
                    HttpMethod.POST,
                    "/api/organizations",
                    mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to tenantPassword),
                    HttpStatus.CREATED
                )
            )

        val passwordHash = single("SELECT password_hash AS v FROM ${schemas.metadata}.users WHERE id = :id", UUID.fromString(userId))
        val secretHash = single("SELECT secret_hash AS v FROM ${schemas.metadata}.service_accounts WHERE id = :id", UUID.fromString(accountId))
        val secrets =
            listOf(
                firstPassword,
                secondPassword,
                tenantPassword,
                account["clientSecret"] as String,
                rotated["clientSecret"] as String,
                passwordHash,
                secretHash
            )
        val ids = listOf(userId, accountId, tenant["id"] as String).map(UUID::fromString)
        val rows =
            runBlocking {
                db
                    .sql(
                        """
                        SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') AS states
                        FROM ${schemas.metadata}.audit_log
                        WHERE object_name LIKE 'admin:%' AND record_id = ANY(:ids)
                        """.trimIndent()
                    ).bind("ids", ids.toTypedArray())
                    .map { row, _ -> row.get("states", String::class.java)!! }
                    .all()
                    .collectList()
                    .awaitSingle()
            }
        assertThat(rows).hasSize(5)
        val answers =
            listOf(
                raw("admin:user", userId),
                raw("admin:service-account", accountId),
                raw("admin:organization", tenant["id"] as String)
            )
        (rows + answers).forEach { text ->
            secrets.forEach { assertThat(text).doesNotContain(it) }
            assertThat(text)
                .doesNotContain("password_hash")
                .doesNotContain("passwordHash")
                .doesNotContain("secret_hash")
                .doesNotContain("secretHash")
                .doesNotContain("clientSecret")
                .doesNotContain("\$2a\$")
        }
        assertThat(answers[0]).contains("\"passwordChanged\"")
        assertThat(answers[1]).contains("\"secretRotated\"")
    }

    // ------------------------------------------------------------------ readers

    @Test
    fun `the admin trail is MANAGE_ORGANIZATION's - everyone else gets an empty list, never a 403`() {
        val role = createRole("Watched")
        val roleId = roleId(role)

        val reader = userWith(listOf(null to "READ"))
        val nobody = userWith(emptyList())
        val manager = userWith(listOf(null to "MANAGE_ORGANIZATION"))

        assertThat(raw("admin:role", roleId, reader)).isEqualTo("[]")
        assertThat(raw("admin:role", roleId, nobody)).isEqualTo("[]")
        // asked for no object, a READ holder gets the record trail and none of the admin one
        assertThat(list("/api/audit?recordId=$roleId", reader)).isEmpty()
        assertThat(list("/api/audit?recordId=$roleId", admin).map { it["objectName"] }).containsExactly("admin:role")
        // no READ, no list: as before
        client
            .get()
            .uri("/api/audit?recordId=$roleId")
            .header(HttpHeaders.AUTHORIZATION, nobody)
            .exchange()
            .expectStatus()
            .isForbidden

        // a holder who is not ADMIN reads it whole, no READ needed
        val seen = entries("admin:role", roleId, manager).single()
        assertThat(seen["operation"]).isEqualTo("CREATE")
        assertThat(changes(seen)).containsKeys("name", "label", "ownRecordsOnly", "permissions", "fieldPermissions")
    }

    // ------------------------------------------------------------------ nothing written

    @Test
    fun `a refused admin call writes no entry`() {
        val role = createRole("Refused")
        val roleId = roleId(role)
        val email = "${uniqueName("refused")}@wasichai.local"
        val userId = createUser(email, "refused-password", emptyList())
        val obj = createObject()
        val objectId = objectId(obj)
        val me = json(send(HttpMethod.GET, "/api/auth/me"))["userId"] as String
        val mine = entries("admin:user", me).size

        send(HttpMethod.POST, "/api/roles", mapOf("name" to role, "label" to "Again"), HttpStatus.CONFLICT)
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(null to "FLY"), HttpStatus.BAD_REQUEST)
        send(HttpMethod.POST, "/api/users", mapOf("email" to email, "displayName" to "Again", "password" to "refused-password"), HttpStatus.CONFLICT)
        send(HttpMethod.PUT, "/api/users/$userId", mapOf("password" to "short"), HttpStatus.BAD_REQUEST)
        send(HttpMethod.DELETE, "/api/users/$me", status = HttpStatus.FORBIDDEN)
        send(HttpMethod.POST, "/api/objects", mapOf("name" to obj, "label" to "Again"), HttpStatus.CONFLICT)
        send(HttpMethod.POST, "/api/metadata/objects/$obj/fields", mapOf("name" to "codigo", "type" to "TEXT"), HttpStatus.CONFLICT)

        assertThat(entries("admin:role", roleId)).hasSize(1)
        assertThat(entries("admin:permission", roleId)).isEmpty()
        assertThat(entries("admin:user", userId)).hasSize(1)
        assertThat(entries("admin:user", me)).hasSize(mine)
        assertThat(entries("admin:object", objectId)).hasSize(1)
    }

    @Test
    fun `a rolled-back admin call leaves no entry`() {
        val name = "R" + uniqueName("").uppercase()

        assertThatThrownBy {
            asAdmin {
                transactions.executeAndAwait {
                    adminService.createRole(CreateRoleRequest(name, "Rolled back"))
                    error("roll it back")
                }
            }
        }.hasMessageContaining("roll it back")

        assertThat(count("SELECT count(*) AS n FROM ${schemas.metadata}.roles WHERE name = :o", name)).isZero()
        assertThat(count("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = 'admin:role' AND after_state->>'name' = :o", name))
            .isZero()

        // the same call, committed, leaves its one entry
        asAdmin { transactions.executeAndAwait { adminService.createRole(CreateRoleRequest(name, "Committed")) } }
        assertThat(count("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE object_name = 'admin:role' AND after_state->>'name' = :o", name))
            .isEqualTo(1)
    }

    @Test
    fun `the audit_log operation CHECK is the one core always had`() {
        val definition =
            single(
                """
                SELECT pg_get_constraintdef(oid) AS v FROM pg_constraint
                WHERE conrelid = '${schemas.metadata}.audit_log'::regclass AND conname = :id
                """.trimIndent(),
                "audit_log_operation_valid"
            )
        assertThat(definition).contains("'CREATE'", "'UPDATE'", "'DELETE'").doesNotContain("admin")
    }

    // ------------------------------------------------------------------ helpers

    private fun send(
        method: HttpMethod,
        uri: String,
        body: Any? = null,
        status: HttpStatus = HttpStatus.OK,
        token: String = admin
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

    private fun raw(
        objectName: String,
        recordId: String,
        token: String = admin
    ): String = send(HttpMethod.GET, "/api/audit?objectName=$objectName&recordId=$recordId&limit=500", token = token)

    // newest first, as the api answers
    private fun entries(
        objectName: String,
        recordId: String,
        token: String = admin
    ): List<Map<String, Any?>> = list("/api/audit?objectName=$objectName&recordId=$recordId&limit=500", token)

    @Suppress("UNCHECKED_CAST")
    private fun changes(entry: Map<String, Any?>): Map<String, Pair<Any?, Any?>> =
        (entry["changes"] as List<Map<String, Any?>>).associate { it["field"] as String to (it["before"] to it["after"]) }

    // the stored states, oldest first
    private fun states(
        objectName: String,
        recordId: String
    ): List<Pair<Map<String, Any?>?, Map<String, Any?>?>> =
        runBlocking {
            db
                .sql(
                    """
                    SELECT before_state::text AS b, after_state::text AS a FROM ${schemas.metadata}.audit_log
                    WHERE object_name = :objectName AND record_id = :recordId ORDER BY occurred_at, id
                    """.trimIndent()
                ).bind("objectName", objectName)
                .bind("recordId", UUID.fromString(recordId))
                .map { row, _ -> row.get("b", String::class.java)?.let(::json) to row.get("a", String::class.java)?.let(::json) }
                .all()
                .collectList()
                .awaitSingle()
        }

    private fun count(
        sql: String,
        value: String
    ): Long =
        runBlocking {
            db
                .sql(sql)
                .bind("o", value)
                .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun single(
        sql: String,
        value: Any
    ): String =
        runBlocking {
            db
                .sql(sql)
                .bind(if (sql.contains(":id")) "id" else "o", value)
                .map { row, _ -> row.get("v", String::class.java)!! }
                .one()
                .awaitSingle()
        }

    private fun idOf(
        table: String,
        column: String,
        value: String
    ): String =
        runBlocking {
            db
                .sql("SELECT id FROM ${schemas.metadata}.$table WHERE $column = :v")
                .bind("v", value)
                .map { row, _ -> row.get("id", UUID::class.java)!!.toString() }
                .one()
                .awaitSingle()
        }

    private fun createRole(label: String): String {
        val name = "R" + uniqueName("").uppercase()
        send(HttpMethod.POST, "/api/roles", mapOf("name" to name, "label" to label), HttpStatus.CREATED)
        return name
    }

    private fun roleId(name: String): String = list("/api/roles", admin).first { it["name"] == name }["id"] as String

    private fun createUser(
        email: String,
        password: String,
        roles: List<String>
    ): String =
        json(
            send(
                HttpMethod.POST,
                "/api/users",
                mapOf("email" to email, "displayName" to "Audited", "password" to password, "roles" to roles),
                HttpStatus.CREATED
            )
        )["id"] as String

    // a person holding a role with exactly these grants (object null = every object)
    private fun userWith(grants: List<Pair<String?, String>>): String {
        val role = createRole("Grants")
        send(HttpMethod.PUT, "/api/roles/$role/permissions", permissions(*grants.toTypedArray()))
        val email = "${uniqueName("caller")}@wasichai.local"
        createUser(email, "caller-password", listOf(role))
        return bearer(email, "caller-password")
    }

    private fun createObject(): String {
        val name = uniqueName("audited")
        send(
            HttpMethod.POST,
            "/api/objects",
            mapOf("name" to name, "label" to "Audited", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))),
            HttpStatus.CREATED
        )
        return name
    }

    private fun objectId(name: String): String = json(send(HttpMethod.GET, "/api/objects/$name"))["id"] as String

    private fun permissions(vararg grants: Pair<String?, String>) =
        mapOf("permissions" to grants.map { (obj, action) -> mapOf("objectName" to obj, "action" to action, "allowed" to true) })

    private fun fieldPermissions(
        obj: String,
        field: String,
        read: Boolean,
        write: Boolean
    ) = mapOf("fields" to listOf(mapOf("objectName" to obj, "fieldName" to field, "read" to read, "write" to write)))

    // a service call has no web filter to give it the caller: hand it the admin token's authentication, as the filter would
    private fun <T> asAdmin(block: suspend () -> T): T =
        runBlocking {
            val jwt = decoder.decode(admin.removePrefix("Bearer ")).awaitSingle()
            mono { block() }
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt)))
                .awaitSingle()
        }
}
