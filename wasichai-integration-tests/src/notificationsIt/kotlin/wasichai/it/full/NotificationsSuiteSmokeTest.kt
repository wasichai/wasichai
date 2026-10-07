package wasichai.it.full

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// the suite boots, and NotificationsTenant gives a class a tenant of its own
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsSuiteSmokeTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }

    @Test
    fun `the tenant's admin is signed in to the tenant`() {
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.email")
            .isEqualTo(tenant.adminEmail)
            .jsonPath("$.organizationId")
            .isEqualTo(tenant.organizationId.toString())
            .jsonPath("$.roles[0]")
            .isEqualTo("ADMIN")
    }

    @Test
    fun `a user made with a role and units signs in to the same tenant`() {
        val role = tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ")))
        val parent = tenant.createUnit()
        val unit = tenant.createUnit(parent = parent.code)
        val user = tenant.createUser(roles = listOf(role), units = listOf(unit.code))

        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, user.token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.userId")
            .isEqualTo(user.id.toString())
            .jsonPath("$.organizationId")
            .isEqualTo(tenant.organizationId.toString())
            .jsonPath("$.roles[0]")
            .isEqualTo(role)
        val units =
            runBlocking {
                db
                    .sql("SELECT unit_id FROM ${schemas.metadata}.user_org_units WHERE user_id = :user")
                    .bind("user", user.id)
                    .map { row, _ -> row.get("unit_id", UUID::class.java)!! }
                    .all()
                    .collectList()
                    .awaitSingle()
            }
        assertThat(units).containsExactly(unit.id)
    }

    @Test
    fun `an object and a record belong to the tenant`() {
        val name = tenant.createObject(mapOf("codigo" to "TEXT", "vence" to "DATE"))
        val id = tenant.createRecord(name, mapOf("codigo" to "S-1", "vence" to "2026-12-31"))

        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("S-1")
        // the seeded tenant cannot see it
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, tenant.login(WasichaiIntegrationTest.ADMIN_EMAIL, WasichaiIntegrationTest.ADMIN_PASSWORD))
            .exchange()
            .expectStatus()
            .isNotFound
    }
}
