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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.identity.JwtService
import wasichai.core.metadata.CreateObjectRequest
import wasichai.core.metadata.CreateRelationshipRequest
import wasichai.core.metadata.DeclaredModel
import wasichai.core.metadata.DeclaredModels
import wasichai.core.metadata.FieldRequest
import wasichai.core.metadata.ObjectActionRequest
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue 165, ADR-081: an app declares its model in code and the platform ensures it in every tenant, at startup and
// on provisioning, with nobody behind it. the consumer is hneyra/curricula: plans and courses its code writes
@Import(DeclaredModelApiTest.ModelConfig::class)
class DeclaredModelApiTest : WasichaiIntegrationTest() {
    @TestConfiguration
    class ModelConfig {
        @Bean
        fun catalog(): DeclaredModel = CATALOG
    }

    @Autowired
    private lateinit var models: DeclaredModels

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `the model is there from the start, owned by the app`() {
        objectOf(admin, COURSE)
            .jsonPath("$.declaredBy")
            .isEqualTo(MODEL)
            .jsonPath("$.fields[?(@.name == 'codigo')].unique")
            .isEqualTo(true)
            .jsonPath("$.fields[?(@.name == 'plan')].type")
            .isEqualTo("RELATION")
        objectOf(admin, PLAN)
            .jsonPath("$.apiOnly")
            .isEqualTo(true)
            .jsonPath("$.auditMode")
            .isEqualTo("OPERATION")
        get(admin, "/api/relationships")
            .jsonPath("$[?(@.name == '$RELATIONSHIP')].declaredBy")
            .isEqualTo(MODEL)
        get(admin, "/api/metadata/objects/$COURSE/actions")
            .jsonPath("$[0].name")
            .isEqualTo("PUBLICAR")
    }

    @Test
    fun `ensuring it again writes nothing, not even an admin entry`() {
        val organizationId = organizationOf(admin)
        val before = adminEntries(organizationId)

        val reports = runBlocking { models.ensure(organizationId) }

        assertThat(reports.single().unchanged).isTrue()
        assertThat(adminEntries(organizationId)).isEqualTo(before)
    }

    @Test
    fun `a tenant is born with the model, and its admin trail says the platform made it`() {
        val (tenant, tenantId) = provision()

        objectOf(tenant, COURSE).jsonPath("$.declaredBy").isEqualTo(MODEL)
        val sources = platformSources(tenantId)
        assertThat(sources).isNotEmpty().containsOnly("model:$MODEL")
    }

    @Test
    fun `a person changes none of it, and the records stay theirs to write`() {
        put(admin, "/api/objects/$COURSE", mapOf("label" to "Otro")).isEqualTo(409)
        delete(admin, "/api/objects/$COURSE").isEqualTo(409)
        post(admin, "/api/metadata/objects/$COURSE/fields", mapOf("name" to "extra", "type" to "TEXT")).isEqualTo(409)
        put(admin, "/api/metadata/objects/$COURSE/fields/creditos", mapOf("label" to "Otro")).isEqualTo(409)
        delete(admin, "/api/metadata/objects/$COURSE/fields/creditos").isEqualTo(409)
        delete(admin, "/api/relationships/$RELATIONSHIP").isEqualTo(409)
        post(admin, "/api/metadata/objects/$COURSE/actions", mapOf("name" to "ARCHIVAR")).isEqualTo(409)
        // a relationship of a person's object that would put its column on a declared one
        val own = uniqueName("propio")
        post(admin, "/api/objects", mapOf("name" to own, "label" to own, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT")))).isEqualTo(201)
        post(
            admin,
            "/api/relationships",
            mapOf("name" to uniqueName("rel"), "label" to "Propio", "type" to "MANY_TO_ONE", "source" to COURSE, "target" to own)
        ).isEqualTo(409)

        post(admin, "/api/objects/$COURSE/records", mapOf("attributes" to mapOf("codigo" to uniqueName("C"), "creditos" to 3))).isEqualTo(201)
    }

    @Test
    fun `an incompatible change is reported and left out, and the rest of the model applies`() {
        val (tenant, tenantId) = provision()
        val changed =
            CATALOG.copy(
                objects =
                    CATALOG.objects.map { obj ->
                        if (obj.name != COURSE) {
                            obj
                        } else {
                            obj.copy(
                                label = "Asignatura",
                                fields =
                                    obj.fields.map { if (it.name == "creditos") it.copy(type = "DECIMAL") else it } +
                                        FieldRequest(name = "horas", type = "INTEGER")
                            )
                        }
                    }
            )

        val report = runBlocking { models.ensure(tenantId, changed) }

        assertThat(report.incompatible)
            .singleElement()
            .asString()
            .contains("field $COURSE.creditos")
            .contains("never changes type")
        assertThat(report.created).containsExactly("field $COURSE.horas")
        assertThat(report.updated).containsExactly("object $COURSE")
        objectOf(tenant, COURSE)
            .jsonPath("$.label")
            .isEqualTo("Asignatura")
            .jsonPath("$.fields[?(@.name == 'creditos')].type")
            .isEqualTo("INTEGER")
            .jsonPath("$.fields[?(@.name == 'horas')].type")
            .isEqualTo("INTEGER")
    }

    @Test
    fun `an object a person made under a declared name is taken over once it matches`() {
        val (tenant, tenantId) = provision()
        val name = uniqueName("libre")
        post(tenant, "/api/objects", mapOf("name" to name, "label" to "Libre", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT")))).isEqualTo(201)
        val other =
            DeclaredModel(
                "otro-modelo",
                objects =
                    listOf(
                        CreateObjectRequest(
                            name = name,
                            label = "Libre",
                            fields = listOf(FieldRequest(name = "codigo", type = "TEXT"), FieldRequest(name = "nuevo", type = "TEXT"))
                        )
                    )
            )

        val report = runBlocking { models.ensure(tenantId, other) }

        assertThat(report.created).containsExactly("field $name.nuevo")
        assertThat(report.updated).containsExactly("object $name")
        objectOf(tenant, name).jsonPath("$.declaredBy").isEqualTo("otro-modelo")
        put(tenant, "/api/objects/$name", mapOf("label" to "Mío")).isEqualTo(409)
        // and another model cannot take what this one owns
        val stolen = runBlocking { models.ensure(tenantId, DeclaredModel("tercero", objects = listOf(CreateObjectRequest(name = name, label = "Libre")))) }
        assertThat(stolen.incompatible).singleElement().asString().contains("declared by 'otro-modelo'")
    }

    private fun provision(): Pair<String, UUID> {
        val slug = "dm-" + uniqueName("").take(8)
        post(admin, "/api/organizations", mapOf("name" to "Declared", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to "supersecret"))
            .isEqualTo(201)
        val tenant = bearer("$slug@wasichai.local", "supersecret")
        return tenant to organizationOf(tenant)
    }

    private fun organizationOf(token: String): UUID =
        UUID.fromString(runBlocking { decoder.decode(token.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))

    private fun objectOf(
        token: String,
        name: String
    ): WebTestClient.BodyContentSpec = get(token, "/api/objects/$name")

    private fun get(
        token: String,
        uri: String
    ): WebTestClient.BodyContentSpec =
        client
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun post(
        token: String,
        uri: String,
        body: Any
    ) = Status(
        client
            .post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()
    )

    private fun put(
        token: String,
        uri: String,
        body: Any
    ) = Status(
        client
            .put()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()
    )

    private fun delete(
        token: String,
        uri: String
    ) = Status(
        client
            .delete()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()
    )

    // a status, said as the assertion reads
    private class Status(
        val value: Int
    ) {
        fun isEqualTo(expected: Int) {
            assertThat(value).isEqualTo(expected)
        }
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

    // the source of every model entry the platform wrote in the tenant, with no user
    private fun platformSources(organizationId: UUID): List<String> =
        runBlocking {
            db
                .sql(
                    "SELECT source FROM ${schemas.metadata}.audit_log WHERE organization_id = :org AND user_id IS NULL " +
                        "AND object_name IN ('admin:object', 'admin:field', 'admin:relationship')"
                ).bind("org", organizationId)
                .map { row, _ -> row.get("source", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    private companion object {
        const val MODEL = "catalogo-test"
        const val PLAN = "dmtest_plan"
        const val COURSE = "dmtest_curso"
        const val RELATIONSHIP = "dmtest_plan_cursos"

        val CATALOG =
            DeclaredModel(
                name = MODEL,
                objects =
                    listOf(
                        CreateObjectRequest(
                            name = PLAN,
                            label = "Plan",
                            fields = listOf(FieldRequest(name = "codigo", type = "TEXT", unique = true, editable = false)),
                            apiOnly = true,
                            auditMode = "OPERATION"
                        ),
                        CreateObjectRequest(
                            name = COURSE,
                            label = "Curso",
                            fields = listOf(FieldRequest(name = "codigo", type = "TEXT", unique = true), FieldRequest(name = "creditos", type = "INTEGER"))
                        )
                    ),
                relationships =
                    listOf(
                        CreateRelationshipRequest(
                            name = RELATIONSHIP,
                            label = "Cursos",
                            type = "ONE_TO_MANY",
                            source = PLAN,
                            target = COURSE,
                            fieldName = "plan"
                        )
                    ),
                actions = mapOf(COURSE to listOf(ObjectActionRequest("PUBLICAR", "Publicar")))
            )
    }
}
