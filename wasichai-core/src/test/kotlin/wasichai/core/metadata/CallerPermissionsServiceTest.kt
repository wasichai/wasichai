package wasichai.core.metadata

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import wasichai.core.common.Actions
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.RoleQueries
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// issue 53 (ADR-053): tenant-wide capabilities come from the services' own check, objects and admin stay as they were
class CallerPermissionsServiceTest {
    private val organizationId = UUID.randomUUID()
    private val predio = CustomObject(UUID.randomUUID(), organizationId, "predio", "Predio", "Predios", null, true, "predio__1", null, null)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    // (role, action, object) rows. a null object is an org-wide grant, as in the permissions table.
    private class Grant(
        val role: String,
        val action: String,
        val objectId: UUID?
    )

    // the real CurrentUser rule over a fake permissions table: what is tested is what the services enforce
    private class FakeRoles(
        private val grants: List<Grant>
    ) : RoleQueries(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data")) {
        override suspend fun hasPermission(
            roleNames: List<String>,
            organizationId: UUID,
            action: String,
            objectId: UUID?
        ): Boolean = grants.any { it.role in roleNames && it.action == action && (it.objectId == null || it.objectId == objectId) }

        override suspend fun permittedObjects(
            roleNames: List<String>,
            organizationId: UUID,
            action: String
        ): PermittedObjects {
            val mine = grants.filter { it.role in roleNames && it.action == action }
            return PermittedObjects(mine.any { it.objectId == null }, mine.mapNotNull { it.objectId }.toSet())
        }
    }

    private fun service(
        caller: AuthenticatedUser,
        vararg grants: Grant
    ): CallerPermissionsService {
        val currentUser =
            object : CurrentUser(FakeRoles(grants.toList())) {
                override suspend fun require() = caller
            }
        val objects = mock(CustomObjectRepository::class.java)
        val actions = mock(ObjectActionRepository::class.java)
        runTest {
            doReturn(listOf(predio)).`when`(objects).findAll(organizationId)
            doReturn(emptyMap<UUID, List<String>>()).`when`(actions).namesByObject(organizationId)
            doReturn(emptyMap<UUID, List<String>>()).`when`(actions).heldBy(caller.roles, organizationId)
        }
        return CallerPermissionsService(objects, actions, currentUser)
    }

    private fun person(vararg roles: String) = AuthenticatedUser(UUID.randomUUID(), organizationId, "ana@example.com", roles.toList())

    private fun account(vararg roles: String) = AuthenticatedUser(UUID.randomUUID(), organizationId, "", roles.toList(), serviceAccount = "rentas")

    private fun json(response: CallerPermissionsResponse) = mapper.writeValueAsString(response)

    @Test
    fun `an org-wide MANAGE_METADATA grant is a capability`() =
        runTest {
            val answer = service(person("MODELER"), Grant("MODELER", Actions.MANAGE_METADATA, null)).ofCaller()

            assertThat(answer.capabilities).containsExactly(Actions.MANAGE_METADATA)
        }

    @Test
    fun `object-level grants give no capability, MANAGE_METADATA on one object included`() =
        runTest {
            val answer =
                service(
                    person("CLERK"),
                    Grant("CLERK", Actions.READ, predio.id),
                    Grant("CLERK", Actions.CREATE, predio.id),
                    Grant("CLERK", Actions.MANAGE_METADATA, predio.id)
                ).ofCaller()

            assertThat(answer.capabilities).isEmpty()
            // and the open question stays closed: the object's array is the record actions it always was
            assertThat(answer.objects).isEqualTo(mapOf("predio" to listOf(Actions.READ, Actions.CREATE)))
        }

    @Test
    fun `both capabilities come in the fixed order, whatever the grant order`() =
        runTest {
            val answer =
                service(
                    person("GESTOR"),
                    Grant("GESTOR", Actions.MANAGE_ORGANIZATION, null),
                    Grant("GESTOR", Actions.MANAGE_METADATA, null)
                ).ofCaller()

            assertThat(answer.capabilities).containsExactly(Actions.MANAGE_METADATA, Actions.MANAGE_ORGANIZATION)
        }

    @Test
    fun `the administrator holds both capabilities with no grant row`() =
        runTest {
            val answer = service(person(AuthenticatedUser.ADMIN_ROLE)).ofCaller()

            assertThat(answer.capabilities).isEqualTo(CallerPermissionsService.CAPABILITIES)
            assertThat(answer.capabilities).containsExactly(Actions.MANAGE_METADATA, Actions.MANAGE_ORGANIZATION)
        }

    @Test
    fun `a service account never holds MANAGE_ORGANIZATION, even granted it`() =
        runTest {
            val granted =
                service(
                    account("INTEGRADOR"),
                    Grant("INTEGRADOR", Actions.MANAGE_METADATA, null),
                    Grant("INTEGRADOR", Actions.MANAGE_ORGANIZATION, null)
                ).ofCaller()
            // an ADMIN claim on an account's token makes it no administrator either (ADR-043)
            val claimed = service(account(AuthenticatedUser.ADMIN_ROLE)).ofCaller()

            assertThat(granted.capabilities).containsExactly(Actions.MANAGE_METADATA)
            assertThat(claimed.admin).isFalse()
            assertThat(claimed.capabilities).isEmpty()
        }

    @Test
    fun `the key is always there, an empty list for a caller with no capability`() =
        runTest {
            val answer = service(person("NOBODY")).ofCaller()

            assertThat(json(answer)).isEqualTo("""{"admin":false,"capabilities":[],"objects":{}}""")
        }

    @Test
    fun `admin and objects serialise exactly as before, the new key aside`() =
        runTest {
            val member =
                service(
                    person("CLERK"),
                    Grant("CLERK", Actions.READ, null),
                    Grant("CLERK", Actions.UPDATE, predio.id),
                    Grant("CLERK", Actions.MANAGE_METADATA, null)
                ).ofCaller()
            val admin = service(person(AuthenticatedUser.ADMIN_ROLE)).ofCaller()

            assertThat(json(member)).isEqualTo(
                """{"admin":false,"capabilities":["MANAGE_METADATA"],"objects":{"predio":["READ","UPDATE"]}}"""
            )
            assertThat(json(admin)).isEqualTo(
                """{"admin":true,"capabilities":["MANAGE_METADATA","MANAGE_ORGANIZATION"],""" +
                    """"objects":{"predio":["READ","CREATE","UPDATE","DELETE"]}}"""
            )
        }
}
