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
import org.springframework.test.web.reactive.server.WebTestClient
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.JwtService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// issue 48 (ADR-048): an app keeps a caller to the records of their projects. two callers with different
// scopes on the same objects, across every core route that reads a record. out of scope reads as missing.
@Import(RecordReadScopeApiTest.ScopeConfig::class)
class RecordReadScopeApiTest : WasichaiIntegrationTest() {
    // the app's rule, as SGSPE has it: a caller reads only the records whose project_id is one of theirs.
    // callers are named by email, a service account by its name; one not named here is not restricted.
    class ProjectScope : RecordReadScope {
        val projects = ConcurrentHashMap<String, Set<String>>()
        val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override suspend fun criterion(
            caller: AuthenticatedUser,
            definition: ObjectDefinition
        ): RecordCriterion? {
            val key = caller.serviceAccount ?: caller.email
            asked += key
            val allowed = projects[key] ?: return null
            val field = definition.fields.firstOrNull { it.name == "project_id" } ?: return null
            if (allowed.isEmpty()) return RecordCriterion { _, _ -> "false" }
            val column = SqlIdentifier.quote(field.columnName)
            // an OR of its own, on purpose: it must stay inside its parens
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
    private lateinit var records: RecordService

    @Autowired
    private lateinit var decoder: ReactiveJwtDecoder

    private lateinit var admin: String
    private lateinit var obra: String
    private lateinit var persona: String
    private lateinit var titular: String
    private lateinit var vinculo: String
    private lateinit var role: String

    // project A: Ana, and the works A-1 (Ana's) and A-2 (Beto's). project B: Beto, and B-1 (Beto's)
    private lateinit var ana: String
    private lateinit var beto: String
    private lateinit var a1: String
    private lateinit var a2: String
    private lateinit var b1: String

    // alice reads project A, bob project B
    private lateinit var alice: String
    private lateinit var bob: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
        // ADMIN is never asked: an empty scope here would hide everything from it if it were
        scope.projects[WasichaiIntegrationTest.ADMIN_EMAIL] = emptySet()
        obra = uniqueName("obra")
        persona = uniqueName("persona")
        createObject(persona, listOf(text("nombre"), text("project_id")))
        createObject(obra, listOf(text("codigo"), text("project_id")))
        titular = createRelationship("MANY_TO_ONE", "titular")
        vinculo = createRelationship("MANY_TO_MANY", null)

        ana = createRecord(admin, persona, mapOf("nombre" to "Ana", "project_id" to "A"))
        beto = createRecord(admin, persona, mapOf("nombre" to "Beto", "project_id" to "B"))
        a1 = createRecord(admin, obra, mapOf("codigo" to "A-1", "project_id" to "A", "titular" to ana))
        a2 = createRecord(admin, obra, mapOf("codigo" to "A-2", "project_id" to "A", "titular" to beto))
        b1 = createRecord(admin, obra, mapOf("codigo" to "B-1", "project_id" to "B", "titular" to beto))

        role = newRole(ownRecordsOnly = false)
        grant(role, "READ", "CREATE", "UPDATE", "DELETE")
        alice = newUserToken("A", role = role)
        bob = newUserToken("B", role = role)
    }

    @Test
    fun `two callers on the same object list, count and get only their own projects`() {
        list(alice, obra).let {
            assertThat(it.total).isEqualTo(2)
            assertThat(it.codes).containsExactlyInAnyOrder("A-1", "A-2")
        }
        list(bob, obra).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.codes).containsExactly("B-1")
        }
        // a filter or a search narrows inside the scope, never past it
        assertThat(list(alice, obra, "?codigo=B-1").total).isEqualTo(0)
        assertThat(list(alice, obra, "?q=B-").total).isEqualTo(0)
        assertThat(list(bob, persona).names).containsExactly("Beto")

        get(alice, obra, a1).expectStatus().isOk
        get(bob, obra, b1).expectStatus().isOk
        val unseen = get(alice, obra, b1).expectStatus().isNotFound.problem()
        val missing = get(alice, obra, UUID.randomUUID().toString()).expectStatus().isNotFound.problem()
        // never a 403, and the same answer as a record that does not exist
        assertThat(unseen.filterKeys { it != "detail" }).isEqualTo(missing.filterKeys { it != "detail" })
        assertThat(unseen["detail"]).isEqualTo("Record $b1 does not exist")
        get(bob, obra, a1).expectStatus().isNotFound
    }

    @Test
    fun `a record out of scope cannot be updated or deleted, it is a 404`() {
        update(alice, obra, b1, mapOf("codigo" to "B-1x", "project_id" to "B")).expectStatus().isNotFound
        delete(alice, obra, b1).expectStatus().isNotFound

        get(admin, obra, b1)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.codigo")
            .isEqualTo("B-1")
        assertThat(auditOperations(admin, b1)).containsExactly("CREATE")

        // in scope, the same calls go through
        update(bob, obra, b1, mapOf("codigo" to "B-1x", "project_id" to "B")).expectStatus().isOk
        delete(bob, obra, b1).expectStatus().isNoContent
    }

    @Test
    fun `related records are scoped on both sides, and a walk from a record out of scope is a 404`() {
        // from the work, which carries the key: its titular, when in scope
        assertThat(related(alice, obra, a1, titular).names).containsExactly("Ana")
        assertThat(related(alice, obra, a2, titular).total).isEqualTo(0)
        relatedCall(alice, obra, b1, titular).expectStatus().isNotFound

        // from the person, whom works point back at: only the works in scope
        related(alice, persona, ana, titular).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.codes).containsExactly("A-1")
        }
        related(bob, persona, beto, titular).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.codes).containsExactly("B-1")
        }
        relatedCall(bob, persona, ana, titular).expectStatus().isNotFound

        // a join table: the admin links both, each caller sees their side
        link(admin, a1, beto).expectStatus().isNoContent
        link(admin, a1, ana).expectStatus().isNoContent
        assertThat(related(admin, obra, a1, vinculo).total).isEqualTo(2)
        assertThat(related(alice, obra, a1, vinculo).names).containsExactly("Ana")
        relatedCall(bob, obra, a1, vinculo).expectStatus().isNotFound
        assertThat(related(bob, persona, beto, vinculo).total).isEqualTo(0)
    }

    @Test
    fun `a link or unlink naming a record out of scope is a 404 on either end`() {
        link(alice, a1, beto).expectStatus().isNotFound
        link(bob, b1, ana).expectStatus().isNotFound
        link(alice, b1, ana).expectStatus().isNotFound
        assertThat(related(admin, obra, a1, vinculo).total).isEqualTo(0)

        link(alice, a1, ana).expectStatus().isNoContent
        unlinkCall(bob, a1, ana).expectStatus().isNotFound
        assertThat(related(admin, obra, a1, vinculo).total).isEqualTo(1)
    }

    @Test
    fun `history of a record out of scope is a 404, and the audit log leaves its entries out`() {
        history(alice, obra, a1).expectStatus().isOk
        history(bob, obra, b1)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
        history(alice, obra, b1).expectStatus().isNotFound
        history(bob, obra, a1).expectStatus().isNotFound

        assertThat(auditRecords(alice, obra)).containsExactlyInAnyOrder(a1, a2)
        assertThat(auditRecords(bob, obra)).containsExactly(b1)
        // asking for one record out of scope finds nothing, never an error
        assertThat(auditRecords(alice, obra, "&recordId=$b1")).isEmpty()
        assertThat(auditRecords(admin, obra)).containsExactlyInAnyOrder(a1, a2, b1)
    }

    // a record that is gone cannot be shown to be in anyone's scope: only a caller with no scope sees it
    @Test
    fun `entries of a deleted record are shown only to a caller with no scope on the object`() {
        val a3 = createRecord(admin, obra, mapOf("codigo" to "A-3", "project_id" to "A"))
        delete(admin, obra, a3).expectStatus().isNoContent
        val carol = newUserToken(null, role = role)

        assertThat(auditRecords(alice, obra)).doesNotContain(a3)
        history(alice, obra, a3).expectStatus().isNotFound
        assertThat(auditRecords(carol, obra)).contains(a3, b1)
        history(carol, obra, a3)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)
    }

    @Test
    fun `a RELATION value may only name a record in scope, with the answer for a missing one`() {
        val unseen = create(alice, obra, mapOf("codigo" to "A-9", "project_id" to "A", "titular" to beto)).expectStatus().isBadRequest.problem()
        val missing =
            create(alice, obra, mapOf("codigo" to "A-9", "project_id" to "A", "titular" to UUID.randomUUID().toString()))
                .expectStatus()
                .isBadRequest
                .problem()

        assertThat(unseen).isEqualTo(missing)
        assertThat((unseen["errors"] as List<*>).map { (it as Map<*, *>)["field"] }).containsExactly("titular")
        assertThat(list(admin, obra).total).isEqualTo(3)

        create(alice, obra, mapOf("codigo" to "A-9", "project_id" to "A", "titular" to ana)).expectStatus().isCreated
        // an update that keeps a value is no new claim on it (D30), one that changes it is checked
        update(bob, obra, b1, mapOf("codigo" to "B-2", "project_id" to "B", "titular" to beto)).expectStatus().isOk
        update(bob, obra, b1, mapOf("codigo" to "B-2", "project_id" to "B", "titular" to ana)).expectStatus().isBadRequest
    }

    @Test
    fun `a caller whose scope is empty reads nothing, and the totals are 0`() {
        val nobody = newUserToken("", role = role)

        list(nobody, obra).let {
            assertThat(it.total).isEqualTo(0)
            assertThat(it.codes).isEmpty()
        }
        assertThat(list(nobody, persona).total).isEqualTo(0)
        get(nobody, obra, a1).expectStatus().isNotFound
        history(nobody, obra, a1).expectStatus().isNotFound
        relatedCall(nobody, persona, ana, titular).expectStatus().isNotFound
        assertThat(auditRecords(nobody, obra)).isEmpty()
    }

    // without its parens the scope would read: created_by = me AND project_id = 'A' OR project_id = 'B',
    // and every B record of anyone would show up
    @Test
    fun `an app's OR cannot widen the owner filter`() {
        val owners = newRole(ownRecordsOnly = true)
        grant(owners, "READ", "CREATE")
        val owner = newUserToken("A", "B", role = owners)
        val mine = createRecord(owner, obra, mapOf("codigo" to "O-1", "project_id" to "A"))

        list(owner, obra).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.codes).containsExactly("O-1")
        }
        get(owner, obra, b1).expectStatus().isNotFound
        get(owner, obra, mine).expectStatus().isOk
    }

    @Test
    fun `ADMIN, the platform and a caller with no scope read every record`() {
        assertThat(list(admin, obra).total).isEqualTo(3)
        assertThat(scope.asked).doesNotContain(WasichaiIntegrationTest.ADMIN_EMAIL)

        val organizationId =
            UUID.fromString(runBlocking { decoder.decode(admin.removePrefix("Bearer ")).awaitSingle() }.getClaimAsString(JwtService.CLAIM_ORGANIZATION))
        val platform = runBlocking { records.asPlatform(organizationId) { records.list(obra, RecordQuery(page = PageRequest.of(0, 25))) } }
        assertThat(platform.totalElements).isEqualTo(3)

        val carol = newUserToken(null, role = role)
        assertThat(list(carol, obra).total).isEqualTo(3)
    }

    @Test
    fun `a service account is scoped like a person`() {
        val account = serviceAccountToken(role, "B")

        list(account, obra).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.codes).containsExactly("B-1")
        }
        get(account, obra, a1).expectStatus().isNotFound
    }

    // the scope is handed the whole definition: it filters on a field the caller may not read
    @Test
    fun `the scope may filter on a field the caller cannot read`() {
        val hidden = newRole(ownRecordsOnly = false)
        grant(hidden, "READ")
        client
            .put()
            .uri("/api/roles/$hidden/field-permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("fields" to listOf(mapOf("objectName" to obra, "fieldName" to "project_id", "read" to false, "write" to false))))
            .exchange()
            .expectStatus()
            .isOk
        val dora = newUserToken("B", role = hidden)

        val body =
            listCall(dora, obra)
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertThat(body).contains("B-1").doesNotContain("A-1").doesNotContain("project_id")
    }

    // ---- helpers ----

    private data class Page(
        val total: Long?,
        // codigo of each work, nombre of each person
        val codes: List<String>,
        val names: List<String>
    )

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

    // obra -> persona. the name of the relationship made
    private fun createRelationship(
        type: String,
        fieldName: String?
    ): String {
        val name = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                buildMap {
                    put("name", name)
                    put("label", "Titular")
                    put("inverseLabel", "Obras")
                    put("type", type)
                    put("source", obra)
                    put("target", persona)
                    fieldName?.let { put("fieldName", it) }
                }
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun create(
        token: String,
        name: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun createRecord(
        token: String,
        name: String,
        attributes: Map<String, Any?>
    ): String =
        create(token, name, attributes)
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String

    private fun get(
        token: String,
        name: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun update(
        token: String,
        name: String,
        id: String,
        attributes: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()

    private fun delete(
        token: String,
        name: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun listCall(
        token: String,
        name: String,
        query: String = ""
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$name/records$query")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun list(
        token: String,
        name: String,
        query: String = ""
    ): Page = listCall(token, name, query).expectStatus().isOk.page()

    private fun relatedCall(
        token: String,
        name: String,
        id: String,
        relationship: String
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun related(
        token: String,
        name: String,
        id: String,
        relationship: String
    ): Page = relatedCall(token, name, id, relationship).expectStatus().isOk.page()

    private fun link(
        token: String,
        work: String,
        person: String
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$obra/records/$work/related/$vinculo")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("otherId" to person))
            .exchange()

    private fun unlinkCall(
        token: String,
        work: String,
        person: String
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri("/api/objects/$obra/records/$work/related/$vinculo/$person")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun history(
        token: String,
        name: String,
        id: String
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    // the record ids /api/audit lists for the object
    private fun auditRecords(
        token: String,
        name: String,
        extra: String = ""
    ): List<String> = audit(token, "?objectName=$name$extra").map { it["recordId"] as String }.distinct()

    private fun auditOperations(
        token: String,
        id: String
    ): List<String> = audit(token, "?objectName=$obra&recordId=$id").map { it["operation"] as String }

    private fun audit(
        token: String,
        query: String
    ): List<Map<*, *>> =
        client
            .get()
            .uri("/api/audit$query")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(List::class.java)
            .returnResult()
            .responseBody!!
            .map { it as Map<*, *> }

    private fun WebTestClient.ResponseSpec.page(): Page {
        val body = expectBody(Map::class.java).returnResult().responseBody!!
        val content = (body["content"] as List<*>).map { (it as Map<*, *>)["attributes"] as Map<*, *> }
        return Page(
            total = (body["totalElements"] as Number?)?.toLong(),
            codes = content.mapNotNull { it["codigo"] as String? },
            names = content.mapNotNull { it["nombre"] as String? }
        )
    }

    // the problem body, minus what differs per request
    private fun WebTestClient.ResponseSpec.problem(): Map<*, *> =
        expectBody(Map::class.java)
            .returnResult()
            .responseBody!!
            .filterKeys { it != "instance" }

    private fun newRole(ownRecordsOnly: Boolean): String {
        val name = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to "Projects", "ownRecordsOnly" to ownRecordsOnly))
            .exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // organization-wide: /api/audit needs it, and it opens both objects
    private fun grant(
        role: String,
        vararg actions: String
    ) {
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to actions.map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
    }

    // a person with [role], reading [projects]. null: not named in the scope at all, so unrestricted.
    // "": named with no project, so reads nothing
    private fun newUserToken(
        vararg projects: String?,
        role: String
    ): String {
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        if (projects.any { it != null }) scope.projects[email] = projects.filterNotNull().filter { it.isNotEmpty() }.toSet()
        return bearer(email, "supersecret")
    }

    private fun serviceAccountToken(
        role: String,
        project: String
    ): String {
        val name = uniqueName("erp")
        val body =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to name, "roles" to listOf(role)))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        scope.projects[name] = setOf(project)
        return "Bearer " +
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to body["clientId"], "clientSecret" to body["clientSecret"]))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!["token"] as String
    }
}
