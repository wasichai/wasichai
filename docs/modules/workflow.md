# Workflow module

`wasichai-workflow` gives an object a state machine: states, transitions and who may fire each one, drawn on a
canvas (ADR-018). The current state lives on the record itself, in a `workflow_state` column (ADR-013), so views,
filters and the map read it like any other column. Installing the module adds the transitions API, a WORKFLOW page
component that shows a record's state and its open transitions, and a TRANSITION page action that fires one.

## Install

Backend, under the BOM:

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-workflow")
```

Frontend:

```bash
yarn add @wasichai/workflow
```

```tsx
<WasichaiApp modules={[workflowModule()]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

- The `workflows` table: one definition per object (states, transitions, roles), edited on an xyflow canvas.
- The transitions REST API:

  | Method | Path |
  |---|---|
  | GET | `/api/objects/{object}/workflow` |
  | PUT | `/api/objects/{object}/workflow` |
  | DELETE | `/api/objects/{object}/workflow` |
  | GET | `/api/objects/{object}/records/{id}/transitions` |
  | POST | `/api/objects/{object}/records/{id}/transitions/{name}` |

  A transition is a record update: on an `appendOnly` object it answers `409`, and every `RecordWriteGuard` sees it
  (kind `TRANSITIONED`, with its name) before the state moves. `apiOnly` does not refuse it: it runs the workflow's
  own rules ([ADR-040](../adr/0040-append-only-objects-and-a-pre-write-guard.md)).

- A screen and sidebar entry: the `workflow:builder` route at `<basePath>/workflows` (lazy-loaded), listed under the
  `automation` nav group as "Workflows".
- Registry slots: the `WORKFLOW` page component (record state and transition buttons, plus a state-pill preview in
  the page builder), the `TRANSITION` page action (fires one named transition), and the `workflow` object flag that
  core's object editor reads to show the state column's scope.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.workflow.enabled` | `true` | Switches the module's beans, migration and, with `wasichai-pages` present, the WORKFLOW page component on or off. |

Env form: `WASICHAI_WORKFLOW_ENABLED`.

## Extension points

**Implements:**
- `WorkflowStates` (`data`), as `WorkflowStatesAdapter`, replacing core's `NoWorkflowStates` null object. Core's
  auto-configuration runs after this module's (`@AutoConfiguration(before = [WasichaiDataAutoConfiguration::class])`)
  so the replacement is in place before core decides whether it still needs the null object.
- `SystemColumnContributor` (`platform`), as `WorkflowSystemColumns`, which reserves
  `ObjectSchemaManager.STATE_COLUMN` (`workflow_state`) with scope `WORKFLOW`, so `SqlIdentifier` refuses a Custom
  Field of that name once the module is present.
- `PageComponentProvider` (`wasichai-pages`), as `WorkflowPageComponent` at `@Order(200)`, only when
  `wasichai.pages.PageComponentProvider` is on the classpath (`WasichaiWorkflowPagesAutoConfiguration`). Ordered after
  gis's `MapPageComponent` (100) so a generated page's tabs read MAP then WORKFLOW, as in the original app.

**Defines:** None.

**Overridable beans** (all `@ConditionalOnMissingBean`): `workflowSystemColumns`, `workflowRepository`,
`workflowStatesAdapter`, `workflowService`, `workflowController`, and, with `wasichai-pages` present,
`workflowPageComponent`.

## Database

Migration location `classpath:db/wasichai/workflow`, history table `flyway_history_workflow`. It creates the
`workflows` table (one row per object, `definition` as `jsonb`, unique per object and per organization+name). On
core tables, it does not migrate anything itself: attaching a workflow to an object makes `ObjectSchemaManager` add
the `workflow_state` column to that object's physical table at that point, not at module install time.

## Frontend package

`@wasichai/workflow`: the `workflowModule(options)` factory, with one option, `basePath` (default `'automation'`),
the url prefix of the builder route. Main exports from `index.ts`: `WorkflowPanel` (the record panel), the
`useWorkflow` / `useAvailableTransitions` / `useApplyTransition` / `useSaveWorkflow` / `useDeleteWorkflow` query
hooks, and the workflow types. i18n namespace `workflow` (`es`, `en`), with a `pages.*` block reused by the
transition action, its picker and the builder preview.

`WorkflowBuilderPage` — the canvas editor — is reachable only as the lazy `workflow:builder` route; it is not
exported from `index.ts`, so `@xyflow/react` (the package's one heavy dependency, plus its `d3-*` and `zustand`
transitives) loads only when someone opens the builder, never in an app's initial bundle.

See [`packages/workflow` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/workflow) for the full API.

## Without this module

`NoWorkflowStates` answers instead of `WorkflowStatesAdapter`: every object reports no state and no transitions, so
records have no state column to filter or draw. A field named `workflow_state` is not reserved, so nothing stops a
Custom Field with that name; installing workflow later then finds the column already taken by that field
(ADR-025 consequence). The `workflow:builder` route and its nav entry are absent, the WORKFLOW page component and
TRANSITION page action never register, and a fresh page action defaults to NAVIGATE instead of TRANSITION
(ADR-031 D5).

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md):
- **D4**: a page whose ACTION is `TRANSITION` but this module is absent draws nothing for that action, instead of
  the error the original raised.
- **D5**: a freshly dropped page action defaults to `TRANSITION` only when this module is installed; otherwise it
  defaults to `NAVIGATE`.

## Known limitations

- The canvas's drag, zoom and connection interactions are not covered by automated tests (xyflow measures its
  container with a `ResizeObserver`, which jsdom always reports as zero). The pure functions behind it
  (`workflowGraph.ts`: layout, connection legality, definition↔graph conversion) are tested directly; the canvas
  itself is checked by looking at it.
