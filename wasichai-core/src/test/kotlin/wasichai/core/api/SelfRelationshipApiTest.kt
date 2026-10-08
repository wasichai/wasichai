package wasichai.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.util.UriUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest

// issue 61: a relationship from an object to itself (a parent unit, a previous version, a duplicate-of)
// is walked both ways. forward from its source end, as every relationship always was; ?direction=inverse
// from its target end. one test per relationship type, then paging, refusals, the listing and the
// caller's own-records rule.
class SelfRelationshipApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String
    private lateinit var unit: String
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun createUnit() {
        token = bearer()
        unit = uniqueName("unit")
        createObject(unit, "Unit", "Units")
    }

    @Test
    fun `many-to-one self - the default reads the parent, inverse lists the children`() {
        val rel = createRelationship("MANY_TO_ONE", unit, unit, "parent_unit", inverseLabel = "Child units")
        val root = createRecord(unit, mapOf("nombre" to "Root"))
        val north = createRecord(unit, mapOf("nombre" to "North", "parent_unit" to root))
        val south = createRecord(unit, mapOf("nombre" to "South", "parent_unit" to root))
        val town = createRecord(unit, mapOf("nombre" to "Town", "parent_unit" to north))

        // from a child: its one parent, with or without the parameter
        assertThat(related(unit, north, rel).names).containsExactly("Root")
        assertThat(related(unit, north, rel, "?direction=forward").names).containsExactly("Root")
        assertThat(related(unit, town, rel).names).containsExactly("North")
        // the root has no parent
        assertThat(related(unit, root, rel).total).isEqualTo(0)

        // from a parent: its children, not its grandchildren
        related(unit, root, rel, "?direction=inverse&sort=nombre").let {
            assertThat(it.total).isEqualTo(2)
            assertThat(it.names).containsExactly("North", "South")
        }
        assertThat(related(unit, north, rel, "?direction=INVERSE").names).containsExactly("Town")
        assertThat(related(unit, south, rel, "?direction=inverse").total).isEqualTo(0)
    }

    @Test
    fun `one-to-many self - the default follows the record's own key to the parent, inverse lists the children`() {
        // the key sits on the target: each child names its parent. forward stays the walk the read always made
        val rel = createRelationship("ONE_TO_MANY", unit, unit, "parent", inverseLabel = "Parent")
        val root = createRecord(unit, mapOf("nombre" to "Root"))
        val east = createRecord(unit, mapOf("nombre" to "East", "parent" to root))
        createRecord(unit, mapOf("nombre" to "West", "parent" to root))

        assertThat(related(unit, east, rel).names).containsExactly("Root")
        assertThat(related(unit, east, rel, "?direction=forward").names).containsExactly("Root")
        assertThat(related(unit, root, rel).total).isEqualTo(0)

        related(unit, root, rel, "?direction=inverse&sort=nombre").let {
            assertThat(it.total).isEqualTo(2)
            assertThat(it.names).containsExactly("East", "West")
        }
        assertThat(related(unit, east, rel, "?direction=inverse").total).isEqualTo(0)

        // the listing says what each direction reads: forward one parent, inverse many children
        val sides = relationshipsOf(unit).filter { it.get("relationship").asString() == rel }.map { side(it) }
        assertThat(sides).containsExactly(listOf("Parent", "false", unit, "forward"), listOf("Label $rel", "true", unit, "inverse"))
    }

    @Test
    fun `one-to-one self - forward follows the key, inverse finds the record pointing at this one`() {
        val rel = createRelationship("ONE_TO_ONE", unit, unit, "previous_version", inverseLabel = "Next version")
        val v1 = createRecord(unit, mapOf("nombre" to "v1"))
        val v2 = createRecord(unit, mapOf("nombre" to "v2", "previous_version" to v1))
        val v3 = createRecord(unit, mapOf("nombre" to "v3", "previous_version" to v2))

        assertThat(related(unit, v3, rel).names).containsExactly("v2")
        assertThat(related(unit, v1, rel).total).isEqualTo(0)

        assertThat(related(unit, v1, rel, "?direction=inverse").names).containsExactly("v2")
        assertThat(related(unit, v2, rel, "?direction=inverse").names).containsExactly("v3")
        assertThat(related(unit, v3, rel, "?direction=inverse").total).isEqualTo(0)
    }

    @Test
    fun `many-to-many self - forward reads the targets, inverse the sources, and link stays as it was`() {
        val rel = createRelationship("MANY_TO_MANY", unit, unit, null, inverseLabel = "Duplicated by")
        val a = createRecord(unit, mapOf("nombre" to "A"))
        val b = createRecord(unit, mapOf("nombre" to "B"))
        val c = createRecord(unit, mapOf("nombre" to "C"))
        val d = createRecord(unit, mapOf("nombre" to "D"))
        // the record in the path is the source, the body names the target, as always
        link(a, rel, b).expectStatus().isNoContent
        link(a, rel, c).expectStatus().isNoContent
        link(d, rel, b).expectStatus().isNoContent

        assertThat(related(unit, a, rel, "?sort=nombre").names).containsExactly("B", "C")
        assertThat(related(unit, a, rel, "?direction=inverse").total).isEqualTo(0)
        assertThat(related(unit, b, rel).total).isEqualTo(0)
        assertThat(related(unit, b, rel, "?direction=inverse&sort=nombre").names).containsExactly("A", "D")
        assertThat(related(unit, c, rel, "?direction=inverse").names).containsExactly("A")

        unlink(a, rel, b).expectStatus().isNoContent
        assertThat(related(unit, b, rel, "?direction=inverse").names).containsExactly("D")
        assertThat(related(unit, a, rel).names).containsExactly("C")
    }

    @Test
    fun `the inverse read pages by offset, skips the count and resumes after a cursor`() {
        val rel = createRelationship("MANY_TO_ONE", unit, unit, "parent_unit", inverseLabel = "Child units")
        val root = createRecord(unit, mapOf("nombre" to "Root"))
        val names = (1..5).map { "C$it" }
        names.forEach { createRecord(unit, mapOf("nombre" to it, "parent_unit" to root)) }
        createRecord(unit, mapOf("nombre" to "Elsewhere"))

        val first = keyset("/api/objects/$unit/records/$root/related/$rel?direction=inverse&sort=nombre&size=2")
        assertThat(first.total).isEqualTo(5)
        assertThat(first.totalPages).isEqualTo(3)
        assertThat(first.names).containsExactly("C1", "C2")
        val second = keyset("/api/objects/$unit/records/$root/related/$rel?direction=inverse&sort=nombre&size=2&page=1")
        assertThat(second.names).containsExactly("C3", "C4")

        // count=false: no total, and a cursor to walk every child exactly once
        val read = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page =
                keyset(
                    "/api/objects/$unit/records/$root/related/$rel?direction=inverse&sort=nombre&size=2&count=false" +
                        (cursor?.let { "&after=${UriUtils.encodeQueryParam(it, Charsets.UTF_8)}" } ?: "")
                )
            assertThat(page.total).isNull()
            read += page.names
            cursor = page.nextCursor
        } while (cursor != null)
        assertThat(read).isEqualTo(names)
    }

    @Test
    fun `inverse on a relationship between two objects is a 400 on direction, and so is an unknown direction`() {
        val other = uniqueName("person")
        createObject(other, "Person", "People")
        val rel = createRelationship("MANY_TO_ONE", unit, other, "manager", inverseLabel = "Managed units")
        val person = createRecord(other, mapOf("nombre" to "Ana"))
        val managed = createRecord(unit, mapOf("nombre" to "Lima", "manager" to person))

        // from either end, inverse means nothing here
        relatedCall(other, person, rel, "?direction=inverse")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("direction")
        relatedCall(unit, managed, rel, "?direction=inverse")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("direction")
        // forward, spelled out or not, reads from the object's own end as always
        assertThat(related(unit, managed, rel, "?direction=forward").names).containsExactly("Ana")
        assertThat(related(other, person, rel, "?direction=forward").names).containsExactly("Lima")
        assertThat(related(other, person, rel).names).containsExactly("Lima")

        val self = createRelationship("MANY_TO_ONE", unit, unit, "parent_unit", inverseLabel = null)
        listOf("sideways", "", "inverse,forward").forEach {
            relatedCall(unit, managed, self, "?direction=$it")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("direction")
        }
    }

    @Test
    fun `an object's relationships list a self-relationship once per direction, any other once`() {
        val other = uniqueName("person")
        createObject(other, "Person", "People")
        val parent = createRelationship("MANY_TO_ONE", unit, unit, "parent_unit", inverseLabel = "Child units")
        // no inverse label: the inverse side falls back to the plural
        val duplicate = createRelationship("MANY_TO_MANY", unit, unit, null, inverseLabel = null)
        val manager = createRelationship("MANY_TO_ONE", unit, other, "manager", inverseLabel = "Managed units")

        val sides = relationshipsOf(unit)
        assertThat(sides).hasSize(5)
        val byName = sides.groupBy { it.get("relationship").asString() }
        assertThat(byName.getValue(parent).map { side(it) })
            .containsExactly(
                listOf("Label $parent", "false", unit, "forward"),
                listOf("Child units", "true", unit, "inverse")
            )
        assertThat(byName.getValue(duplicate).map { side(it) })
            .containsExactly(
                listOf("Label $duplicate", "true", unit, "forward"),
                listOf("Units", "true", unit, "inverse")
            )
        // any other relationship: one entry, and no direction key at all
        assertThat(byName.getValue(manager)).hasSize(1)
        assertThat(byName.getValue(manager).single().has("direction")).isFalse()
        assertThat(side(byName.getValue(manager).single())).isEqualTo(listOf("Label $manager", "false", other, null))

        // seen from the other object, still once
        val fromOther = relationshipsOf(other)
        assertThat(fromOther).hasSize(1)
        assertThat(side(fromOther.single())).isEqualTo(listOf("Managed units", "true", unit, null))
    }

    @Test
    fun `own records only holds on the inverse read as on the forward one`() {
        val rel = createRelationship("MANY_TO_ONE", unit, unit, "parent_unit", inverseLabel = "Child units")
        val member = memberToken(ownRecordsOnly = true)
        // the member's own root; the admin hangs a child of its own under it
        val root = createRecord(unit, mapOf("nombre" to "Root"), member)
        val adminChild = createRecord(unit, mapOf("nombre" to "Admin's child", "parent_unit" to root))
        createRecord(unit, mapOf("nombre" to "Member's child", "parent_unit" to root), member)

        assertThat(related(unit, root, rel, "?direction=inverse").total).isEqualTo(2)
        related(unit, root, rel, "?direction=inverse", member).let {
            assertThat(it.total).isEqualTo(1)
            assertThat(it.names).containsExactly("Member's child")
        }
        // forward, the same rule: the admin's child names the member's root, which the admin reads
        assertThat(related(unit, adminChild, rel).names).containsExactly("Root")
    }

    // ---- helpers ----

    private fun side(node: JsonNode): List<String?> =
        listOf(
            node.get("label").asString(),
            node.get("many").asBoolean().toString(),
            node.get("objectName").asString(),
            node.get("direction")?.asString()
        )

    private fun relationshipsOf(objectName: String): List<JsonNode> =
        json
            .readTree(
                client
                    .get()
                    .uri("/api/objects/$objectName/relationships")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            ).toList()

    private fun link(
        id: String,
        relationship: String,
        otherId: String
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/objects/$unit/records/$id/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("otherId" to otherId))
            .exchange()

    private fun unlink(
        id: String,
        relationship: String,
        otherId: String
    ): WebTestClient.ResponseSpec =
        client
            .delete()
            .uri("/api/objects/$unit/records/$id/related/$relationship/$otherId")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()

    private data class Read(
        val total: Long?,
        val totalPages: Int?,
        val names: List<String>,
        val nextCursor: String?
    )

    private fun createObject(
        name: String,
        label: String,
        plural: String
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to label, "pluralLabel" to plural, "fields" to listOf(mapOf("name" to "nombre", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    // the name of the relationship made; its label is "Label <name>"
    private fun createRelationship(
        type: String,
        source: String,
        target: String,
        fieldName: String?,
        inverseLabel: String?
    ): String {
        val name = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                buildMap {
                    put("name", name)
                    put("label", "Label $name")
                    inverseLabel?.let { put("inverseLabel", it) }
                    put("type", type)
                    put("source", source)
                    put("target", target)
                    fieldName?.let { put("fieldName", it) }
                }
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    private fun createRecord(
        objectName: String,
        attributes: Map<String, Any?>,
        caller: String = token
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, caller)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String

    private fun relatedCall(
        objectName: String,
        id: String,
        relationship: String,
        query: String = "",
        caller: String = token
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri("/api/objects/$objectName/records/$id/related/$relationship$query")
            .header(HttpHeaders.AUTHORIZATION, caller)
            .exchange()

    private fun related(
        objectName: String,
        id: String,
        relationship: String,
        query: String = "",
        caller: String = token
    ): Read = read(relatedCall(objectName, id, relationship, query, caller).expectStatus().isOk)

    private fun keyset(uri: String): Read =
        read(
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
        )

    private fun read(spec: WebTestClient.ResponseSpec): Read {
        val tree = json.readTree(spec.expectBody(String::class.java).returnResult().responseBody!!)
        return Read(
            total = tree.get("totalElements").takeUnless { it.isNull }?.asLong(),
            totalPages = tree.get("totalPages").takeUnless { it.isNull }?.asInt(),
            names = tree.get("content").toList().map { it.get("attributes").get("nombre").asString() },
            nextCursor = tree.get("nextCursor")?.asString()
        )
    }

    // a fresh user holding one fresh role with every record action on every object
    private fun memberToken(ownRecordsOnly: Boolean): String {
        val role = "R" + uniqueName("").uppercase()
        client
            .post()
            .uri("/api/roles")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to role, "label" to "Members", "ownRecordsOnly" to ownRecordsOnly))
            .exchange()
            .expectStatus()
            .isCreated
        client
            .put()
            .uri("/api/roles/$role/permissions")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("permissions" to listOf("READ", "CREATE", "UPDATE").map { mapOf("objectName" to null, "action" to it, "allowed" to true) }))
            .exchange()
            .expectStatus()
            .isOk
        val email = "${uniqueName("member")}@wasichai.local"
        client
            .post()
            .uri("/api/users")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("email" to email, "displayName" to "Member", "password" to "supersecret", "roles" to listOf(role)))
            .exchange()
            .expectStatus()
            .isCreated
        return bearer(email, "supersecret")
    }
}
