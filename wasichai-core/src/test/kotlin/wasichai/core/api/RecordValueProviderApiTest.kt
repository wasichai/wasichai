package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.common.ValidationException
import wasichai.core.data.IdempotencyKeys
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.RecordValueProvider
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.JwtService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 153, ADR-077: the server fixes a value on a create, against a real database. the consumer is elearning-backend's
// curso.propietario_id: a UUID, not required, off the forms, nobody writes it, and it is whoever creates the record
@Import(RecordValueProviderApiTest.ProviderConfig::class)
class RecordValueProviderApiTest : WasichaiIntegrationTest() {
    class OwnerProvider : RecordValueProvider {
        override suspend fun valuesFor(
            definition: ObjectDefinition,
            attributes: Map<String, Any?>,
            caller: AuthenticatedUser?
        ): Map<String, Any?> {
            if (!definition.obj.name.startsWith(OWNED)) return emptyMap()
            if (attributes["codigo"] == REFUSED) throw ValidationException("Refused", "codigo", "the provider said no")
            return caller?.let { mapOf("propietario_id" to it.userId.toString()) }.orEmpty()
        }
    }

    class RequiredProvider : RecordValueProvider {
        override suspend fun valuesFor(
            definition: ObjectDefinition,
            attributes: Map<String, Any?>,
            caller: AuthenticatedUser?
        ): Map<String, Any?> = if (definition.obj.name.startsWith(COVERED)) mapOf("codigo_interno" to "SRV-1") else emptyMap()
    }

    @TestConfiguration
    class ProviderConfig {
        @Bean
        fun ownerProvider(): RecordValueProvider = OwnerProvider()

        @Bean
        fun requiredProvider(): RecordValueProvider = RequiredProvider()
    }

    @Autowired
    private lateinit var records: RecordService

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var organizationId: UUID

    @BeforeEach
    fun setUp() {
        admin = bearer()
        organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
    }

    @Test
    fun `CREATE alone is enough, and one row, one audit entry and the answer carry the owner`() {
        val name = createOwned()
        val role = newRole()
        grant(role, name to "CREATE", name to "READ")
        val member = newUserToken(role)
        val memberId = idOf(member)

        val id = post(name, member, mapOf("codigo" to "C-1"))

        get(name, id).jsonPath("$.attributes.propietario_id").isEqualTo(memberId)
        assertThat(rows(name)).isEqualTo(1)
        assertThat(auditOf(name)).containsExactly("CREATE")
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].operation")
            .isEqualTo("CREATE")
        // the api lists no changes for a create, so the snapshot is read from the row
        assertThat(ownerInAudit(name)).isEqualTo(memberId)
    }

    @Test
    fun `the POST answers with the owner`() {
        val name = createOwned()

        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.attributes.propietario_id")
            .isEqualTo(idOf(admin))
    }

    @Test
    fun `nobody passes for the owner, ADMIN included, neither on the create nor afterwards`() {
        val name = createOwned()
        val spoofed = UUID.randomUUID().toString()
        val adminId = idOf(admin)

        val id = post(name, admin, mapOf("codigo" to "C-1", "propietario_id" to spoofed))
        get(name, id).jsonPath("$.attributes.propietario_id").isEqualTo(adminId)

        // editable: false keeps it after the create: a PUT leaves it alone, a PATCH that names it is a 403
        client
            .put()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-2", "propietario_id" to spoofed)))
            .exchange()
            .expectStatus()
            .isOk
        get(name, id).jsonPath("$.attributes.propietario_id").isEqualTo(adminId)
        client
            .patch()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("propietario_id" to spoofed)))
            .exchange()
            .expectStatus()
            .isForbidden
        get(name, id).jsonPath("$.attributes.propietario_id").isEqualTo(adminId)
    }

    @Test
    fun `a provider's refusal is a 400 and nothing is stored`() {
        val name = createOwned()

        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to REFUSED)))
            .exchange()
            .expectStatus()
            .isBadRequest
        assertThat(rows(name)).isZero()
        assertThat(auditOf(name)).isEmpty()
    }

    @Test
    fun `a required field is covered by a provider`() {
        val name = uniqueName(COVERED)
        createObject(name, listOf(text("codigo"), mapOf("name" to "codigo_interno", "type" to "TEXT", "required" to true)))

        val id = post(name, admin, mapOf("codigo" to "C-1"))

        get(name, id).jsonPath("$.attributes.codigo_interno").isEqualTo("SRV-1")
    }

    @Test
    fun `an object no provider has a say on is what it was`() {
        val name = uniqueName("plain")
        val sent = UUID.randomUUID().toString()
        createObject(name, listOf(text("codigo"), mapOf("name" to "propietario_id", "type" to "UUID")))

        val id = post(name, admin, mapOf("codigo" to "C-1", "propietario_id" to sent))

        get(name, id).jsonPath("$.attributes.propietario_id").isEqualTo(sent)
    }

    @Test
    fun `a replayed Idempotency-Key answers the first owner and writes nothing`() {
        val name = createOwned()
        val key = UUID.randomUUID().toString()

        repeat(2) {
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .header(IdempotencyKeys.HEADER, key)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "C-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody()
                .jsonPath("$.attributes.propietario_id")
                .isEqualTo(idOf(admin))
        }
        assertThat(rows(name)).isEqualTo(1)
        assertThat(auditOf(name)).containsExactly("CREATE")
    }

    @Test
    fun `the platform has no owner to fix, in a batch too`() {
        val name = createOwned()

        val created =
            runBlocking {
                records.asPlatform(organizationId) {
                    records.createAll(name, listOf(RecordRequest(mapOf("codigo" to "A")), RecordRequest(mapOf("codigo" to "B"))))
                }
            }

        assertThat(created.map { it.attributes["propietario_id"] }).containsOnlyNulls()
        assertThat(rows(name)).isEqualTo(2)
    }

    @Test
    fun `a provider's refusal fails a batch whole`() {
        val name = createOwned()

        assertThatThrownBy {
            runBlocking {
                records.asPlatform(organizationId) {
                    records.createAll(name, listOf(RecordRequest(mapOf("codigo" to "A")), RecordRequest(mapOf("codigo" to REFUSED))))
                }
            }
        }.isInstanceOf(ValidationException::class.java)
        assertThat(rows(name)).isZero()
        assertThat(auditOf(name)).isEmpty()
    }

    private fun createOwned(): String {
        val name = uniqueName(OWNED)
        createObject(name, listOf(text("codigo"), mapOf("name" to "propietario_id", "type" to "UUID", "visible" to false, "editable" to false)))
        return name
    }

    private fun text(name: String) = mapOf("name" to name, "type" to "TEXT")

    private fun createObject(
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun post(
        name: String,
        token: String,
        attributes: Map<String, Any?>
    ): String =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .idOf()

    private fun get(
        name: String,
        id: String
    ): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun WebTestClient.ResponseSpec.idOf(): String =
        expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    // the user behind a token, as the api tells it
    private fun idOf(token: String): String =
        client
            .get()
            .uri("/api/auth/me")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"userId\":\"")
            .substringBefore("\"")

    private fun rows(name: String): Long =
        client
            .get()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"totalElements\":")
            .takeWhile { it.isDigit() }
            .toLong()

    private fun auditOf(name: String): List<String> =
        runBlocking {
            db
                .sql("SELECT operation FROM ${schemas.metadata}.audit_log WHERE object_name = :name ORDER BY occurred_at, id")
                .bind("name", name)
                .map { row, _ -> row.get("operation", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    private fun ownerInAudit(name: String): String =
        runBlocking {
            db
                .sql("SELECT after_state ->> 'propietario_id' AS owner FROM ${schemas.metadata}.audit_log WHERE object_name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("owner", String::class.java)!! }
                .one()
                .awaitSingle()
        }

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Provider", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // replaces the role's grants with these
    private fun grant(
        role: String,
        vararg entries: Pair<String, String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to entries.map { (target, action) -> mapOf("objectName" to target, "action" to action, "allowed" to true) }))
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

    private companion object {
        const val OWNED = "curso"
        const val COVERED = "fijado"
        const val REFUSED = "REFUSED"
    }
}
