package wasichai.core.identity

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import reactor.util.context.Context
import wasichai.core.common.Actions
import wasichai.core.platform.RequestMemo
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 105 (ADR-066): what the caller's roles say about an object is read once per request, whatever is asked of it
class RoleQueriesMemoTest {
    private val organizationId = UUID.randomUUID()
    private val predio = UUID.randomUUID()
    private val area = UUID.randomUUID()

    // the two reads behind the memo, counted. GESTOR reads and creates predio, own records only, area read-only
    private class CountingRoles : RoleQueries(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data")) {
        var objectReads = 0
        var ownerReads = 0
        lateinit var area: UUID

        override suspend fun loadObjectAccess(
            roleNames: List<String>,
            organizationId: UUID,
            objectId: UUID
        ): ObjectAccess {
            objectReads++
            return ObjectAccess(setOf(Actions.READ, Actions.CREATE), ownRecordsOnly = true, FieldAccess(mapOf(area to true), mapOf(area to false)))
        }

        override suspend fun loadOwnRecordsOnly(
            roleNames: List<String>,
            organizationId: UUID
        ): Boolean {
            ownerReads++
            return true
        }
    }

    private val roles = CountingRoles().also { it.area = area }
    private val access = AccessPolicy(roles)
    private val currentUser = CurrentUser(roles)
    private val gestor = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", listOf("GESTOR"))

    private suspend fun <T> inRequest(block: suspend () -> T): T = withContext(RequestMemo.open(Context.empty()).asCoroutineContext()) { block() }

    @Test
    fun `a request asks once per object, for every action, the field rules and the owner rule`() =
        runTest {
            inRequest {
                assertThat(currentUser.hasPermission(gestor, Actions.READ, predio)).isTrue()
                assertThat(currentUser.hasPermission(gestor, Actions.CREATE, predio)).isTrue()
                assertThat(currentUser.hasPermission(gestor, Actions.UPDATE, predio)).isFalse()
                assertThat(access.fieldAccess(gestor, predio).canWrite(area)).isFalse()
                assertThat(access.ownerFilter(gestor)).isEqualTo(gestor.userId)
                // asked directly, the same answer
                assertThat(roles.objectAccess(listOf("GESTOR"), organizationId, predio).actions).contains(Actions.READ)
            }

            assertThat(roles.objectReads).isEqualTo(1)
            assertThat(roles.ownerReads).isEqualTo(0)
        }

    @Test
    fun `another object is another read, the owner rule is not`() =
        runTest {
            inRequest {
                currentUser.hasPermission(gestor, Actions.READ, predio)
                currentUser.hasPermission(gestor, Actions.READ, UUID.randomUUID())
                access.ownRecordsOnly(gestor)
            }

            assertThat(roles.objectReads).isEqualTo(2)
            assertThat(roles.ownerReads).isEqualTo(0)
        }

    @Test
    fun `the owner rule before any object is one read`() =
        runTest {
            inRequest {
                access.ownerFilter(gestor)
                access.ownerFilter(gestor)
            }

            assertThat(roles.ownerReads).isEqualTo(1)
        }

    @Test
    fun `each request asks again, and so does every call outside one`() =
        runTest {
            inRequest { currentUser.hasPermission(gestor, Actions.READ, predio) }
            inRequest { currentUser.hasPermission(gestor, Actions.READ, predio) }
            access.fieldAccess(gestor, predio)
            access.fieldAccess(gestor, predio)

            assertThat(roles.objectReads).isEqualTo(4)
        }

    @Test
    fun `ADMIN and a caller without roles read nothing`() =
        runTest {
            val admin = gestor.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE))
            val nobody = gestor.copy(roles = emptyList())
            inRequest {
                assertThat(access.fieldAccess(admin, predio).unrestricted).isTrue()
                assertThat(access.ownerFilter(admin)).isNull()
                assertThat(currentUser.hasPermission(admin, Actions.DELETE, predio)).isTrue()
                assertThat(access.fieldAccess(nobody, predio).unrestricted).isTrue()
                assertThat(access.ownerFilter(nobody)).isNull()
                assertThat(currentUser.hasPermission(nobody, Actions.READ, predio)).isFalse()
            }

            assertThat(roles.objectReads).isEqualTo(0)
            assertThat(roles.ownerReads).isEqualTo(0)
        }
}
