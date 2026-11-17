package wasichai.core.metadata

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory

// issue 165, ADR-081, ADR-085: what the platform refuses to start with, and that nothing declared runs nothing.
// mocks only, so it runs in the plain test task
class DeclaredModelsStartupTest {
    private val tenants = mock(TenantDirectory::class.java)

    private fun models(
        vararg models: DeclaredModel,
        roles: DeclaredModelRoles? = mock(DeclaredModelRoles::class.java)
    ) = DeclaredModels(
        models.toList(),
        tenants,
        mock(MetadataService::class.java),
        mock(RelationshipService::class.java),
        mock(ObjectActionService::class.java),
        mock(CustomObjectRepository::class.java),
        mock(CustomFieldRepository::class.java),
        mock(RelationshipRepository::class.java),
        mock(ObjectActionRepository::class.java),
        mock(FieldTypeRegistry::class.java),
        mock(ClusterLock::class.java),
        roles
    )

    private fun model(
        name: String,
        vararg objects: String
    ) = DeclaredModel(name, objects.map { CreateObjectRequest(name = it, label = it) })

    @Test
    fun `a model name that is no owner label refuses to start`() {
        assertThatThrownBy { models(model("Curricula", "curso")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Curricula")
    }

    @Test
    fun `two models with one name, or one object in two models, refuse to start`() {
        assertThatThrownBy { models(model("curricula", "curso"), model("curricula", "plan")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("two declared models are named 'curricula'")
        assertThatThrownBy { models(model("curricula", "curso"), model("otra", " Curso ")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("object 'curso' is declared by both 'curricula' and 'otra'")
    }

    @Test
    fun `no model declared ensures nothing and asks for no tenant`() =
        runTest {
            assertThat(models().ensureAll()).isEmpty()
            verifyNoInteractions(tenants)
        }

    @Test
    fun `a declared ADMIN, a declared MANAGE_ORGANIZATION or one role in two models refuses to start`() {
        fun withRoles(
            name: String,
            vararg roles: DeclaredRole
        ) = DeclaredModel(name, objects = emptyList(), roles = roles.toList())

        assertThatThrownBy { models(withRoles("a", DeclaredRole(" admin ", "Admin"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ADMIN")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("x", "Bad"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'X'")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "manage_organization"))))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MANAGE_ORGANIZATION")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "MANAGE_TENANTS"))))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MANAGE_TENANTS")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("CAJA", "Caja")), withRoles("b", DeclaredRole(" caja", "Caja"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("role 'CAJA' is declared by both 'a' and 'b'")
        assertThatThrownBy { models(withRoles("a", DeclaredRole("CAJA", "Caja")), roles = null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("declared model 'a' declares roles")
        // a denied administration grant hands nothing out, and a model without roles needs no port
        models(withRoles("a", DeclaredRole("JEFE", "Jefe", permissions = listOf(PermissionEntry(null, "MANAGE_ORGANIZATION", allowed = false)))))
        models(model("b", "curso"), roles = null)
    }
}
