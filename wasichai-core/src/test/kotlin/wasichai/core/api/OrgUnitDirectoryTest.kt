package wasichai.core.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.OrgUnitRef
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// ADR-045: the ports modules read units and people through. units and memberships are written
// straight to the tables here; the admin routes come with their own tests.
class OrgUnitDirectoryTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var units: OrgUnitDirectory

    @Autowired
    private lateinit var users: UserDirectory

    @Autowired
    private lateinit var roles: RoleDirectory

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Test
    fun `the closure is the user's units and every unit above them, on every branch`(): Unit =
        runBlocking {
            // gerencia > subgerencia > area, a sibling area, and a second tree
            val gerencia = unit(DEMO, code("GER"), "Gerencia")
            val subgerencia = unit(DEMO, code("SUB"), "Subgerencia", gerencia)
            val area = unit(DEMO, code("AREA"), "Area", subgerencia)
            val sibling = unit(DEMO, code("SIB"), "Sibling", subgerencia)
            val other = unit(DEMO, code("OTRA"), "Otra gerencia")
            val otherArea = unit(DEMO, code("OAREA"), "Otra area", other)
            val unrelated = unit(DEMO, code("NADA"), "Nada")
            val user = user(DEMO, "${uniqueName("p")}@x.test")
            member(user, area)
            member(user, otherArea)

            val closure = units.closureOf(DEMO, user)

            assertThat(closure).containsExactlyInAnyOrder(area, subgerencia, gerencia, otherArea, other)
            assertThat(closure).doesNotContain(sibling, unrelated)
            // a member of a parent is not a member of its children
            val boss = user(DEMO, "${uniqueName("p")}@x.test")
            member(boss, gerencia)
            assertThat(units.closureOf(DEMO, boss)).containsExactly(gerencia)
            // no unit, no closure
            assertThat(units.closureOf(DEMO, user(DEMO, "${uniqueName("p")}@x.test"))).isEmpty()
        }

    @Test
    fun `another organization's units never appear`(): Unit =
        runBlocking {
            val org = organization()
            val ownCode = code("OWN")
            val theirCode = code("AJENA")
            val own = unit(DEMO, ownCode, "Own")
            val theirs = unit(org, theirCode, "Ajena")
            val user = user(DEMO, "${uniqueName("p")}@x.test")
            member(user, own)
            // a membership across tenants should never exist; if it does, it still reaches nothing
            member(user, theirs)

            assertThat(units.closureOf(DEMO, user)).containsExactly(own)
            assertThat(units.closureOf(org, user)).isEmpty()
            assertThat(units.unitsOf(DEMO, user).map { it.id }).containsExactly(own)
            assertThat(units.idsByCode(DEMO, listOf(ownCode, theirCode))).isEqualTo(mapOf(ownCode to own))
            assertThat(units.idsByCode(org, listOf(ownCode, theirCode))).isEqualTo(mapOf(theirCode to theirs))
            assertThat(units.codesById(DEMO, listOf(own, theirs))).isEqualTo(mapOf(own to ownCode))
        }

    @Test
    fun `codes are looked up normalised, and ids answer their codes`(): Unit =
        runBlocking {
            val code = code("RENTAS")
            val id = unit(DEMO, code, "Rentas")

            assertThat(units.idsByCode(DEMO, listOf("  ${code.lowercase()} ", code(" NOPE"))))
                .isEqualTo(mapOf(code to id))
            assertThat(units.codesById(DEMO, listOf(id, UUID.randomUUID()))).isEqualTo(mapOf(id to code))
        }

    @Test
    fun `a user's direct units carry their path from the root, sorted by label`(): Unit =
        runBlocking {
            val root = code("ROOT")
            val mid = code("MID")
            val leaf = code("LEAF")
            val alone = code("ALONE")
            val rootId = unit(DEMO, root, "Root")
            val midId = unit(DEMO, mid, "Mid", rootId)
            val leafId = unit(DEMO, leaf, "B leaf", midId)
            val aloneId = unit(DEMO, alone, "A alone")
            val user = user(DEMO, "${uniqueName("p")}@x.test")
            member(user, leafId)
            member(user, aloneId)

            assertThat(units.unitsOf(DEMO, user)).containsExactly(
                OrgUnitRef(aloneId, alone, "A alone", listOf(alone)),
                OrgUnitRef(leafId, leaf, "B leaf", listOf(root, mid, leaf))
            )
        }

    @Test
    fun `people are found by email whatever the case, enabled ones only, never a service account`(): Unit =
        runBlocking {
            val email = "${uniqueName("Ana")}@Example.test"
            val ana = user(DEMO, email)
            val disabledEmail = "${uniqueName("off")}@x.test"
            val disabled = user(DEMO, disabledEmail, enabled = false)
            val elsewhereEmail = "${uniqueName("far")}@x.test"
            val elsewhere = user(organization(), elsewhereEmail)
            val account = serviceAccount()
            val accountEmail = emailOf(account)

            val found = users.idsByEmail(DEMO, listOf(" ${email.uppercase()} ", disabledEmail, elsewhereEmail, accountEmail, "nobody@x.test"))

            assertThat(found).isEqualTo(mapOf(email.lowercase() to ana))
            assertThat(users.existing(DEMO, listOf(ana, disabled, elsewhere, account, UUID.randomUUID()))).containsExactly(ana)
            assertThat(users.emailsById(DEMO, listOf(ana, elsewhere))).isEqualTo(mapOf(ana to email))
        }

    // ADR-060: a delivery names people one by one, so a unit, a role and everyone are fanned out
    @Test
    fun `a unit's members are found down its subtree, enabled people of the tenant only`(): Unit =
        runBlocking {
            val gerencia = unit(DEMO, code("GER"), "Gerencia")
            val area = unit(DEMO, code("AREA"), "Area", gerencia)
            val sibling = unit(DEMO, code("SIB"), "Sibling")
            val boss = user(DEMO, "${uniqueName("p")}@x.test")
            val clerk = user(DEMO, "${uniqueName("p")}@x.test")
            val off = user(DEMO, "${uniqueName("p")}@x.test", enabled = false)
            val elsewhere = user(DEMO, "${uniqueName("p")}@x.test")
            member(boss, gerencia)
            member(clerk, area)
            member(off, area)
            member(elsewhere, sibling)

            assertThat(units.memberIdsWithin(DEMO, listOf(gerencia))).containsExactlyInAnyOrder(boss, clerk)
            assertThat(units.memberIdsWithin(DEMO, listOf(area))).containsExactly(clerk)
            assertThat(units.memberIdsWithin(DEMO, listOf(area, sibling))).containsExactlyInAnyOrder(clerk, elsewhere)
            // another tenant's unit ids reach nobody
            assertThat(units.memberIdsWithin(organization(), listOf(gerencia))).isEmpty()
        }

    @Test
    fun `a role's holders and everyone are enabled people of the tenant, never a service account`(): Unit =
        runBlocking {
            val org = organization()
            val role = "R_" + uniqueName("").uppercase()
            role(org, role)
            val ana = user(org, "${uniqueName("p")}@x.test")
            val luis = user(org, "${uniqueName("p")}@x.test")
            val off = user(org, "${uniqueName("p")}@x.test", enabled = false)
            grant(ana, org, role)
            grant(off, org, role)
            val stranger = user(organization(), "${uniqueName("p")}@x.test")

            assertThat(roles.holderIds(org, listOf(role, "NOPE"))).containsExactly(ana)
            assertThat(roles.holderIds(DEMO, listOf(role))).isEmpty()
            assertThat(users.enabledIds(org)).containsExactlyInAnyOrder(ana, luis)
            assertThat(users.enabledIds(org)).doesNotContain(off, stranger)
            assertThat(users.enabledIds(DEMO)).doesNotContain(serviceAccount())
        }

    @Test
    fun `empty inputs answer empty`(): Unit =
        runBlocking {
            val user = user(DEMO, "${uniqueName("p")}@x.test")

            assertThat(units.idsByCode(DEMO, emptyList())).isEmpty()
            assertThat(units.idsByCode(DEMO, listOf("  "))).isEmpty()
            assertThat(units.codesById(DEMO, emptyList())).isEmpty()
            assertThat(units.unitsOf(DEMO, user)).isEmpty()
            assertThat(users.idsByEmail(DEMO, emptyList())).isEmpty()
            assertThat(users.existing(DEMO, emptyList())).isEmpty()
            assertThat(users.emailsById(DEMO, emptyList())).isEmpty()
            assertThat(units.memberIdsWithin(DEMO, emptyList())).isEmpty()
            assertThat(roles.holderIds(DEMO, emptyList())).isEmpty()
        }

    // the db is shared: every unit code is unique to its test
    private fun code(prefix: String): String = prefix.trim() + "_" + uniqueName("").uppercase()

    private suspend fun unit(
        organizationId: UUID,
        code: String,
        label: String,
        parent: UUID? = null
    ): UUID {
        val spec =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.org_units (organization_id, parent_id, code, label) " +
                        "VALUES (:organizationId, :parentId, :code, :label) RETURNING id"
                ).bind("organizationId", organizationId)
                .bind("code", code)
                .bind("label", label)
        val bound = parent?.let { spec.bind("parentId", it) } ?: spec.bindNull("parentId", UUID::class.java)
        return bound.map { row, _ -> Rows.uuid(row, "id") }.one().awaitSingle()
    }

    private suspend fun user(
        organizationId: UUID,
        email: String,
        enabled: Boolean = true
    ): UUID =
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.users (organization_id, email, password_hash, display_name, enabled) " +
                    "VALUES (:organizationId, :email, 'x', :email, :enabled) RETURNING id"
            ).bind("organizationId", organizationId)
            .bind("email", email)
            .bind("enabled", enabled)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()

    private suspend fun member(
        user: UUID,
        unit: UUID
    ) {
        db
            .sql("INSERT INTO ${schemas.metadata}.user_org_units (user_id, unit_id) VALUES (:userId, :unitId)")
            .bind("userId", user)
            .bind("unitId", unit)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun role(
        organizationId: UUID,
        name: String
    ) {
        db
            .sql("INSERT INTO ${schemas.metadata}.roles (organization_id, name, label) VALUES (:organizationId, :name, :name)")
            .bind("organizationId", organizationId)
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun grant(
        user: UUID,
        organizationId: UUID,
        role: String
    ) {
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id) " +
                    "SELECT :userId, r.id FROM ${schemas.metadata}.roles r WHERE r.organization_id = :organizationId AND r.name = :role"
            ).bind("userId", user)
            .bind("organizationId", organizationId)
            .bind("role", role)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun organization(): UUID {
        val slug = uniqueName("org")
        return db
            .sql("INSERT INTO ${schemas.metadata}.organizations (name, slug) VALUES (:slug, :slug) RETURNING id")
            .bind("slug", slug)
            .map { row, _ -> Rows.uuid(row, "id") }
            .one()
            .awaitSingle()
    }

    // through the api, so its backing users row is exactly what a real account has
    private fun serviceAccount(): UUID {
        val body =
            client
                .post()
                .uri("/api/service-accounts")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .bodyValue(mapOf("name" to uniqueName("sa"), "roles" to emptyList<String>()))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return UUID.fromString(body.substringAfter("\"id\":\"").substringBefore("\""))
    }

    private suspend fun emailOf(user: UUID): String =
        db
            .sql("SELECT email FROM ${schemas.metadata}.users WHERE id = :id")
            .bind("id", user)
            .map { row, _ -> Rows.string(row, "email") }
            .one()
            .awaitSingle()

    companion object {
        private val DEMO = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
