package wasichai.core.data

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 33 (D29): which relation values are looked up, how many reads, and what the refusal says.
// issue 39 (D30): whose read scope each read is filtered by
class RelationTargetsTest {
    private val organizationId = ObjectDefinitionFixtures.obj.organizationId
    private val customers = UUID.randomUUID()
    private val plots = UUID.randomUUID()
    private val buyer = relation("buyer", customers)
    private val seller = relation("seller", customers)
    private val plot = relation("plot", plots)
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val definition = ObjectDefinition(ObjectDefinitionFixtures.obj, listOf(codigo, buyer, seller, plot))

    private val known = UUID.randomUUID()
    private val lookups = mutableListOf<Pair<UUID, List<UUID>>>()
    private val scopes = mutableListOf<AuthenticatedUser?>()
    private val member = AuthenticatedUser(UUID.randomUUID(), organizationId, "member@example.com", listOf("SALES"))

    // the database knows one record, [known], of every target object
    private val targets =
        object : RelationTargets(mock(DatabaseClient::class.java), mock(WasichaiSchemas::class.java), mock(CustomObjectRepository::class.java)) {
            override suspend fun existing(
                organizationId: UUID,
                targetObjectId: UUID,
                ids: List<UUID>,
                scope: AuthenticatedUser?
            ): Set<UUID> {
                lookups += targetObjectId to ids
                scopes += scope
                return ids.filter { it == known }.toSet()
            }
        }

    @Test
    fun `two fields on the same target are one read, and the refusal names them in field order`() =
        runTest {
            val one = UUID.randomUUID()
            val two = UUID.randomUUID()
            // the map's order is not the field order
            val attributes = linkedMapOf<String, Any?>("seller" to two.toString(), "plot" to known.toString(), "buyer" to one.toString())

            val ex = runCatching { targets.rejectMissing(organizationId, definition, attributes) }.exceptionOrNull()

            assertThat(ex).isInstanceOf(ValidationException::class.java)
            ex as ValidationException
            assertThat(ex.message).isEqualTo("Invalid value for 'buyer', 'seller'")
            assertThat(ex.violations.map { it.field }).containsExactly("buyer", "seller")
            assertThat(ex.violations.map { it.message }).containsOnly("no record with this id")
            assertThat(lookups.map { it.first }).containsExactlyInAnyOrder(customers, plots)
            assertThat(lookups.first { it.first == customers }.second).containsExactlyInAnyOrder(one, two)
        }

    @Test
    fun `null, left out, not a uuid and other types are not looked up`() =
        runTest {
            targets.rejectMissing(organizationId, definition, mapOf("codigo" to "X", "buyer" to null, "seller" to "not-a-uuid"))
            assertThat(lookups).isEmpty()
        }

    @Test
    fun `a value the stored row already holds is not looked up again`() =
        runTest {
            val stored = UUID.randomUUID()
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to stored.toString()), before = mapOf("buyer" to stored.toString()))
            assertThat(lookups).isEmpty()

            // a changed one is
            runCatching { targets.rejectMissing(organizationId, definition, mapOf("buyer" to UUID.randomUUID().toString()), before = mapOf("buyer" to stored)) }
            assertThat(lookups).hasSize(1)
        }

    @Test
    fun `a known record passes`() =
        runTest {
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to known.toString(), "plot" to known))
            assertThat(lookups).hasSize(2)
        }

    @Test
    fun `a caller's reads are scoped to them, still one per target object`() =
        runTest {
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to known, "seller" to known, "plot" to known), reader = member)
            assertThat(lookups.map { it.first }).containsExactlyInAnyOrder(customers, plots)
            assertThat(scopes).containsOnly(member)
        }

    @Test
    fun `ADMIN, the platform and automations read tenant-wide`() =
        runTest {
            val admin = member.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE))
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to known), reader = admin)
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to known), reader = null)
            assertThat(scopes).containsExactly(null, null)
        }

    @Test
    fun `a service account holding an ADMIN role is still scoped`() =
        runTest {
            val account = member.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE), serviceAccount = "erp")
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to known), reader = account)
            assertThat(scopes).containsExactly(account)
        }

    @Test
    fun `out of scope reads as missing, the same refusal`() =
        runTest {
            val unseen = UUID.randomUUID()
            val ex = runCatching { targets.rejectMissing(organizationId, definition, mapOf("buyer" to unseen), reader = member) }.exceptionOrNull()
            assertThat(ex).isInstanceOf(ValidationException::class.java)
            ex as ValidationException
            assertThat(ex.message).isEqualTo("Invalid value for 'buyer'")
            assertThat(ex.violations.map { it.message }).containsExactly("no record with this id")
        }

    // keeping a value is no new claim on the target: an update must not fail on a record the caller
    // lost sight of after it was linked
    @Test
    fun `a value the stored row already holds is not looked up, even for a scoped caller`() =
        runTest {
            val stored = UUID.randomUUID()
            targets.rejectMissing(organizationId, definition, mapOf("buyer" to stored), before = mapOf("buyer" to stored), reader = member)
            assertThat(lookups).isEmpty()
        }

    private fun relation(
        name: String,
        target: UUID
    ) = ObjectDefinitionFixtures.field(name, FieldType.RELATION).copy(relationTargetObjectId = target)
}
