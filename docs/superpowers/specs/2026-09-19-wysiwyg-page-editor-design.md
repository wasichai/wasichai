# A page is a tree, edited on a canvas

**Status**: approved design · 2026-09-19 · leads to ADR-021

## Context

A record detail page is a flat list of components. Each one carries `column: Int` to say which half
of a two-column page holds it, and `tab: String?` to say which tab it belongs to. Grouping happens
at render time: `frontend/src/components/page-renderer/pageTabs.ts` walks the flat list and builds
tab groups from equal strings, in first-appearance order.

That model was deliberate and it bought what it was meant to buy. Tabs shipped without a migration,
without breaking a single stored row, and without touching the seven renderer tests, because a page
where nobody typed a tab name renders exactly as it did before.

It stops at the point where a tab is something you *place*. An admin who wants a section grouping
three fields above a map, inside the second of four tabs, cannot express it: there is no container,
and "section" is not a string you can type into the model. The editor reflects this honestly —
`PageBuilderPage.tsx` is a stack of per-component forms, and its own comment says so:

> `// form-based page editor. drag and drop is a later phase; this already covers the model.`

This is that later phase, and it takes the model with it.

Two defects found while surveying, both in scope because this work touches exactly the code that
carries them:

1. **`PageService.toRequest()` drops `tab`.** A `PUT /api/pages/{name}` that omits `definition`
   revalidates the stored one through `toRequest()`, which maps seven of the eight component fields.
   The eighth is `tab`. Renaming a page therefore silently untabs every component on it.
2. **`MAP.geometry` is never stored.** `PageRenderer.tsx:91` reads `component.geometry` to target a
   single geometry of the object, and `frontend/src/types/metadata.ts:140` declares it. The Kotlin
   `PageComponent` and `PageComponentRequest` have no such field, so it is dropped on write and
   absent on read. The branch has been dead since ADR-019 shipped multi-geometry: a `MAP` always
   draws every geometry the object has.

## Decisions

Taken with the user, in order. Each is recorded with the alternative it beat, because the
alternatives were live options and the reasoning is what makes the choice reviewable.

### D1 — The scaffold is a free tree

Any container holds any children, to any depth. A section inside a section, tabs inside a section
inside another tab.

Rejected: a bounded tree (`Page → Tabs → Columns → [Component | Section]`, the Salesforce Lightning
App Builder shape) and keeping the flat list with grouping keys. The bounded tree was the
recommendation; the user chose the free one and accepts that validation, the renderer and the canvas
all become recursive.

A free tree still needs bounds that are not design constraints but guard rails — see D6.

### D2 — The canvas shows structure for real and content as a mock

Columns, widths, tab strips, card chrome and typography on the canvas are the renderer's own. Inside
each leaf goes a static skeleton built from metadata the editor has already loaded: the object's real
field labels, the real relationship label, the real transition name.

Rejected: mounting the real `PageRenderer` against a sample record (needs a record to exist,
every `RelatedList`/`RecordHistory`/`WorkflowPanel` fetches on mount, MapLibre mounts inside a
draggable box, and interactive controls swallow drag events), and plain schematic boxes (robust and
cheap, but a tree diagram rather than a page).

A consequence worth naming: because nothing inside a mock is interactive, nothing inside a mock can
steal a drag. The fidelity choice and the drag mechanics agree by construction.

### D3 — Every container owns its own column count

`layout` moves onto `PageComponent`. A container declares `SINGLE_COLUMN` or `TWO_COLUMN`; its
children carry `column: 1|2` against *their parent's* layout. `Page.layout` keeps its exact current
meaning: it is the root container's layout.

Rejected: an explicit `ROW`/`COLUMN` node pair (makes every drop target literally a `children` array,
which is cleaner for the canvas, at the cost of two node types of pure scaffolding), and keeping one
page-level layout (a section inside column 1 would be forced to subdivide again).

The deciding argument is extensibility without new vocabulary: a 2:1 split or three columns is a new
value of the existing `PageLayout` enum, not a new node type.

### D4 — An Action fires a transition or navigates

Two kinds under one type: `TRANSITION` applies a named workflow transition; `NAVIGATE` goes
somewhere.

Rejected: transition only (the narrow, YAGNI-correct option), and adding on-demand execution of an
Automation. The last one is out of scope and stays out: automations are event-triggered today, there
is no endpoint to run one, and adding it opens who-may-fire-it and what-if-conditions-fail. That is
its own phase.

The union-of-fields shape this produces is the house pattern already: `AutomationAction` carries one
shape for every action type and each field matters only to the type that reads it.

### D5 — Drag and drop is built on `@dnd-kit`

`@dnd-kit/core` 6.3.1, `@dnd-kit/sortable` 10.0.0, `@dnd-kit/modifiers` 9.0.0.

Rejected: native HTML5 drag events (zero dependencies, but `dragover`/`dragleave` bubble, so every
nested container needs its own enter/leave counter to know whether the cursor is in it or in a
child — plus no keyboard and no touch), and no drag at all.

The deciding argument is not dragging, it is the keyboard. A nested tree with insertion points
between siblings, hand-rolled on HTML5 events, cannot be operated without a mouse. `@dnd-kit` ships
a `KeyboardSensor` with ARIA announcements. The alternative to the dependency was not "no
dependency"; it was writing the same library worse.

CLAUDE.md's "few dependencies" rule points the other way and is overridden knowingly, for that
reason.

### D6 — Bounded freedom

A free tree is free by design and bounded by defence:

| Bound | Value | Why |
|---|---|---|
| Depth | 10 | Without it a hand-written `definition` blows the validator's stack before reaching Postgres |
| Node count | 200 | `/api/gis/layers` once crossed the 256 KB `DataBufferLimitException` because nobody bounded a response. This one is bounded before it is born |

Neither is reachable by dragging.

### D7 — `TABS` accepts only `TAB` children

A type rule, not a restriction on D1. A tab strip whose children are not tabs means nothing. Every
other container accepts anything.

### D8 — "Label" in the palette is the existing `TEXT` component

Stated as an assumption when the design was presented and approved without correction. No new type.

## The model

`backend/src/main/kotlin/com/sapgis/pages/Page.kt`:

```kotlin
enum class ComponentType {
    // containers. they hold children and draw nothing of their own.
    TABS, TAB, SECTION,

    FORM, MAP, RELATED_LIST, TEXT,
    HISTORY, WORKFLOW,

    // a button: fires a transition, or goes somewhere
    ACTION
}

enum class ActionKind { TRANSITION, NAVIGATE }

enum class ActionStyle { PRIMARY, SECONDARY }
```

```kotlin
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

`tab: String?` is gone. `PageDefinition(components)` and `Page.layout` keep their shape: the root is
still a list of components laid out in the page's own columns.

`PageComponentRequest` gains the same fields, including `children: List<PageComponentRequest>`.

### Tab labels

A `TAB`'s `title` is its label on the strip. `generate()` keeps writing the `GeneratedTab` keys
(`DETAILS`, `MAP`, `RELATED`, `HISTORY`) there, and the client keeps resolving them with
`t('pages.tabs.' + title, { defaultValue: title })`: a key that exists is translated, anything an
admin typed prints verbatim. The backend still has no language and still does not need one.

### Frontend types

`frontend/src/types/metadata.ts` mirrors the above. `tab` is removed; `children`, `layout`, `action`,
`transition`, `target`, `url` and `style` are added. `geometry` stays and finally has a server behind
it.

## Validation

`PageService.validated()` becomes recursive, threading depth and the parent container's layout.

| Rule | Failure |
|---|---|
| Depth ≤ 10 | 400, `components`, "nested more than 10 deep" |
| Total nodes ≤ 200 | 400, `components`, "a page holds at most 200 components" |
| A leaf with non-empty `children` | 400, naming the type |
| `TABS` with a child that is not `TAB` | 400 |
| `TAB` outside a `TABS` | 400 |
| `column` within `1..parentLayout.columns` | 400, naming the parent's layout |
| `MAP.geometry` is a GEOMETRY field of the object | 400 |
| `MAP` on a non-spatial object | 400 (unchanged) |
| `RELATED_LIST.relationship` exists | 400 (unchanged) |
| `FORM` names a form **or** fields, never both | 400 (unchanged) |
| `TEXT.content` not blank | 400 (unchanged) |
| `ACTION.action` present | 400 |
| `ACTION/TRANSITION.transition` exists in the object's workflow | 400 |
| `ACTION/NAVIGATE` names exactly one of `target` or `url` | 400 |
| `ACTION/NAVIGATE.target` is an object of this organization | 400 |
| `ACTION/NAVIGATE.url` is `http://` or `https://` | 400 |

`target` names an **object**, never a relationship. Resolving it as "a relationship of this object,
otherwise an object name" was considered and dropped: the two namespaces can collide — `predio` has
a relationship named `titular` and an organization can have an object named `titular` — and a rule
that silently prefers one is a rule nobody can read off the stored value. Navigating to a related
record is what `RELATED_LIST` is for.

`column` is checked against the parent, which is the whole point of D3: `column: 2` inside a
`TWO_COLUMN` section that sits inside a `SINGLE_COLUMN` page is valid and must stay valid.

### The workflow port grows one method

Validating an `ACTION` needs the transition names of the object's workflow. `data/WorkflowStates` is
the port the `pages` and `data` modules already use, and architectural rule 4 says modules talk
through ports. It gains a method rather than widening `ObjectWorkflowState`, whose comment — *"what
the record path needs to know about an object's workflow. nothing more"* — stays true:

```kotlin
interface WorkflowStates {
    suspend fun stateOf(organizationId: UUID, objectId: UUID): ObjectWorkflowState

    // pages validate an ACTION against these. the record path never asks.
    suspend fun transitionNames(organizationId: UUID, objectId: UUID): Set<String>
}
```

`WorkflowStatesAdapter` implements it. An object with no workflow returns an empty set, so an
`ACTION/TRANSITION` on a workflow-less object fails with "has no workflow", not a null.

### The `toRequest()` defect, fixed and pinned

`PageDefinition.toRequest()` maps every field, `children` included, recursively. A test pins it: a
`PUT` carrying only `label` leaves the stored tree byte-identical.

## Migration — V10

Only stored pages migrate. Generated pages are derived from metadata on every request, so `generate()`
simply emits the new shape.

`V10__a_page_is_a_tree.sql` rewrites `pages.definition`:

- components carrying a `tab` are grouped by it, in first-appearance order, into one `TABS` node
  holding one `TAB` per distinct name, `title` set to the tab string;
- components with no `tab` stay at the root, ahead of the strip;
- the `tab` key is stripped from every component.

**The second bullet changes how one kind of page looks, and it is the only one that does.** Today an
untabbed component on an otherwise tabbed page does not sit above the strip — `pageTabs.ts` collects
every untabbed component into a group named `''`, and `PageRenderer` renders that group as a tab of
its own, labelled `t('pages.tabs.page')`, positioned wherever the first untabbed component happened
to appear. A page where every component is tabbed, or none is, migrates with no visible change at
all; only a mixed page moves, and it moves to the reading that a tree makes available and the flat
model could not express: content that belongs to the page rather than to any tab.

The alternative — emitting a titleless `TAB` to reproduce the current appearance — was rejected. It
would carry the flat model's workaround into the tree and force the renderer to keep a special case
for a tab with no name.

Leaves need no `children` or `layout` key written: Jackson 3 applies the data-class defaults when a
key is absent, and Spring Boot leaves `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so a stray key would be
ignored anyway. Stripping `tab` is hygiene, not necessity.

This is a one-way migration. The renderer understands one shape. The alternative — reading both
forever — was rejected: it means two renderers and two validators for as long as the product lives.

## The editor

`features/pages/` becomes a directory:

```
PageBuilderPage.tsx        object picker, save/reset, the three panes
builder/pageTree.ts        the tree operations. pure, no React, no DOM.
builder/pageTree.test.ts
builder/Palette.tsx        Containers · Content · Actions
builder/Canvas.tsx         walks the tree, places drop zones
builder/CanvasNode.tsx     one node: chrome plus children, or chrome plus mock
builder/Inspector.tsx      the per-component form, moved out of PageBuilderPage
builder/preview/           one mock per leaf type
```

### `pageTree.ts` — where the decisions live

```ts
// index path from the root. [0, 2, 1] is the second child of the third child of the first node.
export type Path = number[]

export function nodeAt(tree: Node[], at: Path): Node | null
export function insert(tree: Node[], at: Path, node: Node): Node[]
export function remove(tree: Node[], at: Path): Node[]
export function move(tree: Node[], from: Path, to: Path): Node[]
export function canDrop(tree: Node[], from: Path, to: Path): boolean
export function depthOf(tree: Node[]): number
export function countOf(tree: Node[]): number
```

`canDrop` refuses the one thing a free tree must refuse: **a node into itself or into one of its
descendants**. That is not a design restriction; the result would not be a tree. It also enforces D7
(`TABS` takes only `TAB`, `TAB` only inside `TABS`) and D6's depth bound, so the canvas refuses at
the cursor what the server would refuse at the request.

`move` is `remove` then `insert`, with the target path corrected when the removal shifts it — the
classic off-by-one of tree moves, and the reason this module is pure and tested first.

### Stable ids for the drag

`@dnd-kit` needs identifiers that survive a drag. An index path does not: it changes the instant
anything moves. So the editor works on an annotated tree — on load every node gets a client-side
`uid` (`crypto.randomUUID()`), and `toDefinition()` strips them on save. The wire model stays clean
and `@dnd-kit` stays correct.

### The palette

| Group | Items |
|---|---|
| Containers | Tabs, Tab, Section |
| Content | Form, Map, Related list, History, Workflow, Text |
| Actions | Action |

Dragging a palette item creates a node with that type's defaults. Dropping `TABS` creates it with one
empty `TAB` child, because a strip with no tabs is not a thing anyone wants to look at.

## The renderer

`PageRenderer` walks the tree. `pageTabs.ts` and `pageTabs.test.ts` are **deleted**: grouping by
equal strings no longer exists, a `TABS` node is explicit.

`components/ui/tabs.tsx` is **not touched**. It already takes `TabSpec[]` with a `render` thunk,
which is exactly a `TAB` child, and it already mounts a panel on first open and keeps it mounted —
the property that made an unopened tab cost nothing and a typed-but-unsaved form survive a tab
switch. That behaviour is preserved, not re-earned.

Two current behaviours are carried across on purpose, because tests pin them:

- **The first `FORM` owns submission.** Today `components.findIndex(...)` over a flat list. It
  becomes the first `FORM` in pre-order depth-first traversal. Every other form stays a read-along
  field group whose submit does nothing.
- **A tab with nothing to draw is left off the strip.** Today `pageTabs.ts` drops a group whose only
  components are `RELATED_LIST`s pointing at relationships that no longer exist. That check moves
  into the renderer and now applies to any container that renders empty.

`ACTION` is new in the renderer:

- `TRANSITION`: a button wired to the existing `useAvailableTransitions` / `useApplyTransition`. When
  the named transition is absent from the available list, or present and not allowed, the button is
  disabled and carries the reason — the same treatment `WorkflowPanel` already gives.
- `NAVIGATE`: a link to the target object's record list, or to an external URL.

## Tests

### Backend — `PageApiTest.kt`

- a tree round-trips unchanged through create and update;
- depth 11 → 400; 201 nodes → 400;
- `TABS` holding a `FORM` → 400; a `TAB` at the root → 400; a `FORM` with children → 400;
- `column: 2` inside a `TWO_COLUMN` section inside a `SINGLE_COLUMN` page → **200**, and comes back
  with the column intact;
- `MAP` with an unknown geometry → 400; with a real one → stored and returned;
- `ACTION/TRANSITION` naming a transition the workflow does not have → 400; on an object with no
  workflow → 400 saying so;
- `ACTION/NAVIGATE` with both `target` and `url` → 400; with neither → 400;
- a `PUT` carrying only `label` leaves the stored tree identical (the `toRequest` regression);
- a page stored in the flat shape before V10 reads back as a tree, with its tabs in first-appearance
  order.

### Frontend

`builder/pageTree.test.ts`, pure: insert at a path; remove; reorder within one container; move
between containers; move a node into its own descendant is refused; `TABS`/`TAB` typing is enforced;
depth and count are reported correctly; `move` gets the shifted target right when removing from
before the insertion point.

`Canvas.test.tsx`: dropping a Section onto an empty canvas puts it at the root; dropping a Form into
it nests it; the inspector patches the node that is selected and no other.

`PageRenderer.test.tsx`, rewritten: tabs mount on first open and stay mounted; the first `FORM` in
pre-order owns submit and the second does not; a `TAB` whose only child is a dead `RELATED_LIST` is
absent from the strip; a two-column section inside a one-column page lays out in two columns.

### In the browser, against `predio`

Rebuild by hand the page that `generate()` produces today, save it, reload, and see it render
identically. Screenshots.

## Phases

Three, each one leaving the application whole.

0. **Smoke test `@dnd-kit` under React 19.3 `StrictMode`.** Throwaway. If it fails, D5 reopens
   before anything has been built on it.
1. **The tree.** Backend model, recursive validation, the `WorkflowStates` method, V10, `generate()`
   emitting trees, frontend types, the recursive renderer, the `ACTION` component, `pageTabs.ts`
   deleted — and the existing form editor adapted to author the new shape. Both defects fixed here.
2. **The canvas.** `pageTree.ts` first, tested alone; then palette, canvas, mocks, inspector. The
   form editor is replaced, not kept beside it.

**Phase 1 is large and cannot be usefully split.** The shape of `definition` is one contract between
the server, the renderer and the editor: move any one of the three and the other two are wrong until
they follow. Splitting it would mean a commit where saved pages render broken, which the Definition
of Done forbids. The editor's adaptation inside phase 1 is deliberately minimal — it keeps authoring
exactly what it can author today, expressed as a tree — because phase 2 deletes it.

## Risks

- **`@dnd-kit/core` 6.3.1 predates React 19.** Its peer range says `react >=16.8`, which is a range,
  not a promise. The first implementation step is a smoke test under React 19.3 `StrictMode`. If it
  fails, work stops there and D5 is reopened rather than built upon.
- **V10 is one-way.** A stored page cannot be read by the old renderer afterwards.
- **Seven mocks are seven pieces of work.** They are mechanical, but they are the visible half of D2
  and cannot be skipped without the canvas becoming option 3 of that decision by accident.

## Out of scope

- Running an Automation on demand (D4).
- Page kinds other than `RECORD_DETAIL`.
- Layouts beyond one and two columns. D3 makes them a new enum value when they are wanted.
- Deferred by earlier explicit decision and untouched here: pgvector semantic search, GDAL/OGR
  import, Pulumi + k3s.
