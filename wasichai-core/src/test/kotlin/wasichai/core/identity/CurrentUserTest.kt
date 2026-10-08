package wasichai.core.identity

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 56 (ADR-056): who may create and delete tenants, with wasichai.organizations.separate-provisioning off and on
class CurrentUserTest {
    private val organizationId = UUID.randomUUID()

    // org-wide (role, action) rows: all a tenant-wide check reads
    private class FakeRoles(
        private val grants: Set<Pair<String, String>>
    ) : RoleQueries(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data")) {
        override suspend fun hasPermission(
            roleNames: List<String>,
            organizationId: UUID,
            action: String,
            objectId: UUID?
        ): Boolean = roleNames.any { (it to action) in grants }
    }

    private fun currentUser(
        separateProvisioning: Boolean,
        vararg grants: Pair<String, String>
    ) = CurrentUser(FakeRoles(grants.toSet()), separateProvisioning)

    private fun person(vararg roles: String) = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", roles.toList())

    private fun account(vararg roles: String) = AuthenticatedUser(UUID.randomUUID(), organizationId, "", roles.toList(), serviceAccount = "rentas")

    @Test
    fun `switch off, MANAGE_TENANTS is MANAGE_ORGANIZATION's, ADMIN included, as it always was`() =
        runTest {
            val users = currentUser(false, "GESTOR" to Actions.MANAGE_ORGANIZATION, "OPERADOR" to Actions.MANAGE_TENANTS)

            assertThat(users.hasPermission(person(AuthenticatedUser.ADMIN_ROLE), Actions.MANAGE_TENANTS)).isTrue()
            assertThat(users.hasPermission(person("GESTOR"), Actions.MANAGE_TENANTS)).isTrue()
            // the grant alone waits for the switch: off, the rule is the one it always was
            assertThat(users.hasPermission(person("OPERADOR"), Actions.MANAGE_TENANTS)).isFalse()
            assertThat(users.hasPermission(person("NOBODY"), Actions.MANAGE_TENANTS)).isFalse()
        }

    @Test
    fun `switch on, only a MANAGE_TENANTS grant counts, never ADMIN or MANAGE_ORGANIZATION alone`() =
        runTest {
            val users = currentUser(true, "GESTOR" to Actions.MANAGE_ORGANIZATION, "OPERADOR" to Actions.MANAGE_TENANTS)

            assertThat(users.hasPermission(person(AuthenticatedUser.ADMIN_ROLE), Actions.MANAGE_TENANTS)).isFalse()
            assertThat(users.hasPermission(person(AuthenticatedUser.ADMIN_ROLE, "GESTOR"), Actions.MANAGE_TENANTS)).isFalse()
            assertThat(users.hasPermission(person("OPERADOR"), Actions.MANAGE_TENANTS)).isTrue()
            assertThat(users.hasPermission(person(AuthenticatedUser.ADMIN_ROLE, "OPERADOR"), Actions.MANAGE_TENANTS)).isTrue()
            assertThatThrownBy { runBlocking { users.requirePermission(person(AuthenticatedUser.ADMIN_ROLE), Actions.MANAGE_TENANTS) } }
                .isInstanceOf(ForbiddenException::class.java)
                .hasMessage("Missing permission MANAGE_TENANTS")
        }

    @Test
    fun `switch on, an ADMIN role granted MANAGE_TENANTS holds it like any other role`() =
        runTest {
            val users = currentUser(true, AuthenticatedUser.ADMIN_ROLE to Actions.MANAGE_TENANTS)

            assertThat(users.hasPermission(person(AuthenticatedUser.ADMIN_ROLE), Actions.MANAGE_TENANTS)).isTrue()
        }

    @Test
    fun `switch on, ADMIN still short-circuits every other action`() =
        runTest {
            val users = currentUser(true)
            val admin = person(AuthenticatedUser.ADMIN_ROLE)

            listOf(Actions.READ, Actions.DELETE, Actions.MANAGE_METADATA, Actions.MANAGE_ORGANIZATION).forEach {
                assertThat(users.hasPermission(admin, it)).describedAs(it).isTrue()
            }
        }

    @Test
    fun `a service account never holds MANAGE_TENANTS, whatever the switch and its grants`() =
        runTest {
            val grants = arrayOf("INTEGRADOR" to Actions.MANAGE_TENANTS, "INTEGRADOR" to Actions.MANAGE_ORGANIZATION)

            listOf(false, true).forEach { separate ->
                val users = currentUser(separate, *grants)
                assertThat(users.hasPermission(account("INTEGRADOR"), Actions.MANAGE_TENANTS)).describedAs("switch $separate").isFalse()
                assertThat(users.hasPermission(account(AuthenticatedUser.ADMIN_ROLE), Actions.MANAGE_TENANTS)).describedAs("switch $separate").isFalse()
                assertThat(users.holdsTenantsGrant(account("INTEGRADOR"))).isFalse()
            }
        }

    // the guard on handing it on: a grant row, never ADMIN alone, even with the switch off
    @Test
    fun `holding the grant is a row on one of the caller's roles, whatever the switch`() =
        runTest {
            listOf(false, true).forEach { separate ->
                val users = currentUser(separate, "OPERADOR" to Actions.MANAGE_TENANTS, "GESTOR" to Actions.MANAGE_ORGANIZATION)

                assertThat(users.holdsTenantsGrant(person("OPERADOR"))).describedAs("switch $separate").isTrue()
                assertThat(users.holdsTenantsGrant(person(AuthenticatedUser.ADMIN_ROLE))).describedAs("switch $separate").isFalse()
                assertThat(users.holdsTenantsGrant(person("GESTOR"))).describedAs("switch $separate").isFalse()
            }
        }
}
