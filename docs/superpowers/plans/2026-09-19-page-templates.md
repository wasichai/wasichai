# Page Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a record detail page a named root and a template that declares fixed regions, chosen and changed from a gallery, while the tree inside a region stays free.

**Architecture:** `PageDefinition` stops being a bare list and becomes one `PAGE` node holding one `REGION` per region its template declares. The template is a code-defined catalogue on the page row, replacing the `layout` column it supersedes. Inside a region nothing changes. Stored pages are not migrated — they are deleted and regenerated from the derived default.

**Tech Stack:** Kotlin 2.4.20 · Spring Boot 4.1 WebFlux · Flyway/Postgres 18 · React 19.3 · Vite · Tailwind · `@dnd-kit` · `@radix-ui/react-dialog` · vitest · JUnit 5 + WebTestClient

**Spec:** `/Users/jorge/.claude/plans/breezy-weaving-pine.md`

## Global Constraints

- Code, identifiers and comments in **English**. Comments caveman style: short, blunt, say *why*.
- Formatting per `.editorconfig` (Kotlin 4 spaces, TS 2, max 160 cols), enforced by ktlint / prettier.
- **JPA / Hibernate / Envers are forbidden.** R2DBC + `DatabaseClient` only.
- Modules talk through ports and services, never another module's repositories.
- Metadata-driven: never generate code per Custom Object.
- **Never an apostrophe inside a single-quoted string**, test names included. This has broken the repo three times.
- Change history goes in `docs/HISTORY.md`, never in `CLAUDE.md`. Architectural decisions in `docs/adr/`; next free number is **0022**.
- Region vocabulary is global across templates: `HEADER`, `MAIN`, `LEFT`, `CENTER`, `RIGHT`. **Every template declares `MAIN`.**
- Bounds: `MAX_DEPTH` **12** (was 10 — the scaffold spends two levels and the free tree keeps its ten), `MAX_COMPONENTS` 200.
- Push after each completed phase with a descriptive commit.
- **Never run two Gradle commands at once, and never compile while a test run is in flight** — it rewrites `build/classes` under the test JVM and produces bogus failures.

**Verification commands:**

```bash
./gradlew :backend:ktlintCheck :backend:test
SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest
cd frontend && yarn lint && yarn test --run && yarn build
```

Baselines at branch point (`cec5c3a`): **56** backend unit, **188** backend integration, **263** frontend across 26 files.

If `LayerApiTest` fails with `DataBufferLimitException`, that is accumulated test-DB drift and not your change:
`docker exec sapgis-postgres psql -U sapgis -d postgres -c 'DROP DATABASE IF EXISTS sapgis_test' -c 'CREATE DATABASE sapgis_test'`

## File Structure

**Backend**

| File | Responsibility |
|---|---|
| `pages/PageTemplate.kt` (new) | the catalogue: `PageRegion`, `TemplateRegion`, `TemplateRow`, `PageTemplate` |
| `pages/Page.kt` | `ComponentType.PAGE/REGION`, `PageComponent.region`, `PageDefinition.page` |
| `pages/PageService.kt` | recursive validation against a template, `generate()` |
| `pages/PageController.kt` | `PageTemplateController` + response DTOs, `PageResponse.template` |
| `pages/PageRepository.kt` | the `template` column in place of `layout` |
| `db/migration/V11__a_page_has_a_template.sql` (new) | delete pages, swap the column |

**Frontend**

| File | Responsibility |
|---|---|
| `types/metadata.ts` | `PageTemplate`, `TemplateRow`, `TemplateRegion`, the widened union, `region` |
| `features/pages/builder/templates.ts` (new) | `regionStyle`, `ROW_CLASS`, `regionKeys` — the one place layout geometry is written down |
| `components/page-renderer/PageRenderer.tsx` | `PAGE` and `REGION` arms |
| `features/pages/builder/pageTree.ts` | `accepts` rules for the furniture |
| `features/pages/builder/CanvasRegion.tsx` (new) | one region: a frame and a `Slots`, nothing else |
| `features/pages/builder/Canvas.tsx` | template rows, key-to-index bridge |
| `features/pages/builder/Palette.tsx` | narrow `ICONS` to placeable types |
| `components/ui/dialog.tsx` (new) | the first Radix dialog in this repo |
| `features/pages/builder/preview/TemplatePreview.tsx` (new) | a template drawn as boxes |
| `features/pages/builder/retemplate.ts` (new) | `orphans`, `retemplate` — pure |
| `features/pages/builder/TemplateDialog.tsx` (new) | pick a template, then say where orphans go |
| `features/pages/PageBuilderPage.tsx` | the settings card, the template summary |

---

# Phase 1 — The model and the catalogue

### Task 1: The catalogue

Pure data with pure invariants. Nothing depends on it yet, so it can be built alongside Task 3.

**Files:**
- Create: `backend/src/main/kotlin/com/sapgis/pages/PageTemplate.kt`
- Create: `backend/src/test/kotlin/com/sapgis/pages/PageTemplateTest.kt`

**Interfaces:**
- Produces: `PageRegion` (`HEADER`, `MAIN`, `LEFT`, `CENTER`, `RIGHT`, with `parse`), `TemplateRegion(name, span)`, `TemplateRow(regions)`, `PageTemplate` with `.value`, `.rows`, `.regions`, `.regionNames`, `PageTemplate.COLUMNS = 12`, `PageTemplate.parse(raw: String?)`.

- [ ] **Step 1: Write the failing invariant tests**

Create `backend/src/test/kotlin/com/sapgis/pages/PageTemplateTest.kt`:

```kotlin
package com.sapgis.pages

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageTemplateTest {
    @Test
    fun `every row fills the grid`() {
        PageTemplate.entries.forEach { template ->
            template.rows.forEach { row ->
                assertThat(row.regions.sumOf { it.span })
                    .describedAs("${template.value} has a row that does not fill ${PageTemplate.COLUMNS} columns")
                    .isEqualTo(PageTemplate.COLUMNS)
            }
        }
    }

    @Test
    fun `no template names the same region twice`() {
        PageTemplate.entries.forEach { template ->
            assertThat(template.regionNames).describedAs(template.value).doesNotHaveDuplicates()
        }
    }

    // the main region is what makes a template change cheap: the admin's principal content never
    // has to move, so only a header or a sidebar can ever be orphaned.
    @Test
    fun `every template declares a main region`() {
        PageTemplate.entries.forEach { template ->
            assertThat(template.regionNames).describedAs(template.value).contains(PageRegion.MAIN)
        }
    }

    @Test
    fun `the catalogue holds the nine standards`() {
        assertThat(PageTemplate.entries.map { it.value })
            .containsExactly(
                "one-region",
                "two-regions",
                "three-regions",
                "header-and-one-region",
                "header-and-two-regions",
                "header-and-three-regions",
                "header-and-left-sidebar",
                "header-and-right-sidebar",
                "main-and-right-sidebar"
            )
    }

    @Test
    fun `a blank name is the one-region template`() {
        assertThat(PageTemplate.parse(null)).isEqualTo(PageTemplate.ONE_REGION)
        assertThat(PageTemplate.parse("  ")).isEqualTo(PageTemplate.ONE_REGION)
    }

    @Test
    fun `an unknown name is refused`() {
        assertThat(runCatching { PageTemplate.parse("four-regions") }.exceptionOrNull())
            .isInstanceOf(com.sapgis.common.ValidationException::class.java)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*PageTemplateTest*'`
Expected: FAIL — `PageTemplate` does not exist.

- [ ] **Step 3: Write the catalogue**

Create `backend/src/main/kotlin/com/sapgis/pages/PageTemplate.kt`:

```kotlin
package com.sapgis.pages

import com.sapgis.common.ValidationException
import tools.jackson.annotation.JsonValue

// a region's name is a key, not a word: the backend has no language, so the client translates it.
// the vocabulary is shared across templates on purpose -- a region both templates name keeps its
// contents when the template changes, so only what genuinely disappears needs a decision.
enum class PageRegion {
    HEADER,
    MAIN,
    LEFT,
    CENTER,
    RIGHT;

    companion object {
        fun parse(raw: String?): PageRegion =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
                ?: throw ValidationException(
                    "Unknown region '${raw.orEmpty()}'",
                    "page",
                    "must be one of ${entries.joinToString(", ")}"
                )
    }
}

// span is out of PageTemplate.COLUMNS. twelve divides by 2, 3 and 4, so halves, thirds and a 2:1
// sidebar are all whole numbers, and it drops straight into a css grid or a flex-grow.
data class TemplateRegion(
    val name: PageRegion,
    val span: Int
)

data class TemplateRow(
    val regions: List<TemplateRegion>
)

// the catalogue is code, not a table. one description serves three readers: the validator, the
// renderer, and the preview the client draws without any shipped artwork.
enum class PageTemplate(
    @get:JsonValue val value: String,
    val rows: List<TemplateRow>
) {
    ONE_REGION("one-region", rows(row(PageRegion.MAIN to 12))),
    TWO_REGIONS("two-regions", rows(row(PageRegion.MAIN to 6, PageRegion.RIGHT to 6))),
    THREE_REGIONS("three-regions", rows(row(PageRegion.LEFT to 4, PageRegion.MAIN to 4, PageRegion.RIGHT to 4))),
    HEADER_AND_ONE_REGION("header-and-one-region", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 12))),
    HEADER_AND_TWO_REGIONS("header-and-two-regions", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 6, PageRegion.RIGHT to 6))),
    HEADER_AND_THREE_REGIONS(
        "header-and-three-regions",
        rows(row(PageRegion.HEADER to 12), row(PageRegion.LEFT to 4, PageRegion.MAIN to 4, PageRegion.RIGHT to 4))
    ),
    HEADER_AND_LEFT_SIDEBAR("header-and-left-sidebar", rows(row(PageRegion.HEADER to 12), row(PageRegion.LEFT to 4, PageRegion.MAIN to 8))),
    HEADER_AND_RIGHT_SIDEBAR("header-and-right-sidebar", rows(row(PageRegion.HEADER to 12), row(PageRegion.MAIN to 8, PageRegion.RIGHT to 4))),
    MAIN_AND_RIGHT_SIDEBAR("main-and-right-sidebar", rows(row(PageRegion.MAIN to 8, PageRegion.RIGHT to 4)));

    // reading order, row by row, left to right. this is the order the PAGE node's children take.
    val regions: List<TemplateRegion> get() = rows.flatMap { it.regions }
    val regionNames: List<PageRegion> get() = regions.map { it.name }

    companion object {
        const val COLUMNS = 12

        fun parse(raw: String?): PageTemplate {
            if (raw.isNullOrBlank()) return ONE_REGION
            return entries.firstOrNull { it.value.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown template '$raw'",
                    "template",
                    "must be one of ${entries.joinToString(", ") { it.value }}"
                )
        }
    }
}

private fun row(vararg regions: Pair<PageRegion, Int>): TemplateRow = TemplateRow(regions.map { TemplateRegion(it.first, it.second) })

private fun rows(vararg rows: TemplateRow): List<TemplateRow> = rows.toList()
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :backend:ktlintCheck :backend:test --tests '*PageTemplateTest*'`
Expected: PASS, ktlint clean.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/pages/PageTemplate.kt backend/src/test/kotlin/com/sapgis/pages/PageTemplateTest.kt
git commit -m "feat(pages): a catalogue of page templates, with their regions"
```

### Task 2: The catalogue endpoint

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageController.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: `PageTemplate`, `TemplateRow`, `TemplateRegion` from Task 1.
- Produces: `GET /api/metadata/page-templates` → `List<PageTemplateResponse>` where `PageTemplateResponse(name: String, columns: Int, rows: List<PageTemplateRowResponse>)`.

- [ ] **Step 1: Write the failing test**

Add to `PageApiTest.kt`:

```kotlin
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
            .jsonPath("$.length()")
            .isEqualTo(9)
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
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest.the catalogue*'`
Expected: FAIL with 404.

- [ ] **Step 3: Add the DTOs and the controller**

In `PageController.kt`, beside `PageResponse`:

```kotlin
// explicit dtos, not the enum: PageTemplate carries @get:JsonValue, so serialising it directly
// would emit the bare name and the rows would vanish.
data class PageTemplateRegionResponse(
    val name: String,
    val span: Int
)

data class PageTemplateRowResponse(
    val regions: List<PageTemplateRegionResponse>
)

data class PageTemplateResponse(
    val name: String,
    val columns: Int,
    val rows: List<PageTemplateRowResponse>
)

fun PageTemplate.toResponse(): PageTemplateResponse =
    PageTemplateResponse(
        name = value,
        columns = PageTemplate.COLUMNS,
        rows = rows.map { row -> PageTemplateRowResponse(row.regions.map { PageTemplateRegionResponse(it.name.name, it.span) }) }
    )
```

And at the bottom of the file, beside `ObjectPageController`:

```kotlin
// static and tenant-free: the catalogue is code, the same nine for everybody. it sits under
// /api/metadata beside system-fields because it is the same kind of thing -- a fixed list the UI
// needs to build a form. not under /api/pages, where GET /api/pages/{name} would shadow it.
@RestController
@RequestMapping("/api/metadata/page-templates")
class PageTemplateController {
    @GetMapping
    fun templates(): List<PageTemplateResponse> = PageTemplate.entries.map { it.toResponse() }
}
```

- [ ] **Step 4: Run the test**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest.the catalogue*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/pages/PageController.kt backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): serve the template catalogue"
```

### Task 3: The page is a node

The model only. No new rules — Task 4 adds those. Can run alongside Tasks 1 and 2.

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/Page.kt`
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageRepository.kt`
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageController.kt`

**Interfaces:**
- Produces: `ComponentType.PAGE`, `ComponentType.REGION`, `ComponentType.placeable`, `PageComponent.region: PageRegion?`, `PageDefinition(page: PageComponent)`, `PageDefinitionRequest(page: PageComponentRequest?)`, `Page.template: PageTemplate` replacing `Page.layout`.

- [ ] **Step 1: Widen `ComponentType` and `PageComponent`**

In `Page.kt`, add to the enum before `TABS`:

```kotlin
    // the root, and the slots the template puts in it. scaffolding: the palette never offers them.
    PAGE,
    REGION,
```

and change the two derived properties:

```kotlin
    val container: Boolean
        get() = this == TABS || this == TAB || this == SECTION || this == PAGE || this == REGION

    // what an admin may place. the template owns the rest.
    val placeable: Boolean get() = this != PAGE && this != REGION
```

Add to `PageComponent`, after `children`:

```kotlin
    // REGION: which of the template's regions this is. a key, not a word -- a blank title would
    // quietly destroy the page's structure, so this is deliberately not `title`.
    val region: PageRegion? = null,
```

Replace `PageDefinition`:

```kotlin
// one root, always. a single field rather than a one-element list, so "exactly one page" is
// unrepresentable rather than a rule something has to enforce.
data class PageDefinition(
    val page: PageComponent
)
```

And in `Page`, swap `val layout: PageLayout` for `val template: PageTemplate`.

- [ ] **Step 2: Mirror it on the request side and fix `toRequest()`**

In `PageService.kt`: `PageComponentRequest` gains `val region: String? = null` after `children`. `PageDefinitionRequest` becomes `data class PageDefinitionRequest(val page: PageComponentRequest? = null)`. `CreatePageRequest` and `UpdatePageRequest` swap `layout: String?` for `template: String?`.

`PageComponent.toRequest()` **must map `region`** — a field it forgets is lost on a plain label change, which is the bug the existing `renaming a page leaves its tree alone` test exists to catch:

```kotlin
private fun PageDefinition.toRequest(): PageDefinitionRequest = PageDefinitionRequest(page.toRequest())

private fun PageComponent.toRequest(): PageComponentRequest =
    PageComponentRequest(
        type = type.name,
        column = column,
        title = title,
        layout = layout.value,
        children = children.map { it.toRequest() },
        region = region?.name,
        relationship = relationship,
        fields = fields,
        form = form,
        geometry = geometry,
        content = content,
        action = action?.name,
        transition = transition,
        target = target,
        url = url,
        style = style?.name
    )
```

- [ ] **Step 3: Swap the column in the repository and the controller**

In `PageRepository.kt`, replace every `layout` with `template`: the column list, the insert and update bindings (`page.template.value`), and the row mapper (`template = PageTemplate.parse(Rows.string(row, "template"))`).

In `PageController.kt`, `PageResponse.layout: String` becomes **`template: PageTemplateResponse`** — the whole template, rows and spans included, reusing the DTO Task 2 adds:

```kotlin
data class PageResponse(
    val id: String,
    val name: String,
    val label: String,
    val objectName: String?,
    val kind: String,
    // the whole template, not just its name: the renderer needs the spans to lay the page out, and
    // a name alone would cost it a second request and a loading state on every record it draws.
    // it also means the canvas and the renderer lay out from the same object, which is the cheapest
    // defence there is against the two drifting apart.
    val template: PageTemplateResponse,
    val generated: Boolean,
    val definition: PageDefinition
)
```

built with `template = page.template.toResponse()`.

**This makes Task 2 a prerequisite of this step** — `PageTemplateResponse` and `toResponse()` come from there. If Task 2 has not landed yet, do that one first.

On the way **up** the template still travels as a bare name: `CreatePageRequest.template` and `UpdatePageRequest.template` stay `String?`, because the client picks a template, it does not describe one.

- [ ] **Step 4: Make it compile**

Run: `./gradlew :backend:ktlintCheck :backend:compileKotlin`
Expected: BUILD SUCCESSFUL. `PageService.validated`, `create`, `update` and `generate` will need their `layout` references renamed to satisfy the compiler — do the minimum to compile; Tasks 4 and 5 rewrite their bodies.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/pages/
git commit -m "feat(pages): the root is a page node, and the page row carries a template"
```

### Task 4: Validate the tree against its template

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: `PageTemplate` (Task 1), the model (Task 3).
- Produces: `validated(request, template, definition)`, `walk(components, parentLayout: PageLayout?, parent)`, `check(...)` with two extra parameters.

- [ ] **Step 1: Write the failing tests**

Add to `PageApiTest.kt`, with these helpers beside the existing `tabs`/`tab`/`section`:

```kotlin
    private fun page(vararg regions: Map<String, Any>): Map<String, Any> = mapOf("type" to "PAGE", "children" to regions.toList())

    private fun region(
        name: String,
        children: List<Map<String, Any>> = emptyList(),
        layout: String = "single-column"
    ): Map<String, Any> = mapOf("type" to "REGION", "region" to name, "layout" to layout, "children" to children)
```

and change `createPage` to send `"template" to template` instead of `"layout" to layout`, with the body's `definition` being `mapOf("page" to root)`.

```kotlin
    @Test
    fun `a definition with no page is refused`() {
        createPageRaw(uniqueName("page").take(30), predio, "one-region", emptyMap())
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a root that is not a page is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", section("Datos", listOf(form(1, null))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page nested inside the tree is refused`() {
        val buried = page(region("MAIN", listOf(page(region("MAIN")))))

        createPage(uniqueName("page").take(30), predio, "one-region", buried)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `the page holding something that is not a region is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(form(1, null)))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a region outside the page is refused`() {
        val stray = page(region("MAIN", listOf(section("Datos", listOf(region("RIGHT"))))))

        createPage(uniqueName("page").take(30), predio, "one-region", stray)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a region naming something that is not a region is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MIDDLE")))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page missing one of its template's regions is refused`() {
        createPage(uniqueName("page").take(30), predio, "two-regions", page(region("MAIN")))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page carrying a region its template does not declare is refused`() {
        createPage(uniqueName("page").take(30), predio, "one-region", page(region("MAIN"), region("RIGHT")))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `the same region twice is refused`() {
        createPage(uniqueName("page").take(30), predio, "two-regions", page(region("MAIN"), region("MAIN")))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `regions out of the template's order are refused`() {
        createPage(uniqueName("page").take(30), predio, "main-and-right-sidebar", page(region("RIGHT"), region("MAIN")))
            .expectStatus()
            .isBadRequest
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
```

Add a `createPageRaw(name, objectName, template, definition: Map<String, Any>)` helper that posts an arbitrary `definition` map, so the no-root case can be expressed.

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: the new refusal tests FAIL with 201.

- [ ] **Step 3: Rewrite `validated`**

Replace the signature and the `inspect`/entry lines:

```kotlin
    private suspend fun validated(
        request: PageDefinitionRequest,
        template: PageTemplate,
        definition: ObjectDefinition
    ): PageDefinition {
        val root =
            request.page
                ?: throw ValidationException("A definition with no page", "page", "definition must hold one page node")

        // a free tree is free by design and bounded by defence. without this a hand-written
        // definition blows this validator's own stack before postgres ever sees it.
        inspect(listOf(root), depth = 1)
```

Change `flatten`/lookup lines to `flatten(listOf(root))`, then `walk`:

```kotlin
        fun walk(
            components: List<PageComponentRequest>,
            parentLayout: PageLayout?,
            parent: ComponentType?
        ): List<PageComponent> =
            components.map { component ->
                val type = ComponentType.parse(component.type)
                val own = PageLayout.parse(component.layout)
                check(component, type, parentLayout, parent, definition, fieldNames, relationshipNames, formNames, transitions, objectNames, template)
                PageComponent(
                    type = type,
                    // the page and its regions are placed by the template, so a column means nothing
                    // there. normalise rather than refuse: toRequest() re-emits the stored node on
                    // every definition-less PUT, so refusing would break a plain label change forever.
                    column = if (type.container && parentLayout == null) 1 else component.column,
                    title = component.title?.trim()?.ifBlank { null },
                    layout = if (type == ComponentType.PAGE) PageLayout.SINGLE_COLUMN else own,
                    children = walk(component.children, if (type == ComponentType.PAGE) null else own, type),
                    region = if (type == ComponentType.REGION) PageRegion.parse(component.region) else null,
                    // ... every other field exactly as it is today
                )
            }

        return PageDefinition(walk(listOf(root), null, null).single())
    }
```

- [ ] **Step 4: Add the rules to `check`**

`check` gains a final `template: PageTemplate` parameter. At the top, before the TABS rules:

```kotlin
        // the page is the root and its regions come from the template. neither is something an
        // admin places, so the rule is not "you may not add one" -- it is that the list must equal
        // the template's, exactly.
        if (type == ComponentType.PAGE && parent != null) {
            throw ValidationException("A PAGE inside the tree", "page", "PAGE is the root and nothing else")
        }
        if (parent == ComponentType.PAGE && type != ComponentType.REGION) {
            throw ValidationException("The page holds ${type.name}", "page", "PAGE accepts only REGION children")
        }
        if (type == ComponentType.REGION && parent != ComponentType.PAGE) {
            throw ValidationException("A REGION sits outside the page", "page", "REGION must be a child of PAGE")
        }
        if (parent == null && type != ComponentType.PAGE) {
            throw ValidationException("The root is a ${type.name}", "page", "the root must be a PAGE")
        }
        if (type == ComponentType.PAGE) {
            val declared = template.regionNames
            val actual = component.children.map { PageRegion.parse(it.region) }
            val reason = "template '${template.value}' declares regions ${declared.joinToString(", ")}"
            actual.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
                throw ValidationException("The page holds region ${it.key} twice", "page", reason)
            }
            declared.firstOrNull { it !in actual }?.let { throw ValidationException("The page is missing region $it", "page", reason) }
            actual.firstOrNull { it !in declared }?.let { throw ValidationException("The page holds region $it", "page", reason) }
            if (actual != declared) throw ValidationException("The page lists its regions out of order", "page", reason)
        }
```

Make the column rule conditional:

```kotlin
        if (parentLayout != null && (component.column < 1 || component.column > parentLayout.columns)) {
```

Add `ComponentType.PAGE, ComponentType.REGION` to the containers arm of the `when (type)`.

- [ ] **Step 5: Raise the depth bound and fix the stale comment**

In the companion object: `private const val MAX_DEPTH = 12`, with a comment saying why:

```kotlin
        // 12, not 10: the scaffold spends two levels (PAGE, REGION), and the free tree inside a
        // region keeps exactly the ten ADR-021 gave it.
        private const val MAX_DEPTH = 12
```

And at `flatten`, replace the stale comment `// bounded by the checks in Task 4, so this never runs away` — it refers to a task number that means nothing in this codebase — with `// inspect() runs first and bounds the tree, so this never runs away.`

- [ ] **Step 6: Wire `create` and `update`**

`create`: `val template = PageTemplate.parse(request.template)`, passed to `validated` and stored.
`update`: `val template = request.template?.let { PageTemplate.parse(it) } ?: existing.template`, and the comment becomes `// the template may have changed, so the kept definition is checked again too`.

- [ ] **Step 7: Run the suite**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: the new tests PASS. Tests asserting the old `$.definition.components[...]` paths FAIL — Task 5 re-paths them. Note which; do not fix them here.

- [ ] **Step 8: Commit**

```bash
./gradlew :backend:ktlintCheck
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): a page's regions must be exactly its template's"
```

### Task 5: The generated page comes as a template

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4.

- [ ] **Step 1: Re-path the existing generated-page tests and add the two new ones**

Every `$.definition.components[0]` becomes `$.definition.page.children[0].children[0]` (root → `MAIN` → the old root child). Then add:

```kotlin
    @Test
    fun `the generated page is a one-region page`() {
        resolve(predio)
            .jsonPath("$.template")
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
```

This test needs an `ObjectMapper`. Inject one the way the suite already injects `DatabaseClient`:

```kotlin
    @Autowired
    private lateinit var objectMapper: ObjectMapper
```

with `tools.jackson.databind.ObjectMapper` — Jackson 3, as in `PageRepository.kt:12`.

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: FAIL — `generate()` still emits the old flat root.

- [ ] **Step 3: Wrap `generate()`'s output**

The whole existing body is untouched. Replace only the final `components` line and the `Page(...)` construction:

```kotlin
        val tabs = mutableListOf(...)   // unchanged, all of it

        // one region, because a derived page has no admin intent to read: a template is an
        // authoring choice. and a region it leaves empty is one the admin cannot delete.
        val template = PageTemplate.ONE_REGION
        val contents = mapOf(PageRegion.MAIN to listOf(PageComponent(type = ComponentType.TABS, children = tabs)))
        // built from template.regions, not a literal, so this cannot drift from the validator's rule
        val root =
            PageComponent(
                type = ComponentType.PAGE,
                children = template.regions.map { PageComponent(type = ComponentType.REGION, region = it.name, children = contents[it.name] ?: emptyList()) }
            )

        return Page(
            id = UUID.nameUUIDFromBytes("${definition.obj.id}:${kind.name}".toByteArray()),
            organizationId = definition.obj.organizationId,
            objectId = definition.obj.id,
            name = "${definition.obj.name}-${kind.slug}",
            label = definition.obj.label,
            kind = kind,
            template = template,
            definition = PageDefinition(root)
        )
```

- [ ] **Step 4: Run the whole page suite**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*' --tests '*WorkflowApiTest*' --tests '*FormApiTest*'`
Expected: every test PASSES. `WorkflowApiTest:44,59` assert on a generated page's shape and will need the same re-pathing; `FormApiTest:233` asserts on a page it stored itself and needs its `createPage` updated.

- [ ] **Step 5: Commit**

```bash
./gradlew :backend:ktlintCheck
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/
git commit -m "feat(pages): the generated page arrives in a one-region template"
```

### Task 6: V11 empties the pages

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__a_page_has_a_template.sql`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a stored page does not survive the migration`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "one-region", page(region("MAIN", listOf(text(1, "mio")))))
            .expectStatus()
            .isCreated

        runMigrationSql("V11__a_page_has_a_template.sql")

        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.template")
            .isEqualTo("one-region")
    }

    @Test
    fun `the migration replays without error`() {
        runMigrationSql("V11__a_page_has_a_template.sql")
        runMigrationSql("V11__a_page_has_a_template.sql")
    }
```

Parameterise the existing `runMigrationSql()` helper by filename — it currently hardcodes `V10__a_page_is_a_tree.sql`.

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*migration*'`
Expected: FAIL — the resource does not exist.

- [ ] **Step 3: Write the migration**

```sql
-- a page's top-level arrangement is a template now, not a column count, and the shape of
-- `definition` changed with it: one PAGE node holding one REGION per region the template declares.
--
-- stored pages are NOT rewritten. there is no honest way to guess which region an admin's free
-- tree belonged in, so they are dropped and every object falls back to the page generate() derives
-- from its metadata -- which is exactly what DELETE /api/pages/{name} has always meant.
--
-- this throws away every hand-configured page. that is the decision, not an accident.
--
-- pages only. forms and views are untouched: nothing here makes a stored form invalid, and a FORM
-- component only ever stored the form's *name*.
--
-- delete first, so the NOT NULL column needs no backfill. every statement survives a replay,
-- because the integration test runs this file by hand a second time.

DELETE FROM sapgis.pages;

ALTER TABLE sapgis.pages DROP CONSTRAINT IF EXISTS pages_layout_valid;
ALTER TABLE sapgis.pages DROP COLUMN IF EXISTS layout;

-- no CHECK on template: the catalogue is code, and a CHECK would make adding a tenth one a migration.
ALTER TABLE sapgis.pages ADD COLUMN IF NOT EXISTS template text NOT NULL DEFAULT 'one-region';
```

- [ ] **Step 4: Run the tests, then the whole backend**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest`
Expected: 0 failures. If Flyway refuses a checksum, recreate the test database.

- [ ] **Step 5: Commit and push the phase**

```bash
./gradlew :backend:ktlintCheck :backend:test
git add backend/src/main/resources/db/migration/V11__a_page_has_a_template.sql backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): drop stored pages rather than guess which region they belonged in"
git push -u origin feat/page-templates
```

---

# Phase 2 — The renderer

**This phase goes before any editor work.** `PageRenderer`'s switch falls to `default: return null` for types it does not know, and V11 leaves every object on the new generated shape — so until this lands, every record detail page in the tenant renders empty.

### Task 7: The types and the shared geometry

**Files:**
- Modify: `frontend/src/types/metadata.ts`
- Create: `frontend/src/features/pages/builder/templates.ts`
- Create: `frontend/src/features/pages/builder/templates.test.ts`

**Interfaces:**
- Produces: `TemplateRegion`, `TemplateRow`, `PageTemplate` types; `PageComponentType` widened with `'PAGE' | 'REGION'`; `PageComponent.region?: string | null`; `Page.template: PageTemplate`; `PagePayload.template: string`; `regionStyle(span): CSSProperties`; `ROW_CLASS`; `regionKeys(template): string[]`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { regionKeys, regionStyle } from './templates'
import type { PageTemplate } from '@/types/metadata'

const sidebar: PageTemplate = {
  name: 'header-and-right-sidebar',
  columns: 12,
  rows: [{ regions: [{ name: 'HEADER', span: 12 }] }, { regions: [{ name: 'MAIN', span: 8 }, { name: 'RIGHT', span: 4 }] }]
}

describe('regionStyle', () => {
  // tailwind cannot build a class from a runtime number, so the width has to be inline
  it('turns a span into a flex share', () => {
    expect(regionStyle(8)).toEqual({ flexGrow: 8, flexBasis: 0 })
  })
})

describe('regionKeys', () => {
  it('flattens the rows in reading order', () => {
    expect(regionKeys(sidebar)).toEqual(['HEADER', 'MAIN', 'RIGHT'])
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && yarn test --run templates`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the types and the helper**

In `types/metadata.ts`:

```ts
export interface TemplateRegion {
  name: string
  span: number
}

export interface TemplateRow {
  regions: TemplateRegion[]
}

// the catalogue is the backend's. name and region names are keys the client translates.
export interface PageTemplate {
  name: string
  columns: number
  rows: TemplateRow[]
}

export type PageComponentType = 'PAGE' | 'REGION' | 'TABS' | 'TAB' | 'SECTION' | 'FORM' | 'MAP' | 'RELATED_LIST' | 'TEXT' | 'HISTORY' | 'WORKFLOW' | 'ACTION'
```

`PageComponent` gains `region?: string | null`. `Page` swaps `layout: PageLayout` for `template: PageTemplate`. `PagePayload` swaps `layout` for `template: string`. `PageLayout` stays — `SECTION`, `TAB` and `REGION` still use it.

Create `builder/templates.ts`:

```ts
import type { CSSProperties } from 'react'
import type { PageTemplate } from '@/types/metadata'

// the canvas, the renderer and the preview all lay a template's rows out. if they disagree the
// canvas shows a page the renderer never draws, so the geometry is written down exactly once.
export const ROW_CLASS = 'flex flex-col gap-3 lg:flex-row'

// tailwind cannot generate a width from a runtime value, so spans go through style, never a class
export function regionStyle(span: number): CSSProperties {
  return { flexGrow: span, flexBasis: 0 }
}

export function regionKeys(template: PageTemplate): string[] {
  return template.rows.flatMap((row) => row.regions.map((region) => region.name))
}
```

- [ ] **Step 4: Run the test and the build**

Run: `cd frontend && yarn test --run templates && yarn lint`
Expected: PASS. `tsc` will report errors wherever `page.layout` was read — Task 8 and Phase 3 fix them.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/metadata.ts frontend/src/features/pages/builder/templates.ts frontend/src/features/pages/builder/templates.test.ts
git commit -m "feat(pages): the wire types for templates, and the geometry everyone shares"
```

### Task 8: The renderer draws regions

**Files:**
- Modify: `frontend/src/components/page-renderer/PageRenderer.tsx`
- Test: `frontend/src/components/page-renderer/PageRenderer.test.tsx`

**Interfaces:**
- Consumes: `ROW_CLASS`, `regionStyle` from Task 7.

- [ ] **Step 1: Write the failing tests**

```tsx
it('lays the template rows out and draws what each region holds', () => {
  const tree = node('PAGE', {
    children: [
      node('REGION', { region: 'HEADER', children: [node('TEXT', { content: 'arriba' })] }),
      node('REGION', { region: 'MAIN', children: [node('FORM')] }),
      node('REGION', { region: 'RIGHT', children: [node('TEXT', { content: 'al lado' })] })
    ]
  })
  const page = pageOf(tree, {
    name: 'header-and-right-sidebar',
    columns: 12,
    rows: [{ regions: [{ name: 'HEADER', span: 12 }] }, { regions: [{ name: 'MAIN', span: 8 }, { name: 'RIGHT', span: 4 }] }]
  })

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  expect(screen.getByText('arriba')).toBeInTheDocument()
  expect(screen.getByText('al lado')).toBeInTheDocument()
})

// a span is inline, never a class: a class assertion would pass while tailwind emits no css
it('gives each region the share its span asks for', () => {
  const tree = node('PAGE', {
    children: [node('REGION', { region: 'MAIN', children: [node('TEXT', { content: 'ancho' })] }), node('REGION', { region: 'RIGHT' })]
  })
  const page = pageOf(tree, { name: 'main-and-right-sidebar', columns: 12, rows: [{ regions: [{ name: 'MAIN', span: 8 }, { name: 'RIGHT', span: 4 }] }] })

  const { container } = renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  const shares = [...container.querySelectorAll('[data-region]')].map((e) => (e as HTMLElement).style.flexGrow)
  expect(shares).toEqual(['8', '4'])
})

it('draws nothing for a region the template does not name', () => {
  const tree = node('PAGE', { children: [node('REGION', { region: 'MAIN' }), node('REGION', { region: 'LEFT', children: [node('TEXT', { content: 'fantasma' })] })] })
  const page = pageOf(tree, { name: 'one-region', columns: 12, rows: [{ regions: [{ name: 'MAIN', span: 12 }] }] })

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  expect(screen.queryByText('fantasma')).not.toBeInTheDocument()
})
```

Update the file's `node()` helper to accept `region`, and `pageOf(root, template)` to build a `Page` whose `definition` is `{ page: root }`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test --run PageRenderer`
Expected: FAIL — `PAGE` and `REGION` fall through to `default: return null`, so nothing renders.

- [ ] **Step 3: Give the renderer its two arms**

Replace the final return with a walk from the root, and add to the switch:

```tsx
      case 'PAGE': {
        // the template says how the regions sit; the tree says what is in them.
        return (
          <div key={key} className="space-y-3 p-8">
            {page.template.rows.map((row, position) => (
              <div key={position} className={ROW_CLASS}>
                {row.regions.map((slot) => {
                  const child = component.children.find((candidate) => candidate.region === slot.name)
                  // the server validates that the tree matches its template. a mismatch is a bug,
                  // and healing it here would hide one.
                  if (!child) return null
                  return (
                    <div key={slot.name} data-region={slot.name} style={regionStyle(slot.span)} className="min-w-0">
                      {renderComponent(child, -1)}
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        )
      }

      case 'REGION':
        return <div key={key} className="space-y-5">{body(component.children, component.layout, false)}</div>
```

and `return renderComponent(page.definition.page, 0)` at the bottom, replacing the old `body(page.definition.components, page.layout, true)`.

The `firstForm` and `draws` helpers now take the root node instead of a list — adjust their call sites.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && yarn test --run PageRenderer && yarn lint`
Expected: PASS.

- [ ] **Step 5: Commit and push the phase**

```bash
git add frontend/src/components/page-renderer/
git commit -m "feat(pages): the renderer lays a page out by its template"
git push
```

---

# Phase 3 — The canvas gains regions

### Task 9: The tree rules and the palette

**Files:**
- Modify: `frontend/src/features/pages/builder/pageTree.ts`
- Modify: `frontend/src/features/pages/builder/Palette.tsx`
- Test: `frontend/src/features/pages/builder/pageTree.test.ts`

**Interfaces:**
- Produces: `accepts` refusing all furniture; `CONTAINERS` including `PAGE` and `REGION`.

- [ ] **Step 1: Write the failing tests**

```ts
describe('accepts, with the scaffold', () => {
  it('lets a region hold anything a section could', () => {
    expect(accepts('REGION', 'FORM')).toBe(true)
    expect(accepts('REGION', 'SECTION')).toBe(true)
    expect(accepts('REGION', 'TABS')).toBe(true)
  })

  it('keeps a region out of every drop', () => {
    expect(accepts('PAGE', 'REGION')).toBe(false)
    expect(accepts('SECTION', 'REGION')).toBe(false)
    expect(accepts(null, 'REGION')).toBe(false)
  })

  it('keeps the page out of every drop', () => {
    expect(accepts(null, 'PAGE')).toBe(false)
    expect(accepts('REGION', 'PAGE')).toBe(false)
  })

  // the root is not a drop target any more: the page is the only thing that lives there
  it('lets nothing live beside the page', () => {
    expect(accepts(null, 'SECTION')).toBe(false)
    expect(accepts(null, 'FORM')).toBe(false)
  })

  it('lets only regions live in a page', () => {
    expect(accepts('PAGE', 'FORM')).toBe(false)
    expect(accepts('PAGE', 'SECTION')).toBe(false)
  })

  it('keeps a tab inside a strip, region or no region', () => {
    expect(accepts('REGION', 'TAB')).toBe(false)
    expect(accepts('TABS', 'TAB')).toBe(true)
  })
})
```

The existing test `lets any other container hold anything, and a leaf hold nothing` asserts `accepts(null, 'SECTION')` is **true** — that flips. Update it and say why in a comment.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test --run pageTree`
Expected: FAIL.

- [ ] **Step 3: Write the rules**

```ts
const CONTAINERS: PageComponentType[] = ['TABS', 'TAB', 'SECTION', 'PAGE', 'REGION']

// the page is the root the tree was built around, and its regions come from the template. both are
// furniture: no drop makes one, no drag moves one. inside a region the old rules stand -- a tab
// strip holds only tabs, a tab lives nowhere but a strip, everything else is free.
export function accepts(parent: PageComponentType | null, child: PageComponentType): boolean {
  // child side first: narrowing parent early makes tsc call a later parent check unreachable
  if (child === 'PAGE' || child === 'REGION') return false
  if (parent === null || parent === 'PAGE') return false
  if (child === 'TAB') return parent === 'TABS'
  if (parent === 'TABS') return false
  return isContainer(parent)
}
```

- [ ] **Step 4: Narrow the palette**

In `Palette.tsx`, the `ICONS: Record<PageComponentType, …>` will now fail `tsc` — that is the correct breakage. Fix it by narrowing the type, not by inventing icons:

```ts
// what an admin may drag. PAGE and REGION come from the template, so they have no icon.
type PaletteType = Exclude<PageComponentType, 'PAGE' | 'REGION'>

const ICONS: Record<PaletteType, ComponentType<{ className?: string }>> = { ... }
```

and type the three group arrays as `PaletteType[]`.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && yarn test --run pageTree && yarn lint`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/pages/builder/pageTree.ts frontend/src/features/pages/builder/pageTree.test.ts frontend/src/features/pages/builder/Palette.tsx
git commit -m "feat(pages): the page and its regions are furniture, not cargo"
```

### Task 10: The canvas draws the template

**Files:**
- Create: `frontend/src/features/pages/builder/CanvasRegion.tsx`
- Modify: `frontend/src/features/pages/builder/Canvas.tsx`
- Modify: `frontend/src/features/pages/PageBuilderPage.tsx`
- Test: `frontend/src/features/pages/builder/Canvas.test.tsx`

**Interfaces:**
- Consumes: `ROW_CLASS`/`regionStyle` (Task 7), `accepts` (Task 9).
- Produces: `CanvasRegion`; `Canvas` taking `template: PageTemplate` instead of `layout`.

- [ ] **Step 1: Write the failing `applyDrop` tests**

```tsx
const scaffold = () => [
  node('PAGE', [node('REGION', [], { region: 'MAIN' }), node('REGION', [], { region: 'RIGHT' })])
]

it('drops a palette item into the region it was aimed at', () => {
  const next = applyDrop(scaffold(), { active: 'palette:FORM', over: 'slot:0.1.0:1' })
  expect(nodeAt(next, [0, 1, 0])?.type).toBe('FORM')
})

it('refuses a drop beside the page', () => {
  const tree = scaffold()
  expect(applyDrop(tree, { active: 'palette:FORM', over: 'slot:1:1' })).toEqual(tree)
})

it('refuses a drop inside the page, beside a region', () => {
  const tree = scaffold()
  expect(applyDrop(tree, { active: 'palette:FORM', over: 'slot:0.2:1' })).toEqual(tree)
})

it('moves a node from one region to the other, keeping its id', () => {
  const tree = [node('PAGE', [node('REGION', [node('FORM')], { region: 'MAIN' }), node('REGION', [], { region: 'RIGHT' })])]
  const uid = nodeAt(tree, [0, 0, 0])!.uid
  const next = applyDrop(tree, { active: `node:${uid}`, over: 'slot:0.1.0:1' })
  expect(nodeAt(next, [0, 1, 0])?.uid).toBe(uid)
  expect(nodeAt(next, [0, 0])?.children).toHaveLength(0)
})
```

The `node()` factory in this file needs a third argument for extra fields.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test --run Canvas`
Expected: the refusal tests FAIL — until Task 9's `accepts` is in place they are accepted. (If Task 9 has landed, they pass already; say so in the report and keep them.)

- [ ] **Step 3: Write `CanvasRegion`**

```tsx
// one region. what it is NOT is the point: it cannot be dragged, selected or deleted, and it must
// not sit between a click and the node the click was aimed at -- that is how the click-vs-drag fix
// gets broken again.
export function CanvasRegion({ node, path, ...shared }: CanvasRegionProps) {
  const { t } = useTranslation()
  return (
    <div className="rounded-md border border-border bg-surface/40">
      <p className="px-3 pt-2 text-[11px] font-medium uppercase tracking-wide text-ink-muted">
        {t(`pages.regions.${node.region}`, { defaultValue: node.region ?? '' })}
      </p>
      <div className="p-2">
        <Slots entries={node.children.map((child, index) => ({ child, index }))} path={path} end={node.children.length} column={1} {...shared} />
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Rework `Canvas`**

Drop the `layout` prop for `template`. Delete the two-column root branch. Render:

```tsx
  const root = tree[0]
  if (!root || root.type !== 'PAGE') return null

  return (
    <div className="space-y-3">
      {template.rows.map((row, position) => (
        <div key={position} className={ROW_CLASS}>
          {row.regions.map((slot) => {
            // the template speaks keys, Slots and applyDrop speak index paths. bridge them here,
            // once, and pass the real index down.
            const index = root.children.findIndex((child) => child.region === slot.name)
            if (index === -1) return null
            return (
              <div key={slot.name} style={regionStyle(slot.span)} className="min-w-0">
                <CanvasRegion node={root.children[index]} path={[0, index]} {...shared} />
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
```

In `PageBuilderPage.tsx`, pass `template={draft.template}` and change `parentLayoutOf(tree, uid, draft.layout)` to seed from the root node's own layout.

- [ ] **Step 5: Add the DOM tests**

In `PageBuilderPage.test.tsx`: mounting a templated page renders each region's translated name; **no region is draggable** (every `[aria-roledescription="draggable"]` is a content node); clicking a leaf inside a region still selects it and the inspector shows it; an empty region shows the `pages.dropHere` placeholder.

- [ ] **Step 6: Run everything**

Run: `cd frontend && yarn lint && yarn test --run && yarn build`
Expected: PASS.

- [ ] **Step 7: Commit and push the phase**

```bash
git add frontend/src/features/pages/
git commit -m "feat(pages): the canvas lays its regions out by the template"
git push
```

---

# Phase 4 — Choosing and changing a template

Tasks 11, 12 and 13 touch disjoint new files and can be built in parallel. Task 14 consumes all three.

### Task 11: A dialog primitive

**Files:**
- Create: `frontend/src/components/ui/dialog.tsx`

**Interfaces:**
- Produces: `Dialog`, `DialogContent`, `DialogTitle`, `DialogDescription`.

- [ ] **Step 1: Write it**

Follow `frontend/src/components/ui/select.tsx` exactly — thin re-exports of the Radix primitives with `cn()`-merged classes. `DialogContent` wraps `Portal` + `Overlay` + `Content`, `max-w-3xl`, and a close button carrying `aria-label={t('common.close')}`.

Radix logs a console error when `Content` has no `Title` and no explicit `aria-describedby`, and that error will appear in every test that mounts it — wire both from the start.

- [ ] **Step 2: Check it compiles and nothing regressed**

Run: `cd frontend && yarn lint && yarn test --run`
Expected: PASS, suite unchanged.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ui/dialog.tsx
git commit -m "feat(ui): a dialog, the first in this repo"
```

### Task 12: The template preview

**Files:**
- Create: `frontend/src/features/pages/builder/preview/TemplatePreview.tsx`
- Create: `frontend/src/features/pages/builder/preview/TemplatePreview.test.tsx`

**Interfaces:**
- Consumes: `regionStyle`, `ROW_CLASS` (Task 7).
- Produces: `<TemplatePreview template labels? highlight? className? />`.

- [ ] **Step 1: Write the failing test**

```tsx
it('draws one box per region, in reading order', () => {
  const { container } = render(<TemplatePreview template={sidebar} labels />)
  expect([...container.querySelectorAll('[data-region]')].map((e) => e.getAttribute('data-region'))).toEqual(['HEADER', 'MAIN', 'RIGHT'])
})

// inline, never a class: tailwind emits no css for a runtime width, so a class assertion lies
it('gives each box the share its span asks for', () => {
  const { container } = render(<TemplatePreview template={sidebar} />)
  const shares = [...container.querySelectorAll('[data-region]')].map((e) => (e as HTMLElement).style.flexGrow)
  expect(shares).toEqual(['12', '8', '4'])
})

it('falls back to the key when it has no word for a region', () => {
  render(<TemplatePreview template={{ name: 'x', columns: 12, rows: [{ regions: [{ name: 'NUEVA', span: 12 }] }] }} labels />)
  expect(screen.getByText('NUEVA')).toBeInTheDocument()
})

// it goes inside a button, so it must contain nothing focusable
it('renders no interactive element at all', () => {
  const { container } = render(<TemplatePreview template={sidebar} labels />)
  expect(container.querySelectorAll('button, a, input, select, textarea, [tabindex]')).toHaveLength(0)
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && yarn test --run TemplatePreview`
Expected: FAIL — module not found.

- [ ] **Step 3: Write it**

Rows with `space-y-1`; each row `flex gap-1` with `ROW_CLASS`-free simple flex (a preview never stacks); each region a `div` with `data-region`, `style={regionStyle(span)}`, classes `rounded border border-dashed border-border bg-surface-muted`, and when `labels` the translated name in `text-[10px] text-ink-muted`. `highlight` swaps the border for `border-brand bg-brand-soft`.

- [ ] **Step 4: Run the test**

Run: `cd frontend && yarn test --run TemplatePreview && yarn lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pages/builder/preview/TemplatePreview.tsx frontend/src/features/pages/builder/preview/TemplatePreview.test.tsx
git commit -m "feat(pages): draw a template from its own regions"
```

### Task 13: Moving a tree to another template

**Files:**
- Create: `frontend/src/features/pages/builder/retemplate.ts`
- Create: `frontend/src/features/pages/builder/retemplate.test.ts`

**Interfaces:**
- Consumes: `Node` from `pageTree.ts`, `regionKeys` from `templates.ts`.
- Produces: `orphans(tree, to): { region: string; count: number }[]`, `retemplate(tree, to, moves: Record<string, string>): Node[]`.

- [ ] **Step 1: Write the failing tests**

One test per rule: regions rebuilt in the new template's order; a key both templates have keeps its children **and its uid**; a dying region's children are appended to the region `moves` names; two dying regions aimed at one survivor append in source-template order; a new key starts empty; a moved child keeps its uid with `column` clamped to 1; the PAGE node keeps its uid; `orphans` ignores an empty dying region; and `retemplate` with a `moves` naming a region the new template has not got **returns the tree unchanged** — refuse, do not throw, the same habit as `applyDrop`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test --run retemplate`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the module**

Pure. No DOM, no React, no i18n.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && yarn test --run retemplate && yarn lint`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pages/builder/retemplate.ts frontend/src/features/pages/builder/retemplate.test.ts
git commit -m "feat(pages): move a tree to another template without losing anything"
```

### Task 14: The picker, wired

**Files:**
- Create: `frontend/src/features/pages/builder/TemplateDialog.tsx`
- Modify: `frontend/src/features/pages/PageBuilderPage.tsx`
- Modify: `frontend/src/lib/queries.ts`
- Modify: `frontend/src/locales/es/common.json`, `frontend/src/locales/en/common.json`
- Test: `frontend/src/features/pages/PageBuilderPage.test.tsx`

**Interfaces:**
- Consumes: Tasks 11, 12, 13.
- Produces: `useTemplates()` querying `/metadata/page-templates`.

- [ ] **Step 1: Add the locale keys**

Under `pages` in **both** files, keeping them structurally identical: `template`, `changeTemplate`, `templatePicker.{title,hint,regions,next,back,apply,moveTitle,moveHint,moveTo,moveCount}`, `regions.{HEADER,MAIN,LEFT,CENTER,RIGHT}`, `templates.<each of the nine>.{label,description}`, `types.PAGE`, `types.REGION`, and `common.close`.

**Also change `pages.confirmReset`**: resetting now throws the template away too, and silently widening what a confirmation covers is the kind of thing that bites.

Remove the page-level `pages.layout` usage from the settings card but **keep** `pages.layouts.*` — the inspector still uses them for `SECTION`, `TAB` and `REGION`.

- [ ] **Step 2: Write the dialog**

Two steps. Step one: the template list on the left as `<button type="button" aria-pressed>` rows each holding a small `TemplatePreview`, and on the right a big `TemplatePreview` plus the description and the region names. The footer reads `Apply` when `orphans(tree, picked)` is empty and `Next` when it is not.

Step two, per orphaned region: its name, its count, and a `Select` of the surviving regions, beside a `TemplatePreview` of the target with the chosen destination highlighted.

**Pre-select every destination** the moment `picked` changes. Radix `Select` does not fire `onValueChange` when the user re-picks the value already shown — the same trap that makes `blank()` pre-fill an `ACTION`'s kind. Without this, an admin who agrees with the default and clicks Apply sends a `moves` that `retemplate` refuses.

**No draggable ever renders inside the dialog** — a comment should say so. The trigger sits above `DndProvider`, so a `useDraggable` in there would be the defect this project already shipped once.

- [ ] **Step 3: Wire the settings card**

Replace the page-level Layout select with a template summary: the translated name, a small `TemplatePreview`, and a `Change` button opening the dialog. On apply: `setTree(next)` and `setDraft({ ...draft, template })`. **Nothing is saved** — the existing Save button commits it.

- [ ] **Step 4: Write the tests**

Mock `@/components/ui/dialog` with a double that renders children inline when `open`, the same trick the file already uses for `@/components/ui/select`. Then pin: choosing a template that drops a populated region asks where its components go; applying moves them; Save sends one `PAGE`, the new region set, the moved component inside the chosen region, and no `uid` on the wire.

**Generalise the existing context test**: it asserts over `button[aria-roledescription="draggable"]` with a hardcoded count. Change it to assert that **every** `[aria-roledescription="draggable"]` in the document has a non-empty `aria-describedby`. That is the fingerprint of a draggable outside its `DndContext`, and regions change who renders what inside the provider.

- [ ] **Step 5: Run everything**

Run: `cd frontend && yarn lint && yarn test --run && yarn build`
Expected: PASS.

- [ ] **Step 6: Commit and push the phase**

```bash
git add frontend/src/
git commit -m "feat(pages): pick a template, and say where the orphans go"
git push
```

---

# Phase 5 — Close

### Task 15: Write it down

**Files:**
- Create: `docs/adr/0022-a-page-has-a-template.md`
- Modify: `docs/api/rest.md`, `docs/HISTORY.md`

- [ ] **Step 1: Write ADR-0022**

Read `docs/adr/0021-a-page-is-a-tree.md` first and match its shape and voice. Head it `**Status**: accepted · 2026-09-19 · amends ADR-021` — the house convention is to amend with a new ADR, never to edit the old one.

It must record: that the tree stops being free at the first level and why the user chose that; that the scaffold is top-level only and the tree inside a region is untouched; that the catalogue is code, not a table; that `layout` leaves the page row because the template supersedes it; that `MAX_DEPTH` rose to 12 so the free tree keeps its ten; and — in its own paragraph — that **V11 discards stored pages** rather than migrating them.

Also record the trade the user accepted knowingly: **the server cannot tell "moved" from "deleted"**. The client performs the move and sends a valid tree, so a buggy client that simply drops a vanishing region's components produces a payload the server accepts. There is no server-side safety net and, given the decision, cannot be one.

- [ ] **Step 2: Update the API docs**

`docs/api/rest.md`'s page section: the `definition` example is wrong end to end. Replace it with the `page`/`region` shape, document `template` on create and update, list the new refusals, and add `GET /api/metadata/page-templates`.

- [ ] **Step 3: Add the history entry**

One entry covering: the root node, the catalogue, regions as fixed scaffolding, the change-template flow, and that stored pages were discarded — naming that a `FORM` component's binding to a **named stored form** is lost with them, even though the forms themselves survive.

- [ ] **Step 4: Full verification**

```bash
./gradlew :backend:ktlintCheck :backend:test
SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest
cd frontend && yarn lint && yarn test --run && yarn build
```

Expected: 0 failures. Never run the two Gradle commands at once.

- [ ] **Step 5: Commit and push**

```bash
git add docs/
git commit -m "docs(pages): record that a page has a template"
git push
```
