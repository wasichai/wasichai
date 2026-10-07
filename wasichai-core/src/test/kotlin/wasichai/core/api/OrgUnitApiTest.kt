package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-045: the admin of organizational units, and who sits in which
class OrgUnitApiTest : WasichaiIntegrationTest() {
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `creates, lists, reads, renames and deletes units, codes normalised`() {
        val root = code("GER")
        val child = code("SUB")

        // lower case and spaces in: the code comes back as a token
        val created = post(admin, mapOf("code" to "  ${root.lowercase()} ", "label" to "  Gerencia  ")).expectStatus().isCreated
        val createdBody = json(created.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(createdBody["code"].asString()).isEqualTo(root)
        assertThat(createdBody["label"].asString()).isEqualTo("Gerencia")
        assertThat(createdBody["parentCode"].isNull).isTrue()
        assertThat(createdBody["memberCount"].asInt()).isZero()
        post(admin, mapOf("code" to child, "label" to "Subgerencia", "parentCode" to root.lowercase()))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.parentCode")
            .isEqualTo(root)

        val listed = list(admin)
        assertThat(listed.getValue(root)["parentCode"].isNull).isTrue()
        assertThat(listed.getValue(child)["parentCode"].asString()).isEqualTo(root)
        assertThat(listed.getValue(child)["memberCount"].asInt()).isZero()

        client
            .get()
            .uri("/api/org-units/${child.lowercase()}")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo(child)
            .jsonPath("$.label")
            .isEqualTo("Subgerencia")
            .jsonPath("$.parentCode")
            .isEqualTo(root)
            .jsonPath("$.members.length()")
            .isEqualTo(0)

        // a missing key keeps: the parent stays
        put(admin, child, mapOf("label" to "Subgerencia de Rentas"))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Subgerencia de Rentas")
            .jsonPath("$.parentCode")
            .isEqualTo(root)
            .jsonPath("$.memberCount")
            .isEqualTo(0)

        delete(admin, child).expectStatus().isNoContent
        delete(admin, root).expectStatus().isNoContent
        client
            .get()
            .uri("/api/org-units/$root")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
        delete(admin, root).expectStatus().isNotFound
        put(admin, root, mapOf("label" to "Gone")).expectStatus().isNotFound
    }

    @Test
    fun `rejects a bad code or label, a repeated code and an unknown parent`() {
        listOf("1ABC", "A", "BAD-CODE", "", "A".repeat(50)).forEach { bad ->
            post(admin, mapOf("code" to bad, "label" to "Bad"))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("code")
        }
        listOf("   ", "x".repeat(121)).forEach { bad ->
            post(admin, mapOf("code" to code("LBL"), "label" to bad))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("label")
        }
        post(admin, mapOf("code" to code("NOLBL"))).expectStatus().isBadRequest

        val unit = code("DUP")
        post(admin, mapOf("code" to unit, "label" to "First")).expectStatus().isCreated
        post(admin, mapOf("code" to unit.lowercase(), "label" to "Again")).expectStatus().isEqualTo(409)

        post(admin, mapOf("code" to code("ORPHAN"), "label" to "Orphan", "parentCode" to code("NOPE")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("parentCode")

        // label rules hold on PUT too
        put(admin, unit, mapOf("label" to " "))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("label")
        put(admin, unit, mapOf("label" to null)).expectStatus().isBadRequest
        put(admin, unit, mapOf("parentCode" to code("NOPE")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("parentCode")
    }

    @Test
    fun `an unknown key in a PUT is a bad request and changes nothing`() {
        val unit = createUnit(code("KEY"), "Keys")

        put(admin, unit, mapOf("label" to "Changed", "color" to "red"))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("color")
        put(admin, unit, mapOf("code" to "OTHER")).expectStatus().isBadRequest
        assertThat(list(admin).getValue(unit)["label"].asString()).isEqualTo("Keys")
    }

    @Test
    fun `moves a unit, to the root with null, never under itself or its own subtree`() {
        val a = createUnit(code("A"), "A")
        val b = createUnit(code("B"), "B", a)
        val c = createUnit(code("C"), "C", b)
        val other = createUnit(code("O"), "Other")

        listOf(a, b, c).forEach { target ->
            put(admin, a, mapOf("parentCode" to target))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("parentCode")
        }

        // the subtree goes along
        put(admin, b, mapOf("parentCode" to other.lowercase()))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.parentCode")
            .isEqualTo(other)
        assertThat(list(admin).getValue(c)["parentCode"].asString()).isEqualTo(b)

        put(admin, b, mapOf("parentCode" to null))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.parentCode")
            .isEmpty
        val listed = list(admin)
        assertThat(listed.getValue(b)["parentCode"].isNull).isTrue()
        assertThat(listed.getValue(c)["parentCode"].asString()).isEqualTo(b)
        // a is no longer above c, so it may go under it
        put(admin, a, mapOf("parentCode" to c)).expectStatus().isOk
    }

    @Test
    fun `depth is capped at ten, counting the subtree a move carries`() {
        val chain = mutableListOf<String>()
        repeat(10) { chain += createUnit(code("L$it"), "Level ${it + 1}", chain.lastOrNull()) }

        post(admin, mapOf("code" to code("DEEP"), "label" to "Eleventh", "parentCode" to chain.last()))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("parentCode")

        // a subtree two high: under level 9 it would reach 11, under level 8 it ends at 10
        val top = createUnit(code("X"), "X")
        createUnit(code("Y"), "Y", top)
        put(admin, top, mapOf("parentCode" to chain[8]))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("parentCode")
        put(admin, top, mapOf("parentCode" to chain[7])).expectStatus().isOk
    }

    @Test
    fun `a unit with sub-units or members cannot be deleted`() {
        val parent = createUnit(code("P"), "Parent")
        val child = createUnit(code("K"), "Child", parent)
        delete(admin, parent).expectStatus().isEqualTo(409)

        val user = createUser(admin, "${uniqueName("m")}@wasichai.local")
        setUnits(admin, user, listOf(child)).expectStatus().isOk
        delete(admin, child).expectStatus().isEqualTo(409)

        setUnits(admin, user, emptyList()).expectStatus().isOk
        delete(admin, child).expectStatus().isNoContent
        delete(admin, parent).expectStatus().isNoContent
    }

    @Test
    fun `replaces a user's units, shows them on the user, lists the members`() {
        val root = createUnit(code("R"), "Root")
        val child = createUnit(code("H"), "Child", root)
        val email = "${uniqueName("m")}@wasichai.local"
        val user = createUser(admin, email)

        setUnits(admin, user, listOf(child.lowercase(), root, " $root "))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(user)
            .jsonPath("$.orgUnits")
            .isEqualTo(listOf(child, root).sorted())

        val listed =
            json(
                client
                    .get()
                    .uri("/api/users")
                    .header(HttpHeaders.AUTHORIZATION, admin)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            ).first { it["id"].asString() == user }
        assertThat(texts(listed["orgUnits"])).containsExactlyElementsOf(listOf(child, root).sorted())
        assertThat(list(admin).getValue(child)["memberCount"].asInt()).isEqualTo(1)
        client
            .get()
            .uri("/api/org-units/$child")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.members.length()")
            .isEqualTo(1)
            .jsonPath("$.members[0].id")
            .isEqualTo(user)
            .jsonPath("$.members[0].email")
            .isEqualTo(email)
            .jsonPath("$.members[0].displayName")
            .isEqualTo("Member")

        // replaces, does not append
        setUnits(admin, user, listOf(root))
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.orgUnits")
            .isEqualTo(listOf(root))

        // unknown code: 400 on its index, and the set stays
        setUnits(admin, user, listOf(child, code("NOPE")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("units[1]")
        assertThat(list(admin).getValue(root)["memberCount"].asInt()).isEqualTo(1)

        setUnits(admin, UUID.randomUUID().toString(), listOf(root)).expectStatus().isNotFound

        // a service account is nobody's member
        val account =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("name" to uniqueName("sa"), "roles" to emptyList<String>()))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .let { json(it)["id"].asString() }
        setUnits(admin, account, listOf(root)).expectStatus().isNotFound
    }

    @Test
    fun `a member reads their own units with paths`() {
        val root = createUnit(code("GR"), "Gerencia")
        val sub = createUnit(code("SG"), "Subgerencia", root)
        val area = createUnit(code("AR"), "Area", sub)
        val other = createUnit(code("OT"), "Otra")
        val email = "${uniqueName("m")}@wasichai.local"
        val user = createUser(admin, email)
        setUnits(admin, user, listOf(area, other)).expectStatus().isOk

        val mine =
            json(
                client
                    .get()
                    .uri("/api/auth/me/org-units")
                    .header(HttpHeaders.AUTHORIZATION, bearer(email, PASSWORD))
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            )
        // by label: Area before Otra
        assertThat(texts(mine, "code")).containsExactly(area, other)
        assertThat(mine[0]["label"].asString()).isEqualTo("Area")
        assertThat(texts(mine[0]["path"])).containsExactly(root, sub, area)
        assertThat(texts(mine[1]["path"])).containsExactly(other)
        assertThat(mine[0].has("id")).isFalse()
    }

    @Test
    fun `only MANAGE_ORGANIZATION administers units, and never a service account`() {
        val unit = createUnit(code("PERM"), "Perm")
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to role, "label" to "Reader", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "READ", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("u")}@wasichai.local"
        val user = createUser(admin, email, listOf(role))
        val person = bearer(email, PASSWORD)
        refusedEverywhere(person, unit, user)
        // reading your own units needs nothing
        client
            .get()
            .uri("/api/auth/me/org-units")
            .header(HttpHeaders.AUTHORIZATION, person)
            .exchange()
            .expectStatus()
            .isOk

        // an account with MANAGE_ORGANIZATION granted through a role is still refused (ADR-043)
        val manager = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to manager, "label" to "Manager", "ownRecordsOnly" to false))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$manager/permissions")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("permissions" to listOf(mapOf("objectName" to null, "action" to "MANAGE_ORGANIZATION", "allowed" to true))))
            .exchange()
            .expectStatus()
            .isOk
        val account =
            json(
                client
                    .post()
                    .uri("/api/service-accounts")
                    .header(HttpHeaders.AUTHORIZATION, admin)
                    .bodyValue(mapOf("name" to uniqueName("sa"), "roles" to listOf(manager)))
                    .exchange()
                    .expectStatus()
                    .isCreated
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            )
        val token =
            "Bearer " +
                json(
                    client
                        .post()
                        .uri("/api/auth/token")
                        .bodyValue(mapOf("clientId" to account["clientId"].asString(), "clientSecret" to account["clientSecret"].asString()))
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!
                )["token"].asString()
        refusedEverywhere(token, unit, user)
        client
            .get()
            .uri("/api/auth/me/org-units")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("[]")
    }

    @Test
    fun `another tenant's units are missing, and deleting that tenant takes its units along`() {
        val slug = "tenant-" + uniqueName("").take(8)
        client
            .post()
            .uri("/api/organizations")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to "Tenant", "slug" to slug, "adminEmail" to "$slug@wasichai.local", "adminPassword" to PASSWORD))
            .exchange()
            .expectStatus()
            .isCreated
        val theirAdmin = bearer("$slug@wasichai.local", PASSWORD)
        val theirs = code("AJENA")
        val theirChild = code("AJENAH")
        post(theirAdmin, mapOf("code" to theirs, "label" to "Ajena")).expectStatus().isCreated
        post(theirAdmin, mapOf("code" to theirChild, "label" to "Ajena hija", "parentCode" to theirs)).expectStatus().isCreated
        val theirUser = createUser(theirAdmin, "${uniqueName("t")}@wasichai.local")
        setUnits(theirAdmin, theirUser, listOf(theirChild)).expectStatus().isOk

        // the same code is free in this tenant
        val mine = createUnit(theirs, "Mine")
        assertThat(list(admin)).doesNotContainKey(theirChild)
        client
            .get()
            .uri("/api/org-units/$theirChild")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNotFound
        put(admin, theirChild, mapOf("label" to "Taken")).expectStatus().isNotFound
        delete(admin, theirChild).expectStatus().isNotFound
        post(admin, mapOf("code" to code("Z"), "label" to "Z", "parentCode" to theirChild)).expectStatus().isBadRequest
        put(admin, mine, mapOf("parentCode" to theirChild)).expectStatus().isBadRequest
        setUnits(admin, theirUser, listOf(mine)).expectStatus().isNotFound
        val ownUser = createUser(admin, "${uniqueName("m")}@wasichai.local")
        setUnits(admin, ownUser, listOf(theirChild)).expectStatus().isBadRequest
        assertThat(list(theirAdmin).getValue(theirChild)["label"].asString()).isEqualTo("Ajena hija")

        // units, children and members go with the organization
        client
            .delete()
            .uri("/api/organizations/current")
            .header(HttpHeaders.AUTHORIZATION, theirAdmin)
            .exchange()
            .expectStatus()
            .isNoContent
        assertThat(list(admin).getValue(mine)["label"].asString()).isEqualTo("Mine")
    }

    // ------------------------------------------------------------------ helpers

    // codes must start with a letter: the prefix does, the suffix makes it unique in the shared database
    private fun code(prefix: String): String = prefix + "_" + uniqueName("").uppercase()

    private fun refusedEverywhere(
        token: String,
        unit: String,
        user: String
    ) {
        val requests =
            listOf(
                client.get().uri("/api/org-units"),
                client.get().uri("/api/org-units/$unit"),
                client.post().uri("/api/org-units").bodyValue(mapOf("code" to code("NO"), "label" to "No")),
                client.put().uri("/api/org-units/$unit").bodyValue(mapOf("label" to "No")),
                client.delete().uri("/api/org-units/$unit"),
                client.put().uri("/api/users/$user/org-units").bodyValue(mapOf("units" to listOf(unit)))
            )
        requests.forEach { request ->
            request
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isForbidden
        }
    }

    private fun createUnit(
        code: String,
        label: String,
        parent: String? = null
    ): String {
        post(admin, mapOf("code" to code, "label" to label, "parentCode" to parent)).expectStatus().isCreated
        return code
    }

    private fun post(
        token: String,
        body: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/org-units")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()

    private fun put(
        token: String,
        code: String,
        body: Map<String, Any?>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/org-units/$code")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()

    private fun delete(
        token: String,
        code: String
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri("/api/org-units/$code")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private fun setUnits(
        token: String,
        user: String,
        units: List<String>
    ): WebTestClient.ResponseSpec =
        client
            .put()
            .uri("/api/users/$user/org-units")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("units" to units))
            .exchange()

    // by code
    private fun list(token: String): Map<String, JsonNode> =
        json(
            client
                .get()
                .uri("/api/org-units")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        ).associateBy { it["code"].asString() }

    private fun createUser(
        token: String,
        email: String,
        roles: List<String> = emptyList()
    ): String =
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to PASSWORD, "roles" to roles))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { json(it)["id"].asString() }

    private fun json(body: String): JsonNode = mapper.readTree(body)

    // an array's strings, or one field of each element
    private fun texts(
        node: JsonNode,
        field: String? = null
    ): List<String> = (0 until node.size()).map { (if (field == null) node[it] else node[it][field]).asString() }

    companion object {
        private const val PASSWORD = "supersecret"
        private val mapper = JsonMapper.builder().build()
    }
}
