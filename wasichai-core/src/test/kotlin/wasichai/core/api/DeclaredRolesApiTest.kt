package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import tools.jackson.databind.json.JsonMapper
import wasichai.core.identity.JwtService
import wasichai.core.metadata.CreateObjectRequest
import wasichai.core.metadata.DeclaredModel
import wasichai.core.metadata.DeclaredModels
import wasichai.core.metadata.DeclaredRole
import wasichai.core.metadata.FieldPermissionEntry
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.PermissionEntry
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-085: a role the app declares is the app's. a person reads it, assigns it, and cannot change it
@Import(DeclaredRolesApiTest.ModelConfig::class)
class DeclaredRolesApiTest : WasichaiIntegrationTest() {
    @TestConfiguration
    class ModelConfig {
        @Bean
        fun testModel(): DeclaredModel = MODEL
    }

    @Autowired
    private lateinit var models: DeclaredModels

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var tenant: String
    private lateinit var tenantId: UUID

    @BeforeEach
    fun setUp() {
        admin = bearer()
        val slug = "dra-" + uniqueName("").take(8)
        status(
            post(
                admin,
                "/api/organizations",
                mapOf("name" to "Roles", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret")
            ),
            201
        )
        tenant = bearer("$slug@wasichai.local", "supersecret")
        tenantId = UUID.fromString(runBlocking { decoder.decode(tenant.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
    }

    @Test
    fun `a declared role reads with declaredBy, a person's without`() {
        status(post(tenant, "/api/roles", mapOf("name" to "MIO", "label" to "Mío")), 201)

        assertThat(role("CARGA_DATOS")!!["declaredBy"]).isEqualTo("test-model")
        assertThat(role("MIO")!!).doesNotContainKey("declaredBy")
    }

    @Test
    fun `relabelling, re-granting or deleting a declared role is 409 naming the model`() {
        val before = role("CARGA_DATOS")

        listOf(
            put(tenant, "/api/roles/CARGA_DATOS", mapOf("label" to "Otro")),
            put(tenant, "/api/roles/CARGA_DATOS/permissions", mapOf("permissions" to listOf(mapOf("action" to "READ")))),
            put(
                tenant,
                "/api/roles/CARGA_DATOS/field-permissions",
                mapOf("fields" to listOf(mapOf("objectName" to OBJECT, "fieldName" to "dni", "read" to false)))
            ),
            delete(tenant, "/api/roles/CARGA_DATOS")
        ).forEach {
            assertThat(it.first).describedAs(it.second).isEqualTo(409)
            assertThat(it.second).contains("'test-model'")
        }

        assertThat(role("CARGA_DATOS")).isEqualTo(before)
    }

    @Test
    fun `an administrator still creates a role of their own and assigns a declared one`() {
        status(post(tenant, "/api/roles", mapOf("name" to "PROPIO", "label" to "Propio")), 201)
        val email = "holder-${uniqueName("").take(8)}@wasichai.local"
        val user =
            json.readValue(
                post(tenant, "/api/users", mapOf("email" to email, "displayName" to "H", "password" to "supersecret"))
                    .also {
                        status(it, 201)
                    }.second,
                Map::class.java
            )

        status(put(tenant, "/api/users/${user["id"]}/roles", mapOf("roles" to listOf("CARGA_DATOS"))), 200)
    }

    @Test
    fun `a declared role can be given to a local service account`() {
        val account = json.readValue(post(tenant, "/api/service-accounts", mapOf("name" to "erp")).also { status(it, 201) }.second, Map::class.java)

        status(put(tenant, "/api/service-accounts/${account["id"]}", mapOf("roles" to listOf("CARGA_DATOS"))), 200)
    }

    @Test
    fun `a released role can be changed again`() {
        runBlocking { models.ensure(tenantId, MODEL.copy(roles = emptyList())) }

        assertThat(role("CARGA_DATOS")!!).doesNotContainKey("declaredBy")
        status(put(tenant, "/api/roles/CARGA_DATOS", mapOf("label" to "Otro")), 200)
        status(put(tenant, "/api/roles/CARGA_DATOS/permissions", mapOf("permissions" to listOf(mapOf("action" to "READ")))), 200)
        status(delete(tenant, "/api/roles/CARGA_DATOS"), 204)
    }

    @Suppress("UNCHECKED_CAST")
    private fun role(name: String): Map<String, Any?>? {
        val body =
            client
                .get()
                .uri("/api/roles")
                .header(HttpHeaders.AUTHORIZATION, tenant)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return (json.readValue(body, List::class.java) as List<Map<String, Any?>>).firstOrNull { it["name"] == name }
    }

    private fun post(
        token: String,
        uri: String,
        body: Any
    ): Pair<Int, String?> =
        send(
            client
                .post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
        )

    private fun put(
        token: String,
        uri: String,
        body: Any
    ): Pair<Int, String?> =
        send(
            client
                .put()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
        )

    private fun delete(
        token: String,
        uri: String
    ): Pair<Int, String?> = send(client.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, token))

    private fun send(spec: org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec<*>): Pair<Int, String?> {
        val result = spec.exchange().expectBody(String::class.java).returnResult()
        return result.status.value() to result.responseBody
    }

    private fun status(
        response: Pair<Int, String?>,
        expected: Int
    ) {
        assertThat(response.first).describedAs(response.second).isEqualTo(expected)
    }

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()

        const val OBJECT = "drapi_ficha"

        val MODEL =
            DeclaredModel(
                name = "test-model",
                objects = listOf(CreateObjectRequest(name = OBJECT, label = "Ficha", fields = listOf(FieldRequest(name = "dni", type = "TEXT")))),
                roles =
                    listOf(
                        DeclaredRole(
                            name = "CARGA_DATOS",
                            label = "Carga de datos",
                            permissions = listOf(PermissionEntry(OBJECT, "CREATE")),
                            fieldPermissions = listOf(FieldPermissionEntry(OBJECT, "dni", read = true, write = true))
                        )
                    )
            )
    }
}
