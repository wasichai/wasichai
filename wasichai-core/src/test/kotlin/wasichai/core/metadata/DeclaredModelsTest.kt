package wasichai.core.metadata

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import tools.jackson.databind.json.JsonMapper
import wasichai.core.identity.JwtService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 165, ADR-081: what the platform refuses to start with, and that nothing declared runs nothing.
// ADR-085: the roles a model declares, ensured in a real tenant after its objects, and released when dropped
@Import(DeclaredModelsTest.ModelConfig::class)
class DeclaredModelsTest : WasichaiIntegrationTest() {
    @TestConfiguration
    class ModelConfig {
        @Bean
        fun padron(): DeclaredModel = BEAN_MODEL
    }

    @Autowired
    private lateinit var models: DeclaredModels

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private val tenants = mock(TenantDirectory::class.java)

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    private fun models(
        vararg models: DeclaredModel,
        roles: DeclaredModelRoles? = mock(DeclaredModelRoles::class.java)
    ) = DeclaredModels(
        models.toList(),
        tenants,
        mock(MetadataService::class.java),
        mock(RelationshipService::class.java),
        mock(ObjectActionService::class.java),
        mock(CustomObjectRepository::class.java),
        mock(CustomFieldRepository::class.java),
        mock(RelationshipRepository::class.java),
        mock(ObjectActionRepository::class.java),
        mock(FieldTypeRegistry::class.java),
        mock(ClusterLock::class.java),
        roles
    )

    private fun model(
        name: String,
        vararg objects: String
    ) = DeclaredModel(name, objects.map { CreateObjectRequest(name = it, label = it) })

    @Test
    fun `a model name that is no owner label refuses to start`() {
        assertThatThrownBy { models(model("Curricula", "curso")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Curricula")
    }

    @Test
    fun `two models with one name, or one object in two models, refuse to start`() {
        assertThatThrownBy { models(model("curricula", "curso"), model("curricula", "plan")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("two declared models are named 'curricula'")
        assertThatThrownBy { models(model("curricula", "curso"), model("otra", " Curso ")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("object 'curso' is declared by both 'curricula' and 'otra'")
    }

    @Test
    fun `no model declared ensures nothing and asks for no tenant`() =
        runTest {
            assertThat(models().ensureAll()).isEmpty()
            verifyNoInteractions(tenants)
        }

    @Test
    fun `a declared ADMIN, a declared MANAGE_ORGANIZATION or one role in two models refuses to start`() {
        fun withRoles(
            name: String,
            vararg roles: DeclaredRole
        ) = DeclaredModel(name, objects = emptyList(), roles = roles.toList())

        assertThatThrownBy { models(withRoles("a", DeclaredRole(" admin ", "Admin"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ADMIN")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("x", "Bad"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'X'")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "manage_organization"))))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MANAGE_ORGANIZATION")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "MANAGE_TENANTS"))))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MANAGE_TENANTS")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("CAJA", "Caja")), withRoles("b", DeclaredRole(" caja", "Caja"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("role 'CAJA' is declared by both 'a' and 'b'")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("CAJA", "Caja")), roles = null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("declared model 'a' declares roles")
        // a denied administration grant hands nothing out, and a model without roles needs no port
        models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "MANAGE_ORGANIZATION", allowed = false)))))
        models(model("b", "curso"), roles = null)
    }

    @Test
    fun `a declared role is made in the tenant with its grants and field rules`() {
        val (tenant, tenantId) = provision()

        val report = runBlocking { models.ensure(tenantId, ROLES) }

        assertThat(report.created).contains("role CARGA_DATOS")
        assertThat(report.incompatible).isEmpty()
        val role = role(tenant, "CARGA_DATOS")!!
        assertThat(role["label"]).isEqualTo("Carga de datos")
        assertThat(role["declaredBy"]).isEqualTo(MODEL)
        assertThat(grants(role)).containsExactlyInAnyOrder("*.CREATE=true", "*.MANAGE_METADATA=true", "$OBJECT.VALIDAR=true")
        assertThat(fieldRules(role)).containsExactly("$OBJECT.dni=true/false")
    }

    @Test
    fun `a second run writes nothing, not even an admin entry`() {
        val (_, tenantId) = provision()
        runBlocking { models.ensure(tenantId, ROLES) }
        val before = adminEntries(tenantId)

        val again = runBlocking { models.ensure(tenantId, ROLES) }

        assertThat(again.unchanged).isTrue()
        assertThat(adminEntries(tenantId)).isEqualTo(before)
    }

    @Test
    fun `a role is ensured after the objects whose grants it names`() {
        val (tenant, tenantId) = provision()

        val report = runBlocking { models.ensure(tenantId, ROLES) }

        val role = report.created.indexOf("role CARGA_DATOS")
        assertThat(role).isGreaterThan(report.created.indexOf("object $OBJECT")).isGreaterThan(report.created.indexOf("action $OBJECT.VALIDAR"))
        assertThat(report.created.indexOf("object $OBJECT")).isGreaterThanOrEqualTo(0)
        assertThat(grants(role(tenant, "CARGA_DATOS")!!)).contains("$OBJECT.VALIDAR=true")
    }

    @Test
    fun `a person's role of the same name is taken over and made as declared`() {
        val (tenant, tenantId) = provision()
        runBlocking { models.ensure(tenantId, ROLES.copy(roles = emptyList())) }
        status(post(tenant, "/api/roles", mapOf("name" to "carga_datos", "label" to "Mío", "ownRecordsOnly" to true)), 201)
        status(put(tenant, "/api/roles/CARGA_DATOS/permissions", mapOf("permissions" to listOf(mapOf("action" to "READ")))), 200)

        val report = runBlocking { models.ensure(tenantId, ROLES) }

        assertThat(report.updated).containsExactly("role CARGA_DATOS (taken over)")
        assertThat(report.created).isEmpty()
        val role = role(tenant, "CARGA_DATOS")!!
        assertThat(role["label"]).isEqualTo("Carga de datos")
        assertThat(role["ownRecordsOnly"]).isEqualTo(false)
        assertThat(role["declaredBy"]).isEqualTo(MODEL)
        assertThat(grants(role)).containsExactlyInAnyOrder("*.CREATE=true", "*.MANAGE_METADATA=true", "$OBJECT.VALIDAR=true")
        assertThat(fieldRules(role)).containsExactly("$OBJECT.dni=true/false")
    }

    @Test
    fun `a role naming an object the tenant lacks is left out and reported`() {
        val (tenant, tenantId) = provision()
        // a person's role under a declared name whose grant cannot be held: it keeps what it had
        status(post(tenant, "/api/roles", mapOf("name" to "AJENO_CAMPO", "label" to "Mío")), 201)
        val model =
            ROLES.copy(
                roles =
                    ROLES.roles +
                        DeclaredRole("AJENO", "Ajeno", permissions = listOf(PermissionEntry("drtest_ajeno", "READ"))) +
                        DeclaredRole("AJENO_CAMPO", "Ajeno", fieldPermissions = listOf(FieldPermissionEntry(OBJECT, "ruc"))) +
                        DeclaredRole("AJENA_ACCION", "Ajena", permissions = listOf(PermissionEntry(OBJECT, "ANULAR")))
            )

        val report = runBlocking { models.ensure(tenantId, model) }

        assertThat(report.incompatible).containsExactlyInAnyOrder(
            "role AJENO: object drtest_ajeno is not in this tenant",
            "role AJENO_CAMPO: field $OBJECT.ruc is not in this tenant",
            "role AJENA_ACCION: action $OBJECT.ANULAR is not in this tenant"
        )
        assertThat(report.created).contains("object $OBJECT", "role CARGA_DATOS").doesNotContain("role AJENO", "role AJENA_ACCION")
        assertThat(role(tenant, "AJENO")).isNull()
        assertThat(role(tenant, "AJENA_ACCION")).isNull()
        val kept = role(tenant, "AJENO_CAMPO")!!
        assertThat(kept["label"]).isEqualTo("Mío")
        assertThat(kept).doesNotContainKey("declaredBy")
        assertThat(role(tenant, "CARGA_DATOS")!!["declaredBy"]).isEqualTo(MODEL)
    }

    @Test
    fun `a role removed from the model is released, with its holders`() {
        val (tenant, tenantId) = provision()
        val model = ROLES.copy(roles = ROLES.roles + DeclaredRole("ANTIGUO", "Antiguo", permissions = listOf(PermissionEntry(null, "READ"))))
        runBlocking { models.ensure(tenantId, model) }
        val email = "holder-${uniqueName("").take(8)}@wasichai.local"
        status(post(tenant, "/api/users", mapOf("email" to email, "displayName" to "Holder", "password" to "supersecret", "roles" to listOf("ANTIGUO"))), 201)

        val report = runBlocking { models.ensure(tenantId, ROLES) }

        assertThat(report.updated).containsExactly("released role ANTIGUO")
        val released = role(tenant, "ANTIGUO")!!
        assertThat(released).doesNotContainKey("declaredBy")
        assertThat(grants(released)).containsExactly("*.READ=true")
        val holder = list(tenant, "/api/users").single { it["email"] == email }
        assertThat(holder["roles"] as List<*>).containsExactly("ANTIGUO")
        // released once: the next run has nothing to release
        assertThat(runBlocking { models.ensure(tenantId, ROLES) }.unchanged).isTrue()
    }

    @Test
    fun `a tenant provisioned is born with the declared roles`() {
        val (tenant, _) = provision()

        val role = role(tenant, BEAN_ROLE)!!
        assertThat(role["declaredBy"]).isEqualTo(BEAN_MODEL.name)
        assertThat(grants(role)).containsExactly("$BEAN_OBJECT.READ=true")
    }

    @Test
    fun `the admin entries of a declared role have no user and say model name`() {
        val (tenant, tenantId) = provision()
        runBlocking { models.ensure(tenantId, ROLES) }
        val roleId = UUID.fromString(role(tenant, "CARGA_DATOS")!!["id"] as String)

        val entries = entriesOf(tenantId, roleId)

        assertThat(entries).isNotEmpty()
        assertThat(entries).allSatisfy { (user, source) ->
            assertThat(user).isNull()
            assertThat(source).isEqualTo("model:$MODEL")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun provision(): Pair<String, UUID> {
        val slug = "dr-" + uniqueName("").take(8)
        status(
            post(
                admin,
                "/api/organizations",
                mapOf(
                    "name" to "Declared",
                    "slug" to slug,
                    "adminEmail" to "$slug@wasichai.local",
                    "adminPassword" to "supersecret"
                )
            ),
            201
        )
        val tenant = bearer("$slug@wasichai.local", "supersecret")
        val id = runBlocking { decoder.decode(tenant.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION)
        return tenant to UUID.fromString(id)
    }

    private fun role(
        token: String,
        name: String
    ): Map<String, Any?>? = list(token, "/api/roles").firstOrNull { it["name"] == name }

    private fun grants(role: Map<String, Any?>): List<String> =
        (role["permissions"] as List<*>).map { it as Map<*, *> }.map { "${it["objectName"] ?: "*"}.${it["action"]}=${it["allowed"]}" }

    private fun fieldRules(role: Map<String, Any?>): List<String> =
        (role["fieldPermissions"] as List<*>).map { it as Map<*, *> }.map { "${it["objectName"]}.${it["fieldName"]}=${it["read"]}/${it["write"]}" }

    @Suppress("UNCHECKED_CAST")
    private fun list(
        token: String,
        uri: String
    ): List<Map<String, Any?>> {
        val body =
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return json.readValue(body, List::class.java) as List<Map<String, Any?>>
    }

    private fun post(
        token: String,
        uri: String,
        body: Any
    ): Pair<Int, String?> {
        val result =
            client
                .post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return result.status.value() to result.responseBody
    }

    private fun put(
        token: String,
        uri: String,
        body: Any
    ): Pair<Int, String?> {
        val result =
            client
                .put()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return result.status.value() to result.responseBody
    }

    private fun status(
        response: Pair<Int, String?>,
        expected: Int
    ) {
        assertThat(response.first).describedAs(response.second).isEqualTo(expected)
    }

    private fun adminEntries(organizationId: UUID): Long =
        runBlocking {
            db
                .sql("SELECT COUNT(*) AS n FROM ${schemas.metadata}.audit_log WHERE organization_id = :org AND object_name LIKE 'admin:%'")
                .bind("org", organizationId)
                .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        }

    // (user, source) of every admin entry about the role
    private fun entriesOf(
        organizationId: UUID,
        roleId: UUID
    ): List<Pair<UUID?, String?>> =
        runBlocking {
            db
                .sql(
                    "SELECT user_id, source FROM ${schemas.metadata}.audit_log WHERE organization_id = :org AND record_id = :id " +
                        "AND object_name IN ('admin:role', 'admin:permission')"
                ).bind("org", organizationId)
                .bind("id", roleId)
                .map { row, _ -> row.get("user_id", UUID::class.java) to row.get("source", String::class.java) }
                .all()
                .collectList()
                .awaitSingle()
        }

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()

        const val MODEL = "roles-test"
        const val OBJECT = "drtest_contribuyente"

        val ROLES =
            DeclaredModel(
                name = MODEL,
                objects = listOf(CreateObjectRequest(name = OBJECT, label = "Contribuyente", fields = listOf(FieldRequest(name = "dni", type = "TEXT")))),
                actions = mapOf(OBJECT to listOf(ObjectActionRequest("VALIDAR", "Validar"))),
                roles =
                    listOf(
                        DeclaredRole(
                            name = "CARGA_DATOS",
                            label = "Carga de datos",
                            permissions =
                                listOf(PermissionEntry(null, "MANAGE_METADATA"), PermissionEntry(null, "CREATE"), PermissionEntry(OBJECT, "validar")),
                            fieldPermissions = listOf(FieldPermissionEntry(OBJECT, "dni", read = true, write = false))
                        )
                    )
            )

        const val BEAN_OBJECT = "drbean_padron"
        const val BEAN_ROLE = "PADRON_LECTOR"

        val BEAN_MODEL =
            DeclaredModel(
                name = "roles-bean",
                objects = listOf(CreateObjectRequest(name = BEAN_OBJECT, label = "Padrón", fields = listOf(FieldRequest(name = "codigo", type = "TEXT")))),
                roles = listOf(DeclaredRole(BEAN_ROLE, "Lector del padrón", permissions = listOf(PermissionEntry(BEAN_OBJECT, "READ"))))
            )
    }
}
