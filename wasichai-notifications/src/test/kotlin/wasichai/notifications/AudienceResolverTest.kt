package wasichai.notifications

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import wasichai.core.common.FieldViolation
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.UserDirectory
import java.util.UUID

// directories faked by a default answer: a suspend fun that returns at once needs no stubbing dsl
class AudienceResolverTest {
    private val org = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val ana = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val gone = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val sgft = UUID.fromString("00000000-0000-0000-0000-0000000000f1")

    private val calls = mutableListOf<String>()

    private val users: UserDirectory =
        mock(UserDirectory::class.java) { inv ->
            calls += inv.method.name
            assertThat(inv.arguments[0]).isEqualTo(org)
            when (inv.method.name) {
                "existing" -> (inv.arguments[1] as Collection<*>).filter { it == ana || it == bob }.toSet()
                "idsByEmail" -> (inv.arguments[1] as Collection<*>).map { it as String }.filter { it == "bob@x.pe" }.associateWith { bob }
                "emailsById" -> (inv.arguments[1] as Collection<*>).filter { it == ana }.associate { ana to "Ana@X.pe" }
                else -> error("unexpected ${inv.method.name}")
            }
        }

    private val roles: RoleDirectory =
        mock(RoleDirectory::class.java) { inv ->
            calls += inv.method.name
            setOf("CAJERO", "ADMIN")
        }

    private val units: OrgUnitDirectory =
        mock(OrgUnitDirectory::class.java) { inv ->
            calls += inv.method.name
            when (inv.method.name) {
                "idsByCode" -> (inv.arguments[1] as Collection<*>).filter { it == "SGFT" }.associate { "SGFT" to sgft }
                "codesById" -> (inv.arguments[1] as Collection<*>).filter { it == sgft }.associate { sgft to "SGFT" }
                else -> error("unexpected ${inv.method.name}")
            }
        }

    private val resolver = AudienceResolver(users, roles, units)

    private fun resolve(
        audience: List<Audience>,
        strict: Boolean
    ) = runBlocking { resolver.resolve(org, audience, strict) }

    @Test
    fun `every kind of recipient becomes its stored target`() {
        val result =
            resolve(
                listOf(Audience.All, Audience.User(ana), Audience.Email("bob@x.pe"), Audience.Role("CAJERO"), Audience.Unit("SGFT")),
                strict = true
            )

        assertThat(result.violations).isEmpty()
        assertThat(result.dropped).isEmpty()
        assertThat(result.targets).containsExactly(
            StoredTarget.all(),
            StoredTarget.user(ana),
            StoredTarget.user(bob),
            StoredTarget.role("CAJERO"),
            StoredTarget.unit(sgft)
        )
    }

    @Test
    fun `strict names every unknown recipient by its index`() {
        val result =
            resolve(
                listOf(Audience.User(gone), Audience.Email("nadie@x.pe"), Audience.Role("JEFE"), Audience.Unit("NOPE"), Audience.Role("CAJERO")),
                strict = true
            )

        assertThat(result.violations).containsExactly(
            FieldViolation("audience[0]", "unknown user '$gone'"),
            FieldViolation("audience[1]", "unknown email 'nadie@x.pe'"),
            FieldViolation("audience[2]", "unknown role 'JEFE'"),
            FieldViolation("audience[3]", "unknown unit 'NOPE'")
        )
        assertThat(result.targets).containsExactly(StoredTarget.role("CAJERO"))
        assertThat(result.dropped).isEmpty()
    }

    @Test
    fun `lenient drops unknown recipients and keeps the rest`() {
        val result = resolve(listOf(Audience.User(gone), Audience.Email("nadie@x.pe"), Audience.User(ana)), strict = false)

        assertThat(result.violations).isEmpty()
        assertThat(result.dropped).containsExactly("unknown user '$gone'", "unknown email 'nadie@x.pe'")
        assertThat(result.targets).containsExactly(StoredTarget.user(ana))
    }

    @Test
    fun `lenient with nobody left answers no targets`() {
        val result = resolve(listOf(Audience.Role("JEFE")), strict = false)

        assertThat(result.targets).isEmpty()
        assertThat(result.dropped).containsExactly("unknown role 'JEFE'")
    }

    @Test
    fun `lookups are batched, one per kind, and skipped when a kind is absent`() {
        resolve(listOf(Audience.User(ana), Audience.User(bob), Audience.Email("bob@x.pe"), Audience.Email("a@x.pe")), strict = false)
        assertThat(calls).containsExactlyInAnyOrder("existing", "idsByEmail")

        calls.clear()
        resolve(listOf(Audience.All), strict = true)
        assertThat(calls).isEmpty()

        calls.clear()
        resolve(listOf(Audience.Role("CAJERO"), Audience.Role("ADMIN"), Audience.Unit("SGFT"), Audience.Unit("X1")), strict = false)
        assertThat(calls).containsExactlyInAnyOrder("namesOf", "idsByCode")
    }

    @Test
    fun `an email naming someone also named by id is one target`() {
        val result = resolve(listOf(Audience.User(bob), Audience.Email("bob@x.pe")), strict = true)
        assertThat(result.targets).containsExactly(StoredTarget.user(bob))
    }

    @Test
    fun `describe shows users with their email and units by code`() {
        val views =
            runBlocking {
                resolver.describe(org, listOf(StoredTarget.all(), StoredTarget.user(ana), StoredTarget.role("CAJERO"), StoredTarget.unit(sgft)))
            }

        assertThat(views).containsExactly(
            AudienceView("ALL", null),
            AudienceView("USER", ana.toString(), "Ana@X.pe"),
            AudienceView("ROLE", "CAJERO"),
            AudienceView("UNIT", "SGFT")
        )
    }

    @Test
    fun `describe batches across notifications`() {
        val views =
            runBlocking {
                resolver.describeAll(org, mapOf(ana to listOf(StoredTarget.user(ana)), bob to listOf(StoredTarget.unit(sgft), StoredTarget.user(ana))))
            }

        assertThat(views.getValue(ana)).containsExactly(AudienceView("USER", ana.toString(), "Ana@X.pe"))
        assertThat(views.getValue(bob)).containsExactly(AudienceView("UNIT", "SGFT"), AudienceView("USER", ana.toString(), "Ana@X.pe"))
        assertThat(calls).containsExactlyInAnyOrder("emailsById", "codesById")
    }
}
