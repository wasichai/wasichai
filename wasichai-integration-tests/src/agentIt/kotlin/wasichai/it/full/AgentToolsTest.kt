package wasichai.it.full

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import wasichai.agent.AgentToolCatalog
import wasichai.agent.AgentToolResult
import wasichai.agent.AgentTools

// The security claim of the AI phase: the agent's tools see exactly what the asking user sees.
// The model is never called here. The tools are, directly, as a real authenticated user.
class AgentToolsTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var tools: AgentTools

    @Autowired
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var member: String
    private lateinit var granted: String
    private lateinit var forbidden: String
    private lateinit var recordId: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        granted = uniqueName("agentok")
        forbidden = uniqueName("agentno")
        createObject(granted)
        createObject(forbidden)
        recordId = createRecord(granted, "A-1", 42)

        val role = newRole()
        // READ on one object only, and one field of it hidden
        grantOn(role, granted, listOf("READ"))
        hideField(role, granted, "secreto")
        member = newUserToken(role)
    }

    @Test
    fun `list_objects shows only the objects the caller may read`() {
        val mine = run(member, AgentToolCatalog.LIST_OBJECTS)
        assertThat(mine.error).isFalse()
        assertThat(mine.json).contains(granted).doesNotContain(forbidden)

        val theirs = run(admin, AgentToolCatalog.LIST_OBJECTS)
        assertThat(theirs.json).contains(granted).contains(forbidden)
    }

    @Test
    fun `query_records on an object the caller may not read is refused`() {
        val refused = run(member, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to forbidden))
        assertThat(refused.error).isTrue()
        assertThat(refused.summary).startsWith("refused")
        assertThat(refused.json).doesNotContain("records")

        // the admin reaches the same object through the same tool
        val allowed = run(admin, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to forbidden))
        assertThat(allowed.error).isFalse()
    }

    @Test
    fun `a field the role cannot read is invisible to describe_object and to query_records`() {
        val described = run(member, AgentToolCatalog.DESCRIBE_OBJECT, mapOf("object" to granted))
        assertThat(described.error).isFalse()
        assertThat(described.json).contains("codigo").doesNotContain("secreto")

        val queried = run(member, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to granted))
        assertThat(queried.error).isFalse()
        assertThat(queried.json).contains("A-1").doesNotContain("secreto").doesNotContain("classified")

        val single = run(member, AgentToolCatalog.GET_RECORD, mapOf("object" to granted, "id" to recordId))
        assertThat(single.json).contains("A-1").doesNotContain("secreto")

        // the administrator is untouched by the rule
        val adminDescribed = run(admin, AgentToolCatalog.DESCRIBE_OBJECT, mapOf("object" to granted))
        assertThat(adminDescribed.json).contains("codigo").contains("secreto")
        val adminQueried = run(admin, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to granted))
        assertThat(adminQueried.json).contains("classified")
    }

    @Test
    fun `describe_object on an object the caller may not read is refused`() {
        val refused = run(member, AgentToolCatalog.DESCRIBE_OBJECT, mapOf("object" to forbidden))
        assertThat(refused.error).isTrue()
        assertThat(refused.summary).startsWith("refused")
    }

    @Test
    fun `count_records reports the true total and never leaks a hidden field`() {
        val counted = run(member, AgentToolCatalog.COUNT_RECORDS, mapOf("object" to granted))
        assertThat(counted.error).isFalse()
        assertThat(counted.json).contains("\"count\":1").doesNotContain("secreto")
    }

    @Test
    fun `an unknown object name comes back as a refusal, not a crash`() {
        val unknown = run(member, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to "no_such_object"))
        assertThat(unknown.error).isTrue()
        assertThat(unknown.json).contains("does not exist")
    }

    @Test
    fun `an unknown tool name comes back as a refusal`() {
        val unknown = run(member, "drop_everything")
        assertThat(unknown.error).isTrue()
        assertThat(unknown.json).contains("Unknown tool")
    }

    @Test
    fun `a missing required argument is reported to the model`() {
        val missing = run(member, AgentToolCatalog.QUERY_RECORDS)
        assertThat(missing.error).isTrue()
        assertThat(missing.json).contains("object")
    }

    @Test
    fun `the limit is capped no matter what the model asks for`() {
        repeat(3) { createRecord(granted, "BULK-$it", it) }
        val page = run(member, AgentToolCatalog.QUERY_RECORDS, mapOf("object" to granted, "limit" to 9999))
        assertThat(page.error).isFalse()
        assertThat(page.json).contains("\"limit\":50")
    }

    @Test
    fun `history and transitions obey the same permission as the records do`() {
        val history = run(member, AgentToolCatalog.RECORD_HISTORY, mapOf("object" to granted, "id" to recordId))
        assertThat(history.error).isFalse()
        // the hidden field must not surface through the audit log either
        assertThat(history.json).doesNotContain("classified")

        val transitions = run(member, AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to granted, "id" to recordId))
        assertThat(transitions.error).isFalse()

        val refused = run(member, AgentToolCatalog.LIST_RELATIONSHIPS, mapOf("object" to forbidden))
        assertThat(refused.error).isTrue()
    }

    // ------------------------------------------------------------------ helpers

    // the sdk is blocking, so the real service hops to Dispatchers.IO before every call. the hop
    // must not lose the caller: run the tools through the same hop and prove the identity survives.
    private fun run(
        token: String,
        tool: String,
        input: Map<String, Any?> = emptyMap()
    ): AgentToolResult {
        val jwt = jwtDecoder.decode(token.removePrefix("Bearer ")).block()!!
        val context = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt))
        return runBlocking(context.asCoroutineContext()) {
            withContext(Dispatchers.IO) { tools.invoke(tool, input) }
        }
    }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Agent target",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "secreto", "type" to "TEXT"),
                            mapOf("name" to "valor", "type" to "INTEGER")
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        objectName: String,
        codigo: String,
        valor: Int
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf("attributes" to mapOf("codigo" to codigo, "secreto" to "classified", "valor" to valor))
            ).exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .substringAfter("\"id\":\"")
            .substringBefore("\"")

    private fun newRole(): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Agent reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun grantOn(
        role: String,
        targetObject: String,
        actions: List<String>
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "permissions" to
                        actions.map { mapOf("objectName" to targetObject, "action" to it, "allowed" to true) }
                )
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun hideField(
        role: String,
        targetObject: String,
        fieldName: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "fields" to
                        listOf(
                            mapOf("objectName" to targetObject, "fieldName" to fieldName, "read" to false, "write" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun newUserToken(role: String): String {
        val email = "${uniqueName("agent")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "email" to email,
                    "displayName" to "Agent member",
                    "password" to "supersecret",
                    "roles" to listOf(role)
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
