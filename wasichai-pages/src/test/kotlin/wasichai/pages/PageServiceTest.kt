package wasichai.pages

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.ObjectMapper
import wasichai.core.common.Actions
import wasichai.core.common.ValidationException
import wasichai.core.data.NoWorkflowStates
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.RelatedSide
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.WasichaiSchemas
import wasichai.forms.FormService
import java.util.UUID

// review T9c: a module's provider is consulted while validating a definition, and a component
// with no installed provider is refused by name -- both proven directly through the service, not
// just through generatedTabs (that only covers what a GENERATED page gets).
class PageServiceTest {
    private val obj = CustomObject(UUID.randomUUID(), UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "predio__1234abcd", null, null)
    private val definition = ObjectDefinition(obj, emptyList())
    private val user = AuthenticatedUser(UUID.randomUUID(), obj.organizationId, "user@example.com", listOf("ADMIN"))

    // a repository subclass, not a mock: insert/update hand back exactly what they were given, so
    // the test asserts on what PageService built rather than on a canned response.
    private class FakePageRepository(
        private val stored: Page? = null
    ) : PageRepository(mock(DatabaseClient::class.java), ObjectMapper(), WasichaiSchemas("metadata", "data")) {
        override suspend fun findByName(
            organizationId: UUID,
            name: String
        ): Page? = stored

        override suspend fun findByObjectAndKind(
            objectId: UUID,
            kind: PageKind
        ): Page? = null

        override suspend fun insert(page: Page): Page = page

        override suspend fun update(page: Page): Page = page
    }

    private suspend fun service(
        providers: List<PageComponentProvider>,
        stored: Page? = null
    ): PageService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val relationships = mock(RelationshipService::class.java)
        val forms = mock(FormService::class.java)
        doReturn(user).`when`(currentUser).requireWithPermission(Actions.MANAGE_METADATA)
        doReturn(definition).`when`(metadata).loadDefinition(obj.organizationId, "predio")
        doReturn(definition).`when`(metadata).loadDefinitionById(obj.organizationId, obj.id)
        doReturn(emptyList<RelatedSide>()).`when`(relationships).forObject("predio")
        return PageService(NoWorkflowStates(), FakePageRepository(stored), metadata, relationships, forms, currentUser, PageComponentTypes(providers))
    }

    // one region (the default template), holding one placed component -- just enough tree to
    // exercise the type in question without pulling in fields, forms or actions.
    private fun request(componentType: String) =
        CreatePageRequest(
            objectName = "predio",
            name = "predio-detail",
            label = "Predio",
            definition =
                PageDefinitionRequest(
                    PageComponentRequest(
                        type = "PAGE",
                        children =
                            listOf(
                                PageComponentRequest(
                                    type = "REGION",
                                    region = "MAIN",
                                    children = listOf(PageComponentRequest(type = componentType))
                                )
                            )
                    )
                )
        )

    @Test
    fun `a MAP without any provider is refused by name`() {
        val error =
            assertThrows<ValidationException> {
                runTest { service(emptyList()).create(request("MAP")) }
            }
        assertThat(error.message).isEqualTo("Unknown component 'MAP'")
    }

    @Test
    fun `an installed provider's check runs during validation`() {
        var checked = false
        val map =
            object : PageComponentProvider {
                override val type = ComponentType("MAP")

                override fun check(
                    component: PageComponentRequest,
                    definition: ObjectDefinition
                ) {
                    checked = true
                }
            }

        runTest { service(listOf(map)).create(request("MAP")) }

        assertThat(checked).isTrue()
    }

    // review T9a: a stored definition naming a type whose module is gone must survive a plain
    // label change -- MAP was authored while gis was installed, no provider is registered here.
    @Test
    fun `a label-only update keeps a stored MAP untouched, even with its module uninstalled`() {
        val storedDefinition =
            PageDefinition(
                PageComponent(
                    type = ComponentType.PAGE,
                    children =
                        listOf(
                            PageComponent(
                                type = ComponentType.REGION,
                                region = PageRegion.MAIN,
                                children = listOf(PageComponent(type = ComponentType("MAP")))
                            )
                        )
                )
            )
        val stored =
            Page(
                id = UUID.randomUUID(),
                organizationId = obj.organizationId,
                objectId = obj.id,
                name = "predio-detail",
                label = "Predio",
                kind = PageKind.RECORD_DETAIL,
                template = PageTemplate.ONE_REGION,
                definition = storedDefinition
            )

        lateinit var updated: ResolvedPage
        runTest {
            // no MAP provider installed: an update that re-validated the stored tree would fail here
            updated = service(emptyList(), stored).update("predio-detail", UpdatePageRequest(label = "New Predio"))
        }

        assertThat(updated.page.label).isEqualTo("New Predio")
        assertThat(updated.page.definition).isEqualTo(storedDefinition)
    }
}
