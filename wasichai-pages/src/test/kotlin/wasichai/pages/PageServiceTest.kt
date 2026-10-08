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
import wasichai.core.metadata.Relationship
import wasichai.core.metadata.RelationshipDirection
import wasichai.core.metadata.RelationshipService
import wasichai.core.metadata.RelationshipType
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
        stored: Page? = null,
        sides: List<RelatedSide> = emptyList()
    ): PageService {
        val currentUser = mock(CurrentUser::class.java)
        val metadata = mock(MetadataService::class.java)
        val relationships = mock(RelationshipService::class.java)
        val forms = mock(FormService::class.java)
        doReturn(user).`when`(currentUser).require()
        doReturn(user).`when`(currentUser).requireWithPermission(Actions.MANAGE_METADATA)
        doReturn(definition).`when`(metadata).loadDefinition(obj.organizationId, "predio")
        doReturn(definition).`when`(metadata).loadDefinitionById(obj.organizationId, obj.id)
        doReturn(sides).`when`(relationships).forObject("predio")
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

    // ---- D33: a TAB has a key ----

    private fun inMain(vararg components: PageComponentRequest) =
        CreatePageRequest(
            objectName = "predio",
            name = "predio-detail",
            label = "Predio",
            definition =
                PageDefinitionRequest(
                    PageComponentRequest(
                        type = "PAGE",
                        children = listOf(PageComponentRequest(type = "REGION", region = "MAIN", children = components.toList()))
                    )
                )
        )

    private fun tabs(vararg tabs: PageComponentRequest) = PageComponentRequest(type = "TABS", children = tabs.toList())

    private fun tab(
        key: String?,
        vararg children: PageComponentRequest
    ) = PageComponentRequest(type = "TAB", title = "Ficha", key = key, children = children.toList())

    private fun createRefused(request: CreatePageRequest): ValidationException =
        assertThrows<ValidationException> {
            runTest { service(emptyList()).create(request) }
        }

    private fun tabKeys(component: PageComponent): List<String?> =
        (if (component.type == ComponentType.TAB) listOf(component.key) else emptyList()) + component.children.flatMap { tabKeys(it) }

    @Test
    fun `a tab key is trimmed and upper-cased, and a blank one is none`() {
        lateinit var created: ResolvedPage
        runTest { created = service(emptyList()).create(inMain(tabs(tab("details "), tab("   ")))) }

        assertThat(tabKeys(created.page.definition.page)).containsExactly("DETAILS", null)
    }

    @Test
    fun `a tab key out of format is refused`() {
        listOf("1TAB", "MY-TAB", "TAB KEY", "A".repeat(41)).forEach {
            val error = createRefused(inMain(tabs(tab(it))))
            assertThat(error.message).isEqualTo("Invalid tab key '$it'")
            assertThat(error.violations.single().field).isEqualTo("components")
        }
    }

    @Test
    fun `a key on anything but a TAB is refused`() {
        val error = createRefused(inMain(PageComponentRequest(type = "SECTION", key = "FICHA")))
        assertThat(error.message).isEqualTo("key is only for TAB")
        assertThat(error.violations.single().field).isEqualTo("components")
    }

    // ?tab=KEY must never be ambiguous, so a nested strip may not reuse an outer tab's key
    @Test
    fun `a tab key repeated in a nested strip is refused`() {
        val error = createRefused(inMain(tabs(tab("FICHA", tabs(tab("ficha"))))))
        assertThat(error.message).isEqualTo("repeated tab key 'FICHA'")
        assertThat(error.violations.single().field).isEqualTo("components")
    }

    @Test
    fun `a tab key repeated across strips is refused`() {
        val error = createRefused(inMain(tabs(tab("FICHA")), tabs(tab("OTRA"), tab(" Ficha"))))
        assertThat(error.message).isEqualTo("repeated tab key 'FICHA'")
    }

    // relabel keeps the stored tree as is; a template change with no definition re-validates it through toRequest()
    @Test
    fun `an update without a definition keeps the tab keys`() {
        val keyed =
            PageComponent(
                type = ComponentType.TABS,
                children =
                    listOf(
                        PageComponent(type = ComponentType.TAB, title = "Ficha", key = "FICHA", children = listOf(PageComponent(type = ComponentType.FORM)))
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
                template = PageTemplate.TWO_REGIONS,
                definition =
                    PageDefinition(
                        PageComponent(
                            type = ComponentType.PAGE,
                            children =
                                listOf(
                                    PageComponent(type = ComponentType.REGION, region = PageRegion.MAIN, children = listOf(keyed)),
                                    PageComponent(type = ComponentType.REGION, region = PageRegion.RIGHT)
                                )
                        )
                    )
            )

        lateinit var relabelled: ResolvedPage
        lateinit var retemplated: ResolvedPage
        runTest {
            relabelled = service(emptyList(), stored).update("predio-detail", UpdatePageRequest(label = "Otro"))
            retemplated = service(emptyList(), stored).update("predio-detail", UpdatePageRequest(template = "main-and-right-sidebar"))
        }

        assertThat(tabKeys(relabelled.page.definition.page)).containsExactly("FICHA")
        assertThat(retemplated.page.template).isEqualTo(PageTemplate.MAIN_AND_RIGHT_SIDEBAR)
        assertThat(tabKeys(retemplated.page.definition.page)).containsExactly("FICHA")
    }

    // #61: a self-relationship is listed once per direction, and a RELATED_LIST names no direction:
    // the generated page keeps its one forward tab, as before
    @Test
    fun `a generated page keeps one related tab for a self-relationship`() {
        val parent =
            Relationship(UUID.randomUUID(), obj.organizationId, "parent", "Parent", "Children", RelationshipType.MANY_TO_ONE, obj.id, obj.id, null, null)
        val sides =
            listOf(
                RelatedSide(parent, obj, "Parent", false, RelationshipDirection.FORWARD),
                RelatedSide(parent, obj, "Children", true, RelationshipDirection.INVERSE)
            )

        lateinit var resolved: ResolvedPage
        runTest { resolved = service(emptyList(), sides = sides).resolve("predio", PageKind.RECORD_DETAIL) }

        fun all(component: PageComponent): List<PageComponent> = listOf(component) + component.children.flatMap { all(it) }
        val lists = all(resolved.page.definition.page).filter { it.type == ComponentType.RELATED_LIST }
        assertThat(lists.map { it.title to it.relationship }).containsExactly("Parent" to "parent")
    }
}
