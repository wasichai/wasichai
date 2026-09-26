package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import wasichai.pages.PageTemplate

class PageApiTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var token: String
    private lateinit var predio: String
    private lateinit var nota: String

    @BeforeEach
    fun createObjects() {
        token = bearer()
        predio = uniqueName("predio")
        nota = uniqueName("nota")
        createObject(predio, "Predio", "POLYGON")
        createObject(nota, "Nota", null)
    }

    @Test
    fun `a spatial object with no stored page gets a generated form and map, each in its own tab`() {
        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.objectName")
            .isEqualTo(predio)
            .jsonPath("$.kind")
            .isEqualTo("RECORD_DETAIL")
            // a generated page has no admin intent to read, so it always gets the plainest template
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
            .jsonPath("$.definition.page.children[0].children[0].type")
            .isEqualTo("TABS")
            .jsonPath("$.definition.page.children[0].children[0].children[0].title")
            .isEqualTo("DETAILS")
            .jsonPath("$.definition.page.children[0].children[0].children[0].children[0].type")
            .isEqualTo("FORM")
            .jsonPath("$.definition.page.children[0].children[0].children[1].title")
            .isEqualTo("MAP")
            .jsonPath("$.definition.page.children[0].children[0].children[1].children[0].type")
            .isEqualTo("MAP")
    }

    @Test
    fun `the generated page is a one-region page`() {
        resolve(predio)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
            .jsonPath("$.definition.page.type")
            .isEqualTo("PAGE")
            .jsonPath("$.definition.page.children.length()")
            .isEqualTo(1)
            .jsonPath("$.definition.page.children[0].region")
            .isEqualTo("MAIN")
            .jsonPath("$.definition.page.children[0].children[0].type")
            .isEqualTo("TABS")
    }

    // generate() is now the only source of a page's initial shape for every object, so it must not
    // be able to drift out of agreement with the validator. this posts the derived page straight
    // back rather than rebuilding it, so nothing here can paper over a mismatch.
    @Test
    fun `the generated page is accepted by the validator`() {
        val derived =
            resolve(predio)
                .returnResult()
                .responseBody!!
                .decodeToString()
        val tree = objectMapper.readTree(derived)

        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "objectName" to predio,
                    "name" to uniqueName("page").take(30),
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    // the response carries the whole template; the request wants its name
                    "template" to tree.get("template").get("name").asString(),
                    "definition" to objectMapper.convertValue(tree.get("definition"), Map::class.java)
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `a flat object gets a single-column page with no map`() {
        resolve(nota)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
            .jsonPath("$.definition.page.children[0].children[0].children.length()")
            .isEqualTo(2)
            .jsonPath("$.definition.page.children[0].children[0].children[0].title")
            .isEqualTo("DETAILS")
            // history closes every generated page: the record's own trail, no configuration needed
            .jsonPath("$.definition.page.children[0].children[0].children[1].title")
            .isEqualTo("HISTORY")
            // a flat object has no map tab at all
            .jsonPath("$.definition.page.children[0].children[0].children[?(@.title == 'MAP')]")
            .doesNotExist()
    }

    @Test
    fun `the generated page carries one related list per relationship`() {
        val relationship = createRelationship(predio, nota)

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.definition.page.children[0].children[0].children[2].title")
            .isEqualTo("RELATED")
            .jsonPath("$.definition.page.children[0].children[0].children[2].children[0].type")
            .isEqualTo("RELATED_LIST")
            .jsonPath("$.definition.page.children[0].children[0].children[2].children[0].relationship")
            .isEqualTo(relationship)
            .jsonPath("$.definition.page.children[0].children[0].children[2].children[0].title")
            .isEqualTo("Titular")

        // and from the other side too
        resolve(nota)
            .jsonPath("$.definition.page.children[0].children[0].children[1].title")
            .isEqualTo("RELATED")
            .jsonPath("$.definition.page.children[0].children[0].children[1].children[0].type")
            .isEqualTo("RELATED_LIST")
            .jsonPath("$.definition.page.children[0].children[0].children[1].children[0].relationship")
            .isEqualTo(relationship)
    }

    // an admin may group components however they like; the tab strip is authored, not derived
    @Test
    fun `a stored page keeps the tabs it was given`() {
        val name = uniqueName("page").take(30)
        createPage(
            name,
            predio,
            "one-region",
            page(
                region(
                    "MAIN",
                    listOf(tabs(tab("Ficha", listOf(form(1, null))), tab("Auditoría", listOf(mapOf("type" to "HISTORY", "column" to 1)))))
                )
            )
        ).expectStatus().isCreated

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.definition.page.children[0].children[0].children[0].title")
            .isEqualTo("Ficha")
            .jsonPath("$.definition.page.children[0].children[0].children[1].title")
            .isEqualTo("Auditoría")
    }

    @Test
    fun `a tab with a blank title keeps no title`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(tabs(tab("   ", listOf(form(1, null))))))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].children[0].title")
            .doesNotExist()
    }

    @Test
    fun `a page keeps the tree it was given`() {
        val name = uniqueName("page").take(30)
        createPage(
            name,
            predio,
            "one-region",
            page(
                region(
                    "MAIN",
                    listOf(tabs(tab("Detalles", listOf(section("Datos", listOf(form(1, null))))), tab("Mapa", listOf(map(1)))))
                )
            )
        ).expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].type")
            .isEqualTo("TABS")
            .jsonPath("$.definition.page.children[0].children[0].children[0].type")
            .isEqualTo("TAB")
            .jsonPath("$.definition.page.children[0].children[0].children[0].title")
            .isEqualTo("Detalles")
            .jsonPath("$.definition.page.children[0].children[0].children[0].children[0].type")
            .isEqualTo("SECTION")
            .jsonPath("$.definition.page.children[0].children[0].children[0].children[0].children[0].type")
            .isEqualTo("FORM")
            .jsonPath("$.definition.page.children[0].children[0].children[1].children[0].type")
            .isEqualTo("MAP")
    }

    // an update that omits the definition revalidates the stored one through toRequest(). the old
    // one mapped seven of eight fields, so a plain label change silently untabbed the whole page.
    // comparing the whole stored definition, not a few picked paths, is the point: a field dropped
    // from toRequest() must fail this test, not slip through because nobody asserted on it.
    @Test
    fun `renaming a page leaves its tree alone`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(tabs(tab("Detalles", listOf(form(1, null))))))))
            .expectStatus()
            .isCreated

        val before = readRawDefinition(name)

        client
            .put()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("label" to "Ficha del predio"))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Ficha del predio")

        val after = readRawDefinition(name)
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `a stored page replaces the generated one`() {
        val name = uniqueName("page")
        createPage(name, predio, "one-region", page(region("MAIN", listOf(form(1, listOf("codigo")), map(2)), "two-column")))
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.name")
            .isEqualTo(name)

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(false)
            .jsonPath("$.name")
            .isEqualTo(name)
            .jsonPath("$.definition.page.children[0].children[0].fields[0]")
            .isEqualTo("codigo")

        // the object's stored pages show up on the metadata endpoint
        client
            .get()
            .uri("/api/metadata/objects/$predio/pages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo(name)
    }

    @Test
    fun `a second page for the same object and kind is refused`() {
        createPage(uniqueName("page"), predio, "one-region", page(region("MAIN", listOf(form(1, null)), "two-column")))
            .expectStatus()
            .isCreated
        createPage(uniqueName("page"), predio, "one-region", page(region("MAIN", listOf(form(1, null)), "two-column")))
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `a related list naming an unknown relationship is refused`() {
        createPage(
            uniqueName("page"),
            predio,
            "one-region",
            page(region("MAIN", listOf(mapOf("type" to "RELATED_LIST", "column" to 1, "relationship" to "nope")), "two-column"))
        ).expectStatus()
            .isBadRequest
    }

    @Test
    fun `a map on an object without geometry is refused`() {
        createPage(uniqueName("page"), nota, "one-region", page(region("MAIN", listOf(map(2)), "two-column")))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a map may name one of the object's geometries`() {
        val name = uniqueName("page").take(30)
        val targeted = mapOf("type" to "MAP", "column" to 1, "geometry" to "geom")

        createPage(name, predio, "one-region", page(region("MAIN", listOf(targeted))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].geometry")
            .isEqualTo("geom")
    }

    @Test
    fun `a map naming a geometry the object does not have is refused`() {
        val targeted = mapOf("type" to "MAP", "column" to 1, "geometry" to "no_existe")

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(targeted))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a component outside the layout columns is refused`() {
        createPage(uniqueName("page"), predio, "one-region", page(region("MAIN", listOf(map(2)))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `the catalogue lists every template with its rows`() {
        client
            .get()
            .uri("/api/metadata/page-templates")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            // the catalogue's own size, not a literal: the test's name says "every template", and a
            // number here just rots on the next one
            .jsonPath("$.length()")
            .isEqualTo(PageTemplate.entries.size)
            .jsonPath("$[?(@.name == 'header-and-right-sidebar')].columns")
            .isEqualTo(12)
            .jsonPath("$[?(@.name == 'header-and-right-sidebar')].rows.length()")
            .isEqualTo(2)
            .jsonPath("$[?(@.name == 'header-and-right-sidebar')].rows[1].regions[0].name")
            .isEqualTo("MAIN")
            .jsonPath("$[?(@.name == 'header-and-right-sidebar')].rows[1].regions[0].span")
            .isEqualTo(8)
            .jsonPath("$[?(@.name == 'header-and-right-sidebar')].rows[1].regions[1].span")
            .isEqualTo(4)
            // the left-hand twin: same shape mirrored, and the only pair that differs by order alone
            .jsonPath("$[?(@.name == 'main-and-left-sidebar')].rows[0].regions[0].name")
            .isEqualTo("LEFT")
            .jsonPath("$[?(@.name == 'main-and-left-sidebar')].rows[0].regions[0].span")
            .isEqualTo(4)
            .jsonPath("$[?(@.name == 'main-and-left-sidebar')].rows[0].regions[1].name")
            .isEqualTo("MAIN")
    }

    @Test
    fun `deleting a stored page falls back to the generated default`() {
        val name = uniqueName("page")
        createPage(name, predio, "one-region", page(region("MAIN", listOf(form(1, null)), "two-column"))).expectStatus().isCreated
        resolve(predio).jsonPath("$.generated").isEqualTo(false)

        client
            .delete()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.definition.page.children[0].children[0].children[1].title")
            .isEqualTo("MAP")
    }

    @Test
    fun `updating a page swaps its definition`() {
        val name = uniqueName("page")
        createPage(name, predio, "one-region", page(region("MAIN", listOf(form(1, null), map(2)), "two-column"))).expectStatus().isCreated

        client
            .put()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "label" to "Ficha",
                    // no layout named, so the region falls back to single-column
                    "definition" to mapOf("page" to page(region("MAIN", listOf(form(1, null), text(1, "hola")))))
                )
            ).exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.label")
            .isEqualTo("Ficha")
            .jsonPath("$.definition.page.children[0].children[1].content")
            .isEqualTo("hola")

        // the map no longer fits a single column
        client
            .put()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("definition" to mapOf("page" to page(region("MAIN", listOf(map(2)))))))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    // the client rearranges the tree and sends one that already matches. a PUT that changes the
    // template without a matching tree has to be refused, or the stored page and its template drift.
    @Test
    fun `changing the template refuses a tree that still carries the old regions`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(form(1, null)))))
            .expectStatus()
            .isCreated

        client
            .put()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("template" to "two-regions", "definition" to mapOf("page" to page(region("MAIN", listOf(form(1, null)))))))
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `changing the template accepts a tree the client already moved`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(text(1, "mio")))))
            .expectStatus()
            .isCreated

        client
            .put()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "template" to "two-regions",
                    "definition" to mapOf("page" to page(region("MAIN"), region("RIGHT", listOf(text(1, "mio")))))
                )
            ).exchange()
            .expectStatus()
            .isOk

        resolve(predio)
            .jsonPath("$.template.name")
            .isEqualTo("two-regions")
            .jsonPath("$.definition.page.children[1].region")
            .isEqualTo("RIGHT")
            .jsonPath("$.definition.page.children[1].children[0].content")
            .isEqualTo("mio")
    }

    @Test
    fun `a tree nested past the bound is refused`() {
        // 11 nested sections: one past the pretil
        var node = section("hondo", listOf(form(1, null)))
        repeat(10) { node = section("hondo", listOf(node)) }

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(node))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page with more components than the bound is refused`() {
        val many = (1..201).map { text(1, "nota $it") }

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", many)))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a tab strip holding something that is not a tab is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(tabs(form(1, null))))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a tab outside a strip is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(tab("suelta", listOf(form(1, null)))))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a leaf carrying children is refused`() {
        val formWithChildren = mapOf("type" to "FORM", "column" to 1, "children" to listOf(text(1, "dentro")))

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(formWithChildren))))
            .expectStatus()
            .isBadRequest
    }

    // check() runs inside walk()'s recursion. if it ever stopped doing so, a nested leaf would
    // validate as fine and the page builder would happily save a page the renderer chokes on.
    @Test
    fun `a leaf's own rule is enforced at depth, not just at the root`() {
        val buried = tabs(tab("Detalles", listOf(section("Datos", listOf(mapOf("type" to "RELATED_LIST", "column" to 1, "relationship" to "no_existe"))))))

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(buried))))
            .expectStatus()
            .isBadRequest
    }

    // the whole point of a per-container layout: the page is narrow, the section is not
    @Test
    fun `a second column inside a two-column section on a single-column page is accepted`() {
        val name = uniqueName("page").take(30)
        val wide = section("Datos", listOf(form(1, null), map(2)), layout = "two-column")

        createPage(name, predio, "one-region", page(region("MAIN", listOf(wide))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].layout")
            .isEqualTo("two-column")
            .jsonPath("$.definition.page.children[0].children[0].children[1].column")
            .isEqualTo(2)
    }

    @Test
    fun `an action firing a transition the object has no workflow for is refused`() {
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "aprobar", "title" to "Aprobar")

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(action))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action firing a transition the workflow does have is kept`() {
        val name = uniqueName("page").take(30)
        attachWorkflow(predio)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "approve", "title" to "Aprobar")

        createPage(name, predio, "one-region", page(region("MAIN", listOf(action))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].transition")
            .isEqualTo("approve")
    }

    @Test
    fun `an action firing a transition the workflow does not have is refused`() {
        attachWorkflow(predio)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "rechazar", "title" to "Rechazar")

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(action))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action that navigates needs exactly one destination`() {
        val neither = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ir")
        val both = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ir", "target" to nota, "url" to "https://x.test")

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(neither))))
            .expectStatus()
            .isBadRequest
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(both))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action that navigates to another object is kept`() {
        val name = uniqueName("page").take(30)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ver notas", "target" to nota, "style" to "PRIMARY")

        createPage(name, predio, "one-region", page(region("MAIN", listOf(action))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].action")
            .isEqualTo("NAVIGATE")
            .jsonPath("$.definition.page.children[0].children[0].target")
            .isEqualTo(nota)
            .jsonPath("$.definition.page.children[0].children[0].style")
            .isEqualTo("PRIMARY")
    }

    @Test
    fun `an action with no kind is refused`() {
        val action = mapOf("type" to "ACTION", "column" to 1, "title" to "Nada")

        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(action))))
            .expectStatus()
            .isBadRequest
    }

    // V11 drops every stored page, so V10's flat-to-tree replay has nothing left to pin. the four
    // migration tests that lived here are gone with it.

    @Test
    fun `a definition with no page is refused`() {
        createPageRaw(uniqueName("page").take(30), predio, "one-region", emptyMap())
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A definition with no page")
    }

    @Test
    fun `a root that is not a page is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", section("Datos", listOf(form(1, null))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The root is a SECTION")
    }

    @Test
    fun `a page nested inside the tree is refused`() {
        val buried = page(region("MAIN", listOf(page(region("MAIN")))))

        createPage(uniqueName("page").take(30), predio, "one-region", buried)
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A PAGE inside the tree")
    }

    @Test
    fun `the page holding something that is not a region is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(form(1, null)))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The page holds FORM")
    }

    // a REGION is the real thing the page wants; a blank name is a missing name, not a wrong
    // child type. it must fall through to PageRegion.parse and get the unknown-region message,
    // not the "PAGE accepts only REGION children" message a FORM or MAP would get.
    @Test
    fun `a region with a blank name is refused as an unknown region`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown region ''")
    }

    @Test
    fun `a region outside the page is refused`() {
        val stray = page(region("MAIN", listOf(section("Datos", listOf(region("RIGHT"))))))

        createPage(uniqueName("page").take(30), predio, "one-region", stray)
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A REGION sits outside the page")
    }

    @Test
    fun `a region naming something that is not a region is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MIDDLE")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown region 'MIDDLE'")
    }

    @Test
    fun `a page missing one of its template's regions is refused`() {
        createPage(uniqueName("page").take(30), predio, "two-regions", page(region("MAIN")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The page is missing region RIGHT")
    }

    @Test
    fun `a page carrying a region its template does not declare is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN"), region("RIGHT")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The page holds region RIGHT")
    }

    @Test
    fun `the same region twice is refused`() {
        createPage(uniqueName("page").take(30), predio, "two-regions", page(region("MAIN"), region("MAIN")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The page holds region MAIN twice")
    }

    @Test
    fun `regions out of the template's order are refused`() {
        createPage(uniqueName("page").take(30), predio, "main-and-right-sidebar", page(region("RIGHT"), region("MAIN")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("The page lists its regions out of order")
    }

    // the scaffold exists before anything fills it, so the first save of a fresh template must work
    @Test
    fun `an empty region is accepted`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "two-regions", page(region("MAIN", listOf(form(1, null))), region("RIGHT")))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[1].region")
            .isEqualTo("RIGHT")
            .jsonPath("$.definition.page.children[1].children.length()")
            .isEqualTo(0)
    }

    @Test
    fun `an unknown template is refused`() {
        createPage(uniqueName("page").take(30), predio, "four-regions", page(region("MAIN")))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown template 'four-regions'")
    }

    // the region is the new outermost thing with a layout of its own. ADR-021 resumes here.
    @Test
    fun `a region keeps its own layout and its children their columns`() {
        val name = uniqueName("page").take(30)
        val wide = page(region("MAIN", listOf(form(1, null), map(2)), layout = "two-column"))

        createPage(name, predio, "one-region", wide)
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].layout")
            .isEqualTo("two-column")
            .jsonPath("$.definition.page.children[0].children[1].column")
            .isEqualTo(2)
    }

    @Test
    fun `a component outside the region's columns is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(map(2)))))
            .expectStatus()
            .isBadRequest
    }

    // the scaffold must not quietly cost the admin two of their ten free levels
    @Test
    fun `ten levels of nesting inside a region are accepted and eleven are refused`() {
        var deep: Map<String, Any> = form(1, null)
        repeat(9) { deep = section("hondo", listOf(deep)) }
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(deep))))
            .expectStatus()
            .isCreated

        var deeper: Map<String, Any> = form(1, null)
        repeat(10) { deeper = section("hondo", listOf(deeper)) }
        createPage(uniqueName("page").take(30), nota, "one-region", page(region("MAIN", listOf(deeper))))
            .expectStatus()
            .isBadRequest
    }

    private fun readRawDefinition(name: String): String? =
        runBlocking {
            db
                .sql("SELECT definition::text AS definition FROM wasichai.pages WHERE name = :name")
                .bind("name", name)
                .map { row, _ -> row.get("definition", String::class.java)!! }
                .one()
                .awaitFirstOrNull()
        }

    private fun form(
        column: Int,
        fields: List<String>?
    ): Map<String, Any> =
        buildMap {
            put("type", "FORM")
            put("column", column)
            fields?.let { put("fields", it) }
        }

    private fun map(column: Int): Map<String, Any> = mapOf("type" to "MAP", "column" to column, "title" to "Ubicacion")

    private fun text(
        column: Int,
        content: String
    ): Map<String, Any> = mapOf("type" to "TEXT", "column" to column, "content" to content)

    private fun tabs(vararg children: Map<String, Any>): Map<String, Any> =
        mapOf("type" to "TABS", "column" to 1, "layout" to "single-column", "children" to children.toList())

    private fun tab(
        title: String,
        children: List<Map<String, Any>>
    ): Map<String, Any> = mapOf("type" to "TAB", "column" to 1, "layout" to "single-column", "title" to title, "children" to children)

    private fun section(
        title: String,
        children: List<Map<String, Any>>,
        layout: String = "single-column"
    ): Map<String, Any> = mapOf("type" to "SECTION", "column" to 1, "layout" to layout, "title" to title, "children" to children)

    private fun page(vararg regions: Map<String, Any>): Map<String, Any> = mapOf("type" to "PAGE", "children" to regions.toList())

    private fun region(
        name: String,
        children: List<Map<String, Any>> = emptyList(),
        layout: String = "single-column"
    ): Map<String, Any> = mapOf("type" to "REGION", "region" to name, "layout" to layout, "children" to children)

    private fun attachWorkflow(objectName: String) {
        client
            .put()
            .uri("/api/objects/$objectName/workflow")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to uniqueName("wf").take(30),
                    "label" to "Aprobacion",
                    "enabled" to true,
                    "definition" to
                        mapOf(
                            "states" to
                                listOf(
                                    mapOf("name" to "draft", "label" to "Borrador", "type" to "INITIAL"),
                                    mapOf("name" to "approved", "label" to "Aprobado", "type" to "FINAL")
                                ),
                            "transitions" to listOf(mapOf("name" to "approve", "label" to "Aprobar", "from" to "draft", "to" to "approved"))
                        )
                )
            ).exchange()
            .expectStatus()
            .isOk
    }

    private fun resolve(objectName: String) =
        client
            .get()
            .uri("/api/objects/$objectName/pages/record-detail")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun createPage(
        name: String,
        objectName: String,
        template: String,
        root: Map<String, Any>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "objectName" to objectName,
                    "name" to name,
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    "template" to template,
                    "definition" to mapOf("page" to root)
                )
            ).exchange()

    private fun createPageRaw(
        name: String,
        objectName: String,
        template: String,
        definition: Map<String, Any>
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri("/api/pages")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "objectName" to objectName,
                    "name" to name,
                    "label" to "Detalle",
                    "kind" to "RECORD_DETAIL",
                    "template" to template,
                    "definition" to definition
                )
            ).exchange()

    private fun createRelationship(
        source: String,
        target: String
    ): String {
        val name = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Titular",
                    "inverseLabel" to "Predios",
                    "type" to "MANY_TO_ONE",
                    "source" to source,
                    "target" to target,
                    "fieldName" to "titular"
                )
            ).exchange()
            .expectStatus()
            .isCreated
        return name
    }

    // ---- dynamic forms ----

    private fun field(
        name: String,
        extra: Map<String, Any> = emptyMap()
    ): Map<String, Any> = mapOf("type" to "FIELD", "column" to 1, "field" to name) + extra

    private fun dynamicForm(vararg children: Map<String, Any>): Map<String, Any> =
        mapOf("type" to "DYNAMIC_FORM", "column" to 1, "layout" to "single-column", "children" to children.toList())

    @Test
    fun `a dynamic form keeps the fields it was given, in order`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(dynamicForm(field("geom"), field("codigo"))))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].children[0].field")
            .isEqualTo("geom")
            .jsonPath("$.definition.page.children[0].children[0].children[1].field")
            .isEqualTo("codigo")
    }

    @Test
    fun `a field naming nothing is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(dynamicForm(mapOf("type" to "FIELD", "column" to 1))))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A FIELD names no field")
    }

    @Test
    fun `a field the object has not got is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(dynamicForm(field("fantasma"))))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Unknown field 'fantasma'")
    }

    @Test
    fun `a field outside a dynamic form is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(field("codigo")))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A FIELD sits outside a DYNAMIC_FORM")
    }

    @Test
    fun `a dynamic form holding something that is not a field is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(dynamicForm(text(1, "suelto"))))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("A dynamic form holds TEXT")
    }

    @Test
    fun `the same field twice on one dynamic form is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN", listOf(dynamicForm(field("codigo"), field("codigo"))))))
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Field 'codigo' appears twice")
    }

    // the other direction of the same rule: two forms are two forms, and the rule is "twice on one"
    @Test
    fun `two dynamic forms may each hold the same field`() {
        createPage(
            uniqueName("page").take(30),
            predio,
            "one-region",
            page(region("MAIN", listOf(dynamicForm(field("codigo")), dynamicForm(field("codigo")))))
        ).expectStatus()
            .isCreated
    }

    @Test
    fun `a placement may hide a field and may lock one the object lets through`() {
        val name = uniqueName("page").take(30)
        createPage(
            name,
            predio,
            "one-region",
            page(region("MAIN", listOf(dynamicForm(field("codigo", mapOf("visible" to false, "editable" to false))))))
        ).expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.page.children[0].children[0].children[0].visible")
            .isEqualTo(false)
            .jsonPath("$.definition.page.children[0].children[0].children[0].editable")
            .isEqualTo(false)
    }

    // a placement may take away what the object grants, never add to it
    @Test
    fun `a placement cannot make a read-only field editable`() {
        val locked = uniqueName("locked")
        createObjectWithReadOnlyField(locked)

        createPage(
            uniqueName("page").take(30),
            locked,
            "one-region",
            page(region("MAIN", listOf(dynamicForm(field("sellado", mapOf("editable" to true))))))
        ).expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .isEqualTo("Field 'sellado' is read-only")
    }

    private fun createObjectWithReadOnlyField(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Sellado",
                    "fields" to
                        listOf(
                            mapOf("name" to "codigo", "type" to "TEXT"),
                            mapOf("name" to "sellado", "type" to "TEXT", "editable" to false)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated
    }

    private fun createObject(
        name: String,
        label: String,
        geometryType: String?
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                buildMap {
                    put("name", name)
                    put("label", label)
                    val fields = mutableListOf(mapOf("name" to "codigo", "type" to "TEXT"))
                    geometryType?.let { fields += mapOf("name" to "geom", "type" to "GEOMETRY", "geometryType" to it) }
                    put("fields", fields)
                }
            ).exchange()
            .expectStatus()
            .isCreated
    }

    // clean start: the original replayed its V11 here by hand to pin "a stored page does not survive
    // the migration". wasichai has no stored pages to migrate, so what is left to pin is V11's end state
    // -- template instead of layout -- and the fallback it relied on: a page gone means generated.
    @Test
    fun `the pages table is born in its final shape and a deleted page falls back to the generated one`() {
        val columns =
            runBlocking {
                val sql =
                    "SELECT column_name, is_nullable, column_default FROM information_schema.columns " +
                        "WHERE table_schema = 'wasichai' AND table_name = 'pages'"
                db
                    .sql(sql)
                    .map { row, _ ->
                        Triple(
                            row.get("column_name", String::class.java)!!,
                            row.get("is_nullable", String::class.java)!!,
                            row.get("column_default", String::class.java)
                        )
                    }.all()
                    .collectList()
                    .awaitFirstOrNull()
                    .orEmpty()
            }
        assertThat(columns.map { it.first }).contains("template").doesNotContain("layout")
        val template = columns.first { it.first == "template" }
        assertThat(template.second).isEqualTo("NO")
        assertThat(template.third).isEqualTo("'one-region'::text")

        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(text(1, "mio")))))
            .expectStatus()
            .isCreated
        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(false)

        client
            .delete()
            .uri("/api/pages/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.template.name")
            .isEqualTo("one-region")
    }
}
