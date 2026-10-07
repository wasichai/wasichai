package wasichai.it.full

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.agent.AgentToolCatalog
import wasichai.agent.AgentToolResult
import wasichai.agent.AgentTools
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import java.util.concurrent.ConcurrentHashMap

// issue 48 (ADR-048): the modules that read records read them through core, so the app's read scope reaches
// them with nothing of their own: GIS features (RecordService.rows and get), the agent's tools (RecordService,
// AuditQueryService, WorkflowService) and workflow transitions (WorkflowService). two callers, one object.
@Import(RecordReadScopeModulesTest.ScopeConfig::class)
class RecordReadScopeModulesTest : FullAppIntegrationTest() {
    // a caller reads only the records whose project_id is one of theirs. one not named here is not restricted.
    class ProjectScope : RecordReadScope {
        val projects = ConcurrentHashMap<String, Set<String>>()

        override suspend fun criterion(
            caller: AuthenticatedUser,
            definition: ObjectDefinition
        ): RecordCriterion? {
            val allowed = projects[caller.email] ?: return null
            val field = definition.fields.firstOrNull { it.name == "project_id" } ?: return null
            if (allowed.isEmpty()) return RecordCriterion { _, _ -> "false" }
            val column = SqlIdentifier.quote(field.columnName)
            return RecordCriterion { _, bind -> allowed.sorted().joinToString(" OR ") { "$column = ${bind(it)}" } }
        }
    }

    @TestConfiguration
    class ScopeConfig {
        @Bean
        fun projectScope(): ProjectScope = ProjectScope()
    }

    @Autowired
    private lateinit var scope: ProjectScope

    @Autowired
    private lateinit var tools: AgentTools

    @Autowired
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var obra: String
    private lateinit var a1: String
    private lateinit var b1: String
    private lateinit var alice: String
    private lateinit var bob: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        obra = uniqueName("obra")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to obra,
                    "label" to "Obra",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "project_id", "type" to "TEXT"),
                            mapOf("name" to "punto", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 4326)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
        // records made after the workflow start in its initial state
        client
            .put()
            .uri("/api/objects/$obra/workflow")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("wf"),
                    "label" to "Aprobacion",
                    "enabled" to true,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
        a1 = createRecord("A-1", "A", -77.02)
        b1 = createRecord("B-1", "B", -77.03)

        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Projects", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf("READ", "UPDATE").map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
        alice = newUserToken(role, "A")
        bob = newUserToken(role, "B")
    }

    @Test
    fun `GIS features hold only the records in the caller's scope`() {
        assertThat(featureCodes(alice, "")).containsExactly("A-1")
        assertThat(featureCodes(bob, "")).containsExactly("B-1")
        // the bbox criterion and the scope, each in its own parens
        assertThat(featureCodes(alice, "?bbox=-78,-13,-76,-11")).containsExactly("A-1")
        assertThat(featureCodes(admin, "")).containsExactlyInAnyOrder("A-1", "B-1")

        feature(alice, a1).expectStatus().isOk
        feature(bob, b1).expectStatus().isOk
        feature(alice, b1).expectStatus().isNotFound
        feature(bob, a1).expectStatus().isNotFound
    }

    @Test
    fun `the agent's query, count and get read only the records in scope`() {
        val queried = run(alice, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to obra))
        assertThat(queried.error).isFalse()
        assertThat(queried.json).contains("\"total\":1").contains("A-1").doesNotContain("B-1")

        val spatial = run(bob, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to obra, "bbox" to "-78,-13,-76,-11"))
        assertThat(spatial.json).contains("\"total\":1").contains("B-1").doesNotContain("A-1")

        assertThat(run(alice, AgentToolCatalog.COUNT_RECORDS, mapOf("object" to obra)).json).contains("\"count\":1")
        assertThat(run(admin, AgentToolCatalog.COUNT_RECORDS, mapOf("object" to obra)).json).contains("\"count\":2")

        assertThat(run(alice, AgentToolCatalog.GET_RECORD, mapOf("object" to obra, "id" to a1)).error).isFalse()
        val unseen = run(alice, AgentToolCatalog.GET_RECORD, mapOf("object" to obra, "id" to b1))
        assertThat(unseen.error).isTrue()
        assertThat(unseen.json).contains("does not exist").doesNotContain("B-1")
    }

    @Test
    fun `the agent's history and transitions of a record out of scope are refused as missing`() {
        assertThat(run(bob, AgentToolCatalog.RECORD_HISTORY, mapOf("object" to obra, "id" to b1)).error).isFalse()
        val history = run(alice, AgentToolCatalog.RECORD_HISTORY, mapOf("object" to obra, "id" to b1))
        assertThat(history.error).isTrue()
        assertThat(history.json).contains("does not exist")

        assertThat(run(alice, AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to obra, "id" to a1)).json).contains("approve")
        val transitions = run(alice, AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to obra, "id" to b1))
        assertThat(transitions.error).isTrue()
        assertThat(transitions.json).contains("does not exist")
    }

    @Test
    fun `a workflow transition of a record out of scope is a 404, and the record stays where it was`() {
        transitions(alice, b1).expectStatus().isNotFound
        transition(alice, b1).expectStatus().isNotFound
        client
            .get()
            .uri("/api/objects/$obra/records/$b1")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("draft")

        transitions(bob, b1).expectStatus().isOk
        transition(bob, b1)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.state")
            .isEqualTo("approved")
    }

    // ---- helpers ----

    private fun createRecord(
        codigo: String,
        project: String,
        longitude: Double
    ): String =
        client
            .post()
            .uri("/api/objects/$obra/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "attributes" to mapOf("codigo" to codigo, "project_id" to project),
                    "geometries" to mapOf("punto" to mapOf("type" to "Point", "coordinates" to listOf(longitude, -12.04)))
                )
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String

    private fun newUserToken(
        role: String,
        project: String
    ): String {
        val email = "${uniqueName("scoped")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Scoped", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        scope.projects[email] = setOf(project)
        return bearer(email, "supersecret")
    }

    // the codigo of every feature the map gets
    private fun featureCodes(
        token: String,
        query: String
    ): List<String> {
        val body =
            client
                .get()
                .uri("/api/gis/objects/$obra/features$query")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        return (body["features"] as List<*>).map { ((it as Map<*, *>)["properties"] as Map<*, *>)["codigo"] as String }
    }

    private fun feature(
        token: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/gis/objects/$obra/features/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun transitions(
        token: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$obra/records/$id/transitions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun transition(
        token: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$obra/records/$id/transitions/approve")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    // the tools as the caller, through the same dispatcher hop the real service makes (see AgentToolsTest)
    private fun run(
        token: String,
        tool: String,
        input: Map<String, Any?>
    ): AgentToolResult {
        val jwt = jwtDecoder.decode(token.removePrefix("Bearer ")).block()!!
        val context = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt))
        return runBlocking(context.asCoroutineContext()) {
            withContext(Dispatchers.IO) { tools.invoke(tool, input) }
        }
    }
}
