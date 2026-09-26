# WYSIWYG Page Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a record detail page from a flat component list into a free component tree, and give the admin a drag-and-drop canvas to build it.

**Architecture:** `PageComponent` gains `children` and `layout` and loses `tab`, so containers (`TABS`, `TAB`, `SECTION`) become real nodes instead of strings grouped at render time. Validation, the renderer and the editor all become recursive. The canvas draws real structure with metadata-built mocks inside each leaf, and every tree operation lives in one pure, tested module.

**Tech Stack:** Kotlin 2.4.20 · Spring Boot 4.1 WebFlux · Flyway/Postgres 18 · React 19.3 · Vite · Tailwind · `@dnd-kit` · vitest · JUnit 5 + WebTestClient

**Spec:** `docs/superpowers/specs/2026-09-19-wysiwyg-page-editor-design.md`

## Global Constraints

- Code, identifiers and comments in **English**. Comments caveman style: short, blunt, say *why*.
- Formatting per `.editorconfig` (Kotlin 4 spaces, TS 2, max 160 cols), enforced by ktlint.
- **JPA / Hibernate / Envers are forbidden.** R2DBC + `DatabaseClient` only.
- Modules talk through ports and events, never another module's repositories.
- Change history goes in `docs/HISTORY.md`, never in `CLAUDE.md`.
- Architectural decisions go in `docs/adr/`. Next free number is **0021**.
- Depth bound **10**, node bound **200** (spec D6). Both are guard rails, not features.
- Push after each completed phase with a descriptive commit.
- **Never run a Gradle compile while the integration suite is running** — it rewrites `build/classes` under the test JVM and produces bogus failures.

**Verification commands:**

```bash
./gradlew :backend:ktlintCheck :backend:test
SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest
cd frontend && yarn lint && yarn test && yarn build
```

## File Structure

**Backend**

| File | Responsibility |
|---|---|
| `backend/src/main/kotlin/com/sapgis/data/WorkflowStates.kt` | port; gains `transitionNames` |
| `backend/src/main/kotlin/com/sapgis/workflow/WorkflowStatesAdapter.kt` | implements it |
| `backend/src/main/kotlin/com/sapgis/pages/Page.kt` | the tree model, container types, action enums |
| `backend/src/main/kotlin/com/sapgis/pages/PageService.kt` | recursive validation, tree-emitting `generate()` |
| `backend/src/main/resources/db/migration/V10__a_page_is_a_tree.sql` | one-way jsonb rewrite |
| `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt` | the whole contract |

**Frontend**

| File | Responsibility |
|---|---|
| `frontend/src/types/metadata.ts` | mirrors the wire model |
| `frontend/src/components/page-renderer/PageRenderer.tsx` | recursive walk |
| `frontend/src/components/page-renderer/pageTabs.ts` + test | **deleted** |
| `frontend/src/components/page-renderer/ActionButton.tsx` | the `ACTION` component |
| `frontend/src/features/pages/builder/pageTree.ts` | every tree operation. pure, no React |
| `frontend/src/features/pages/builder/Palette.tsx` | draggable sources |
| `frontend/src/features/pages/builder/Canvas.tsx` | walks the tree, places drop zones |
| `frontend/src/features/pages/builder/CanvasNode.tsx` | one node's chrome |
| `frontend/src/features/pages/builder/Inspector.tsx` | the selected node's settings |
| `frontend/src/features/pages/builder/preview/*.tsx` | one mock per leaf type |
| `frontend/src/features/pages/PageBuilderPage.tsx` | three panes, save/reset |

---

# Phase 0 — The dependency gate

### Task 1: Prove `@dnd-kit` works under React 19.3 StrictMode

Throwaway. Spec D5 says: if this fails, the decision reopens before anything is built on it.

**Files:**
- Create: `frontend/src/features/pages/builder/dndSmoke.test.tsx` (deleted in Step 5)
- Modify: `frontend/package.json`

**Interfaces:**
- Consumes: nothing.
- Produces: a go/no-go answer. No code survives this task.

- [ ] **Step 1: Install the three packages**

```bash
cd frontend && yarn add @dnd-kit/core@6.3.1 @dnd-kit/sortable@10.0.0 @dnd-kit/modifiers@9.0.0
```

- [ ] **Step 2: Write a smoke test that mounts a draggable and a droppable inside StrictMode**

Create `frontend/src/features/pages/builder/dndSmoke.test.tsx`:

```tsx
import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext, useDraggable, useDroppable } from '@dnd-kit/core'
import { expect, it } from 'vitest'

function Draggable() {
  const { attributes, listeners, setNodeRef } = useDraggable({ id: 'form' })
  return (
    <button ref={setNodeRef} {...listeners} {...attributes}>
      form
    </button>
  )
}

function Droppable() {
  const { setNodeRef, isOver } = useDroppable({ id: 'root' })
  return (
    <div ref={setNodeRef} data-testid="root">
      {isOver ? 'over' : 'idle'}
    </div>
  )
}

// StrictMode double-invokes effects. a registry that does not survive that is unusable here.
it('registers a draggable and a droppable under StrictMode without throwing', () => {
  render(
    <StrictMode>
      <DndContext>
        <Draggable />
        <Droppable />
      </DndContext>
    </StrictMode>
  )

  expect(screen.getByRole('button', { name: 'form' })).toBeInTheDocument()
  expect(screen.getByTestId('root')).toHaveTextContent('idle')
})
```

- [ ] **Step 3: Run it**

Run: `cd frontend && yarn test dndSmoke`
Expected: PASS, and **no** console error mentioning `useLayoutEffect`, `act`, or a React version warning.

**If it fails or warns:** stop the plan. Report the exact output and reopen spec D5. Do not continue to Task 2.

- [ ] **Step 4: Check the build accepts the new dependencies**

Run: `cd frontend && yarn lint && yarn build`
Expected: both PASS.

- [ ] **Step 5: Delete the smoke test and commit the dependencies**

```bash
cd /Users/jorge/IdeaProjects/sapgis
rm frontend/src/features/pages/builder/dndSmoke.test.tsx
git add frontend/package.json frontend/yarn.lock
git commit -m "build(frontend): add dnd-kit for the page builder canvas"
```

---

# Phase 1 — The tree

Phase 1 is one shippable unit: the shape of `definition` is a contract between the server, the renderer and the editor, and moving one without the others leaves stored pages rendering broken. Commit per task, push at the end of the phase.

### Task 2: The workflow port learns to name transitions

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/data/WorkflowStates.kt`
- Modify: `backend/src/main/kotlin/com/sapgis/workflow/WorkflowStatesAdapter.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/WorkflowApiTest.kt` (exercised indirectly in Task 6; this task ships the port)

**Interfaces:**
- Consumes: `WorkflowRepository.findByObject(organizationId, objectId): Workflow?`, `WorkflowDefinition.transitions: List<WorkflowTransition>` with `.name`.
- Produces: `suspend fun WorkflowStates.transitionNames(organizationId: UUID, objectId: UUID): Set<String>` — used by `PageService` in Task 6.

- [ ] **Step 1: Add the method to the port**

In `WorkflowStates.kt`, inside the interface, after `stateOf`:

```kotlin
    // pages validate an ACTION against these. the record path never asks.
    suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String>
```

Leave `ObjectWorkflowState` untouched. Its comment — *"what the record path needs to know about an object's workflow. nothing more"* — stays true only if transitions do not move into it.

- [ ] **Step 2: Implement it in the adapter**

In `WorkflowStatesAdapter.kt`, after `stateOf`:

```kotlin
    override suspend fun transitionNames(
        organizationId: UUID,
        objectId: UUID
    ): Set<String> {
        // no workflow and a disabled one are the same answer: nothing can be fired.
        val workflow = workflows.findByObject(organizationId, objectId) ?: return emptySet()
        if (!workflow.enabled) return emptySet()
        return workflow.definition.transitions.map { it.name }.toSet()
    }
```

- [ ] **Step 3: Find every other implementor and make it compile**

Run: `cd /Users/jorge/IdeaProjects/sapgis && grep -rn ": WorkflowStates\|WorkflowStates {" backend/src --include=*.kt`

For each test double found, add the same override returning `emptySet()`.

- [ ] **Step 4: Compile and lint**

Run: `./gradlew :backend:ktlintCheck :backend:compileKotlin :backend:compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/data/WorkflowStates.kt backend/src/main/kotlin/com/sapgis/workflow/WorkflowStatesAdapter.kt
git commit -m "feat(workflow): let the port name an object's transitions"
```

### Task 3: A component has children

The model change plus faithful round-tripping. No new rules yet — those are Tasks 4-6.

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/Page.kt`
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Produces: `ComponentType.{TABS,TAB,SECTION,ACTION}`, `ActionKind.{TRANSITION,NAVIGATE}`, `ActionStyle.{PRIMARY,SECONDARY}`, `PageComponent.children: List<PageComponent>`, `PageComponent.layout: PageLayout`, `PageComponent.geometry/action/transition/target/url/style`. `PageComponentRequest` mirrors all of them with `children: List<PageComponentRequest>`.

- [ ] **Step 1: Write the failing test — a tree round-trips**

Add to `PageApiTest.kt`:

```kotlin
    @Test
    fun `a page keeps the tree it was given`() {
        val name = uniqueName("page").take(30)
        createPage(
            name,
            predio,
            "single-column",
            listOf(tabs(tab("Detalles", listOf(section("Datos", listOf(form(1, null))))), tab("Mapa", listOf(map(1)))))
        ).expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].type")
            .isEqualTo("TABS")
            .jsonPath("$.definition.components[0].children[0].type")
            .isEqualTo("TAB")
            .jsonPath("$.definition.components[0].children[0].title")
            .isEqualTo("Detalles")
            .jsonPath("$.definition.components[0].children[0].children[0].type")
            .isEqualTo("SECTION")
            .jsonPath("$.definition.components[0].children[0].children[0].children[0].type")
            .isEqualTo("FORM")
            .jsonPath("$.definition.components[0].children[1].children[0].type")
            .isEqualTo("MAP")
    }

    // an update that omits the definition revalidates the stored one through toRequest(). the old
    // one mapped seven of eight fields, so a plain label change silently untabbed the whole page.
    @Test
    fun `renaming a page leaves its tree alone`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "single-column", listOf(tabs(tab("Detalles", listOf(form(1, null))))))
            .expectStatus()
            .isCreated

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
            .jsonPath("$.definition.components[0].type")
            .isEqualTo("TABS")
            .jsonPath("$.definition.components[0].children[0].title")
            .isEqualTo("Detalles")
            .jsonPath("$.definition.components[0].children[0].children[0].type")
            .isEqualTo("FORM")
    }
```

Add these helpers beside the existing `form`/`map`/`text`:

```kotlin
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest.a page keeps the tree it was given*'`
Expected: FAIL — `Unknown component 'TABS'`.

- [ ] **Step 3: Add the types to `Page.kt`**

Replace the `ComponentType` enum with:

```kotlin
enum class ComponentType {
    // containers. they hold children and draw nothing of their own.
    TABS,
    TAB,
    SECTION,

    FORM,
    MAP,
    RELATED_LIST,
    TEXT,

    // the record's audit trail
    HISTORY,

    // the record's workflow state and the transitions open to the caller
    WORKFLOW,

    // a button: fires a transition, or goes somewhere
    ACTION;

    val container: Boolean get() = this == TABS || this == TAB || this == SECTION

    companion object {
        fun parse(raw: String): ComponentType =
            entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ValidationException(
                    "Unknown component '$raw'",
                    "components",
                    "must be one of ${entries.joinToString(", ") { it.name }}"
                )
    }
}

enum class ActionKind {
    // apply a named workflow transition to this record
    TRANSITION,

    // go to an object's records, or out to a url
    NAVIGATE;

    companion object {
        fun parse(raw: String): ActionKind =
            entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ValidationException("Unknown action '$raw'", "components", "action must be one of ${entries.joinToString(", ")}")
    }
}

enum class ActionStyle {
    PRIMARY,
    SECONDARY;

    companion object {
        fun parse(raw: String?): ActionStyle =
            if (raw.isNullOrBlank()) {
                SECONDARY
            } else {
                entries.firstOrNull { it.name == raw.uppercase() }
                    ?: throw ValidationException("Unknown style '$raw'", "components", "style must be one of ${entries.joinToString(", ")}")
            }
    }
}
```

Replace `PageComponent` with:

```kotlin
// what the UI renders. everything a component needs travels with it, children included.
data class PageComponent(
    val type: ComponentType,
    // which column of the PARENT container holds it
    val column: Int = 1,
    val title: String? = null,
    // container: how it lays its own children out. a leaf ignores it.
    val layout: PageLayout = PageLayout.SINGLE_COLUMN,
    val children: List<PageComponent> = emptyList(),
    // RELATED_LIST: which relationship to follow
    val relationship: String? = null,
    // FORM: a subset of the object's fields, in order. null means all of them.
    val fields: List<String>? = null,
    // FORM: a stored form of the object, rendered with its sections. excludes fields.
    val form: String? = null,
    // MAP: one geometry field. null draws every one the object has.
    val geometry: String? = null,
    // TEXT: the note to show
    val content: String? = null,
    // ACTION
    val action: ActionKind? = null,
    val transition: String? = null,
    val target: String? = null,
    val url: String? = null,
    val style: ActionStyle? = null
)
```

Delete the `tab` field. Keep `GeneratedTab` exactly as it is — its constants are now `TAB` titles.

- [ ] **Step 4: Mirror it on the request side and fix `toRequest()`**

In `PageService.kt`, replace `PageComponentRequest`:

```kotlin
data class PageComponentRequest(
    val type: String,
    val column: Int = 1,
    val title: String? = null,
    val layout: String? = null,
    val children: List<PageComponentRequest> = emptyList(),
    val relationship: String? = null,
    val fields: List<String>? = null,
    val form: String? = null,
    val geometry: String? = null,
    val content: String? = null,
    val action: String? = null,
    val transition: String? = null,
    val target: String? = null,
    val url: String? = null,
    val style: String? = null
)
```

Replace the file-bottom `toRequest()` — the old one mapped seven of eight fields and silently dropped `tab` on every update that omitted a definition:

```kotlin
// an update that omits the definition revalidates the stored one through here, so every field
// has to survive the trip. one that does not is lost on a plain label change.
private fun PageDefinition.toRequest(): PageDefinitionRequest = PageDefinitionRequest(components.map { it.toRequest() })

private fun PageComponent.toRequest(): PageComponentRequest =
    PageComponentRequest(
        type = type.name,
        column = column,
        title = title,
        layout = layout.value,
        children = children.map { it.toRequest() },
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

- [ ] **Step 5: Make `validated()` carry children through**

In `PageService.validated()`, extract the per-component body into a local function and recurse. Replace the whole method body with:

```kotlin
    private suspend fun validated(
        request: PageDefinitionRequest,
        layout: PageLayout,
        definition: ObjectDefinition
    ): PageDefinition {
        val fieldNames = definition.fields.map { it.name }.toSet()
        val relationshipNames = relationships.forObject(definition.obj.name).map { it.relationship.name }.toSet()
        val all = flatten(request.components)
        // only pay for the lookup when a component actually points at a form
        val formNames = if (all.any { !it.form.isNullOrBlank() }) forms.storedNames(definition.obj.id) else emptySet()

        fun walk(
            components: List<PageComponentRequest>,
            parentLayout: PageLayout
        ): List<PageComponent> =
            components.map { component ->
                val type = ComponentType.parse(component.type)
                val own = PageLayout.parse(component.layout)
                check(component, type, parentLayout, definition, fieldNames, relationshipNames, formNames)
                PageComponent(
                    type = type,
                    column = component.column,
                    title = component.title?.trim()?.ifBlank { null },
                    layout = own,
                    children = walk(component.children, own),
                    relationship = component.relationship?.trim()?.ifBlank { null },
                    fields = component.fields,
                    form = component.form?.trim()?.ifBlank { null },
                    geometry = component.geometry?.trim()?.ifBlank { null },
                    content = component.content,
                    action = component.action?.let { ActionKind.parse(it) },
                    transition = component.transition?.trim()?.ifBlank { null },
                    target = component.target?.trim()?.ifBlank { null },
                    url = component.url?.trim()?.ifBlank { null },
                    style = component.action?.let { ActionStyle.parse(component.style) }
                )
            }

        return PageDefinition(walk(request.components, layout))
    }

    // bounded by the checks in Task 4, so this never runs away
    private fun flatten(components: List<PageComponentRequest>): List<PageComponentRequest> =
        components.flatMap { listOf(it) + flatten(it.children) }
```

Move the existing `when (type) { … }` body into a private `check(...)` function with this signature, keeping every current rule verbatim for now (`RELATED_LIST` relationship, `FORM` form-xor-fields, `TEXT` content, `MAP` on a spatial object, `HISTORY`/`WORKFLOW` nothing), plus the existing column check but against `parentLayout`:

```kotlin
    private fun check(
        component: PageComponentRequest,
        type: ComponentType,
        parentLayout: PageLayout,
        definition: ObjectDefinition,
        fieldNames: Set<String>,
        relationshipNames: Set<String>,
        formNames: Set<String>
    ) {
        if (component.column < 1 || component.column > parentLayout.columns) {
            throw ValidationException(
                "Component ${type.name} sits in column ${component.column}",
                "components",
                "column must be between 1 and ${parentLayout.columns} for layout ${parentLayout.value}"
            )
        }
        // … the existing when(type) block, unchanged except TABS/TAB/SECTION/ACTION -> Unit for now
    }
```

Delete the `MAX_TAB` constant and the tab-length check.

- [ ] **Step 6: Run the new test**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: the new test PASSES. Tests asserting on `tab` FAIL — they are rewritten in Task 7. Note which ones; do not fix them here.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/pages/ backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): a component has children, and toRequest stops losing fields"
```

### Task 4: The guard rails and the structural rules

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: `flatten`, `check`, `walk` from Task 3.
- Produces: `PageService.MAX_DEPTH = 10`, `MAX_COMPONENTS = 200`.

- [ ] **Step 1: Write the failing tests**

Add to `PageApiTest.kt`:

```kotlin
    @Test
    fun `a tree nested past the bound is refused`() {
        // 11 nested sections: one past the pretil
        var node = section("hondo", listOf(form(1, null)))
        repeat(10) { node = section("hondo", listOf(node)) }

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(node))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a page with more components than the bound is refused`() {
        val many = (1..201).map { text(1, "nota $it") }

        createPage(uniqueName("page").take(30), predio, "single-column", many)
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a tab strip holding something that is not a tab is refused`() {
        createPage(uniqueName("page").take(30), predio, "single-column", listOf(tabs(form(1, null))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a tab outside a strip is refused`() {
        createPage(uniqueName("page").take(30), predio, "single-column", listOf(tab("suelta", listOf(form(1, null)))))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `a leaf carrying children is refused`() {
        val formWithChildren = mapOf("type" to "FORM", "column" to 1, "children" to listOf(text(1, "dentro")))

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(formWithChildren))
            .expectStatus()
            .isBadRequest
    }

    // the whole point of a per-container layout: the page is narrow, the section is not
    @Test
    fun `a second column inside a two-column section on a single-column page is accepted`() {
        val name = uniqueName("page").take(30)
        val wide = section("Datos", listOf(form(1, null), map(2)), layout = "two-column")

        createPage(name, predio, "single-column", listOf(wide))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].layout")
            .isEqualTo("two-column")
            .jsonPath("$.definition.components[0].children[1].column")
            .isEqualTo(2)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: all six FAIL (the first five return 201 instead of 400; the sixth already passes if Task 3 was done right — confirm it does).

- [ ] **Step 3: Add the depth and count pass**

In `PageService.validated()`, before computing `fieldNames`, insert:

```kotlin
        // a free tree is free by design and bounded by defence. without this a hand-written
        // definition blows this validator's own stack before postgres ever sees it.
        inspect(request.components, depth = 1)
```

Add these private methods:

```kotlin
    private fun inspect(
        components: List<PageComponentRequest>,
        depth: Int
    ): Int {
        if (components.isNotEmpty() && depth > MAX_DEPTH) {
            throw ValidationException("Components nested $depth deep", "components", "nesting must be at most $MAX_DEPTH deep")
        }
        var count = components.size
        components.forEach { count += inspect(it.children, depth + 1) }
        if (depth == 1 && count > MAX_COMPONENTS) {
            throw ValidationException("Page holds $count components", "components", "a page holds at most $MAX_COMPONENTS components")
        }
        return count
    }
```

Add to the companion object, replacing `MAX_TAB`:

```kotlin
        private const val MAX_DEPTH = 10
        private const val MAX_COMPONENTS = 200
```

- [ ] **Step 4: Add the structural rules**

`walk` currently ignores the parent's type. Change its signature to carry it, and pass it into `check`:

```kotlin
        fun walk(
            components: List<PageComponentRequest>,
            parentLayout: PageLayout,
            parent: ComponentType?
        ): List<PageComponent> =
```

with the recursive call becoming `children = walk(component.children, own, type)` and the top-level call `walk(request.components, layout, null)`.

In `check`, add `parent: ComponentType?` as a parameter and these rules at the top, before the column check:

```kotlin
        // a tab strip whose children are not tabs means nothing, and a tab lives nowhere else.
        // this is a rule about what the types mean, not a limit on how the tree may be shaped.
        if (parent == ComponentType.TABS && type != ComponentType.TAB) {
            throw ValidationException("A tab strip holds ${type.name}", "components", "TABS accepts only TAB children")
        }
        if (type == ComponentType.TAB && parent != ComponentType.TABS) {
            throw ValidationException("A TAB sits outside a TABS", "components", "TAB must be a child of TABS")
        }
        if (!type.container && component.children.isNotEmpty()) {
            throw ValidationException("${type.name} carries children", "components", "only TABS, TAB and SECTION hold children")
        }
```

- [ ] **Step 5: Run the tests**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: all six new tests PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew :backend:ktlintCheck
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): bound the tree, and say what a container may hold"
```

### Task 5: A map can finally name its geometry

Closes the defect: `PageRenderer.tsx:91` reads `component.geometry` and the server never had it.

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: `ObjectDefinition.geometryFields` (already imported in `PageService`), `CustomField.name`.
- Produces: `MAP.geometry` stored and returned.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `a map may name one of the object's geometries`() {
        val name = uniqueName("page").take(30)
        val targeted = mapOf("type" to "MAP", "column" to 1, "geometry" to "geom")

        createPage(name, predio, "single-column", listOf(targeted))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].geometry")
            .isEqualTo("geom")
    }

    @Test
    fun `a map naming a geometry the object does not have is refused`() {
        val targeted = mapOf("type" to "MAP", "column" to 1, "geometry" to "no_existe")

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(targeted))
            .expectStatus()
            .isBadRequest
    }
```

The `predio` fixture's geometry field is named `geom` — `PageApiTest.kt:477` names it literally when `geometryType` is non-null. No discovery needed.

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*map*'`
Expected: first FAILS (`geometry` absent from the response), second FAILS (201 instead of 400).

- [ ] **Step 3: Validate it**

In `check`, replace the `ComponentType.MAP ->` branch:

```kotlin
                    ComponentType.MAP -> {
                        if (definition.geometryFields.isEmpty()) {
                            throw ValidationException(
                                "MAP on a non-spatial object",
                                "components",
                                "'${definition.obj.name}' has no geometry"
                            )
                        }
                        // naming none draws them all, which is what a one-shape object wants
                        val geometry = component.geometry?.trim()?.ifBlank { null }
                        if (geometry != null && definition.geometryFields.none { it.name == geometry }) {
                            throw ValidationException(
                                "Unknown geometry '$geometry'",
                                "components",
                                "'${definition.obj.name}' has no geometry field '$geometry'"
                            )
                        }
                    }
```

`definition` is already a parameter of `check` from Task 3.

- [ ] **Step 4: Run the tests**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*map*'`
Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "fix(pages): a map component can name its geometry, and the server keeps it"
```

### Task 6: The Action component

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: `WorkflowStates.transitionNames` (Task 2), `MetadataService.listObjects()` returning items with `.name`.
- Produces: validated `ACTION` components.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `an action firing a transition the object has no workflow for is refused`() {
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "aprobar", "title" to "Aprobar")

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(action))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action firing a transition the workflow does have is kept`() {
        val name = uniqueName("page").take(30)
        attachWorkflow(predio)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "approve", "title" to "Aprobar")

        createPage(name, predio, "single-column", listOf(action))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].transition")
            .isEqualTo("approve")
    }

    @Test
    fun `an action firing a transition the workflow does not have is refused`() {
        attachWorkflow(predio)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "TRANSITION", "transition" to "rechazar", "title" to "Rechazar")

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(action))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action that navigates needs exactly one destination`() {
        val neither = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ir")
        val both = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ir", "target" to nota, "url" to "https://x.test")

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(neither))
            .expectStatus()
            .isBadRequest
        createPage(uniqueName("page").take(30), predio, "single-column", listOf(both))
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `an action that navigates to another object is kept`() {
        val name = uniqueName("page").take(30)
        val action = mapOf("type" to "ACTION", "column" to 1, "action" to "NAVIGATE", "title" to "Ver notas", "target" to nota, "style" to "PRIMARY")

        createPage(name, predio, "single-column", listOf(action))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].action")
            .isEqualTo("NAVIGATE")
            .jsonPath("$.definition.components[0].target")
            .isEqualTo(nota)
            .jsonPath("$.definition.components[0].style")
            .isEqualTo("PRIMARY")
    }

    @Test
    fun `an action with no kind is refused`() {
        val action = mapOf("type" to "ACTION", "column" to 1, "title" to "Nada")

        createPage(uniqueName("page").take(30), predio, "single-column", listOf(action))
            .expectStatus()
            .isBadRequest
    }
```

Add the helper. The payload shape is copied from `WorkflowApiTest.approvalDefinition()` and
`putWorkflow()` — read `backend/src/test/kotlin/com/sapgis/api/WorkflowApiTest.kt:492-528` and keep
the two in step if it has moved:

```kotlin
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
```

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*action*'`
Expected: the four refusal tests FAIL with 201, and `an action firing a transition the workflow does have is kept` FAILS because `transition` is absent from the response.

- [ ] **Step 3: Hoist the two lookups an Action needs**

In `validated()`, beside `formNames`:

```kotlin
        val hasAction = all.any { !it.action.isNullOrBlank() }
        val transitions = if (hasAction) workflows.transitionNames(definition.obj.organizationId, definition.obj.id) else emptySet()
        val objectNames = if (hasAction) metadata.listObjects().map { it.name }.toSet() else emptySet()
```

Pass `transitions` and `objectNames` into `check`.

- [ ] **Step 4: Validate the Action**

In `check`'s `when (type)`, replace the `ACTION` placeholder:

```kotlin
                    ComponentType.ACTION -> {
                        val kind =
                            component.action?.trim().orEmpty().ifBlank {
                                throw ValidationException("ACTION needs a kind", "components", "action must be TRANSITION or NAVIGATE")
                            }
                        when (ActionKind.parse(kind)) {
                            ActionKind.TRANSITION -> {
                                val transition =
                                    component.transition?.trim().orEmpty().ifBlank {
                                        throw ValidationException("ACTION/TRANSITION needs a transition", "components", "transition is required")
                                    }
                                if (transition !in transitions) {
                                    throw ValidationException(
                                        "Unknown transition '$transition'",
                                        "components",
                                        "'${definition.obj.name}' has no enabled workflow transition '$transition'"
                                    )
                                }
                            }
                            ActionKind.NAVIGATE -> {
                                val target = component.target?.trim()?.ifBlank { null }
                                val url = component.url?.trim()?.ifBlank { null }
                                if ((target == null) == (url == null)) {
                                    throw ValidationException(
                                        "ACTION/NAVIGATE needs one destination",
                                        "components",
                                        "name exactly one of target or url"
                                    )
                                }
                                // target names an object, never a relationship: the two namespaces
                                // can collide and a rule that silently prefers one is unreadable
                                if (target != null && target !in objectNames) {
                                    throw ValidationException("Unknown object '$target'", "components", "target must be an object of this organization")
                                }
                                if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                                    throw ValidationException("Unsupported url '$url'", "components", "url must start with http:// or https://")
                                }
                            }
                        }
                    }
```

- [ ] **Step 5: Run the tests**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: the four new tests PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew :backend:ktlintCheck
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): an action fires a transition or goes somewhere"
```

### Task 7: The generated page comes as a tree

**Files:**
- Modify: `backend/src/main/kotlin/com/sapgis/pages/PageService.kt` (`generate()`)
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt` (rewrite the tab-asserting tests noted in Task 3)

**Interfaces:**
- Produces: a generated `PageDefinition` whose single root component is a `TABS` node.

- [ ] **Step 1: Rewrite the three existing tests that assert on `tab`**

Replace `a spatial object with no stored page gets a generated form and map, each in its own tab`:

```kotlin
    @Test
    fun `a spatial object with no stored page gets a generated form and map, each in its own tab`() {
        resolve(predio)
            .jsonPath("$.generated")
            .isEqualTo(true)
            .jsonPath("$.objectName")
            .isEqualTo(predio)
            .jsonPath("$.kind")
            .isEqualTo("RECORD_DETAIL")
            // the tab strip is the layout now: nothing sits beside anything
            .jsonPath("$.layout")
            .isEqualTo("single-column")
            .jsonPath("$.definition.components[0].type")
            .isEqualTo("TABS")
            .jsonPath("$.definition.components[0].children[0].title")
            .isEqualTo("DETAILS")
            .jsonPath("$.definition.components[0].children[0].children[0].type")
            .isEqualTo("FORM")
            .jsonPath("$.definition.components[0].children[1].title")
            .isEqualTo("MAP")
            .jsonPath("$.definition.components[0].children[1].children[0].type")
            .isEqualTo("MAP")
    }
```

Replace `a flat object gets a single-column page with no map`:

```kotlin
    @Test
    fun `a flat object gets a single-column page with no map`() {
        resolve(nota)
            .jsonPath("$.layout")
            .isEqualTo("single-column")
            .jsonPath("$.definition.components[0].children.length()")
            .isEqualTo(2)
            .jsonPath("$.definition.components[0].children[0].title")
            .isEqualTo("DETAILS")
            // history closes every generated page: the record's own trail, no configuration needed
            .jsonPath("$.definition.components[0].children[1].title")
            .isEqualTo("HISTORY")
            // a flat object has no map tab at all
            .jsonPath("$.definition.components[0].children[?(@.title == 'MAP')]")
            .doesNotExist()
    }
```

Rewrite `the generated page carries one related list per relationship`, `a stored page keeps the tabs it was given`, `a blank tab is no tab` and `a tab nobody could read on a strip is refused` the same way. The last two lose their subject — a tab is a `TAB` node now, not a string — so **delete** them and replace with one test that a blank `TAB` title is kept as null:

```kotlin
    @Test
    fun `a tab with a blank title keeps no title`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "single-column", listOf(tabs(tab("   ", listOf(form(1, null))))))
            .expectStatus()
            .isCreated

        resolve(predio)
            .jsonPath("$.definition.components[0].children[0].title")
            .doesNotExist()
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*'`
Expected: the generated-page tests FAIL — the response is still flat.

- [ ] **Step 3: Rewrite `generate()`**

Replace the body between the `components` declaration and the `return Page(...)`:

```kotlin
        // one tab per thing you go looking for. each holds one column, so the tab strip is the
        // layout and nothing sits beside anything.
        val details = mutableListOf(PageComponent(type = ComponentType.FORM))
        if (workflows.stateOf(definition.obj.organizationId, definition.obj.id).attached) {
            // acting on the record's state is something you do while looking at it, not at its trail
            details += PageComponent(type = ComponentType.WORKFLOW)
        }

        val tabs = mutableListOf(PageComponent(type = ComponentType.TAB, title = GeneratedTab.DETAILS, children = details))
        if (definition.geometryFields.isNotEmpty()) {
            tabs +=
                PageComponent(
                    type = ComponentType.TAB,
                    title = GeneratedTab.MAP,
                    children = listOf(PageComponent(type = ComponentType.MAP, title = definition.obj.label))
                )
        }

        val related =
            relationships.forObject(definition.obj.name).map { side ->
                PageComponent(type = ComponentType.RELATED_LIST, title = side.label, relationship = side.relationship.name)
            }
        if (related.isNotEmpty()) {
            tabs += PageComponent(type = ComponentType.TAB, title = GeneratedTab.RELATED, children = related)
        }

        tabs +=
            PageComponent(
                type = ComponentType.TAB,
                title = GeneratedTab.HISTORY,
                children = listOf(PageComponent(type = ComponentType.HISTORY))
            )

        val components = listOf(PageComponent(type = ComponentType.TABS, children = tabs))
```

Note the behaviour change this locks in and is correct: an object with no relationships no longer gets an empty "Relacionados" tab. Previously `pageTabs.ts` dropped it at render time; now it is never emitted.

- [ ] **Step 4: Fix the other suite that asserts on a generated page**

`PageApiTest` is not the only one. `backend/src/test/kotlin/com/sapgis/api/WorkflowApiTest.kt:44`
and `:59` assert on the flat component list of a generated page and will fail here.

Replace the assertion at `:44` (an object with no workflow) with the concrete path — a filter
expression that matches nothing resolves to `[]`, and `.isEmpty` on one of those has already misled
this codebase once:

```kotlin
            // details holds the form alone until a workflow is attached
            .jsonPath("$.definition.components[0].children[0].children.length()")
            .isEqualTo(1)
```

And the one at `:59` (a workflow attached):

```kotlin
            .jsonPath("$.definition.components[0].children[0].children[1].type")
            .isEqualTo("WORKFLOW")
```

`FormApiTest.kt:233` needs no change: it asserts on a page it stored itself as a flat root list, and
a flat root list is still a valid tree.

- [ ] **Step 5: Run both suites**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest*' --tests '*WorkflowApiTest*' --tests '*FormApiTest*'`
Expected: every test PASSES.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew :backend:ktlintCheck
git add backend/src/main/kotlin/com/sapgis/pages/PageService.kt backend/src/test/kotlin/com/sapgis/api/
git commit -m "feat(pages): the generated page comes as a tree of tabs"
```

### Task 8: V10 migrates the stored pages

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__a_page_is_a_tree.sql`
- Test: `backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt`

**Interfaces:**
- Consumes: the `sapgis.pages.definition` jsonb column.
- Produces: every stored page in the tree shape.

- [ ] **Step 1: Write the failing test**

Add to `PageApiTest.kt`. It writes the old flat shape straight into the column, which is the only way to test a migration that has already run:

```kotlin
    // a page stored before V10 has no children and a `tab` string on each component
    @Test
    fun `a page stored flat reads back as a tree`() {
        val name = uniqueName("page").take(30)
        createPage(name, predio, "single-column", listOf(form(1, null), map(1)))
            .expectStatus()
            .isCreated

        val flat =
            """
            {"components":[
              {"type":"TEXT","column":1,"content":"suelto"},
              {"type":"FORM","column":1,"tab":"Detalles"},
              {"type":"MAP","column":1,"tab":"Mapa"},
              {"type":"TEXT","column":1,"content":"mas","tab":"Detalles"}
            ]}
            """.trimIndent()
        writeRawDefinition(name, flat)
        runMigrationSql()

        resolve(predio)
            // the untabbed component leads, ahead of the strip
            .jsonPath("$.definition.components[0].type")
            .isEqualTo("TEXT")
            .jsonPath("$.definition.components[0].content")
            .isEqualTo("suelto")
            .jsonPath("$.definition.components[1].type")
            .isEqualTo("TABS")
            // first-appearance order, and both Detalles components land in one tab
            .jsonPath("$.definition.components[1].children[0].title")
            .isEqualTo("Detalles")
            .jsonPath("$.definition.components[1].children[0].children.length()")
            .isEqualTo(2)
            .jsonPath("$.definition.components[1].children[1].title")
            .isEqualTo("Mapa")
            .jsonPath("$.definition.components[1].children[0].children[0].tab")
            .doesNotExist()
    }
```

`IntegrationTest` exposes only `client`, so inject the database the way `FieldApiTest.kt:14-15` and
`ObjectCrudApiTest.kt:14-15` already do — add to the top of the class:

```kotlin
    @Autowired
    private lateinit var db: DatabaseClient
```

with `org.springframework.beans.factory.annotation.Autowired` and
`org.springframework.r2dbc.core.DatabaseClient`.

Add the two helpers. The `runBlocking { … awaitFirstOrNull() }` shape is the one every other test in
this suite uses for raw SQL (`FieldApiTest.kt:197-205`); imports are `kotlinx.coroutines.runBlocking`
and `kotlinx.coroutines.reactive.awaitFirstOrNull`.

```kotlin
    private fun writeRawDefinition(
        name: String,
        json: String
    ) = runBlocking {
        db
            .sql("UPDATE sapgis.pages SET definition = CAST(:definition AS jsonb) WHERE name = :name")
            .bind("definition", json)
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    // flyway already ran at boot, so the migration is replayed by hand against the row just written
    private fun runMigrationSql() = runBlocking {
        val sql = javaClass.getResource("/db/migration/V10__a_page_is_a_tree.sql")!!.readText()
        db.sql(sql).fetch().rowsUpdated().awaitFirstOrNull()
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest --tests '*PageApiTest.a page stored flat*'`
Expected: FAIL — before the migration file exists, the resource lookup throws.

- [ ] **Step 3: Write the migration**

```sql
-- a page was a flat list of components grouped at render time by an equal `tab` string. it is a
-- tree now, so the grouping moves into the data: one TABS node holding one TAB per distinct tab,
-- in first-appearance order, exactly as pageTabs.ts computed it.
--
-- components that named no tab stay at the root, ahead of the strip. that is the one visible
-- change: they used to become a tab of their own labelled "Página". a tree can say "this belongs
-- to the page, not to any tab", and the flat model could not.
--
-- leaves need no `children` or `layout` written: jackson applies the data-class defaults when a
-- key is absent. stripping `tab` is hygiene.

WITH exploded AS (
    SELECT p.id,
           c.ord,
           c.value - 'tab'                     AS component,
           NULLIF(TRIM(c.value ->> 'tab'), '') AS tab
    FROM sapgis.pages p,
         LATERAL jsonb_array_elements(p.definition -> 'components') WITH ORDINALITY c(value, ord)
),
roots AS (
    SELECT id, jsonb_agg(component ORDER BY ord) AS components
    FROM exploded
    WHERE tab IS NULL
    GROUP BY id
),
grouped AS (
    SELECT id, tab, MIN(ord) AS first_seen, jsonb_agg(component ORDER BY ord) AS children
    FROM exploded
    WHERE tab IS NOT NULL
    GROUP BY id, tab
),
strips AS (
    SELECT id,
           jsonb_build_object(
               'type', 'TABS',
               'column', 1,
               'layout', 'single-column',
               'children', jsonb_agg(
                   jsonb_build_object(
                       'type', 'TAB',
                       'column', 1,
                       'layout', 'single-column',
                       'title', tab,
                       'children', children
                   ) ORDER BY first_seen
               )
           ) AS strip
    FROM grouped
    GROUP BY id
)
UPDATE sapgis.pages p
SET definition = jsonb_build_object(
        'components',
        COALESCE(r.components, '[]'::jsonb) ||
        CASE WHEN s.strip IS NULL THEN '[]'::jsonb ELSE jsonb_build_array(s.strip) END
    ),
    updated_at = now()
FROM sapgis.pages base
         LEFT JOIN roots r ON r.id = base.id
         LEFT JOIN strips s ON s.id = base.id
WHERE p.id = base.id;
```

- [ ] **Step 4: Run it again with the migration in place**

Run the same command.
Expected: PASS.

If the test DB has drifted (Flyway refusing a checksum, or `/api/gis/layers` style bloat), recreate it:

```bash
psql -h localhost -c 'DROP DATABASE IF EXISTS sapgis_test' -c 'CREATE DATABASE sapgis_test'
```

- [ ] **Step 5: Run the full backend suite**

Run: `./gradlew :backend:ktlintCheck :backend:test` then, separately, `SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest`
Expected: 0 failures. Do not run these two concurrently.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V10__a_page_is_a_tree.sql backend/src/test/kotlin/com/sapgis/api/PageApiTest.kt
git commit -m "feat(pages): migrate stored pages from a flat list to a tree"
```

### Task 9: The renderer walks the tree

**Files:**
- Modify: `frontend/src/types/metadata.ts`
- Modify: `frontend/src/components/page-renderer/PageRenderer.tsx`
- Delete: `frontend/src/components/page-renderer/pageTabs.ts`, `frontend/src/components/page-renderer/pageTabs.test.ts`
- Test: `frontend/src/components/page-renderer/PageRenderer.test.tsx`

**Interfaces:**
- Consumes: `PageComponent` from `@/types/metadata`, `Tabs`/`TabSpec` from `@/components/ui/tabs` (unchanged).
- Produces: `PageComponentType` widened to `'TABS' | 'TAB' | 'SECTION' | 'FORM' | 'MAP' | 'RELATED_LIST' | 'TEXT' | 'HISTORY' | 'WORKFLOW' | 'ACTION'`; `PageComponent.children: PageComponent[]`, `.layout: PageLayout`, `.action`, `.transition`, `.target`, `.url`, `.style`.

- [ ] **Step 1: Update the types**

In `frontend/src/types/metadata.ts`, replace the page block:

```ts
export type PageComponentType = 'TABS' | 'TAB' | 'SECTION' | 'FORM' | 'MAP' | 'RELATED_LIST' | 'TEXT' | 'HISTORY' | 'WORKFLOW' | 'ACTION'

export type PageLayout = 'single-column' | 'two-column'

export type ActionKind = 'TRANSITION' | 'NAVIGATE'

export type ActionStyle = 'PRIMARY' | 'SECONDARY'

export interface PageComponent {
  type: PageComponentType
  // which column of the PARENT container holds it
  column: number
  title: string | null
  // container: how it lays its own children out. a leaf ignores it.
  layout: PageLayout
  children: PageComponent[]
  relationship: string | null
  fields: string[] | null
  // a FORM component either names a stored form or lists fields, never both
  form?: string | null
  // a MAP component may target one geometry of the object; null draws every one it has
  geometry?: string | null
  content: string | null
  action?: ActionKind | null
  transition?: string | null
  target?: string | null
  url?: string | null
  style?: ActionStyle | null
}
```

Delete the `tab` field.

- [ ] **Step 2: Write the failing renderer tests**

In `PageRenderer.test.tsx`, replace the page fixtures with trees and add these. Keep the existing `vi.mock` block and the `mounts.history` counter exactly as they are.

```tsx
function node(type: PageComponent['type'], extra: Partial<PageComponent> = {}): PageComponent {
  return {
    type,
    column: 1,
    title: null,
    layout: 'single-column',
    children: [],
    relationship: null,
    fields: null,
    content: null,
    ...extra
  }
}

it('draws a section inside a tab, down the tree', () => {
  const page = pageOf([node('TABS', { children: [node('TAB', { title: 'Detalles', children: [node('SECTION', { title: 'Datos', children: [node('FORM')] })] })] })])

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  expect(screen.getByRole('tab', { name: 'Detalles' })).toBeInTheDocument()
  expect(screen.getByText('Datos')).toBeInTheDocument()
})

// the same property the flat model earned: an unopened tab must not mount
it('does not mount a tab nobody opened, and keeps it mounted once opened', async () => {
  mounts.history = 0
  const page = pageOf([
    node('TABS', {
      children: [node('TAB', { title: 'Detalles', children: [node('FORM')] }), node('TAB', { title: 'Historial', children: [node('HISTORY')] })]
    })
  ])

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)
  expect(mounts.history).toBe(0)

  await userEvent.click(screen.getByRole('tab', { name: 'Historial' }))
  expect(mounts.history).toBe(1)

  await userEvent.click(screen.getByRole('tab', { name: 'Detalles' }))
  await userEvent.click(screen.getByRole('tab', { name: 'Historial' }))
  expect(mounts.history).toBe(1)
})

// one record, one save button, wherever in the tree the first form happens to sit
it('gives submission to the first form in document order, however deep it is', async () => {
  const onSubmit = vi.fn()
  const page = pageOf([
    node('SECTION', { title: 'Arriba', children: [node('FORM')] }),
    node('FORM', { title: 'Abajo' })
  ])

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={onSubmit} />)

  const saves = screen.getAllByRole('button', { name: /guardar/i })
  expect(saves).toHaveLength(1)
  await userEvent.click(saves[0])
  expect(onSubmit).toHaveBeenCalledTimes(1)
})

it('leaves a tab off the strip when everything in it is gone', () => {
  const page = pageOf([
    node('TABS', {
      children: [
        node('TAB', { title: 'Detalles', children: [node('FORM')] }),
        node('TAB', { title: 'Fantasma', children: [node('RELATED_LIST', { relationship: 'ya_no_existe' })] })
      ]
    })
  ])

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  expect(screen.queryByRole('tab', { name: 'Fantasma' })).not.toBeInTheDocument()
})

it('lays a two-column section out in two columns inside a one-column page', () => {
  const page = pageOf([node('SECTION', { title: 'Datos', layout: 'two-column', children: [node('FORM'), node('TEXT', { column: 2, content: 'al lado' })] })])

  renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)

  expect(within(screen.getByTestId('page-column-2')).getByText('al lado')).toBeInTheDocument()
})
```

Write `pageOf(components)` as a small local helper returning a `Page` with `layout: 'single-column'`, mirroring the fixture the file already builds.

- [ ] **Step 3: Run them to verify they fail**

Run: `cd frontend && yarn test PageRenderer`
Expected: FAIL — `children` is not read anywhere yet.

- [ ] **Step 4: Rewrite the renderer**

Two imports the file does not have yet: `import { type ReactNode } from 'react'` and
`import { cn } from '@/lib/utils'`. Add `PageLayout` to the existing type import from
`@/types/metadata`, and drop the `isTabbed, tabsOf` import entirely.

Replace the body of `PageRenderer` after the `relationships` hook:

```tsx
  const known = (relationships.data ?? []).map((side) => side.relationship)
  // one record, one save button: the first form in document order owns submission, the rest are
  // read-along field groups whose submit does nothing.
  const owner = firstForm(page.definition.components)

  const inColumns = (nodes: PageComponent[], layout: PageLayout) => {
    const count = layout === 'two-column' ? 2 : 1
    const columns: PageComponent[][] = Array.from({ length: count }, () => [])
    nodes.forEach((child) => {
      // a component aimed at a column this container does not have would vanish. put it first.
      const column = Number.isInteger(child.column) && child.column >= 1 && child.column <= count ? child.column : 1
      columns[column - 1].push(child)
    })
    return columns
  }

  const body = (nodes: PageComponent[], layout: PageLayout, padded: boolean) => (
    <div className={cn(layout === 'two-column' ? 'grid gap-5 lg:grid-cols-2 lg:items-start' : 'space-y-5', padded && 'p-8')}>
      {inColumns(nodes, layout).map((column, position) => (
        <div key={position} className="space-y-5" data-testid={`page-column-${position + 1}`}>
          {column.map((child, index) => renderComponent(child, index))}
        </div>
      ))}
    </div>
  )

  const renderComponent = (component: PageComponent, index: number): ReactNode => {
    const key = `${component.type}-${index}`

    switch (component.type) {
      case 'TABS': {
        const open = component.children.filter((child) => draws(child, known))
        if (open.length === 0) return null
        return (
          <Tabs
            key={key}
            label={t('pages.tabs.label')}
            tabs={open.map((child, position) => ({
              id: `${child.title ?? 'tab'}-${position}`,
              // a generated page names its tabs with keys, because the server has no language.
              // anything an admin typed is printed as they typed it.
              label: child.title ? t(`pages.tabs.${child.title}`, { defaultValue: child.title }) : t('pages.tabs.page'),
              render: () => body(child.children, child.layout, true)
            }))}
          />
        )
      }

      // a tab outside a strip cannot happen: the server refuses it
      case 'TAB':
        return null

      case 'SECTION':
        return (
          <Card key={key}>
            {component.title ? (
              <CardHeader>
                <CardTitle>{component.title}</CardTitle>
              </CardHeader>
            ) : null}
            <CardBody>{body(component.children, component.layout, false)}</CardBody>
          </Card>
        )

      case 'ACTION':
        return <ActionButton key={key} component={component} objectName={definition.name} recordId={record.id} />

      // … every existing leaf case, unchanged, except FORM's `owner` becomes `component === owner`
    }
  }

  // the root is a container like any other: it lays its children out in the page's own layout.
  // bypassing body() here would ignore page.layout while the builder still offers it and the
  // server still validates a root component's column against it.
  return body(page.definition.components, page.layout, true)
```

Add these two module-level helpers at the bottom of the file:

```tsx
// pre-order: the first form you would read going down the page
function firstForm(nodes: PageComponent[]): PageComponent | null {
  for (const node of nodes) {
    if (node.type === 'FORM') return node
    const nested = firstForm(node.children)
    if (nested) return nested
  }
  return null
}

// a tab whose every component draws nothing is a button that opens an empty panel
function draws(node: PageComponent, relationships: string[]): boolean {
  if (node.type === 'RELATED_LIST') return relationships.includes(node.relationship ?? '')
  if (node.children.length > 0) return node.children.some((child) => draws(child, relationships))
  return !(node.type === 'TABS' || node.type === 'TAB' || node.type === 'SECTION')
}
```

The `MAP` case keeps reading `component.geometry` — as of Task 5 that value finally arrives.

- [ ] **Step 5: Delete the grouping module**

```bash
cd /Users/jorge/IdeaProjects/sapgis
rm frontend/src/components/page-renderer/pageTabs.ts frontend/src/components/page-renderer/pageTabs.test.ts
```

Remove its import from `PageRenderer.tsx`. `components/ui/tabs.tsx` is **not** touched.

- [ ] **Step 6: Run the tests**

Run: `cd frontend && yarn test PageRenderer`
Expected: PASS. `ActionButton` does not exist yet — stub it in Task 10; until then, comment out the `ACTION` case and its test, or do Task 10 first if working out of order.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/metadata.ts frontend/src/components/page-renderer/
git commit -m "feat(pages): the renderer walks a tree, and the grouping rule is gone"
```

### Task 10: The Action button renders

**Files:**
- Create: `frontend/src/components/page-renderer/ActionButton.tsx`
- Create: `frontend/src/components/page-renderer/ActionButton.test.tsx`

**Interfaces:**
- Consumes: `useAvailableTransitions(objectName, recordId)` and `useApplyTransition(objectName, recordId)` from `@/features/workflows/api`; `AvailableTransition` has `name`, `label`, `toLabel`, `allowed`, `reason`.
- Produces: `<ActionButton component={PageComponent} objectName={string} recordId={string} />`.

- [ ] **Step 1: Write the failing test**

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ActionButton } from '@/components/page-renderer/ActionButton'
import { renderWithProviders } from '@/test/render'
import type { PageComponent } from '@/types/metadata'

const { transitions } = vi.hoisted(() => ({
  transitions: [{ name: 'aprobar', label: 'Aprobar', toLabel: 'Aprobado', allowed: false, reason: 'No es tu rol' }]
}))

vi.mock('@/features/workflows/api', () => ({
  useAvailableTransitions: () => ({ data: transitions }),
  useApplyTransition: () => ({ mutate: vi.fn(), isPending: false, error: null })
}))

function action(extra: Partial<PageComponent>): PageComponent {
  return { type: 'ACTION', column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, ...extra }
}

describe('ActionButton', () => {
  it('says why a transition is closed instead of pretending it is open', () => {
    renderWithProviders(<ActionButton component={action({ action: 'TRANSITION', transition: 'aprobar', title: 'Aprobar' })} objectName="predio" recordId="r1" />)

    const button = screen.getByRole('button', { name: 'Aprobar' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', 'No es tu rol')
  })

  it('draws a link for a navigation, not a button', () => {
    renderWithProviders(<ActionButton component={action({ action: 'NAVIGATE', target: 'nota', title: 'Ver notas' })} objectName="predio" recordId="r1" />)

    expect(screen.getByRole('link', { name: 'Ver notas' })).toHaveAttribute('href', '/data/objects/nota/records')
  })

  it('opens an external url in a new tab, safely', () => {
    renderWithProviders(<ActionButton component={action({ action: 'NAVIGATE', url: 'https://catastro.test', title: 'Catastro' })} objectName="predio" recordId="r1" />)

    const link = screen.getByRole('link', { name: 'Catastro' })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer noopener')
  })
})
```

Before writing the implementation, confirm the record-list route with:
`grep -rn "objects/:object\|records" frontend/src/App.tsx frontend/src/routes* 2>/dev/null | head`
and use whatever path that file declares in place of `/data/objects/nota/records`, in both the test and the component.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && yarn test ActionButton`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the component**

```tsx
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { useApplyTransition, useAvailableTransitions } from '@/features/workflows/api'
import type { PageComponent } from '@/types/metadata'

interface ActionButtonProps {
  component: PageComponent
  objectName: string
  recordId: string
}

// one button an admin placed where they wanted it. WORKFLOW draws every transition at once;
// this draws the one it was told to, and says why when that one is shut.
export function ActionButton({ component, objectName, recordId }: ActionButtonProps) {
  const { t } = useTranslation()
  if (component.action === 'NAVIGATE') return <NavigateAction component={component} />
  return <TransitionAction component={component} objectName={objectName} recordId={recordId} fallback={t('pages.action')} />
}
```

Write `TransitionAction` to read `useAvailableTransitions`, find the entry whose `name` matches `component.transition`, and render a `Button` with `variant={component.style === 'PRIMARY' ? 'primary' : 'secondary'}`, `disabled={!found?.allowed || apply.isPending}`, `title={found ? (found.allowed ? undefined : found.reason) : t('pages.transitionGone')}`, and `onClick={() => apply.mutate(component.transition!)}`. Its label is `component.title ?? found?.label ?? component.transition`.

Write `NavigateAction` to render a `Link` styled as a button when `component.target` is set, and a plain `<a target="_blank" rel="noreferrer noopener">` when `component.url` is set.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && yarn test ActionButton`
Expected: PASS.

- [ ] **Step 5: Re-enable the ACTION case in the renderer and run its suite**

Run: `cd frontend && yarn test PageRenderer`
Expected: PASS.

- [ ] **Step 6: Add the i18n keys**

In both `frontend/src/locales/es/common.json` and `en/common.json`, under `pages`:

| key | es | en |
|---|---|---|
| `action` | Acción | Action |
| `transitionGone` | Esta transición ya no existe en el workflow | This transition is no longer in the workflow |
| `types.TABS` | Pestañas | Tabs |
| `types.TAB` | Pestaña | Tab |
| `types.SECTION` | Sección | Section |
| `types.ACTION` | Acción | Action |

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/page-renderer/ActionButton.tsx frontend/src/components/page-renderer/ActionButton.test.tsx frontend/src/locales
git commit -m "feat(pages): an action component fires a transition or navigates"
```

### Task 11: The form editor authors trees, minimally

Phase 2 deletes this editor. The work here is the smallest thing that keeps the app whole between phases — nothing more.

**Files:**
- Modify: `frontend/src/features/pages/PageBuilderPage.tsx`
- Test: none new. It is replaced in Task 16.

**Interfaces:**
- Consumes: `PageComponent` with `children`.
- Produces: an editor that saves a valid tree.

- [ ] **Step 1: Flatten for editing, rebuild on save**

Add two local helpers to `PageBuilderPage.tsx`:

```tsx
// the form editor only ever knew a flat list. it keeps working on one: the tree is flattened for
// editing and rebuilt on save. phase 2 deletes all of this along with the editor.
function flatten(nodes: PageComponent[], tab: string | null = null): { component: PageComponent; tab: string | null }[] {
  return nodes.flatMap((node) => {
    if (node.type === 'TABS') return node.children.flatMap((child) => flatten(child.children, child.title))
    if (node.type === 'TAB' || node.type === 'SECTION') return flatten(node.children, tab)
    return [{ component: node, tab }]
  })
}

function rebuild(rows: { component: PageComponent; tab: string | null }[]): PageComponent[] {
  const loose = rows.filter((row) => !row.tab).map((row) => row.component)
  const names = [...new Set(rows.map((row) => row.tab).filter(Boolean))] as string[]
  if (names.length === 0) return loose
  const strip: PageComponent = {
    ...blank('TABS'),
    children: names.map((name) => ({
      ...blank('TAB'),
      title: name,
      children: rows.filter((row) => row.tab === name).map((row) => row.component)
    }))
  }
  return [...loose, strip]
}
```

Write `blank(type)` returning a `PageComponent` with every field at its default (`column: 1`, `layout: 'single-column'`, `children: []`, the rest null).

- [ ] **Step 2: Wire them in**

The `useEffect` that seeds `draft` from `page.data` stores the flattened rows in state; `submit()` sends `definition: { components: rebuild(rows) }`. Every existing per-row control keeps working untouched, including the `tab` text input, which now writes into `row.tab` rather than `component.tab`.

Drop the `TYPES` entries the flat editor cannot express: `TABS`, `TAB`, `SECTION` and `ACTION` are not addable here. Leave the list as it is today.

- [ ] **Step 3: Check it end to end**

```bash
cd frontend && yarn lint && yarn test && yarn build
```
Expected: all PASS.

Then, with the backend and `yarn dev` running: open `/builder/pages`, pick `predio`, save, reload, and confirm the page still renders with its tabs.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/pages/PageBuilderPage.tsx
git commit -m "refactor(pages): keep the form editor working against the tree"
```

### Task 12: Write down the decision and push the phase

**Files:**
- Create: `docs/adr/0021-a-page-is-a-tree.md`
- Modify: `docs/api/rest.md`, `docs/HISTORY.md`

- [ ] **Step 1: Write ADR-0021**

Follow the house shape — read `docs/adr/0019-a-geometry-is-a-field.md` first and match its structure (`# ADR-021: …`, `**Status**: accepted · 2026-09-19 · amends ADR-…`, then Context / Decision / Consequences). It must record: why a free tree rather than a bounded one; why the depth and node bounds exist anyway and that neither is reachable by dragging; why `TABS` accepting only `TAB` is a rule about meaning, not a limit on shape; that per-container `layout` makes a 2:1 split a new enum value rather than a new node type; and that V10 is one-way, with the mixed-page appearance change named explicitly.

- [ ] **Step 2: Update the API docs**

In `docs/api/rest.md`, replace the page-definition section with the tree shape: the component fields including `children` and `layout`, the container types, the `ACTION` fields, the bounds, and the refusals a client can expect.

- [ ] **Step 3: Add the history entry**

One entry in `docs/HISTORY.md` covering the phase, including both defects fixed (`toRequest` losing fields, `MAP.geometry` never stored).

- [ ] **Step 4: Full verification**

```bash
./gradlew :backend:ktlintCheck :backend:test
SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest
cd frontend && yarn lint && yarn test && yarn build
```
Expected: 0 failures anywhere. Run the two Gradle commands one after the other, never at the same time.

- [ ] **Step 5: Commit and push the phase**

```bash
git add docs/
git commit -m "docs(pages): record that a page is a tree"
git push
```

---

# Phase 2 — The canvas

### Task 13: `pageTree.ts`, the pure module

Every decision the canvas makes lives here, with no React and no DOM, so it can be wrong in a test instead of in a browser.

**Files:**
- Create: `frontend/src/features/pages/builder/pageTree.ts`
- Create: `frontend/src/features/pages/builder/pageTree.test.ts`

**Interfaces:**
- Produces: `Node`, `Path`, `nodeAt`, `insert`, `remove`, `move`, `canDrop`, `accepts`, `depthOf`, `countOf`, `MAX_DEPTH`, `MAX_COMPONENTS`, `withIds`, `toDefinition`.

- [ ] **Step 1: Write the failing tests**

```ts
import { describe, expect, it } from 'vitest'
import { accepts, canDrop, countOf, depthOf, insert, move, nodeAt, remove, toDefinition, withIds } from './pageTree'
import type { Node } from './pageTree'

function node(type: Node['type'], children: Node[] = [], extra: Partial<Node> = {}): Node {
  return { uid: `${type}-${children.length}-${extra.title ?? ''}`, type, column: 1, title: null, layout: 'single-column', children, ...extra } as Node
}

const tree: Node[] = [
  node('SECTION', [node('FORM'), node('TEXT')], { title: 'Datos' }),
  node('TABS', [node('TAB', [node('MAP')], { title: 'Mapa' })])
]

describe('nodeAt', () => {
  it('walks a path of indices', () => {
    expect(nodeAt(tree, [0])?.type).toBe('SECTION')
    expect(nodeAt(tree, [0, 1])?.type).toBe('TEXT')
    expect(nodeAt(tree, [1, 0, 0])?.type).toBe('MAP')
  })

  it('answers null for a path that leads nowhere', () => {
    expect(nodeAt(tree, [5])).toBeNull()
    expect(nodeAt(tree, [0, 9])).toBeNull()
    expect(nodeAt(tree, [])).toBeNull()
  })
})

describe('insert', () => {
  it('puts a node at an index of the root', () => {
    const next = insert(tree, [1], node('HISTORY'))
    expect(next.map((child) => child.type)).toEqual(['SECTION', 'HISTORY', 'TABS'])
  })

  it('puts a node inside a container', () => {
    const next = insert(tree, [0, 0], node('HISTORY'))
    expect(nodeAt(next, [0, 0])?.type).toBe('HISTORY')
    expect(nodeAt(next, [0, 1])?.type).toBe('FORM')
  })

  it('leaves the original alone', () => {
    insert(tree, [0, 0], node('HISTORY'))
    expect(nodeAt(tree, [0, 0])?.type).toBe('FORM')
  })
})

describe('remove', () => {
  it('takes a node out of a container', () => {
    const next = remove(tree, [0, 0])
    expect(nodeAt(next, [0])?.children.map((child) => child.type)).toEqual(['TEXT'])
  })
})

describe('move', () => {
  it('reorders inside one container', () => {
    const next = move(tree, [0, 0], [0, 2])
    expect(nodeAt(next, [0])?.children.map((child) => child.type)).toEqual(['TEXT', 'FORM'])
  })

  it('moves between containers', () => {
    const next = move(tree, [0, 0], [1, 0, 0])
    expect(nodeAt(next, [0])?.children.map((child) => child.type)).toEqual(['TEXT'])
    expect(nodeAt(next, [1, 0])?.children.map((child) => child.type)).toEqual(['FORM', 'MAP'])
  })

  // removing from before the target shifts every later index in that container
  it('lands where it was aimed even when the removal shifted the target', () => {
    const flat = [node('FORM'), node('TEXT'), node('HISTORY')]
    const next = move(flat, [0], [2])
    expect(next.map((child) => child.type)).toEqual(['TEXT', 'FORM', 'HISTORY'])
  })

  it('refuses a node into its own descendant and changes nothing', () => {
    const next = move(tree, [0], [0, 1])
    expect(next).toEqual(tree)
  })
})

describe('accepts', () => {
  it('lets a tab strip hold tabs and nothing else', () => {
    expect(accepts('TABS', 'TAB')).toBe(true)
    expect(accepts('TABS', 'FORM')).toBe(false)
  })

  it('keeps a tab inside a strip', () => {
    expect(accepts('SECTION', 'TAB')).toBe(false)
    expect(accepts(null, 'TAB')).toBe(false)
  })

  it('lets any other container hold anything, and a leaf hold nothing', () => {
    expect(accepts('SECTION', 'MAP')).toBe(true)
    expect(accepts(null, 'SECTION')).toBe(true)
    expect(accepts('FORM', 'TEXT')).toBe(false)
  })
})

describe('canDrop', () => {
  // nine sections around a form is exactly the bound: the innermost section sits at depth 9
  it('accepts a leaf that lands exactly on the bound', () => {
    let deep: Node = node('FORM')
    for (let i = 0; i < 9; i += 1) deep = node('SECTION', [deep])
    const full = [deep, node('TEXT')]
    expect(depthOf([deep])).toBe(10)
    // the TEXT lands beside the FORM, at depth 10
    expect(canDrop(full, [1], [0, 0, 0, 0, 0, 0, 0, 0, 0, 0])).toBe(true)
  })

  it('refuses a drop whose own height would push the tree past the bound', () => {
    let deep: Node = node('FORM')
    for (let i = 0; i < 9; i += 1) deep = node('SECTION', [deep])
    // this one is two tall, so the same landing spot would reach depth 11
    const full = [deep, node('SECTION', [node('FORM')])]
    expect(canDrop(full, [1], [0, 0, 0, 0, 0, 0, 0, 0, 0, 0])).toBe(false)
  })

  it('refuses a leaf as the parent of anything', () => {
    const flat = [node('FORM'), node('TEXT')]
    expect(canDrop(flat, [1], [0, 0])).toBe(false)
  })
})

describe('depthOf and countOf', () => {
  it('measure the tree', () => {
    expect(depthOf(tree)).toBe(3)
    expect(countOf(tree)).toBe(6)
  })
})

describe('withIds and toDefinition', () => {
  it('adds a uid on the way in and strips it on the way out', () => {
    const annotated = withIds([{ type: 'FORM', column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null }])
    expect(annotated[0].uid).toEqual(expect.any(String))
    expect(toDefinition(annotated)[0]).not.toHaveProperty('uid')
  })

  it('gives every node its own uid, however deep', () => {
    const annotated = withIds(toDefinition(tree))
    const uids = new Set<string>()
    const walk = (nodes: Node[]) => nodes.forEach((child) => { uids.add(child.uid); walk(child.children) })
    walk(annotated)
    expect(uids.size).toBe(countOf(tree))
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test pageTree`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the module**

```ts
import type { PageComponent, PageComponentType } from '@/types/metadata'

// the same bounds the server enforces. refusing at the cursor beats refusing at the request.
export const MAX_DEPTH = 10
export const MAX_COMPONENTS = 200

// a component plus an id the editor owns. dnd-kit needs an identifier that survives a drag, and
// an index path does not: it changes the instant anything moves. the uid never reaches the server.
export interface Node extends Omit<PageComponent, 'children'> {
  uid: string
  children: Node[]
}

// index path from the root. [0, 2, 1] is the second child of the third child of the first node.
export type Path = number[]

const CONTAINERS: PageComponentType[] = ['TABS', 'TAB', 'SECTION']

export function isContainer(type: PageComponentType): boolean {
  return CONTAINERS.includes(type)
}

// a tab strip whose children are not tabs means nothing, and a tab lives nowhere else.
// everything else is free: that is the whole of the shape rule.
export function accepts(parent: PageComponentType | null, child: PageComponentType): boolean {
  if (parent === 'TABS') return child === 'TAB'
  if (child === 'TAB') return parent === 'TABS'
  return parent === null || isContainer(parent)
}

export function nodeAt(tree: Node[], at: Path): Node | null {
  if (at.length === 0) return null
  const [head, ...rest] = at
  const node = tree[head]
  if (!node) return null
  return rest.length === 0 ? node : nodeAt(node.children, rest)
}

export function insert(tree: Node[], at: Path, node: Node): Node[] {
  if (at.length === 1) {
    const next = [...tree]
    next.splice(at[0], 0, node)
    return next
  }
  const [head, ...rest] = at
  return tree.map((child, index) => (index === head ? { ...child, children: insert(child.children, rest, node) } : child))
}

export function remove(tree: Node[], at: Path): Node[] {
  if (at.length === 1) return tree.filter((_, index) => index !== at[0])
  const [head, ...rest] = at
  return tree.map((child, index) => (index === head ? { ...child, children: remove(child.children, rest) } : child))
}

export function move(tree: Node[], from: Path, to: Path): Node[] {
  if (!canDrop(tree, from, to)) return tree
  const node = nodeAt(tree, from)
  if (!node) return tree
  return insert(remove(tree, from), shifted(from, to), node)
}

// taking a node out moves every later index in its own container up by one, target included
function shifted(from: Path, to: Path): Path {
  const parent = from.slice(0, -1)
  const index = from[from.length - 1]
  const sameParent = to.length > parent.length && parent.every((step, position) => step === to[position])
  if (sameParent && to[parent.length] > index) {
    const next = [...to]
    next[parent.length] -= 1
    return next
  }
  return to
}

export function canDrop(tree: Node[], from: Path, to: Path): boolean {
  const node = nodeAt(tree, from)
  if (!node) return false
  // into itself or into one of its own descendants: the result would not be a tree
  if (to.length >= from.length && from.every((step, position) => step === to[position])) return false
  const parent = to.length > 1 ? nodeAt(tree, to.slice(0, -1)) : null
  if (to.length > 1 && !parent) return false
  if (!accepts(parent?.type ?? null, node.type)) return false
  return to.length - 1 + depthOf([node]) <= MAX_DEPTH
}

export function depthOf(tree: Node[]): number {
  return tree.reduce((deepest, node) => Math.max(deepest, 1 + depthOf(node.children)), 0)
}

export function countOf(tree: Node[]): number {
  return tree.reduce((total, node) => total + 1 + countOf(node.children), 0)
}

export function withIds(components: PageComponent[]): Node[] {
  return components.map((component) => ({ ...component, uid: crypto.randomUUID(), children: withIds(component.children) }))
}

export function toDefinition(tree: Node[]): PageComponent[] {
  return tree.map(({ uid: _uid, ...component }) => ({ ...component, children: toDefinition(component.children) }))
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && yarn test pageTree`
Expected: PASS. If `depthOf`/`canDrop` arithmetic is off by one, fix it against the test — the test is the specification.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pages/builder/pageTree.ts frontend/src/features/pages/builder/pageTree.test.ts
git commit -m "feat(pages): the tree operations behind the canvas, pure and tested"
```

### Task 14: The metadata mocks

Spec D2's visible half: real structure, drawn contents, nothing that fetches or mounts.

**Files:**
- Create: `frontend/src/features/pages/builder/preview/ComponentMock.tsx`
- Create: `frontend/src/features/pages/builder/preview/ComponentMock.test.tsx`

**Interfaces:**
- Consumes: `ObjectDefinition` (`.fields` with `.name`/`.label`/`.type`), `RelatedSide` (`.relationship`, `.label`), `AvailableTransition`-shaped names — all already loaded by the builder page.
- Produces: `<ComponentMock component={PageComponent} definition={ObjectDefinition} sides={RelatedSide[]} />`, one switch over the seven leaf types.

- [ ] **Step 1: Write the failing test**

```tsx
it('draws a form with the field labels the object really has', () => {
  render(<ComponentMock component={leaf('FORM')} definition={definition} sides={[]} />)
  expect(screen.getByText('Código')).toBeInTheDocument()
  expect(screen.getByText('Área')).toBeInTheDocument()
})

it('names the geometry a targeted map will draw', () => {
  render(<ComponentMock component={leaf('MAP', { geometry: 'geom' })} definition={definition} sides={[]} />)
  expect(screen.getByText(/geom/)).toBeInTheDocument()
})

it('shows the label of the relationship a related list points at', () => {
  render(<ComponentMock component={leaf('RELATED_LIST', { relationship: 'predio_titular' })} definition={definition} sides={sides} />)
  expect(screen.getByText('Titular')).toBeInTheDocument()
})

it('says when a related list points at a relationship that is gone', () => {
  render(<ComponentMock component={leaf('RELATED_LIST', { relationship: 'fantasma' })} definition={definition} sides={sides} />)
  expect(screen.getByText(/fantasma/)).toBeInTheDocument()
})

// nothing inside a mock may fetch, mount a map, or take a click
it('renders no interactive control at all', () => {
  const { container } = render(<ComponentMock component={leaf('FORM')} definition={definition} sides={[]} />)
  expect(container.querySelectorAll('input, button, select, textarea, a')).toHaveLength(0)
})
```

Write `leaf(type, extra)` as in Task 13, and a `definition` fixture with fields `codigo`/"Código", `area`/"Área" and a GEOMETRY field `geom`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && yarn test ComponentMock`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the mocks**

One component, one `switch`, seven arms. Each draws with `div`s and Tailwind only:

- **FORM** — the object's field labels in a two-column grid, each beside a `h-8 rounded bg-surface-muted` bar. When `component.fields` is set, only those, in that order. When `component.form` is set, the form's name in a caption above.
- **MAP** — a bordered box `h-40` with the targeted geometry's label and type centred, or "todas las geometrías" when `geometry` is null.
- **RELATED_LIST** — the side's label as a caption over three grey rows. When the relationship is not in `sides`, the missing name in `text-danger`.
- **HISTORY** — three stacked rows with a small circle and a grey bar, like a timeline.
- **WORKFLOW** — a pill with the object's initial state name, plus two grey button shapes.
- **TEXT** — `component.content` in `text-ink-muted`, or the placeholder when empty.
- **ACTION** — a button *shape* (a `div`, never a `button`) carrying `component.title`, filled when `style === 'PRIMARY'`.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && yarn test ComponentMock`
Expected: PASS, including the no-interactive-control assertion.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pages/builder/preview/
git commit -m "feat(pages): draw each component from metadata, without mounting it"
```

### Task 15: Palette, canvas and inspector

**Files:**
- Create: `frontend/src/features/pages/builder/Palette.tsx`
- Create: `frontend/src/features/pages/builder/CanvasNode.tsx`
- Create: `frontend/src/features/pages/builder/Canvas.tsx`
- Create: `frontend/src/features/pages/builder/Canvas.test.tsx`
- Create: `frontend/src/features/pages/builder/Inspector.tsx`

**Interfaces:**
- Consumes: everything from `pageTree.ts` (Task 13) and `ComponentMock` (Task 14).
- Produces: `<Palette />`; `<Canvas tree={Node[]} selected={string | null} onChange={(next: Node[]) => void} onSelect={(uid: string | null) => void} definition={ObjectDefinition} sides={RelatedSide[]} />`; `<Inspector node={Node | null} definition={…} sides={…} forms={…} transitions={string[]} objects={string[]} onPatch={(uid: string, patch: Partial<Node>) => void} onRemove={(uid: string) => void} />`.

- [ ] **Step 1: Write the failing canvas tests**

dnd-kit's pointer sensor does not work under jsdom. Drive the canvas through `DndContext`'s `onDragEnd` by exporting the reducer instead of simulating a drag:

```tsx
// the drop handler is exported so it can be tested without a pointer, which jsdom has not got
import { describe, expect, it } from 'vitest'
import { applyDrop } from './Canvas'
import { countOf, nodeAt } from './pageTree'
import type { Node } from './pageTree'

// the same factory pageTree.test.ts uses. repeated rather than shared: a test fixture that two
// suites pull on is a third thing to keep in step.
function node(type: Node['type'], children: Node[] = [], extra: Partial<Node> = {}): Node {
  return { uid: crypto.randomUUID(), type, column: 1, title: null, layout: 'single-column', children, ...extra } as Node
}

it('puts a palette item at the root when dropped on the page', () => {
  const next = applyDrop([], { active: 'palette:SECTION', over: 'slot:0' })
  expect(next.map((node) => node.type)).toEqual(['SECTION'])
})

it('nests a palette item dropped inside a container', () => {
  const tree = [node('SECTION', [])]
  const next = applyDrop(tree, { active: 'palette:FORM', over: 'slot:0.0' })
  expect(nodeAt(next, [0, 0])?.type).toBe('FORM')
})

it('moves an existing node instead of copying it', () => {
  const tree = [node('SECTION', [node('FORM')]), node('SECTION', [])]
  const next = applyDrop(tree, { active: `node:${nodeAt(tree, [0, 0])!.uid}`, over: 'slot:1.0' })
  expect(countOf(next)).toBe(3)
  expect(nodeAt(next, [1, 0])?.type).toBe('FORM')
})

it('refuses a drop the tree rules forbid and changes nothing', () => {
  const tree = [node('TABS', [node('TAB', [])])]
  const next = applyDrop(tree, { active: 'palette:FORM', over: 'slot:0.0' })
  expect(next).toEqual(tree)
})

it('creates a tab strip with one tab already in it', () => {
  const next = applyDrop([], { active: 'palette:TABS', over: 'slot:0' })
  expect(nodeAt(next, [0, 0])?.type).toBe('TAB')
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && yarn test Canvas`
Expected: FAIL — module not found.

- [ ] **Step 3: Write `applyDrop` and the drop-zone id scheme**

A drop zone's id is `slot:` followed by its path joined with dots — `slot:1.0.2` means index 2 inside `[1, 0]`. A draggable is `palette:<TYPE>` for a new component or `node:<uid>` for one already placed.

```tsx
export interface Drop {
  active: string
  over: string | null
}

// one place where a drop becomes a tree. the canvas renders; this decides.
export function applyDrop(tree: Node[], drop: Drop): Node[] {
  if (!drop.over?.startsWith('slot:')) return tree
  const to = drop.over.slice('slot:'.length).split('.').map(Number)

  if (drop.active.startsWith('palette:')) {
    const type = drop.active.slice('palette:'.length) as PageComponentType
    const parent = to.length > 1 ? nodeAt(tree, to.slice(0, -1)) : null
    if (!accepts(parent?.type ?? null, type)) return tree
    if (countOf(tree) >= MAX_COMPONENTS) return tree
    const fresh = blank(type)
    if (to.length - 1 + depthOf([fresh]) > MAX_DEPTH) return tree
    return insert(tree, to, fresh)
  }

  const uid = drop.active.slice('node:'.length)
  const from = pathOf(tree, uid)
  return from ? move(tree, from, to) : tree
}
```

Write `pathOf(tree, uid): Path | null` (a depth-first search returning the index path) and `blank(type): Node` — a fresh node with `crypto.randomUUID()`, defaults for every field, and **one empty `TAB` child when the type is `TABS`**, because a strip with no tabs is nothing to look at.

- [ ] **Step 4: Run the canvas tests**

Run: `cd frontend && yarn test Canvas`
Expected: PASS.

- [ ] **Step 5: Write the three components**

`Palette.tsx` — three labelled groups (Contenedores: TABS, TAB, SECTION · Contenido: FORM, MAP, RELATED_LIST, HISTORY, WORKFLOW, TEXT · Acciones: ACTION). Each item a `useDraggable({ id: 'palette:' + type })` button with the type's icon and `t('pages.types.' + type)`.

`CanvasNode.tsx` — one node. Containers draw a titled frame and recurse through `Canvas`'s renderer, splitting children by `column` against their own `layout`; leaves draw `<ComponentMock />`. The whole node is a `useDraggable({ id: 'node:' + node.uid })` and clicking it calls `onSelect(node.uid)`. The selected node gets a `ring-2 ring-brand`.

`Canvas.tsx` — the `DndContext` with `PointerSensor` and `KeyboardSensor`, `onDragEnd={(event) => onChange(applyDrop(tree, { active: String(event.active.id), over: event.over ? String(event.over.id) : null }))}`, a `DragOverlay` showing the dragged item's label, and a `Slot` component (`useDroppable({ id: 'slot:' + path.join('.') })`) rendered between every pair of siblings and inside every empty container. A slot that is `isOver` draws a `h-1 bg-brand` bar.

`Inspector.tsx` — a switch on the selected node's type rendering exactly the fields that type uses, reusing the controls already in `PageBuilderPage.tsx` today: title, layout and column for everything; relationship select for `RELATED_LIST`; form select plus a fields input for `FORM`; geometry select for `MAP`; content textarea for `TEXT`; and for `ACTION` a kind select, then a transition select or a target select / url input. Each change calls `onPatch(node.uid, patch)`. A delete button calls `onRemove(node.uid)`.

- [ ] **Step 6: Lint and run the frontend suite**

Run: `cd frontend && yarn lint && yarn test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/pages/builder/
git commit -m "feat(pages): a palette, a canvas and an inspector"
```

### Task 16: Wire the builder page and retire the form editor

**Files:**
- Modify: `frontend/src/features/pages/PageBuilderPage.tsx`
- Modify: `frontend/src/locales/es/common.json`, `frontend/src/locales/en/common.json`

**Interfaces:**
- Consumes: `Palette`, `Canvas`, `Inspector`, `withIds`, `toDefinition`, `useResolvedPage`, `useSavePage`, `useDeletePage`, `useObjectRelationships`, `useForms`, `useObjectDefinition`, `useWorkflow`, `useObjects`.

- [ ] **Step 1: Replace the page body**

Keep the object picker, the label/layout card, the save and reset buttons, the error banner and the generated/stored badge exactly as they are. Replace everything below with a three-pane grid: `Palette` at `lg:col-span-2`, `Canvas` at `lg:col-span-7`, `Inspector` at `lg:col-span-3`.

State: `const [tree, setTree] = useState<Node[]>([])`, seeded in the existing `useEffect` with `withIds(page.data.definition.components)`, plus `const [selected, setSelected] = useState<string | null>(null)`. `submit()` sends `definition: { components: toDefinition(tree) }`.

Delete `flatten`, `rebuild` and `blank` from Task 11, the per-component form rows, the `datalist`, `parseFields` if now unused, and the `NO_FORM` sentinel if the inspector defines its own.

- [ ] **Step 2: Add the remaining i18n keys**

Under `pages` in both locale files:

| key | es | en |
|---|---|---|
| `palette` | Componentes | Components |
| `paletteContainers` | Contenedores | Containers |
| `paletteContent` | Contenido | Content |
| `paletteActions` | Acciones | Actions |
| `canvas` | Lienzo | Canvas |
| `inspector` | Propiedades | Properties |
| `nothingSelected` | Selecciona un componente para editarlo | Select a component to edit it |
| `dropHere` | Arrastra un componente aquí | Drag a component here |
| `geometry` | Geometría | Geometry |
| `allGeometries` | Todas las geometrías | Every geometry |
| `actionKind` | Qué hace | What it does |
| `actionKinds.TRANSITION` | Dispara una transición | Fires a transition |
| `actionKinds.NAVIGATE` | Lleva a otro sitio | Goes somewhere |
| `transition` | Transición | Transition |
| `targetObject` | Objeto destino | Target object |
| `url` | Enlace externo | External link |
| `style` | Estilo | Style |
| `styles.PRIMARY` | Destacado | Prominent |
| `styles.SECONDARY` | Normal | Normal |
| `relationshipGone` | Esta relación ya no existe | This relationship is gone |

Remove `tab` and `tabHint`, which no longer have a control. Keep `tabs.*` — the renderer still uses them.

- [ ] **Step 3: Verify the whole frontend**

```bash
cd frontend && yarn lint && yarn test && yarn build
```
Expected: PASS.

- [ ] **Step 4: Verify in the browser**

With `docker compose -f infra/docker/compose.yml up -d`, `./gradlew :backend:bootRun` and `yarn dev` running, on `/builder/pages` with `predio` selected:

1. The canvas shows the generated tree: a tab strip with Detalles, Mapa, Relacionados, Historial.
2. Drag a Section from the palette into the Detalles tab; it lands and is selected.
3. Drag the Form into the Section; it nests.
4. Set the Section to two columns in the inspector; drag the Map beside the form.
5. Add an Action, choose "Lleva a otro sitio", pick an object.
6. Save. Reload the page. Everything is where it was left.
7. Open a record of `predio` and confirm the detail page renders what the canvas showed.

Take screenshots of steps 1, 4 and 7.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/pages/ frontend/src/locales/
git commit -m "feat(pages): build a detail page by dragging it together"
```

### Task 17: Close the phase

**Files:**
- Modify: `docs/HISTORY.md`, `docs/development/` (if it documents the page builder)

- [ ] **Step 1: Add the history entry**

One entry covering the canvas: the palette, the drop rules, the mocks, and the retired form editor.

- [ ] **Step 2: Full verification**

```bash
./gradlew :backend:ktlintCheck :backend:test
SAPGIS_TEST_DB_HOST=localhost SAPGIS_TEST_DB_NAME=sapgis_test ./gradlew :backend:integrationTest
cd frontend && yarn lint && yarn test && yarn build
```
Expected: 0 failures. Never run the two Gradle commands at once.

- [ ] **Step 3: Commit and push**

```bash
git add docs/
git commit -m "docs(pages): record the canvas editor"
git push
```
