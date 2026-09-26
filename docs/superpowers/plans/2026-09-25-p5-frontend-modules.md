# P5 — Frontend module packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Rules file:** the sections "Global Constraints", "Design rulings" and "Shared templates" are the rules every task inherits. An executor reads them plus its own task, nothing else.

**Goal:** Move sapgis's GIS, workflow, automation, documents, page/view/form builders and assistant into eight published npm packages (`@chawpi/gis`, `@chawpi/workflow`, `@chawpi/automation`, `@chawpi/documents`, `@chawpi/pages`, `@chawpi/views`, `@chawpi/forms`, `@chawpi/agent`). Each exposes a `<name>Module(options?)` factory returning a `ChawpiModule`, so an app writes `<ChawpiApp modules={[gisModule(), workflowModule(), …]} />`.

**Architecture:**
- Every package plugs in only through the `ChawpiModule` contract that P4 put in `@chawpi/core` (`frontend/packages/core/src/registry/contract.ts`). Modules never import each other, and core never imports them.
- The pages builder offers module page components and ACTION kinds only through the registry slots `labelKey`, `icon`, `settings`, `preview` and `defaults`. gis (MAP) and workflow (WORKFLOW, TRANSITION) fill those slots, so all eight packages can be built in parallel.
- Code is copied from sapgis with the P4 port script, which Task 1 extends to map module paths. Hand-written seams are each module's factory, its i18n bundle, its query hooks, and its registry adapters (field renderer, page components, panels, history renderer).
- Wave 0 (Task 1) does every shared-file edit up front: tooling, package scaffolds, the lockfile, release-please and core re-exports. Wave 1 (Tasks 2–9) runs one task per package, each writing only inside its own directory. Wave 2 (Task 10) removes the moved keys from core `common.json`, adds the cross-package smoke test and runs the full verification.

**Tech Stack:** React 19.3, react-router 8.4, @tanstack/react-query 5.103, i18next 26.4 + react-i18next 17.0, maplibre-gl 6.10.0 + terra-draw 1.33.0 + terra-draw-maplibre-gl-adapter 1.4.1 (gis), @xyflow/react 12.11.6 (workflow), @tiptap/core|react|starter-kit 3 (documents), @dnd-kit/core 6.3.1 (pages), Vite 8.3 library mode + `tsc` declarations, vitest 5.0 + jsdom 30 + Testing Library, TypeScript 5.9.3, yarn 1.22 workspaces, Node 26.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md` (sections "Frontend design", "Commits, versioning and releases", "Tests"). Binding precedents: `docs/superpowers/plans/2026-09-25-p4-frontend-foundation.md` (Global Constraints, rulings R1–R11) and the P4 ledger `.superpowers/sdd/2026-09-25-p4-frontend-foundation/progress.md`, whose rulings override the P4 plan text.

## Global Constraints

- **NEVER run `git commit`, `git push` or `git stash`.** Leave every change uncommitted in the working tree. Every task ends with `git status --short` to confirm nothing was committed. No step in this plan commits.
- sapgis (`/Users/jorge/IdeaProjects/sapgis`) is READ-ONLY. Copy from it, never edit it. Do not touch `backend/`.
- **Directory ownership (this is what makes Wave 1 parallel):**
  - Task 1 is the only task that edits `frontend/tooling/*`, `release-please-config.json`, `yarn.lock`, `frontend/packages/core/src/index.ts`, and any `package.json`, `tsconfig*.json` or `vite.config.ts` under `frontend/packages/`.
  - Tasks 2–9 write only inside `frontend/packages/<their package>/`, and inside it only `src/**` and `README.md`. They never edit `package.json`, never run `yarn install` or `yarn add`, and never touch `frontend/packages/core`, `ui` or `testing`.
  - If a Wave 1 task finds it needs a dependency or a core export that Task 1 did not provide, it stops and reports `BLOCKED: <what>`. It never works around the gap by editing a shared file.
  - Task 10 is the only task that edits `frontend/packages/core/src/i18n/locales/*/common.json`, and it creates `frontend/packages/smoke/`.
- Formatting: the repo `.editorconfig` (TS/TSX 2 spaces, 160 columns) and root `.prettierrc.json`. Every task ends with `yarn prettier --write <touched files>`, then `yarn workspace @chawpi/<pkg> lint`, `yarn workspace @chawpi/<pkg> test` and `yarn workspace @chawpi/<pkg> build` for every package it touched. All must pass.
- Same UI and behaviour as sapgis: same markup and Tailwind classes, same texts, same REST calls (paths, verbs, bodies). Differences are allowed only where a ruling below says so.
- No new runtime dependencies. Versions are copied from `/Users/jorge/IdeaProjects/sapgis/frontend/package.json` exactly. `@dnd-kit/sortable` and `@dnd-kit/modifiers` are dropped (spec: unused).
- Heavy libraries stay in their owner:
  - `maplibre-gl`, `terra-draw` and `terra-draw-maplibre-gl-adapter` only in `@chawpi/gis`;
  - `@xyflow/react` only in `@chawpi/workflow`;
  - `@tiptap/*` only in `@chawpi/documents`;
  - `@dnd-kit/core` only in `@chawpi/pages`.

  Each package's `src/boundaries.test.ts` (Shared template T3) enforces this.
- A module package imports only `@chawpi/core`, `@chawpi/ui`, its own heavy library, `lucide-react` and the peers (react, react-dom, @tanstack/react-query, i18next, react-i18next, react-router). Tests may also import `@chawpi/testing`, `@testing-library/*` and `vitest`. **A module never imports another `@chawpi/<module>` package.**
- No hardcoded in-app URLs. Module routes are built with `useChawpiLinks().to('<moduleId>:<routeId>', params, search)`, and core routes with the named link builders (`links.record(object, id)`, `links.records(object)`, …). REST paths passed to `api()` stay the sapgis literals.
- Identifiers, comments and test names are in English. Comments are caveman style: short, say why, never restate the code. Spanish UI strings and Spanish test fixtures copied from sapgis stay as they are. Copied text is renamed `sapgis`→`chawpi`, `Sapgis`→`Chawpi`, `SAPGIS`→`Chawpi` (the port script applies this).
- `@chawpi/*` packages never list another `@chawpi/*` package in `devDependencies`. Internal ranges in `peerDependencies` are `"*"`. Sibling sources are reached in lint and tests through tsconfig `paths` and vitest `test.alias`.
- Commands run from the repo root `/Users/jorge/IdeaProjects/chawpi` unless a step says otherwise.
- The contract on disk is the source of truth: `frontend/packages/core/src/registry/contract.ts`. Where this plan and the file disagree, the file wins and the executor reports the difference.

## Design rulings (binding for every task)

- **M1: package wiring.**
  - Each package has the same layout as `@chawpi/core`:
    - `package.json` `exports` point at `dist/`;
    - `vite build` (library mode, ESM, dependencies and peers external) then `tsc -p tsconfig.build.json` for the declarations;
    - `tsconfig.json` (`noEmit`, with `paths` to sibling sources) for lint and tests.
  - Task 1's `frontend/tooling/scaffold-module.mjs` writes all of it. A Wave 1 task only writes `src/**` and `README.md`.
  - The public entry is `src/index.ts`. It exports the factory, the options type, the module id constant, and every component or hook the README names.
- **M2: factory.**
  - Each package exports `<name>Module(options: <Name>ModuleOptions = {}): ChawpiModule` from `src/module.tsx`.
  - Options always include `basePath?: string`. The default basePath keeps sapgis's URLs byte-identical (table below).
  - The factory is pure: it builds a new object on each call and has no global side effects. gis's `workerUrl` is only stored (M9).

  | Package | id / namespace | factory | default basePath | routes (id → path, chrome) | nav (group, order, labelKey) |
  |---|---|---|---|---|---|
  | `@chawpi/gis` | `gis` | `gisModule` | `gis` | `map` → `map`; `layers` → `layers` | declares group `gis` (`gis:nav.gis`, order 20); `map` 10 `gis:nav.maps`; `layers` 20 `gis:nav.layers`; disabled placeholder 30 `gis:nav.mapViews` |
  | `@chawpi/workflow` | `workflow` | `workflowModule` | `automation` | `builder` → `workflows` | `automation` 10 `workflow:nav.workflows` |
  | `@chawpi/automation` | `automation` | `automationModule` | `automation` | `rules` → `rules`; `runs` → `runs` | `automation` 20 `automation:nav.rules`; 30 `automation:nav.runs` |
  | `@chawpi/documents` | `documents` | `documentsModule` | `''` | `types` → `builder/documents`; `print` → `documents/:id/print` (chrome `bare`) | `builder` 30 `documents:nav.documents` |
  | `@chawpi/pages` | `pages` | `pagesModule` | `builder` | `builder` → `pages` | `builder` 10 `pages:nav.pages` |
  | `@chawpi/views` | `views` | `viewsModule` | `builder` | `builder` → `views` | `builder` 40 `views:nav.views` |
  | `@chawpi/forms` | `forms` | `formsModule` | `builder` | `builder` → `forms` | `builder` 20 `forms:nav.forms` |
  | `@chawpi/agent` | `agent` | `agentModule` | `automation` | `assistant` → `assistant` | `automation` 40 `agent:nav.assistant` |

  - Nav icons are the sapgis ones from `sapgis/frontend/src/components/layout/AppShell.tsx:54-74`:
    - `Map`, `Layers`, `Globe2` (gis);
    - `LayoutTemplate` (pages), `FileText` (forms), `FileSignature` (documents), `Columns3` (views);
    - `Workflow` (workflow), `Zap` (rules), `ListChecks` (runs), `Sparkles` (assistant).
  - Every route is `lazy` (`lazy: () => import('./X').then((m) => ({ default: m.X }))`), so a module's heavy library stays out of the app's first bundle.
  - Groups `builder` and `automation` already exist in core's `coreModule`, and a module never re-declares them.
- **M3: i18n.**
  - Each module ships `src/i18n.ts` exporting `<name>Messages: { es: {...}, en: {...} }`, and sets `i18n: <name>Messages`. Core loads it under the namespace = module id.
  - Keys **keep their sapgis paths**. `t('layers.title')` stays `t('layers.title')`, and the bundle holds `{ layers: { title } }`.
  - Components call `useTranslation(['<id>', 'common'])`. The module namespace is searched first; a key missing there resolves through i18next `fallbackNS: 'common'`, set in core (`createChawpiI18n`, Task 1 Step 10), so core strings (`common.save`, `common.cancel`…) keep working unchanged.
  - Nav and slot label keys are namespace-qualified (`'gis:nav.maps'`, `'workflow:pageComponents.WORKFLOW'`).
  - Pure helpers outside React use `getI18n().t('<id>:key')`.
  - Sources of the strings:
    - the sapgis feature `i18n.ts` side-effect bundles (layers, workflows, automations, assistant) are copied as plain objects;
    - keys a module uses from core's `common.json` are **copied** (not moved) into the module bundle.

    Task 10 deletes the core copies. Duplicating a key into two module bundles is allowed (for example `pages.geometry` in gis for the MAP settings editor).
  - `src/i18n.test.ts` (Shared template T4) pins es/en parity.
  - Every test renders with `renderWithProviders(ui, { modules: [<name>Module()] })`, so the module namespace is loaded.
- **M4: queries and types owned by modules.**
  - Query hooks and types that P4 left out of core (P4 R10) are recreated inside their module, in `src/api.ts` and `src/types.ts`. They copy the sapgis bodies from `sapgis/frontend/src/lib/queries.ts`, `types/metadata.ts` and `features/history/api.ts`, with the same query keys, REST paths and retry flags.
  - Core hooks come from `@chawpi/core`: `useObjects`, `useObjectDefinition`, `useRecord`, `usePages`, `useSavePage`, `useViews`, `useForms`, and so on. Core types (`FieldMeta`, `ObjectDefinition`, `PageComponent`, `RecordItem`, …) are imported the same way.
  - When a module reads a key that core carries only through the index signature (`field.geometry`, `record.geometries`, `component.geometry`, `object.geometry`, `entry.documentId`), it narrows the key with a small local accessor in `src/types.ts`, never with a cast spread across files.
- **M5: cross-module needs go through core or duplicate a read.**
  - automation also lists document types. It uses a local `useDocumentTypeOptions(objectName)` with documents' query key `['document-types', objectName]` and the same GET. It sets `retry: false`: an app without documents answers 404, and that must not retry.
  - automation reads the object's workflow (states and transitions) with its own `useWorkflowOutline(objectName)`. It uses the same query key `['workflow', objectName]`, the same GET `/objects/${objectName}/workflow` and `retry: false` as workflow's `useWorkflow`, so the cache is shared, but it has no package dependency.
  - pages never reads workflows or geometries. The TRANSITION picker is workflow's `pageActions.TRANSITION.settings`, and the MAP shape picker is gis's `pageComponents.MAP.settings`.
- **M6: pages builder over registry slots.**
  - The palette lists core's own content types, then every `registry.pageComponents` entry in registration order (label `t(def.labelKey)`, icon `def.icon`).
  - Dropping a module type creates `{ ...blankNode(type), ...def.defaults }`.
  - The inspector draws `def.settings` with `{ component, definition, objectName, onChange }`.
  - The canvas mock draws `def.preview` with `{ component, definition }`. Without one, it draws a blank placeholder that shows the label.
  - ACTION:
    - The kind picker lists every `registry.pageActions` key in registration order, then `NAVIGATE`. Its label is `t(pageActions[kind].labelKey)`, or `pages.actionKinds.NAVIGATE`.
    - A freshly dropped ACTION takes the **first** listed kind, which is TRANSITION when workflow is installed (sapgis parity, since sapgis defaulted to `'TRANSITION'`) and NAVIGATE otherwise. It merges that kind's `defaults`.
    - Changing the kind merges the new kind's `defaults`. `pageActions[kind].settings` draws below the picker.
  - A saved page that contains a type with no registered definition keeps the node untouched on save. The canvas shows a placeholder labelled with the raw type, and the inspector shows only the built-in settings (title, column).
- **M7: records and history seams (the P4 R9 slots, filled).**
  - gis:
    - `fieldRenderers.GEOMETRY` (section `geometries`, `uniqueAllowed: false`);
    - `pageComponents.MAP`;
    - `recordListActions` (open on map);
    - `dashboardCards` (objects with geometry);
    - `objectColumns` (geometry column of the objects page);
    - `objectTileDetails` (type · EPSG line);
    - `auditValueFormatters` (GeoJSON → `gis:history.geometryUpdated`);
    - `auditFieldLabels` (`geometry` → `gis:history.geometry`);
    - `recordQueryKeys` (`(object) => [['features', object]]`).
  - workflow:
    - `pageComponents.WORKFLOW`;
    - `pageActions.TRANSITION`;
    - `objectFlags` (`useWorkflowFlags` → `{ workflow: Boolean(data) }`).
  - documents:
    - `historyRenderers.ISSUE` (`body`: IssuedDocumentLink, `labelKey: 'documents:operations.ISSUE'`, `tone: 'info'`);
    - `recordPanels` (RecordDocuments);
    - the bare print route.
- **M8: GEOMETRY field settings (P4 ledger, binding).**
  - `settings.defaults = { geometryType: 'POLYGON', srid: '4326' }`.
  - `toPayload = (s) => ({ geometryType: s.geometryType, srid: Number(s.srid) || 4326 })`.
  - `dimension` is never sent.
  - The editor is the sapgis geometry Select plus CRS Input from `ObjectBuilderPage`/`ObjectEditorPage`.
- **M9: MapLibre worker.**
  - `@chawpi/gis` contains **no** `?url` import, because library builds must not depend on one bundler's query syntax, and Vite lib mode would inline the worker as a data URL.
  - The app passes the worker URL: `gisModule({ workerUrl })`. The factory only stores it (`src/lib/mapWorker.ts`). MapView calls maplibre's `setWorkerUrl` once, just before its first map. With no URL it logs one console warning, because maplibre would otherwise fail silently.
  - gis loads MapView lazily everywhere, through `LazyMapView.tsx`. Importing `@chawpi/gis` never loads maplibre: it stays out of the app's first bundle, and the package imports cleanly under jsdom. The cost is one frame of an empty box before each map. The README shows the Vite one-liner `import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'`, which is what sapgis's `MapView.tsx:2` did, now in app code, plus the webpack/rspack (`new URL('maplibre-gl/dist/maplibre-gl-worker.mjs', import.meta.url).href`) and copy-to-public alternatives.
  - The consumer also imports `maplibre-gl/dist/maplibre-gl.css`, and the README says so.
- **M10: documents print CSS.**
  - `@chawpi/documents` ships `src/print.css`, copied from the print rules in `sapgis/frontend/src/index.css` and written as plain CSS.
  - The build copies it to `dist/print.css`, and it is exported as `@chawpi/documents/print.css` (Task 1 sets up the `exports` entry and the `cp` build step).
  - The consumer imports it once. The README says so.
- **M11: carried P4 rulings.**
  - The record page falls back only on a 404 (core already does this). Endpoints of absent modules must answer 404.
  - A null or unknown ACTION kind draws nothing (core ActionButton).
  - NAVIGATE without a target links to the objects list (core).
  - Unknown page component types render core's muted placeholder `pages.componentUnavailable`.
  - Modules do not re-implement any of these.
- **M12: tests.**
  - **Partial mocks only.** A ported `vi.mock('@/lib/queries' …)` or `vi.mock('@/components/ui/…')` lands on the `@chawpi/core` or `@chawpi/ui` barrel. Mocking the whole barrel would also replace the providers `renderWithProviders` mounts. Always write `vi.mock('@chawpi/core', async (importOriginal) => ({ ...(await importOriginal<typeof import('@chawpi/core')>()), useX: … }))`, merge two mocks of the same barrel into one factory, and point mocks of the module's own files at their relative path (`./api`).
  - Every sapgis test of moved code is ported with the port script.
  - `vi.mock` paths are rewritten to the package's relative paths, and `renderWithProviders` gets `modules: [<name>Module()]`.
  - Tests that sapgis ran through core (DynamicForm GEOMETRY, PageRenderer MAP/WORKFLOW, ActionButton TRANSITION, RecordHistory ISSUE, RecordDetailPage documents panel) are rewritten in the owning module, against core components plus that module.

## Shared templates

**T1 — module factory skeleton** (`src/module.tsx`; replace `<name>`/`<Name>`, fill the slots per task):

```tsx
import type { ChawpiModule } from '@chawpi/core'
import { <name>Messages } from './i18n'

export const <NAME>_MODULE_ID = '<name>'

export interface <Name>ModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's urls
  basePath?: string
}

export function <name>Module(options: <Name>ModuleOptions = {}): ChawpiModule {
  return {
    id: <NAME>_MODULE_ID,
    basePath: options.basePath ?? '<default basePath>',
    routes: [],
    nav: [],
    i18n: <name>Messages
  }
}
```

**T2 — module wiring test** (`src/module.test.tsx`): proves the registry accepts the module next to core, and that the routes follow `basePath`.

```tsx
import { coreModule, createRegistry, createLinks } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { <name>Module } from './module'

describe('<name>Module', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, <name>Module()])).not.toThrow()
  })

  it('mounts its routes under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, <name>Module()]))
    expect(links.to('<name>:<first route id>'<, params>)).toBe('<sapgis url>')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, <name>Module({ basePath: 'x' })]))
    expect(links.to('<name>:<first route id>'<, params>)).toBe('/x/<route path>')
  })
})
```

**T3 — boundary test** (`src/boundaries.test.ts`). `OWN` lists the package's heavy libraries (an empty string for none):

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

// a module stays installable on its own: no other module package, no other module's heavy library.
// import.meta.dirname, not new URL(.., import.meta.url): vite rewrites the latter under jsdom
const SRC = import.meta.dirname
const OWN = /^(<own heavy libs regex, e.g. maplibre-gl|terra-draw.*>)$/
const HEAVY = /^(maplibre-gl|terra-draw.*|@xyflow\/.+|@tiptap\/.+|@dnd-kit\/.+)$/

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function importsOf(file: string): string[] {
  return [...readFileSync(file, 'utf8').matchAll(/from '([^']+)'|import '([^']+)'/g)].map((match) => match[1] ?? match[2])
}

describe('<name> boundaries', () => {
  it('imports no other module package and no heavy library it does not own', () => {
    const offenders = sources(SRC).flatMap((file) =>
      importsOf(file)
        .map((specifier) =>
          specifier
            .split('/')
            .slice(0, specifier.startsWith('@') ? 2 : 1)
            .join('/')
        )
        .filter((pkg) => (pkg.startsWith('@chawpi/') && pkg !== '@chawpi/core' && pkg !== '@chawpi/ui') || (HEAVY.test(pkg) && !OWN.test(pkg)))
        .map((pkg) => `${relative(SRC, file)} -> ${pkg}`)
    )
    expect(offenders).toEqual([])
  })

  // REST paths passed to api() are fine ('/documents/x' is also a REST path); what links must build
  // is every in-app url handed to a Link, an <a> or navigate()
  it('never spells an in-app url: links build them', () => {
    const offenders = sources(SRC).filter((file) => /(?:\bto=|\bhref=|navigate\()\{?\s*[`'"]\/(?!\/)/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
})
```

For a package without a heavy library, `OWN = /^$/`.

**T4 — i18n parity test** (`src/i18n.test.ts`):

```ts
import { describe, expect, it } from 'vitest'
import { <name>Messages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('<name> messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(<name>Messages.en)).toEqual(leaves(<name>Messages.es))
  })

  it('names its nav entries', () => {
    expect(leaves(<name>Messages.es)).toEqual(expect.arrayContaining([<every nav/slot key without namespace, e.g. 'nav.maps'>]))
  })
})
```

**T5 — README skeleton** (`README.md`): the sections `# @chawpi/<name>`, `## Install` (the `.npmrc` line `@chawpi:registry=https://npm.pkg.github.com`, then `yarn add @chawpi/<name>` plus its heavy library if any), `## Usage` (a full `ChawpiApp` snippet), `## What it adds` (routes, nav, registry slots, as a table), `## Options`, and `## Backend` (the chawpi backend module it needs, and that absent endpoints must answer 404).

## Review Focus

1. **Page with a component or action whose module is not installed.** An app has pages without gis or workflow, and a stored page contains MAP, WORKFLOW or an ACTION of kind TRANSITION. The builder must still open the page, show a labelled placeholder, and save the node back untouched (not drop it or crash). The renderer shows core's placeholder. Pinned in Task 6 (`PageBuilderPage.test.tsx`, "keeps a component whose module is missing").
2. **ACTION default kind with and without workflow.**
   - With workflow, a dropped ACTION is TRANSITION and offers the transitions of an enabled workflow only.
   - Without workflow, it is NAVIGATE, and the kind picker has no TRANSITION.

   Pinned in Task 6 (palette/inspector tests with a fake action module and without one) and Task 3 (TRANSITION settings lists only enabled-workflow transitions).
3. **GEOMETRY settings payload.**
   - A blank or garbage CRS sends `srid: 4326`.
   - The type defaults to POLYGON.
   - `dimension` is never in the body of the object/field POST.

   Pinned in Task 2 (`src/slots/geometrySettings.test.tsx`: `toPayload` / `geometryPayload`, plus the editor through core's `ModuleFieldSettings` with `gisModule()`).
4. **Automation against an object without a workflow.** GET `/objects/x/workflow` answers 404: the state and transition pickers stay empty, nothing retries, and the rule still saves. Pinned in Task 4 (`AutomationBuilderPage.test.tsx`).
5. **Custom basePath and deep links.**
   - With `documentsModule({ basePath: 'docs' })`, the history ISSUE link's print href is `/docs/documents/:id/print`, and a signed-out visit to it lands on login.
   - With `gisModule({ basePath: 'geo' })`, the record list's "open on map" goes to `/geo/map?object=x`.

   Pinned in Task 5 (`IssuedDocumentLink.test.tsx`) and Task 2 (`OpenOnMap.test.tsx`), plus Task 10's smoke test for the redirect.

## File structure

```
frontend/tooling/
  port-from-sapgis.mjs (+ .test.mjs)   + MODULE_MAP: sapgis module paths -> P5 packages (Task 1)
  scaffold-module.mjs (+ .test.mjs)    writes each module package's wiring (Task 1)
frontend/packages/<name>/              one per module, same wiring as core
  package.json tsconfig.json tsconfig.build.json vite.config.ts   (Task 1)
  src/index.ts                          public api (Task 1 placeholder, filled by the package task)
  src/module.tsx src/module.test.tsx    factory + wiring test
  src/i18n.ts src/i18n.test.ts          namespace bundle + parity test
  src/boundaries.test.ts                import boundaries
  src/api.ts src/types.ts               module-owned queries and types
  src/test/setup.ts                     jest-dom (Task 1)
  README.md
frontend/packages/gis/src/        components/{MapView,GeometryField}.tsx, lib/{geo,wms}.ts, map/MapPage.tsx, layers/*, slots/*
frontend/packages/workflow/src/   WorkflowBuilderPage, WorkflowCanvas, WorkflowInspector, WorkflowPanel, workflowGraph, slots/*
frontend/packages/automation/src/ AutomationBuilderPage, AutomationRunsPage, RunTable, automationDraft
frontend/packages/documents/src/  DocumentTypesPage, TemplateEditor, DocumentView, PrintableDocumentPage, RecordDocuments, IssuedDocumentLink, nodes, print.css
frontend/packages/pages/src/      PageBuilderPage, builder/*, builder/preview/*
frontend/packages/views/src/      ViewBuilderPage
frontend/packages/forms/src/      FormBuilderPage
frontend/packages/agent/src/      AssistantPage, transcript
frontend/packages/smoke/          private: ChawpiApp + all 8 modules smoke test (Task 10)
```

## Waves

| Wave | Tasks | Runs | Touches |
|---|---|---|---|
| 0 | Task 1 — prep | alone | tooling, 8 package scaffolds, yarn.lock, release-please, core `index.ts` re-exports |
| 1 | Tasks 2–9 — gis, workflow, automation, documents, pages, views, forms, agent | **all 8 in parallel** | each only `frontend/packages/<own>/src/**` + `README.md` |
| 2 | Task 10 — integration | alone, after every Wave 1 task is reviewed | core `common.json` (es/en), `frontend/packages/smoke/`, full verification |

Wave 1 is safe to run in parallel for four reasons:
- no two tasks share a file;
- every dependency is installed by Task 1;
- pages consumes only the registry contract, not gis or workflow code;
- each task tests against its own fakes.

---

## Wave 0

### Task 1: Prep — port-script module map, eight package scaffolds, lockfile, release-please, core re-exports

**Files:**
- Modify: `frontend/tooling/port-from-sapgis.mjs` (replace `CORE_MAP` and `mapSpecifier`)
- Modify: `frontend/tooling/port-from-sapgis.test.mjs` (append two tests)
- Create: `frontend/tooling/scaffold-module.mjs`, `frontend/tooling/scaffold-module.test.mjs`
- Create (written by the scaffold script): `frontend/packages/{gis,workflow,automation,documents,pages,views,forms,agent}/{package.json,tsconfig.json,tsconfig.build.json,vite.config.ts,src/index.ts,src/test/setup.ts}`
- Modify: `release-please-config.json` (append 8 `extra-files` entries)
- Modify: `frontend/packages/core/src/index.ts` (re-exports listed in Step 9)
- Modify: `frontend/packages/core/src/i18n/createI18n.ts` (Step 10: `fallbackNS`)
- Modify: `frontend/packages/core/src/i18n/createI18n.test.ts` (Step 10: fallback test)
- Modify: `yarn.lock` (by `yarn install`)

**Interfaces:**
- Consumes: `frontend/packages/core/package.json` (version, scripts, peers, devDependencies, publishConfig), and the existing `mapSpecifier(specifier, targetFile)` / `portSource(source, targetFile)` of the port script.
- Produces:
  - `mapSpecifier` resolves sapgis module paths to paths relative to the target when the target is inside the owning package. It returns `null` (reported `MANUAL`) for a cross-module import.
  - The CLI stays `node frontend/tooling/port-from-sapgis.mjs <path under sapgis/frontend/src> <repo-relative target>`.
  - Eight buildable, lintable, empty packages `@chawpi/<name>`. Each has scripts `lint`, `test` and `build`. Each has vitest aliases and tsconfig paths for `@chawpi/ui`, `@chawpi/core`, `@chawpi/testing` and itself.
  - `@chawpi/documents` exports `./print.css`, and its build ends `&& cp src/print.css dist/print.css`.
  - All heavy libraries are installed in the root `node_modules`.
  - `ui`, `core` and `testing` have `dist/` built, so Wave 1 builds can resolve their declarations.
  - The core re-exports of Step 9.
  - Core's `createChawpiI18n` sets `fallbackNS: CORE_NAMESPACE` (Step 10), so a module namespace's `t('common.*')` resolves instead of rendering the raw key.

- [ ] **Step 1: Write the failing port-script tests**

Append to `frontend/tooling/port-from-sapgis.test.mjs`:

```js
const inWorkflow = join(REPO_ROOT, 'frontend/packages/workflow/src/WorkflowBuilderPage.tsx')
const inPagesBuilder = join(REPO_ROOT, 'frontend/packages/pages/src/builder/Inspector.tsx')
const inGisLayers = join(REPO_ROOT, 'frontend/packages/gis/src/layers/LayersPage.tsx')
const inDocuments = join(REPO_ROOT, 'frontend/packages/documents/src/module.tsx')
const inViews = join(REPO_ROOT, 'frontend/packages/views/src/ViewBuilderPage.tsx')

test('maps a module location inside its own package to a relative path', () => {
  assert.equal(mapSpecifier('@/features/workflows/api', inWorkflow), './api')
  assert.equal(mapSpecifier('@/features/pages/builder/preview/ComponentMock', inPagesBuilder), './preview/ComponentMock')
  assert.equal(mapSpecifier('@/components/map/MapView', inGisLayers), '../components/MapView')
  assert.equal(mapSpecifier('@/lib/geo', inGisLayers), '../lib/geo')
  assert.equal(mapSpecifier('@/features/layers/types', inGisLayers), './types')
  assert.equal(mapSpecifier('@/features/history/IssuedDocumentLink', inDocuments), './IssuedDocumentLink')
  assert.equal(mapSpecifier('@/features/assistant/api', join(REPO_ROOT, 'frontend/packages/agent/src/AssistantPage.tsx')), './api')
})

test('leaves a cross-module import for a human, and keeps what stayed in core in core', () => {
  assert.equal(mapSpecifier('@/features/workflows/api', inPagesBuilder), null)
  assert.equal(mapSpecifier('@/lib/geo', inPagesBuilder), null)
  assert.equal(mapSpecifier('@/features/pages/builder/templates', inPagesBuilder), '@chawpi/core')
  assert.equal(mapSpecifier('@/features/views/viewColumns', inViews), '@chawpi/core')
  assert.equal(mapSpecifier('@/features/history/changes', inWorkflow), '@chawpi/core')
  assert.equal(mapSpecifier('@/features/admin/api', inWorkflow), '@chawpi/core')
  const { unmapped } = portSource("import { useWorkflow } from '@/features/workflows/api'\n", inPagesBuilder)
  assert.deepEqual(unmapped, ['@/features/workflows/api'])
})
```

- [ ] **Step 2: Run them to see them fail**

Run: `node --test frontend/tooling/port-from-sapgis.test.mjs`
Expected: FAIL. The first new test gets `'@chawpi/core'` where it expected `'./api'`.

- [ ] **Step 3: Implement the module map**

In `frontend/tooling/port-from-sapgis.mjs`:
- add `const PACKAGES_DIR = join(REPO_ROOT, 'frontend/packages')` below `CORE_SRC`;
- replace the whole `CORE_MAP` constant and the whole `mapSpecifier` function with the code below.

`PACKAGE_MAP`, `I18N_SIDE_EFFECT`, `RENAMES`, `portSource` and `main` stay as they are.

```js
// sapgis location -> location under core/src. the specific rules win over MODULE_MAP (builder files
// that stayed in core); the generic ones only apply after it.
const CORE_SPECIFIC = [
  [/^@\/lib\/api$/, 'api/client'],
  [/^@\/lib\/queries$/, 'queries'],
  [/^@\/lib\/metadata-to-zod$/, 'lib/metadata-to-zod'],
  [/^@\/types\/metadata$/, 'types/metadata'],
  [/^@\/app\/auth$/, 'auth/AuthProvider'],
  [/^@\/components\/layout\/AppShell$/, 'shell/PageHeader'],
  [/^@\/features\/history\/types$/, 'types/audit'],
  [/^@\/features\/views\/viewColumns$/, 'features/records/viewColumns'],
  [/^@\/features\/pages\/builder\/templates$/, 'components/page-renderer/layout']
]
const CORE_GENERIC = [
  [/^@\/components\/(.+)$/, 'components/$1'],
  [/^@\/features\/(.+)$/, 'features/$1']
]

// sapgis location -> [P5 package, location under its src]. resolves only inside that package: from
// anywhere else it would be a cross-module import, which chawpi forbids, so a human decides (MANUAL)
const MODULE_MAP = [
  [/^@\/components\/map\/(.+)$/, 'gis', 'components/$1'],
  [/^@\/lib\/(geo|wms)$/, 'gis', 'lib/$1'],
  [/^@\/features\/map\/(.+)$/, 'gis', 'map/$1'],
  [/^@\/features\/layers\/(.+)$/, 'gis', 'layers/$1'],
  [/^@\/features\/workflows\/(.+)$/, 'workflow', '$1'],
  [/^@\/features\/automations\/(.+)$/, 'automation', '$1'],
  [/^@\/features\/documents\/(.+)$/, 'documents', '$1'],
  [/^@\/features\/history\/IssuedDocumentLink$/, 'documents', 'IssuedDocumentLink'],
  [/^@\/features\/pages\/(.+)$/, 'pages', '$1'],
  [/^@\/features\/views\/(.+)$/, 'views', '$1'],
  [/^@\/features\/forms\/(.+)$/, 'forms', '$1'],
  [/^@\/features\/assistant\/(.+)$/, 'agent', '$1']
]

const isInside = (dir, file) => !relative(dir, file).startsWith('..')

function relativeImport(targetFile, absolute) {
  const path = relative(dirname(targetFile), absolute)
  return path.startsWith('.') ? path : `./${path}`
}

export function mapSpecifier(specifier, targetFile) {
  for (const [pattern, replacement] of PACKAGE_MAP) if (pattern.test(specifier)) return replacement
  // outside core (P5 packages) everything core owns comes from its public api
  const toCore = (pattern, replacement) =>
    isInside(CORE_SRC, targetFile) ? relativeImport(targetFile, join(CORE_SRC, specifier.replace(pattern, replacement))) : '@chawpi/core'
  for (const [pattern, replacement] of CORE_SPECIFIC) if (pattern.test(specifier)) return toCore(pattern, replacement)
  for (const [pattern, pkg, replacement] of MODULE_MAP) {
    if (!pattern.test(specifier)) continue
    const src = join(PACKAGES_DIR, pkg, 'src')
    return isInside(src, targetFile) ? relativeImport(targetFile, join(src, specifier.replace(pattern, replacement))) : null
  }
  for (const [pattern, replacement] of CORE_GENERIC) if (pattern.test(specifier)) return toCore(pattern, replacement)
  return null
}
```

- [ ] **Step 4: Run the port-script tests**

Run: `node --test frontend/tooling/port-from-sapgis.test.mjs`
Expected: PASS for every test, including P4's `leaves what it cannot place for a human`, where `@/lib/geo` from core is still `null`.

- [ ] **Step 5: Write the failing scaffold test**

Create `frontend/tooling/scaffold-module.test.mjs`:

```js
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'

import { MODULES, PACKAGES_DIR, moduleManifest } from './scaffold-module.mjs'

const core = JSON.parse(readFileSync(join(PACKAGES_DIR, 'core/package.json'), 'utf8'))
const manifest = (name) => moduleManifest(MODULES.find((spec) => spec.name === name), core)

test('every module peers on core and ui at any version, in lockstep with core', () => {
  assert.deepEqual(
    MODULES.map((spec) => spec.name),
    ['gis', 'workflow', 'automation', 'documents', 'pages', 'views', 'forms', 'agent']
  )
  for (const spec of MODULES) {
    const pkg = moduleManifest(spec, core)
    assert.equal(pkg.name, `@chawpi/${spec.name}`)
    assert.equal(pkg.version, core.version)
    assert.equal(pkg.peerDependencies['@chawpi/core'], '*')
    assert.equal(pkg.peerDependencies['@chawpi/ui'], '*')
    assert.equal(pkg.peerDependencies.react, core.peerDependencies.react)
    assert.equal(pkg.dependencies['lucide-react'], core.dependencies['lucide-react'])
    // set-version.mjs never bumps devDependencies, so an internal range there would go stale
    assert.deepEqual(Object.keys(pkg.devDependencies).filter((name) => name.startsWith('@chawpi/')), [])
  }
})

test('each heavy library belongs to exactly one module', () => {
  const owners = {}
  for (const spec of MODULES) for (const dep of Object.keys(spec.dependencies)) (owners[dep] ??= []).push(spec.name)
  assert.deepEqual(owners, {
    'maplibre-gl': ['gis'],
    'terra-draw': ['gis'],
    'terra-draw-maplibre-gl-adapter': ['gis'],
    '@xyflow/react': ['workflow'],
    '@tiptap/core': ['documents'],
    '@tiptap/react': ['documents'],
    '@tiptap/starter-kit': ['documents'],
    '@dnd-kit/core': ['pages']
  })
})

test('documents ships its print stylesheet, the others are side-effect free', () => {
  const documents = manifest('documents')
  assert.equal(documents.exports['./print.css'], './dist/print.css')
  assert.match(documents.scripts.build, / && cp src\/print\.css dist\/print\.css$/)
  assert.deepEqual(documents.sideEffects, ['./dist/print.css'])
  assert.equal(manifest('views').sideEffects, false)
  assert.equal(manifest('views').exports['./print.css'], undefined)
})
```

- [ ] **Step 6: Run it to see it fail**

Run: `node --test frontend/tooling/scaffold-module.test.mjs`
Expected: FAIL with `Cannot find module '.../scaffold-module.mjs'`.

- [ ] **Step 7: Write the scaffold script**

Create `frontend/tooling/scaffold-module.mjs`:

````js
#!/usr/bin/env node
// writes the build and test wiring of every P5 module package, the same wiring core has: package.json,
// tsconfigs, vite config, jest-dom setup and an empty entry. sources come later, one task per package;
// an existing src/index.ts or setup file is never overwritten, so re-running it is safe.
// usage: node frontend/tooling/scaffold-module.mjs
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const PACKAGES_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../packages')

// versions copied from sapgis/frontend/package.json exactly
export const MODULES = [
  {
    name: 'gis',
    description: 'Chawpi GIS module: map and layers pages, the GEOMETRY field and the MAP page component',
    dependencies: { 'maplibre-gl': '6.10.0', 'terra-draw': '1.33.0', 'terra-draw-maplibre-gl-adapter': '1.4.1' }
  },
  {
    name: 'workflow',
    description: 'Chawpi workflow module: builder, canvas, WORKFLOW page component and TRANSITION action',
    dependencies: { '@xyflow/react': '12.11.6' }
  },
  { name: 'automation', description: 'Chawpi automation module: rule builder and run log', dependencies: {} },
  {
    name: 'documents',
    description: 'Chawpi documents module: document types, template editor, issued documents and print page',
    dependencies: { '@tiptap/core': '3', '@tiptap/react': '3', '@tiptap/starter-kit': '3' },
    css: ['print.css']
  },
  { name: 'pages', description: 'Chawpi page builder module', dependencies: { '@dnd-kit/core': '6.3.1' } },
  { name: 'views', description: 'Chawpi list view builder module', dependencies: {} },
  { name: 'forms', description: 'Chawpi form builder module', dependencies: {} },
  { name: 'agent', description: 'Chawpi assistant module', dependencies: {} }
]

export function moduleManifest(spec, core) {
  const css = spec.css ?? []
  return {
    name: `@chawpi/${spec.name}`,
    version: core.version,
    description: spec.description,
    type: 'module',
    files: ['dist', 'README.md'],
    // a stylesheet is imported for its effect; everything else may be tree-shaken
    sideEffects: css.length ? css.map((file) => `./dist/${file}`) : false,
    main: './dist/index.js',
    module: './dist/index.js',
    types: './dist/index.d.ts',
    exports: {
      '.': { types: './dist/index.d.ts', import: './dist/index.js' },
      ...Object.fromEntries(css.map((file) => [`./${file}`, `./dist/${file}`])),
      './package.json': './package.json'
    },
    publishConfig: core.publishConfig,
    scripts: {
      lint: core.scripts.lint,
      test: core.scripts.test,
      build: [core.scripts.build, ...css.map((file) => `cp src/${file} dist/${file}`)].join(' && ')
    },
    dependencies: { 'lucide-react': core.dependencies['lucide-react'], ...spec.dependencies },
    peerDependencies: { '@chawpi/core': '*', '@chawpi/ui': '*', ...core.peerDependencies },
    devDependencies: core.devDependencies
  }
}

function tsconfig(name) {
  return {
    extends: '../../tsconfig.base.json',
    compilerOptions: {
      noEmit: true,
      types: ['node', 'vitest/globals', '@testing-library/jest-dom'],
      paths: {
        '@chawpi/ui': ['../ui/src/index.ts'],
        '@chawpi/core': ['../core/src/index.ts'],
        '@chawpi/testing': ['../testing/src/index.ts'],
        [`@chawpi/${name}`]: ['./src/index.ts']
      }
    },
    include: ['src', 'vite.config.ts']
  }
}

// no paths: declarations resolve siblings through node_modules, i.e. their built dist
const TSCONFIG_BUILD = {
  extends: '../../tsconfig.base.json',
  compilerOptions: { declaration: true, emitDeclarationOnly: true, outDir: 'dist', rootDir: 'src', types: [] },
  include: ['src'],
  exclude: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'src/test']
}

function viteConfig(name) {
  return `import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import pkg from './package.json' with { type: 'json' }

const external = [...Object.keys(pkg.dependencies), ...Object.keys(pkg.peerDependencies)]
const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

export default defineConfig({
  plugins: [react()],
  build: {
    lib: { entry: here('./src/index.ts'), formats: ['es'], fileName: 'index' },
    rolldownOptions: {
      // deep imports too (a library's css, its worker file): the consumer's bundler resolves them
      external: (id: string) => external.some((dep) => id === dep || id.startsWith(\`\${dep}/\`))
    },
    sourcemap: true
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    // tests run against sibling sources, so nothing has to be built first
    alias: {
      '@chawpi/ui': here('../ui/src/index.ts'),
      '@chawpi/core': here('../core/src/index.ts'),
      '@chawpi/testing': here('../testing/src/index.ts'),
      '@chawpi/${name}': here('./src/index.ts')
    }
  }
})
`
}

const json = (value) => `${JSON.stringify(value, null, 4)}\n`

function write(path, text, { keep = false } = {}) {
  if (keep && existsSync(path)) return
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, text)
}

function main() {
  const core = JSON.parse(readFileSync(join(PACKAGES_DIR, 'core/package.json'), 'utf8'))
  for (const spec of MODULES) {
    const dir = join(PACKAGES_DIR, spec.name)
    write(join(dir, 'package.json'), json(moduleManifest(spec, core)))
    write(join(dir, 'tsconfig.json'), json(tsconfig(spec.name)))
    write(join(dir, 'tsconfig.build.json'), json(TSCONFIG_BUILD))
    write(join(dir, 'vite.config.ts'), viteConfig(spec.name))
    write(join(dir, 'src/test/setup.ts'), "import '@testing-library/jest-dom/vitest'\n", { keep: true })
    write(join(dir, 'src/index.ts'), '// the package task fills this in\nexport {}\n', { keep: true })
    // a css file is a build target (see moduleManifest's `scripts.build`): write a placeholder so
    // `yarn build` never fails on a missing source file before its owning task fills it in
    for (const file of spec.css ?? []) write(join(dir, `src/${file}`), `/* ${spec.name}: Task 5 fills this in */\n`, { keep: true })
    console.log(`scaffolded @chawpi/${spec.name}`)
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main()
````

- [ ] **Step 8: Run the scaffold test, then the script**

Run: `node --test frontend/tooling/scaffold-module.test.mjs`
Expected: PASS (3 tests).

Run: `node frontend/tooling/scaffold-module.mjs`
Expected: eight lines, from `scaffolded @chawpi/gis` to `scaffolded @chawpi/agent`.

Run: `yarn prettier --write frontend/tooling/port-from-sapgis.mjs frontend/tooling/port-from-sapgis.test.mjs frontend/tooling/scaffold-module.mjs frontend/tooling/scaffold-module.test.mjs 'frontend/packages/{gis,workflow,automation,documents,pages,views,forms,agent}/{package.json,tsconfig.json,tsconfig.build.json,vite.config.ts,src/**/*.ts}'`

- [ ] **Step 9: Re-export what the module packages need from core**

Wave 1 packages may import core only through `@chawpi/core`. In `frontend/packages/core/src/index.ts`, append these exact lines. Each one names a symbol that already exists in core but is not yet exported.

```ts
// workflow's inspector picks which roles may fire a transition
export { useRoles } from './features/admin/api'
```

This is the only one. Tasks 2 and 4–9 grep-verified that everything else they import is already exported.

Then run: `yarn workspace @chawpi/core lint && yarn workspace @chawpi/core test`
Expected: PASS. The `boundaries.test.ts` stays green, because these are re-exports of existing core files.

- [ ] **Step 10: Fix the i18n namespace fallback in core (C1)**

Every module's `useTranslation(['<id>', 'common'])` relies on i18next falling back from the module namespace to `common` for keys like `common.save`. react-i18next 17 only does this when `nsMode: 'fallback'` is set, or when `fallbackNS` is set — neither is set today, so `t('common.loading')` inside a module namespace renders the literal string `"common.loading"` instead of the translation. Fix it in core before any Wave 1 task builds against it, since Wave 1 packages import `@chawpi/core` from its built `dist/`.

In `frontend/packages/core/src/i18n/createI18n.ts`, add `fallbackNS: CORE_NAMESPACE` to the `init({...})` call:

```ts
  const instance = i18next.createInstance()
  void instance.use(initReactI18next).init({
    resources,
    lng: startLanguage(storageKey, languages),
    fallbackLng: languages[0],
    ns: [CORE_NAMESPACE, ...modules.map((module) => module.id)],
    defaultNS: CORE_NAMESPACE,
    fallbackNS: CORE_NAMESPACE,
    interpolation: { escapeValue: false },
    // resources are inline: init synchronously so the first render already has strings
    initAsync: false
  })
```

Add a test to `frontend/packages/core/src/i18n/createI18n.test.ts`, in the `describe('createChawpiI18n', …)` block:

```ts
  it('falls back to common for a module namespace missing a key', () => {
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [{ id: 'x' }] })
    expect(i18n.getFixedT(null, 'x')('common.loading')).toBe('Cargando…')
  })
```

Run: `yarn workspace @chawpi/core lint && yarn workspace @chawpi/core test`
Expected: PASS, including the new test.

- [ ] **Step 11: Register the new packages with release-please**

In `release-please-config.json`, replace:

```json
                { "type": "json", "path": "frontend/packages/testing/package.json", "jsonpath": "$.version" }
```

with:

```json
                { "type": "json", "path": "frontend/packages/testing/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/gis/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/workflow/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/automation/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/documents/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/pages/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/views/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/forms/package.json", "jsonpath": "$.version" },
                { "type": "json", "path": "frontend/packages/agent/package.json", "jsonpath": "$.version" }
```

Edit in place, and do not reformat the rest of the file. P2 may have appended entries of its own.

Run: `node -e "const c=require('./release-please-config.json');console.log(c.packages['.']['extra-files'].filter(e=>typeof e==='object'&&e.path.startsWith('frontend/')).length)"`
Expected: `11`

- [ ] **Step 12: Install once for every package**

Run: `yarn install`
Expected: the install succeeds, and `yarn.lock` gains `maplibre-gl@6.10.0`, `terra-draw@1.33.0`, `terra-draw-maplibre-gl-adapter@1.4.1`, `@xyflow/react@12.11.6`, `@tiptap/core@3`, `@tiptap/react@3`, `@tiptap/starter-kit@3` and `@dnd-kit/core@6.3.1`.

Run: `ls -d node_modules/maplibre-gl node_modules/terra-draw node_modules/terra-draw-maplibre-gl-adapter node_modules/@xyflow/react node_modules/@tiptap/react node_modules/@tiptap/starter-kit node_modules/@dnd-kit/core && ls node_modules/@chawpi`
Expected:
- all seven directories are listed;
- `node_modules/@chawpi` lists `agent automation core documents forms gis pages testing ui views workflow` (workspace symlinks).

- [ ] **Step 13: Build everything in order, then lint every scaffold**

Run: `yarn build`
Expected:
- `ui`, `core` and `testing` build first, then the eight modules;
- every package ends with a `dist/index.js` and a `dist/index.d.ts`;
- the empty modules may log an "empty chunk" warning, which is fine;
- `documents` also copies `print.css`, from the placeholder Step 7's scaffold script wrote at `src/print.css` (Task 5 replaces it).

Run: `for p in gis workflow automation documents pages views forms agent; do yarn workspace @chawpi/$p lint || exit 1; done`
Expected: all eight pass.

Run: `yarn test:tooling`
Expected: PASS (the tests for run-ordered, port-from-sapgis, set-version and scaffold-module).

- [ ] **Step 14: Confirm nothing was committed**

Run: `git status --short`
Expected: modified or untracked files only. There are no new commits (`git log -1 --format=%h` is unchanged).

## Wave 1 (Tasks 2–9 run in parallel)

### Task 2: `@chawpi/gis`

Wave 1. Runs in parallel with Tasks 3–9. Writes only `frontend/packages/gis/src/**` and `frontend/packages/gis/README.md`.

This task moves MapLibre, terra-draw and everything GIS out of sapgis and into one module. The pieces are:
- the map page;
- the layers page;
- the GEOMETRY field (its input, plus the type/CRS settings in the object builder);
- the MAP page component (render, builder settings, builder preview);
- "open on map" in the record list;
- the dashboard card and tile line;
- the objects-page column;
- the history wording for shapes;
- the `features` cache key.

**Two gis-local rulings (on top of M1–M12):**
- **G1: maplibre loads only when a map draws.**
  - The package's own components never import `./components/MapView` statically. They import `MapView` from `./components/LazyMapView`, which is a `React.lazy` wrapper with the same props, drawn inside `<Suspense>` with an empty `bg-surface-muted` box as the fallback.
  - This keeps `maplibre-gl` and `terra-draw` out of the app's first bundle, as the spec requires ("no static MapLibre import").
  - It also means `import { gisModule } from '@chawpi/gis'` never evaluates maplibre. jsdom tests and Task 10's smoke test depend on that.
  - The visible difference from sapgis is one frame of the empty box before the map mounts.
- **G2: the worker url is applied by MapView, not by the factory.** This refines M9 and follows from G1: calling `setWorkerUrl` in the factory would load maplibre eagerly.
  - `gisModule({ workerUrl })` stores the url with `setMapWorkerUrl(url)` (`src/lib/mapWorker.ts`, which does not import maplibre).
  - `MapView` calls `applyMapWorkerUrl(setWorkerUrl)` right before its first `new MapLibreMap(...)`. That applies the url once.
  - If no url was ever given, it `console.warn`s once, pointing at the README. sapgis's own comment says maplibre 6 "silently never loads" geojson sources without a worker url, so silence is the one thing to avoid.
  - The url lives in module-level state (`src/lib/mapWorker.ts`), not per-instance: if an app mounts `gisModule({ workerUrl })` more than once with different urls, the last call wins for every `MapView` on the page.
- **G3: no `display` component for GEOMETRY.** sapgis never drew a shape in a table or detail cell. `FieldRenderer.display` stays absent, and core's default formatting applies (the value is not in `attributes`, so nothing is drawn). This is sapgis parity.

**Files:**
- Port (then edit as listed in the steps):
  - `frontend/packages/gis/src/lib/geo.ts` ← `lib/geo.ts`
  - `frontend/packages/gis/src/lib/wms.ts` ← `lib/wms.ts`
  - `frontend/packages/gis/src/lib/wms.test.ts` ← `lib/wms.test.ts`
  - `frontend/packages/gis/src/components/MapView.tsx` ← `components/map/MapView.tsx`
  - `frontend/packages/gis/src/components/GeometryField.tsx` ← `components/map/GeometryField.tsx`
  - `frontend/packages/gis/src/map/MapPage.tsx` ← `features/map/MapPage.tsx`
  - `frontend/packages/gis/src/layers/LayersPage.tsx` ← `features/layers/LayersPage.tsx`
  - `frontend/packages/gis/src/layers/LayersPage.test.tsx` ← `features/layers/LayersPage.test.tsx`
  - `frontend/packages/gis/src/layers/api.ts` ← `features/layers/api.ts`
  - `frontend/packages/gis/src/layers/types.ts` ← `features/layers/types.ts`
- Create:
  - `frontend/packages/gis/src/types.ts`: GeoJSON/geometry types and the typed accessors for core's index-signature keys
  - `frontend/packages/gis/src/api.ts`: `useFeatures`
  - `frontend/packages/gis/src/lib/mapWorker.ts` + `mapWorker.test.ts`
  - `frontend/packages/gis/src/components/LazyMapView.tsx`
  - `frontend/packages/gis/src/slots/geometrySettings.tsx` + `geometrySettings.test.tsx`
  - `frontend/packages/gis/src/slots/GeometryInput.tsx` + `GeometryInput.test.tsx`
  - `frontend/packages/gis/src/slots/MapComponent.tsx` + `MapComponent.test.tsx`
  - `frontend/packages/gis/src/slots/MapSettings.tsx`
  - `frontend/packages/gis/src/slots/MapPreview.tsx`
  - `frontend/packages/gis/src/slots/mapBuilder.test.tsx` (settings + preview)
  - `frontend/packages/gis/src/slots/OpenOnMap.tsx` + `OpenOnMap.test.tsx`
  - `frontend/packages/gis/src/slots/objectSlots.tsx` + `objectSlots.test.tsx` (dashboard card, objects column cell, tile detail)
  - `frontend/packages/gis/src/slots/audit.ts` + `audit.test.ts`
  - `frontend/packages/gis/src/test/fixtures.ts`
  - `frontend/packages/gis/src/i18n.ts` + `i18n.test.ts`
  - `frontend/packages/gis/src/module.tsx` + `module.test.tsx`
  - `frontend/packages/gis/src/boundaries.test.ts`
  - `frontend/packages/gis/README.md`
- Replace: `frontend/packages/gis/src/index.ts` (Task 1 placeholder)

All sapgis paths are under `/Users/jorge/IdeaProjects/sapgis/frontend/src/`.

**Interfaces:**
- Consumes (all from `@chawpi/core`; verified exported by `frontend/packages/core/src/index.ts`):
  - types: `ChawpiModule`, `FieldInputProps`, `FieldSettingsProps`, `PageComponentProps`, `PageComponentSettingsProps`, `RecordListActionProps`, `DashboardCardProps`, `ObjectDetailProps`, `AuditValueFormatter`, `FieldMeta`, `ObjectDefinition`, `ObjectSummary`, `PageComponent`, `RecordItem`, `RecordPayload`;
  - values: `api`, `ApiError`, `useObjects`, `useObjectDefinition`, `useChawpiLinks`, `PageHeader`, `DynamicForm`, `ModuleFieldSettings`, `createRegistry`, `createLinks`, `coreModule`, `createChawpiI18n`.
  - From `@chawpi/ui`: `Button`, `Card`, `CardBody`, `CardHeader`, `CardTitle`, `Input`, `Label`, `Select`, `SelectContent`, `SelectItem`, `SelectTrigger`, `SelectValue`, `Badge`, `Table`, `Td`, `Th`, `cn`.
  - From `@chawpi/testing` (tests only): `renderWithProviders`.
- Produces (`src/index.ts`):
  - `gisModule(options?: GisModuleOptions): ChawpiModule`
  - `interface GisModuleOptions { basePath?: string; workerUrl?: string }`
  - `GIS_MODULE_ID = 'gis'`
  - `MapView` (lazy; props `MapViewProps`)
  - `GeometryField`
  - `useFeatures(objectName: string | undefined, geometry?: string)`
  - `geometryFields(definition: ObjectDefinition): FieldMeta[]`
  - `featureIdOf(recordId: string, geometry: string): string`
  - `wmsTileUrl(url: string, tileSize?: number): string`
  - types `GeometryType`, `GeometryMeta`, `GeoJsonGeometry`, `Feature`, `FeatureCollection`, `MapViewProps`, `WmsLayerSpec`
  - route keys `gis:map`, `gis:layers`
  - registry claims:
    - field type `GEOMETRY` (section `geometries`, settings keys `geometryType`, `srid`);
    - page component `MAP`;
    - the `features` query key.

---

- [ ] **Step 1: Port the sapgis files mechanically**

Run from the repo root (Task 1's port script maps `@/components/map/*`, `@/lib/geo`, `@/lib/wms`, `@/features/map/*` and `@/features/layers/*` to paths relative inside gis, and everything core owns to `@chawpi/core`):

```bash
node frontend/tooling/port-from-sapgis.mjs lib/geo.ts frontend/packages/gis/src/lib/geo.ts
node frontend/tooling/port-from-sapgis.mjs lib/wms.ts frontend/packages/gis/src/lib/wms.ts
node frontend/tooling/port-from-sapgis.mjs lib/wms.test.ts frontend/packages/gis/src/lib/wms.test.ts
node frontend/tooling/port-from-sapgis.mjs components/map/MapView.tsx frontend/packages/gis/src/components/MapView.tsx
node frontend/tooling/port-from-sapgis.mjs components/map/GeometryField.tsx frontend/packages/gis/src/components/GeometryField.tsx
node frontend/tooling/port-from-sapgis.mjs features/map/MapPage.tsx frontend/packages/gis/src/map/MapPage.tsx
node frontend/tooling/port-from-sapgis.mjs features/layers/LayersPage.tsx frontend/packages/gis/src/layers/LayersPage.tsx
node frontend/tooling/port-from-sapgis.mjs features/layers/LayersPage.test.tsx frontend/packages/gis/src/layers/LayersPage.test.tsx
node frontend/tooling/port-from-sapgis.mjs features/layers/api.ts frontend/packages/gis/src/layers/api.ts
node frontend/tooling/port-from-sapgis.mjs features/layers/types.ts frontend/packages/gis/src/layers/types.ts
```

Expected: ten `ported …` lines and no `MANUAL:` line. `features/layers/i18n.ts` is **not** ported: its strings go into `src/i18n.ts` (Step 3). The script already dropped `import '@/features/layers/i18n'` from `LayersPage.tsx`.

- [ ] **Step 2: Write `src/types.ts` and `src/api.ts`**

`frontend/packages/gis/src/types.ts`:

```ts
import type { FieldMeta, ObjectSummary, PageComponent, RecordItem } from '@chawpi/core'

// the gis half of sapgis's types/metadata.ts. core carries these keys untyped through its
// index signatures; the accessors below are the only place gis reads them back.

export type GeometryType = 'NO_GEOMETRY' | 'POINT' | 'LINESTRING' | 'POLYGON' | 'MULTIPOINT' | 'MULTILINESTRING' | 'MULTIPOLYGON'

export interface GeometryMeta {
  type: Exclude<GeometryType, 'NO_GEOMETRY'>
  srid: number
  dimension: number
}

export type GeoJsonGeometry = {
  type: string
  coordinates: unknown
}

export interface Feature {
  type: 'Feature'
  id: string
  geometry: GeoJsonGeometry | null
  properties: Record<string, unknown>
}

export interface FeatureCollection {
  type: 'FeatureCollection'
  features: Feature[]
}

// geojson shape. good enough: audit and records never send anything else with type+coordinates.
export function isGeometryValue(value: unknown): value is GeoJsonGeometry {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const candidate = value as { type?: unknown; coordinates?: unknown; geometries?: unknown }
  return typeof candidate.type === 'string' && ('coordinates' in candidate || 'geometries' in candidate)
}

export function asGeometry(value: unknown): GeoJsonGeometry | null {
  return isGeometryValue(value) ? value : null
}

// only on GEOMETRY fields
export function fieldGeometry(field: FieldMeta): GeometryMeta | null {
  return (field.geometry as GeometryMeta | null | undefined) ?? null
}

// an object definition is an object summary, so this reads both
export function objectGeometry(object: ObjectSummary): GeometryMeta | null {
  return (object.geometry as GeometryMeta | null | undefined) ?? null
}

// keyed by geometry field name, null where the record has none
export function recordGeometries(record: RecordItem): Record<string, GeoJsonGeometry | null> {
  return (record.geometries as Record<string, GeoJsonGeometry | null> | undefined) ?? {}
}

// a MAP component may target one geometry field; null draws every one
export function componentGeometry(component: PageComponent): string | null {
  return typeof component.geometry === 'string' && component.geometry ? component.geometry : null
}
```

`frontend/packages/gis/src/api.ts` (body copied from `sapgis/frontend/src/lib/queries.ts:66-76`):

```ts
import { useQuery } from '@tanstack/react-query'
import { api } from '@chawpi/core'
import type { FeatureCollection } from './types'

// a geojson Feature holds one geometry, so a request carries one. the name is part of the key,
// or switching geometry would show the previous one's cache. ['features', object] is also what
// recordQueryKeys hands core, so a record write makes every geometry of the object stale.
export function useFeatures(objectName: string | undefined, geometry?: string) {
  const search = geometry ? `?geometry=${encodeURIComponent(geometry)}` : ''
  return useQuery({
    queryKey: ['features', objectName, geometry ?? ''],
    queryFn: () => api<FeatureCollection>(`/gis/objects/${objectName}/features${search}`),
    enabled: Boolean(objectName)
  })
}
```

- [ ] **Step 3: Write `src/i18n.ts` and its parity test (T4)**

The strings are copied from core `common.json` (`nav.gis|maps|layers|mapViews`, `objects.geometry|noGeometry|crs|addGeometry`, `records.noGeometry`, `dashboard.geoObjects`, all of `map.*`, `pages.types.MAP`, `pages.geometry`, `pages.allGeometries`, `pages.mockMap.allGeometries`), from `sapgis/frontend/src/features/history/i18n.ts:24-25,62-63` (`history.geometry|geometryUpdated`), and from `sapgis/frontend/src/features/layers/i18n.ts` (`layers.*`, with `SAPGIS` renamed to `Chawpi`).

`frontend/packages/gis/src/i18n.ts`:

```ts
// the gis namespace. keys keep their sapgis paths, so ported t('map.title') calls work unchanged
// once a component asks for useTranslation(['gis', 'common']).
export const gisMessages = {
  es: {
    nav: { gis: 'GIS', maps: 'Mapas', layers: 'Capas', mapViews: 'Vistas de mapa' },
    objects: { geometry: 'Geometría', noGeometry: 'Sin geometría', crs: 'Sistema de referencia (CRS)', addGeometry: 'Añadir geometría' },
    records: { noGeometry: 'Este objeto no tiene ninguna geometría' },
    dashboard: { geoObjects: 'Objetos con geometría' },
    map: {
      title: 'Mapa',
      layer: 'Capa',
      draw: 'Dibujar',
      clear: 'Limpiar',
      drawHint: 'Dibuja la geometría en el mapa',
      selectObject: 'Selecciona un objeto',
      attributes: 'Atributos',
      features: '{{count}} entidades',
      geometryColumn: 'Geometría'
    },
    pages: {
      types: { MAP: 'Mapa' },
      geometry: 'Geometría',
      allGeometries: 'Todas las geometrías',
      mockMap: { allGeometries: 'todas las geometrías' }
    },
    history: { geometry: 'Geometría', geometryUpdated: 'geometría actualizada' },
    layers: {
      title: 'Capas GIS',
      subtitle: 'Publica cada geometría de un objeto como una capa WMS/WFS en GeoServer',
      object: 'Objeto',
      technicalName: 'Nombre técnico',
      layerName: 'Capa',
      geometryType: 'Geometría',
      srid: 'CRS',
      status: 'Estado',
      published: 'Publicada',
      unpublished: 'Sin publicar',
      publish: 'Publicar',
      unpublish: 'Despublicar',
      confirmUnpublish: '¿Despublicar esta capa de GeoServer?',
      copy: 'Copiar',
      copied: 'Copiado',
      wms: 'WMS',
      wfs: 'WFS',
      preview: 'Previsualizar',
      hidePreview: 'Ocultar vista previa',
      previewTitle: 'Vista previa',
      services: 'Servicios de GeoServer',
      workspace: 'Espacio de trabajo',
      endpoint: 'Endpoint',
      connected: 'GeoServer configurado',
      offline: 'GeoServer no está configurado',
      offlineHint: 'La publicación está deshabilitada. Los objetos y sus datos siguen disponibles en Chawpi.',
      listError: 'No se pudo leer el estado de las capas; se muestran los objetos con geometría.',
      empty: 'Ningún objeto tiene geometrías todavía.'
    }
  },
  en: {
    nav: { gis: 'GIS', maps: 'Maps', layers: 'Layers', mapViews: 'Map views' },
    objects: { geometry: 'Geometry', noGeometry: 'No geometry', crs: 'Coordinate system (CRS)', addGeometry: 'Add geometry' },
    records: { noGeometry: 'This object has no geometry' },
    dashboard: { geoObjects: 'Objects with geometry' },
    map: {
      title: 'Map',
      layer: 'Layer',
      draw: 'Draw',
      clear: 'Clear',
      drawHint: 'Draw the geometry on the map',
      selectObject: 'Select an object',
      attributes: 'Attributes',
      features: '{{count}} features',
      geometryColumn: 'Geometry'
    },
    pages: {
      types: { MAP: 'Map' },
      geometry: 'Geometry',
      allGeometries: 'Every geometry',
      mockMap: { allGeometries: 'every geometry' }
    },
    history: { geometry: 'Geometry', geometryUpdated: 'geometry updated' },
    layers: {
      title: 'GIS layers',
      subtitle: 'Publish each geometry of an object as a WMS/WFS layer in GeoServer',
      object: 'Object',
      technicalName: 'Technical name',
      layerName: 'Layer',
      geometryType: 'Geometry',
      srid: 'CRS',
      status: 'Status',
      published: 'Published',
      unpublished: 'Not published',
      publish: 'Publish',
      unpublish: 'Unpublish',
      confirmUnpublish: 'Unpublish this layer from GeoServer?',
      copy: 'Copy',
      copied: 'Copied',
      wms: 'WMS',
      wfs: 'WFS',
      preview: 'Preview',
      hidePreview: 'Hide preview',
      previewTitle: 'Preview',
      services: 'GeoServer services',
      workspace: 'Workspace',
      endpoint: 'Endpoint',
      connected: 'GeoServer configured',
      offline: 'GeoServer is not configured',
      offlineHint: 'Publishing is disabled. Objects and their data stay available in Chawpi.',
      listError: 'Could not read layer status; showing the objects with geometry.',
      empty: 'No object has any geometry yet.'
    }
  }
}
```

`frontend/packages/gis/src/i18n.test.ts`: Shared template T4 with `<name>` = `gis`. The `arrayContaining` list is:

```ts
['nav.gis', 'nav.maps', 'nav.layers', 'nav.mapViews', 'pages.types.MAP', 'objects.geometry', 'history.geometryUpdated', 'history.geometry']
```

Run: `yarn workspace @chawpi/gis test src/i18n.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 4: Write the failing worker-url test**

`frontend/packages/gis/src/lib/mapWorker.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'

// module state: a fresh copy per test
async function fresh() {
  vi.resetModules()
  return import('./mapWorker')
}

afterEach(() => vi.restoreAllMocks())

describe('map worker url', () => {
  it('hands the configured url to maplibre once, before the first map', async () => {
    const { setMapWorkerUrl, applyMapWorkerUrl } = await fresh()
    const apply = vi.fn()
    setMapWorkerUrl('/assets/maplibre-gl-worker.mjs')
    applyMapWorkerUrl(apply)
    applyMapWorkerUrl(apply)
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith('/assets/maplibre-gl-worker.mjs')
  })

  it('warns once when the app never gave one, instead of failing silently', async () => {
    const { applyMapWorkerUrl } = await fresh()
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const apply = vi.fn()
    applyMapWorkerUrl(apply)
    applyMapWorkerUrl(apply)
    expect(apply).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls[0][0]).toContain('workerUrl')
  })

  it('ignores an empty url', async () => {
    const { setMapWorkerUrl, applyMapWorkerUrl } = await fresh()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const apply = vi.fn()
    setMapWorkerUrl(undefined)
    setMapWorkerUrl('')
    applyMapWorkerUrl(apply)
    expect(apply).not.toHaveBeenCalled()
  })
})
```

Run: `yarn workspace @chawpi/gis test src/lib/mapWorker.test.ts`
Expected: FAIL, `Failed to resolve import "./mapWorker"`.

- [ ] **Step 5: Write `src/lib/mapWorker.ts`**

```ts
// maplibre 6 resolves its worker from import.meta.url, which bundling breaks: without an explicit
// url geojson sources silently never load. the app knows its bundler, so it hands the url to
// gisModule; MapView applies it right before the first map. no maplibre import here, so the
// module can be registered without loading maplibre.
let pending: string | undefined
let settled = false

export function setMapWorkerUrl(url: string | undefined): void {
  if (url) pending = url
}

export function applyMapWorkerUrl(apply: (url: string) => void): void {
  if (pending) {
    apply(pending)
    pending = undefined
    settled = true
    return
  }
  if (settled) return
  settled = true
  console.warn('@chawpi/gis: gisModule() got no workerUrl, so maplibre may never load map data. See the @chawpi/gis README, "MapLibre worker".')
}
```

Run: `yarn workspace @chawpi/gis test src/lib/mapWorker.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 6: Finish the ported library and map files**

Apply these exact edits to the files ported in Step 1.

`src/lib/geo.ts`: replace the first line (`import type { FieldMeta, GeoJsonGeometry, ObjectDefinition } from '@chawpi/core'`) with:

```ts
import type { FieldMeta, ObjectDefinition } from '@chawpi/core'
import type { GeoJsonGeometry } from '../types'
```

`src/lib/wms.ts` and `src/lib/wms.test.ts`: no edit (the script already rewrote the test's `@/lib/wms` to `./wms`).

`src/components/MapView.tsx`:
1. Delete the line `import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'`.
2. Delete the comment and the call right after `WMS_PREFIX` (sapgis lines 25–27):
   ```ts
   // maplibre 6 resolves its worker from import.meta.url, which bundling breaks: point at it
   // explicitly or geojson sources silently never load.
   setWorkerUrl(maplibreWorkerUrl)
   ```
   Keep `setWorkerUrl` in the `from 'maplibre-gl'` import list.
3. Replace `import type { Feature, FeatureCollection, GeoJsonGeometry } from '@chawpi/core'` with `import type { Feature, FeatureCollection, GeoJsonGeometry } from '../types'`.
4. Add `import { applyMapWorkerUrl } from '../lib/mapWorker'` after the `../lib/geo` import.
5. In the first `useEffect` (the one that begins `if (!container.current || map.current) return`), insert as its second statement:
   ```ts
       applyMapWorkerUrl(setWorkerUrl)
   ```
   Everything else stays as ported, including `SOURCE = 'chawpi-features'` and `WMS_PREFIX = 'chawpi-wms-'`, which the script renamed.

`src/components/LazyMapView.tsx` (new):

```tsx
import { lazy, Suspense } from 'react'
import { cn } from '@chawpi/ui'
import type { MapViewProps } from './MapView'

export type { MapViewProps, WmsLayerSpec } from './MapView'

// maplibre and terra-draw load with the first map drawn, not with the module: they stay out of the
// app's first bundle, and registering gis never evaluates them
const Loaded = lazy(() => import('./MapView').then((module) => ({ default: module.MapView })))

export function MapView(props: MapViewProps) {
  return (
    <Suspense fallback={<div className={cn('h-full w-full bg-surface-muted', props.className)} />}>
      <Loaded {...props} />
    </Suspense>
  )
}
```

`src/components/GeometryField.tsx`:
- Replace `import { MapView } from './MapView'` with `import { MapView } from './LazyMapView'`.
- Replace `import type { GeoJsonGeometry } from '@chawpi/core'` with `import type { GeoJsonGeometry } from '../types'`.
- Replace `const { t } = useTranslation()` with `const { t } = useTranslation(['gis', 'common'])`.

`src/map/MapPage.tsx`:
- Replace `import { MapView } from '../components/MapView'` with `import { MapView } from '../components/LazyMapView'`.
- Replace `import { useFeatures, useObjectDefinition, useObjects } from '@chawpi/core'` with the two lines
  ```ts
  import { useObjectDefinition, useObjects } from '@chawpi/core'
  import { useFeatures } from '../api'
  ```
- Replace `import type { Feature } from '@chawpi/core'` with `import { objectGeometry, type Feature } from '../types'`.
- `const { t } = useTranslation()` becomes `const { t } = useTranslation(['gis', 'common'])`.
- `const geoObjects = objects.filter((item) => item.geometry)` becomes `const geoObjects = objects.filter((item) => objectGeometry(item) !== null)`.

`src/layers/LayersPage.tsx`:
- Replace `import { MapView } from '../components/MapView'` with `import { MapView } from '../components/LazyMapView'`.
- Add `import { objectGeometry } from '../types'`.
- `const { t } = useTranslation()` becomes `const { t } = useTranslation(['gis', 'common'])`.
- `objects.filter((item) => item.geometry)` becomes `objects.filter((item) => objectGeometry(item) !== null)`.
- In `unpublishedRow`:
  - `geometryType: object.geometry?.type ?? ''` becomes `geometryType: objectGeometry(object)?.type ?? ''`;
  - `srid: object.geometry?.srid ?? 4326` becomes `srid: objectGeometry(object)?.srid ?? 4326`.

`src/layers/api.ts` and `src/layers/types.ts`: no edit (`api` and the relative types import were mapped by the script).

`src/layers/LayersPage.test.tsx`:
- Keep `vi.mock('../components/MapView', …)`. LazyMapView imports that same module, so the mock still applies.
- Replace the whole `vi.mock('@chawpi/core', () => ({ useObjects: () => ({ data: [] }) }))` block (the script's rewrite of `@/lib/queries`) with the block below. A bare factory would replace all of core, including the providers `renderWithProviders` mounts.
  ```tsx
  vi.mock('@chawpi/core', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@chawpi/core')>()),
    useObjects: () => ({ data: [] })
  }))
  ```
- Add `import { gisModule } from '../module'`, and change every `renderWithProviders(<LayersPage />)` to `renderWithProviders(<LayersPage />, { modules: [gisModule()] })`.
- In `'shows the map preview of a published layer on demand'`, change the last line to `expect(await screen.findByTestId('map-view')).toBeInTheDocument()` (the preview now mounts through `Suspense`).

`module.tsx` does not exist yet, so the layers test cannot run until Step 16. Run the pure test now:

Run: `yarn workspace @chawpi/gis test src/lib`
Expected: PASS (`wms.test.ts` and `mapWorker.test.ts`).

- [ ] **Step 7: Write the shared test fixtures**

`frontend/packages/gis/src/test/fixtures.ts`. `src/test` is excluded from the build and from the boundary scan:

```ts
import type { FieldMeta, ObjectDefinition, PageComponent, RecordItem } from '@chawpi/core'

export function field(name: string, label: string, extra: Partial<FieldMeta> = {}): FieldMeta {
  return {
    id: `f-${name}`,
    name,
    label,
    type: 'TEXT',
    required: false,
    unique: false,
    defaultValue: null,
    description: null,
    position: 0,
    enumOptions: null,
    relationTarget: null,
    visible: true,
    editable: true,
    ...extra
  }
}

export function geometryField(name: string, label: string, type = 'POLYGON'): FieldMeta {
  return field(name, label, { type: 'GEOMETRY', geometry: { type, srid: 32718, dimension: 2 } })
}

export const predio: ObjectDefinition = {
  id: 'o1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  geometry: { type: 'POLYGON', srid: 32718, dimension: 2 },
  fields: [field('codigo', 'Código'), geometryField('lote', 'Lote'), geometryField('acceso', 'Acceso', 'POINT')]
}

export const flat: ObjectDefinition = { ...predio, id: 'o2', name: 'persona', label: 'Persona', pluralLabel: 'Personas', geometry: null, fields: [field('nombre', 'Nombre')] }

export const polygon = { type: 'Polygon', coordinates: [[[0, 0], [1, 0], [1, 1], [0, 0]]] }
export const point = { type: 'Point', coordinates: [-77.04, -12.05] }

export function recordOf(geometries: Record<string, unknown>): RecordItem {
  return { id: 'r1', createdAt: null, updatedAt: null, attributes: { codigo: 'P-001' }, geometries }
}

export function node(type: string, extra: Partial<PageComponent> = {}): PageComponent {
  return { type, column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, ...extra }
}
```

- [ ] **Step 8: GEOMETRY field settings: failing test (Review Focus 3, M8)**

`frontend/packages/gis/src/slots/geometrySettings.test.tsx`:

```tsx
import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ModuleFieldSettings } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { GEOMETRY_SETTING_DEFAULTS, geometryPayload } from './geometrySettings'

const renderer = gisModule().fieldRenderers!.GEOMETRY

describe('GEOMETRY field settings', () => {
  it('starts a new field as a polygon in EPSG:4326', () => {
    expect(renderer.settings!.defaults).toEqual({ geometryType: 'POLYGON', srid: '4326' })
    expect(GEOMETRY_SETTING_DEFAULTS).toEqual({ geometryType: 'POLYGON', srid: '4326' })
  })

  it('sends the type and a numeric srid, and never a dimension', () => {
    expect(renderer.settings!.toPayload({ geometryType: 'POINT', srid: '32718' })).toEqual({ geometryType: 'POINT', srid: 32718 })
    expect(Object.keys(geometryPayload({ geometryType: 'POINT', srid: '32718', dimension: '3' }))).toEqual(['geometryType', 'srid'])
  })

  it('falls back to 4326 for a blank or garbage reference system', () => {
    expect(geometryPayload({ geometryType: 'POLYGON', srid: '' }).srid).toBe(4326)
    expect(geometryPayload({ geometryType: 'POLYGON', srid: 'utm' }).srid).toBe(4326)
    expect(geometryPayload({ geometryType: 'POLYGON', srid: '0' }).srid).toBe(4326)
  })

  it('edits the reference system through the object builder slot and shows the EPSG it will use', () => {
    const onChange = vi.fn()
    renderWithProviders(<ModuleFieldSettings renderer={renderer} settings={{ geometryType: 'POLYGON', srid: '' }} onChange={onChange} />, {
      modules: [gisModule()]
    })
    expect(screen.getByText('EPSG:4326')).toBeInTheDocument()
    expect(screen.getByLabelText('Geometría')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Sistema de referencia (CRS)'), { target: { value: '32718' } })
    expect(onChange).toHaveBeenCalledWith({ geometryType: 'POLYGON', srid: '32718' })
  })
})
```

Run: `yarn workspace @chawpi/gis test src/slots/geometrySettings.test.tsx`
Expected: FAIL, `Failed to resolve import "../module"`. The test stays red until Step 16 writes the factory; that is expected.

- [ ] **Step 9: Write `src/slots/geometrySettings.tsx`**

This is the markup of `sapgis/frontend/src/features/objects/ObjectEditorPage.tsx:253-282`. `useId` replaces the fixed `field-srid` id, because the object builder draws one editor per field row.

```tsx
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { FieldSettingsProps } from '@chawpi/core'
import { Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@chawpi/ui'

export const GEOMETRY_TYPES = ['POINT', 'LINESTRING', 'POLYGON', 'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON'] as const

export const GEOMETRY_SETTING_DEFAULTS: Record<string, string> = { geometryType: 'POLYGON', srid: '4326' }

// the object/field POST reads geometryType and srid. dimension is the server's call: never sent.
export function geometryPayload(settings: Record<string, string>): Record<string, unknown> {
  return { geometryType: settings.geometryType, srid: Number(settings.srid) || 4326 }
}

export function GeometrySettingsEditor({ settings, onChange }: FieldSettingsProps) {
  const { t } = useTranslation(['gis', 'common'])
  const sridId = useId()

  return (
    <div className="grid gap-3 sm:col-span-4 sm:grid-cols-2">
      <div className="space-y-1.5">
        <Label>{t('objects.geometry')}</Label>
        <Select value={settings.geometryType} onValueChange={(value) => onChange({ geometryType: value })}>
          <SelectTrigger aria-label={t('objects.geometry')}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {GEOMETRY_TYPES.map((type) => (
              <SelectItem key={type} value={type}>
                {type}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={sridId}>{t('objects.crs')}</Label>
        <Input
          id={sridId}
          inputMode="numeric"
          value={settings.srid ?? ''}
          onChange={(event) => onChange({ srid: event.target.value })}
          placeholder="32718"
          aria-label={t('objects.crs')}
        />
        <p className="text-xs text-ink-muted">EPSG:{settings.srid || '4326'}</p>
      </div>
    </div>
  )
}
```

- [ ] **Step 10: GEOMETRY input inside core's DynamicForm: failing test**

`frontend/packages/gis/src/slots/GeometryInput.test.tsx`. This ports sapgis `DynamicForm.test.tsx`'s "shows one geometry widget per geometry field" and adds the payload case.

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DynamicForm, type RecordPayload } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { point, polygon, predio, recordOf } from '../test/fixtures'

// maplibre cannot run in jsdom; the widget's own map is out of scope here
vi.mock('../components/GeometryField', () => ({
  GeometryField: ({ name, geometryType, srid, value, onChange }: { name: string; geometryType: string; srid: number; value: { type: string } | null; onChange: (shape: unknown) => void }) => (
    <div data-testid="geometry-field">
      <span>{`${name} ${geometryType} ${srid} ${value?.type ?? 'vacío'}`}</span>
      <button type="button" onClick={() => onChange({ type: 'Point', coordinates: [-77.04, -12.05] })}>{`dibujar ${name}`}</button>
    </div>
  )
}))

describe('GEOMETRY in the record form', () => {
  it('draws one widget per geometry field, with its own type and srid', () => {
    renderWithProviders(<DynamicForm definition={predio} onSubmit={vi.fn()} />, { modules: [gisModule()] })
    expect(screen.getAllByTestId('geometry-field')).toHaveLength(2)
    expect(screen.getByText('lote POLYGON 32718 vacío')).toBeInTheDocument()
    expect(screen.getByText('acceso POINT 32718 vacío')).toBeInTheDocument()
  })

  it('prefills the widget from the record and submits drawn shapes under geometries, never as attributes', async () => {
    const onSubmit = vi.fn<(payload: RecordPayload) => void>()
    renderWithProviders(<DynamicForm definition={predio} record={recordOf({ lote: polygon, acceso: null })} onSubmit={onSubmit} />, {
      modules: [gisModule()]
    })
    expect(screen.getByText('lote POLYGON 32718 Polygon')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'dibujar acceso' }))
    await userEvent.click(screen.getByRole('button', { name: /Guardar|Save/ }))

    const payload = onSubmit.mock.calls[0][0]
    expect(payload.geometries).toEqual({ lote: polygon, acceso: point })
    expect(payload.attributes).not.toHaveProperty('lote')
    expect(payload.attributes).not.toHaveProperty('acceso')
  })
})
```

Run: `yarn workspace @chawpi/gis test src/slots/GeometryInput.test.tsx`
Expected: FAIL, `Failed to resolve import "../module"`.

- [ ] **Step 11: Write `src/slots/GeometryInput.tsx`**

sapgis `DynamicForm.tsx:49-61`. Core already wraps the input in `sm:col-span-2` and keeps the value in the `geometries` section.

```tsx
import type { FieldInputProps } from '@chawpi/core'
import { GeometryField } from '../components/GeometryField'
import { asGeometry, fieldGeometry } from '../types'

// a geometry is a field, so it is drawn where the author put it instead of trailing the form
export function GeometryInput({ field, value, onChange }: FieldInputProps) {
  const meta = fieldGeometry(field)
  return (
    <GeometryField
      name={field.name}
      label={field.label}
      geometryType={meta?.type ?? ''}
      srid={meta?.srid ?? 4326}
      value={asGeometry(value)}
      onChange={onChange}
    />
  )
}
```

- [ ] **Step 12: MAP page component (render, settings, preview): failing tests**

`frontend/packages/gis/src/slots/MapComponent.test.tsx` ports sapgis's PageRenderer MAP behaviour:

```tsx
import type { ReactElement } from 'react'
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { flat, node, point, polygon, predio, recordOf } from '../test/fixtures'
import { MapComponent } from './MapComponent'

// maplibre cannot run in jsdom: the fake prints the feature ids it was handed
vi.mock('../components/MapView', () => ({
  MapView: ({ featureCollection }: { featureCollection?: { features: { id: string }[] } | null }) => (
    <div data-testid="map-view">{(featureCollection?.features ?? []).map((feature) => feature.id).join(',')}</div>
  )
}))

const render = (ui: ReactElement) => renderWithProviders(ui, { modules: [gisModule()] })

describe('MAP page component', () => {
  it('draws one feature per drawn geometry, titled as the admin named it', async () => {
    render(<MapComponent component={node('MAP', { title: 'Ubicación' })} definition={predio} record={recordOf({ lote: polygon, acceso: point })} />)
    expect(screen.getByText('Ubicación')).toBeInTheDocument()
    expect(await screen.findByTestId('map-view')).toHaveTextContent('r1:lote,r1:acceso')
  })

  it('draws only the geometry it targets', async () => {
    render(<MapComponent component={node('MAP', { geometry: 'acceso' })} definition={predio} record={recordOf({ lote: polygon, acceso: point })} />)
    expect(screen.getByText('Mapa')).toBeInTheDocument()
    expect(await screen.findByTestId('map-view')).toHaveTextContent('r1:acceso')
  })

  it('gives a spatial object with nothing drawn an empty map, not an excuse', async () => {
    render(<MapComponent component={node('MAP')} definition={predio} record={recordOf({})} />)
    expect((await screen.findByTestId('map-view')).textContent).toBe('')
  })

  it('says so when the object has no geometry at all', () => {
    render(<MapComponent component={node('MAP')} definition={flat} record={recordOf({})} />)
    expect(screen.getByText('Este objeto no tiene ninguna geometría')).toBeInTheDocument()
    expect(screen.queryByTestId('map-view')).not.toBeInTheDocument()
  })
})
```

`frontend/packages/gis/src/slots/mapBuilder.test.tsx` ports sapgis `ComponentMock.test.tsx:72-81` and the MAP part of its "renders no interactive control" case, plus the inspector's shape picker:

```tsx
import type { ReactElement } from 'react'
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { node, predio } from '../test/fixtures'
import { MapPreview } from './MapPreview'
import { ALL_GEOMETRIES, MapSettings, geometryPatch } from './MapSettings'

const withGis = (ui: ReactElement) => renderWithProviders(ui, { modules: [gisModule()] })

describe('MAP in the page builder', () => {
  it('offers itself to the builder with a label, an icon and no shape picked yet', () => {
    const map = gisModule().pageComponents!.MAP
    expect(map.labelKey).toBe('gis:pages.types.MAP')
    expect(map.icon).toBeDefined()
    expect(map.defaults).toEqual({ geometry: null })
    expect(map.settings).toBe(MapSettings)
    expect(map.preview).toBe(MapPreview)
  })

  it('names the geometry a targeted map will draw', () => {
    withGis(<MapPreview component={node('MAP', { geometry: 'lote' })} definition={predio} />)
    expect(screen.getByText('Lote')).toBeInTheDocument()
    expect(screen.getByText('lote · POLYGON')).toBeInTheDocument()
  })

  it('says every geometry draws when none is targeted', () => {
    withGis(<MapPreview component={node('MAP')} definition={predio} />)
    expect(screen.getByText('todas las geometrías')).toBeInTheDocument()
  })

  it('flags a targeted geometry that no longer exists', () => {
    withGis(<MapPreview component={node('MAP', { geometry: 'borrada' })} definition={predio} />)
    expect(screen.getByText('borrada')).toHaveClass('text-danger')
  })

  it('renders no interactive control in the preview', () => {
    const { container } = withGis(<MapPreview component={node('MAP')} definition={predio} />)
    expect(container.querySelectorAll('button, input, select, textarea, a')).toHaveLength(0)
  })

  it('shows the shape picker on "every geometry" until one is chosen', () => {
    withGis(<MapSettings component={node('MAP')} definition={predio} objectName="predio" onChange={vi.fn()} />)
    expect(screen.getByText('Geometría')).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveTextContent('Todas las geometrías')
  })

  it('turns the picker value into the component patch', () => {
    expect(geometryPatch(ALL_GEOMETRIES)).toEqual({ geometry: null })
    expect(geometryPatch('lote')).toEqual({ geometry: 'lote' })
  })
})
```

Run: `yarn workspace @chawpi/gis test src/slots/MapComponent.test.tsx src/slots/mapBuilder.test.tsx`
Expected: FAIL, `Failed to resolve import "./MapComponent"` / `"./MapPreview"`.

- [ ] **Step 13: Write the MAP slot components**

`frontend/packages/gis/src/slots/MapComponent.tsx` (sapgis `PageRenderer.tsx:189-222`):

```tsx
import { useTranslation } from 'react-i18next'
import type { PageComponentProps } from '@chawpi/core'
import { Card, CardHeader, CardTitle } from '@chawpi/ui'
import { MapView } from '../components/LazyMapView'
import { featureIdOf, geometryFields } from '../lib/geo'
import { componentGeometry, recordGeometries } from '../types'

export function MapComponent({ component, definition, record }: PageComponentProps) {
  const { t } = useTranslation(['gis', 'common'])
  const target = componentGeometry(component)
  const geometries = recordGeometries(record)
  // the component may target one geometry; without a target it draws them all
  const targeted = target ? geometryFields(definition).filter((field) => field.name === target) : geometryFields(definition)
  // a spatial object with nothing drawn yet gets an empty map, not an excuse
  const drawn = targeted.filter((field) => geometries[field.name])

  return (
    <Card className="overflow-hidden">
      <CardHeader>
        <CardTitle>{component.title ?? t('map.title')}</CardTitle>
      </CardHeader>
      <div className="h-[28rem]">
        {targeted.length ? (
          <MapView
            featureCollection={{
              type: 'FeatureCollection',
              // one feature per geometry: a record with a plot and an access point is two shapes
              features: drawn.map((field) => ({
                type: 'Feature' as const,
                id: featureIdOf(record.id, field.name),
                geometry: geometries[field.name] ?? null,
                properties: { ...record.attributes, __geometry: field.label }
              }))
            }}
          />
        ) : (
          <p className="px-5 py-8 text-sm text-ink-muted">{t('records.noGeometry')}</p>
        )}
      </div>
    </Card>
  )
}
```

`frontend/packages/gis/src/slots/MapSettings.tsx` (sapgis `pages/builder/Inspector.tsx:16,162-180`):

```tsx
import { useTranslation } from 'react-i18next'
import type { PageComponent, PageComponentSettingsProps } from '@chawpi/core'
import { Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@chawpi/ui'
import { geometryFields } from '../lib/geo'
import { componentGeometry } from '../types'

// a select item cannot carry null, so "every geometry" needs a value of its own
export const ALL_GEOMETRIES = '__all__'

export function geometryPatch(value: string): Partial<PageComponent> {
  return { geometry: value === ALL_GEOMETRIES ? null : value }
}

export function MapSettings({ component, definition, onChange }: PageComponentSettingsProps) {
  const { t } = useTranslation(['gis', 'common'])
  return (
    <div className="space-y-1.5">
      <Label>{t('pages.geometry')}</Label>
      <Select value={componentGeometry(component) ?? ALL_GEOMETRIES} onValueChange={(value) => onChange(geometryPatch(value))}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_GEOMETRIES}>{t('pages.allGeometries')}</SelectItem>
          {geometryFields(definition).map((field) => (
            <SelectItem key={field.name} value={field.name}>
              {field.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
```

`frontend/packages/gis/src/slots/MapPreview.tsx` (sapgis `pages/builder/preview/ComponentMock.tsx:60-86`):

```tsx
import { useTranslation } from 'react-i18next'
import type { ObjectDefinition, PageComponent } from '@chawpi/core'
import { geometryFields } from '../lib/geo'
import { componentGeometry, fieldGeometry } from '../types'

// what a MAP would show, from metadata already in hand: never mounts a map inside the draggable canvas
export function MapPreview({ component, definition }: { component: PageComponent; definition: ObjectDefinition }) {
  const { t } = useTranslation(['gis', 'common'])
  const target = componentGeometry(component)

  if (!target) {
    return (
      <div className="flex h-40 items-center justify-center rounded border border-border bg-surface-muted text-center text-sm text-ink-muted">
        {t('pages.mockMap.allGeometries')}
      </div>
    )
  }

  const targeted = geometryFields(definition).find((field) => field.name === target)
  return (
    <div className="flex h-40 items-center justify-center rounded border border-border bg-surface-muted text-center text-sm">
      {targeted ? (
        <span className="text-ink-muted">
          <span className="block font-medium text-ink">{targeted.label}</span>
          <span className="block text-xs">
            {targeted.name} · {fieldGeometry(targeted)?.type ?? ''}
          </span>
        </span>
      ) : (
        <span className="text-danger">{target}</span>
      )}
    </div>
  )
}
```

- [ ] **Step 14: Record list, dashboard, objects page and history slots: failing tests**

`frontend/packages/gis/src/slots/OpenOnMap.test.tsx` (Review Focus 5):

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { flat, predio } from '../test/fixtures'
import { OpenOnMap } from './OpenOnMap'

describe('open on map', () => {
  it('links a spatial object to the map page, filtered on it', () => {
    renderWithProviders(<OpenOnMap objectName="predio" definition={predio} />, { modules: [gisModule()] })
    expect(screen.getByRole('link', { name: 'Mapa' })).toHaveAttribute('href', '/gis/map?object=predio')
  })

  it('follows the base path the app picked', () => {
    renderWithProviders(<OpenOnMap objectName="predio" definition={predio} />, { modules: [gisModule({ basePath: 'geo' })] })
    expect(screen.getByRole('link', { name: 'Mapa' })).toHaveAttribute('href', '/geo/map?object=predio')
  })

  it('offers nothing for an object without geometry', () => {
    renderWithProviders(<OpenOnMap objectName="persona" definition={flat} />, { modules: [gisModule()] })
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
```

`frontend/packages/gis/src/slots/objectSlots.test.tsx` ports sapgis `DashboardPage.tsx:11,25-34,45` and `ObjectsPage.tsx:41,53-61`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { gisModule } from '../module'
import { flat, predio } from '../test/fixtures'
import { GeoObjectsCard, GeometryCell, GeometryTileDetail } from './objectSlots'

const withGis = { modules: [gisModule()] }

describe('gis on the dashboard and the objects page', () => {
  it('counts the objects that carry a geometry', () => {
    renderWithProviders(<GeoObjectsCard objects={[predio, flat]} loading={false} />, withGis)
    expect(screen.getByText('Objetos con geometría')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('shows a dash while the objects load', () => {
    renderWithProviders(<GeoObjectsCard objects={[]} loading />, withGis)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('names the geometry type and EPSG code in the objects table, or says there is none', () => {
    const { rerender } = renderWithProviders(<GeometryCell object={predio} />, withGis)
    expect(screen.getByText('POLYGON · EPSG:32718')).toBeInTheDocument()
    rerender(<GeometryCell object={flat} />)
    expect(screen.getByText('Sin geometría')).toBeInTheDocument()
  })

  it('adds the same line to each dashboard tile', () => {
    const { rerender } = renderWithProviders(<GeometryTileDetail object={predio} />, withGis)
    expect(screen.getByText('POLYGON · EPSG:32718')).toBeInTheDocument()
    rerender(<GeometryTileDetail object={flat} />)
    expect(screen.getByText('Sin geometría')).toBeInTheDocument()
  })
})
```

`frontend/packages/gis/src/slots/audit.test.ts` ports sapgis `history/changes.ts:8-12,20,38`:

```ts
import { describe, expect, it } from 'vitest'
import { createChawpiI18n, formatAuditValue } from '@chawpi/core'
import { gisModule } from '../module'
import { polygon } from '../test/fixtures'
import { geometryAuditFormatter } from './audit'

describe('gis in the audit log', () => {
  it('recognises geojson, and nothing that merely has a type', () => {
    expect(geometryAuditFormatter.matches(polygon)).toBe(true)
    expect(geometryAuditFormatter.matches({ type: 'GeometryCollection', geometries: [] })).toBe(true)
    expect(geometryAuditFormatter.matches({ type: 'Polygon' })).toBe(false)
    expect(geometryAuditFormatter.matches([1, 2])).toBe(false)
    expect(geometryAuditFormatter.matches('Polygon')).toBe(false)
  })

  it('names a shape change in words and labels the geometry column, never dumping coordinates', () => {
    const module = gisModule()
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: 'gis-audit-test.lang', modules: [module] })
    expect(i18n.t(geometryAuditFormatter.labelKey)).toBe('geometría actualizada')
    expect(i18n.t(module.auditFieldLabels!.geometry)).toBe('Geometría')
    expect(formatAuditValue(polygon, { valueFormatters: module.auditValueFormatters!, fieldLabels: module.auditFieldLabels! })).toBe('geometría actualizada')
  })
})
```

The last assertion relies on core's `formatAuditValue` translating through the instance initialised last, which is the one this test just created (core's pure helpers use react-i18next's `getI18n()`, P4 R4). If `formatAuditValue` returns the raw key instead, delete that one assertion and report it. The first two assertions already pin gis's part.

Run: `yarn workspace @chawpi/gis test src/slots/OpenOnMap.test.tsx src/slots/objectSlots.test.tsx src/slots/audit.test.ts`
Expected: FAIL, `Failed to resolve import "./OpenOnMap"` / `"./objectSlots"` / `"./audit"`.

- [ ] **Step 15: Write the record list, dashboard, objects and history slots**

`frontend/packages/gis/src/slots/OpenOnMap.tsx` (sapgis `RecordListPage.tsx:68-75`; the url now comes from links):

```tsx
import { Map as MapIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useChawpiLinks, type RecordListActionProps } from '@chawpi/core'
import { Button } from '@chawpi/ui'
import { objectGeometry } from '../types'

export function OpenOnMap({ objectName, definition }: RecordListActionProps) {
  const { t } = useTranslation(['gis', 'common'])
  const links = useChawpiLinks()
  if (!objectGeometry(definition)) return null
  return (
    <Button variant="secondary" asChild>
      <Link to={links.to('gis:map', {}, { object: objectName })}>
        <MapIcon className="h-4 w-4" />
        {t('map.title')}
      </Link>
    </Button>
  )
}
```

`frontend/packages/gis/src/slots/objectSlots.tsx`:

```tsx
import { Map as MapIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { DashboardCardProps, ObjectDetailProps } from '@chawpi/core'
import { Badge, Card, CardBody } from '@chawpi/ui'
import { objectGeometry } from '../types'

// dashboard: how many objects are spatial
export function GeoObjectsCard({ objects, loading }: DashboardCardProps) {
  const { t } = useTranslation(['gis', 'common'])
  const spatial = objects.filter((item) => objectGeometry(item) !== null)
  return (
    <Card>
      <CardBody>
        <div className="flex items-center gap-2 text-ink-muted">
          <MapIcon className="h-4 w-4" />
          <span className="text-sm">{t('dashboard.geoObjects')}</span>
        </div>
        <p className="mt-2 text-3xl font-semibold text-ink">{loading ? '—' : spatial.length}</p>
      </CardBody>
    </Card>
  )
}

// objects page: the geometry column's cell
export function GeometryCell({ object }: ObjectDetailProps) {
  const { t } = useTranslation(['gis', 'common'])
  const geometry = objectGeometry(object)
  return geometry ? (
    <Badge>
      {geometry.type} · EPSG:{geometry.srid}
    </Badge>
  ) : (
    <span className="text-xs text-ink-muted">{t('objects.noGeometry')}</span>
  )
}

// dashboard: the line under each object tile
export function GeometryTileDetail({ object }: ObjectDetailProps) {
  const { t } = useTranslation(['gis', 'common'])
  const geometry = objectGeometry(object)
  return <p className="mt-0.5 text-xs text-ink-muted">{geometry ? `${geometry.type} · EPSG:${geometry.srid}` : t('objects.noGeometry')}</p>
}
```

`frontend/packages/gis/src/slots/audit.ts`:

```ts
import type { AuditValueFormatter } from '@chawpi/core'
import { isGeometryValue } from '../types'

// a polygon is thousands of numbers: the log says a shape changed, never prints it
export const geometryAuditFormatter: AuditValueFormatter = { matches: isGeometryValue, labelKey: 'gis:history.geometryUpdated' }

// the audit column that held an object's single shape before shapes became fields
export const GEOMETRY_AUDIT_FIELD_LABELS: Record<string, string> = { geometry: 'gis:history.geometry' }
```

- [ ] **Step 16: Write the factory and its wiring test (T1, T2)**

`frontend/packages/gis/src/module.test.tsx` (Shared template T2, plus the gis claims):

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { gisModule } from './module'

describe('gisModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, gisModule()])).not.toThrow()
  })

  it('mounts its routes under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, gisModule()]))
    expect(links.to('gis:map')).toBe('/gis/map')
    expect(links.to('gis:layers')).toBe('/gis/layers')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, gisModule({ basePath: 'x' })]))
    expect(links.to('gis:map')).toBe('/x/map')
    expect(links.to('gis:layers')).toBe('/x/layers')
  })

  it('puts maps and layers in its own sidebar group, map views as a placeholder', () => {
    const registry = createRegistry([coreModule, gisModule()])
    const group = registry.navGroups.find((candidate) => candidate.id === 'gis')!
    expect(group.labelKey).toBe('gis:nav.gis')
    expect(group.items.map((item) => [item.labelKey, item.to, item.disabled])).toEqual([
      ['gis:nav.maps', '/gis/map', false],
      ['gis:nav.layers', '/gis/layers', false],
      ['gis:nav.mapViews', null, true]
    ])
  })

  it('keeps shapes out of attributes and out of the unique toggle', () => {
    const renderer = gisModule().fieldRenderers!.GEOMETRY
    expect(renderer.section).toBe('geometries')
    expect(renderer.uniqueAllowed).toBe(false)
  })

  it('marks the features cache stale when a record of the object changes', () => {
    expect(gisModule().recordQueryKeys!('predio')).toEqual([['features', 'predio']])
  })

  it('builds a fresh module on every call', () => {
    expect(gisModule()).not.toBe(gisModule())
    expect(gisModule().fieldRenderers!.GEOMETRY.settings!.defaults).not.toBe(gisModule().fieldRenderers!.GEOMETRY.settings!.defaults)
  })
})
```

Run: `yarn workspace @chawpi/gis test src/module.test.tsx`
Expected: FAIL, `Failed to resolve import "./module"`.

`frontend/packages/gis/src/module.tsx`:

```tsx
import { Globe2, Layers, Map as MapIcon } from 'lucide-react'
import type { ChawpiModule } from '@chawpi/core'
import { gisMessages } from './i18n'
import { setMapWorkerUrl } from './lib/mapWorker'
import { GEOMETRY_AUDIT_FIELD_LABELS, geometryAuditFormatter } from './slots/audit'
import { GeometryInput } from './slots/GeometryInput'
import { GEOMETRY_SETTING_DEFAULTS, GeometrySettingsEditor, geometryPayload } from './slots/geometrySettings'
import { MapComponent } from './slots/MapComponent'
import { MapPreview } from './slots/MapPreview'
import { MapSettings } from './slots/MapSettings'
import { GeoObjectsCard, GeometryCell, GeometryTileDetail } from './slots/objectSlots'
import { OpenOnMap } from './slots/OpenOnMap'

export const GIS_MODULE_ID = 'gis'

export interface GisModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's urls (/gis/map, /gis/layers)
  basePath?: string
  // maplibre's worker script, as the app's bundler serves it. see the README, "MapLibre worker"
  workerUrl?: string
}

export function gisModule(options: GisModuleOptions = {}): ChawpiModule {
  setMapWorkerUrl(options.workerUrl)
  return {
    id: GIS_MODULE_ID,
    basePath: options.basePath ?? 'gis',
    routes: [
      { id: 'map', path: 'map', lazy: () => import('./map/MapPage').then((module) => ({ default: module.MapPage })) },
      { id: 'layers', path: 'layers', lazy: () => import('./layers/LayersPage').then((module) => ({ default: module.LayersPage })) }
    ],
    navGroups: [{ id: 'gis', labelKey: 'gis:nav.gis', order: 20 }],
    nav: [
      { group: 'gis', labelKey: 'gis:nav.maps', order: 10, icon: MapIcon, route: 'map' },
      { group: 'gis', labelKey: 'gis:nav.layers', order: 20, icon: Layers, route: 'layers' },
      { group: 'gis', labelKey: 'gis:nav.mapViews', order: 30, icon: Globe2, disabled: true }
    ],
    fieldRenderers: {
      GEOMETRY: {
        section: 'geometries',
        // a unique shape means nothing, and the server refuses it
        uniqueAllowed: false,
        input: GeometryInput,
        settings: { defaults: { ...GEOMETRY_SETTING_DEFAULTS }, editor: GeometrySettingsEditor, toPayload: geometryPayload }
      }
    },
    pageComponents: {
      MAP: { render: MapComponent, labelKey: 'gis:pages.types.MAP', icon: MapIcon, settings: MapSettings, preview: MapPreview, defaults: { geometry: null } }
    },
    recordListActions: [OpenOnMap],
    dashboardCards: [GeoObjectsCard],
    objectColumns: [{ id: 'geometry', headerKey: 'gis:objects.geometry', cell: GeometryCell }],
    objectTileDetails: [GeometryTileDetail],
    auditValueFormatters: [geometryAuditFormatter],
    auditFieldLabels: { ...GEOMETRY_AUDIT_FIELD_LABELS },
    recordQueryKeys: (objectName) => [['features', objectName]],
    i18n: gisMessages
  }
}
```

Run: `yarn workspace @chawpi/gis test`
Expected: PASS for every file written so far: `i18n`, `mapWorker`, `wms`, `geometrySettings`, `GeometryInput`, `MapComponent`, `mapBuilder`, `OpenOnMap`, `objectSlots`, `audit`, `module`, and the ported `layers/LayersPage.test.tsx` (7 cases).

If a layers case fails on text, the cause is a missing key in `gisMessages.layers` or a `useTranslation()` left without `['gis', 'common']`. Fix it in the package, never in core.

- [ ] **Step 17: Boundary test (T3)**

`frontend/packages/gis/src/boundaries.test.ts` is Shared template T3 with `<name>` = `gis` and:

```ts
const OWN = /^(maplibre-gl|terra-draw|terra-draw-maplibre-gl-adapter)$/
```

Add this third case inside the `describe`, which pins G1:

```ts
  it('loads maplibre only through the lazy map view', () => {
    const eager = sources(SRC)
      .filter((file) => !file.endsWith('LazyMapView.tsx'))
      .filter((file) => /from '\.{1,2}\/(components\/)?MapView'/.test(readFileSync(file, 'utf8')))
    expect(eager.map((file) => relative(SRC, file))).toEqual([])
  })
```

Run: `yarn workspace @chawpi/gis test src/boundaries.test.ts`
Expected: PASS (3 tests). If "never spells an in-app url" fails, a ported file still has a literal `to="/…"`. Replace it with `useChawpiLinks()`. MapPage and LayersPage have none in sapgis.

- [ ] **Step 18: Public entry**

Replace `frontend/packages/gis/src/index.ts` with:

```ts
export { GIS_MODULE_ID, gisModule, type GisModuleOptions } from './module'
export { MapView, type MapViewProps, type WmsLayerSpec } from './components/LazyMapView'
export { GeometryField } from './components/GeometryField'
export { useFeatures } from './api'
export { featureIdOf, geometryFields } from './lib/geo'
export { WMS_TILE_SIZE, wmsTileUrl } from './lib/wms'
export type { Feature, FeatureCollection, GeoJsonGeometry, GeometryMeta, GeometryType } from './types'
```

Run: `yarn prettier --write frontend/packages/gis/src` (lint's prettier check fails otherwise: the port leaves a few files not prettier-stable), then `yarn workspace @chawpi/gis lint`
Expected: PASS (prettier check + `tsc --noEmit`).

- [ ] **Step 19: README (T5)**

`frontend/packages/gis/README.md`:

````markdown
# @chawpi/gis

Maps for chawpi: a map page, GeoServer layer publishing, a GEOMETRY field type drawn on a map, and a
MAP component for record pages. MapLibre and terra-draw load only when a map is on screen.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```bash
yarn add @chawpi/gis maplibre-gl terra-draw terra-draw-maplibre-gl-adapter
```

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { gisModule } from '@chawpi/gis'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'Catastro' }} modules={[gisModule({ workerUrl })]} />
}
```

## MapLibre worker

MapLibre 6 finds its web worker through `import.meta.url`, which bundling breaks. Without an explicit
url, GeoJSON sources silently never load, and chawpi warns once in the console. The package itself
imports no bundler-specific syntax: the app passes the url.

- **Vite:** `import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'` (as above).
- **webpack 5 / rspack:** `const workerUrl = new URL('maplibre-gl/dist/maplibre-gl-worker.mjs', import.meta.url).href`
- **Anything else:** copy `node_modules/maplibre-gl/dist/maplibre-gl-worker.mjs` into your public folder and pass its
  public path, e.g. `gisModule({ workerUrl: '/maplibre-gl-worker.mjs' })`.

Also import `maplibre-gl/dist/maplibre-gl.css` once, or map controls render unstyled.

## What it adds

| Slot | Contribution |
|---|---|
| routes | `gis:map` → `/gis/map` (`?object=&geometry=`), `gis:layers` → `/gis/layers` |
| nav | group "GIS" (order 20): Maps, Layers, Map views (placeholder) |
| fieldRenderers | `GEOMETRY`: values in `record.geometries`; settings `geometryType` (default `POLYGON`) and `srid` (default `4326`) |
| pageComponents | `MAP`: draws the record's shapes, optionally one targeted geometry field |
| recordListActions | "Map" button on spatial objects' record lists |
| dashboardCards / objectTileDetails / objectColumns | spatial object count, `TYPE · EPSG:n` lines, objects-table column |
| auditValueFormatters / auditFieldLabels | shape changes read "geometry updated", never coordinates |
| recordQueryKeys | `['features', object]` goes stale on every record write |

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'gis'` | url prefix of the map and layers pages |
| `workerUrl` | none | MapLibre worker script url (see above) |

## Backend

Needs the chawpi GIS backend module: `/gis/objects/{object}/features`, `/gis/layers`, `/gis/services`.
Layer publishing also needs GeoServer; without it the layers page lists the spatial objects and
disables publishing. Endpoints of an absent backend module must answer 404.
````

- [ ] **Step 20: Format and verify the package**

```bash
yarn prettier --write frontend/packages/gis/src frontend/packages/gis/README.md
yarn workspace @chawpi/gis lint
yarn workspace @chawpi/gis test
yarn workspace @chawpi/gis build
ls frontend/packages/gis/dist
grep -l "maplibre-gl-worker" frontend/packages/gis/dist/*.js || echo "no worker import in dist"
git status --short
```

Expected:
- lint PASS;
- test PASS (13 files);
- build writes `dist/index.js`, `dist/index.d.ts` and at least one separate chunk holding MapView (the lazy import);
- the grep prints `no worker import in dist`;
- `git status --short` lists only untracked or modified paths under `frontend/packages/gis/`, and nothing is committed.

### Task 3: `@chawpi/workflow`

Wave 1. Parallel with Tasks 2 and 4–9. Writes only `frontend/packages/workflow/src/**` and `frontend/packages/workflow/README.md`.

This task moves sapgis's workflow builder, canvas and record panel into `@chawpi/workflow`, then fills four registry slots:
- the WORKFLOW page component (render, label, icon, builder preview);
- the TRANSITION action kind (render, label, builder settings, defaults);
- the `objectFlags` hook that core's object editor reads (`flags.workflow`);
- the builder route plus its nav entry.

`@xyflow/react` lives only here.

**Files:**
- Port (script, then the listed edits), from `/Users/jorge/IdeaProjects/sapgis/frontend/src/features/workflows/`:
  - `frontend/packages/workflow/src/types.ts` ← `types.ts`
  - `frontend/packages/workflow/src/api.ts` ← `api.ts`
  - `frontend/packages/workflow/src/workflowGraph.ts` ← `workflowGraph.ts`
  - `frontend/packages/workflow/src/workflowGraph.test.ts` ← `workflowGraph.test.ts`
  - `frontend/packages/workflow/src/WorkflowCanvas.tsx` ← `WorkflowCanvas.tsx` (keeps `import '@xyflow/react/dist/style.css'`)
  - `frontend/packages/workflow/src/WorkflowInspector.tsx` ← `WorkflowInspector.tsx`
  - `frontend/packages/workflow/src/WorkflowPanel.tsx` ← `WorkflowPanel.tsx`
  - `frontend/packages/workflow/src/WorkflowPanel.test.tsx` ← `WorkflowPanel.test.tsx`
  - `frontend/packages/workflow/src/WorkflowBuilderPage.tsx` ← `WorkflowBuilderPage.tsx`
  - `frontend/packages/workflow/src/WorkflowBuilderPage.test.tsx` ← `WorkflowBuilderPage.test.tsx`
- Create:
  - `frontend/packages/workflow/src/i18n.ts`, which replaces sapgis `features/workflows/i18n.ts` (a side-effect bundle, not ported)
  - `frontend/packages/workflow/src/slots/WorkflowComponent.tsx`, the WORKFLOW `pageComponents` render (Step 7)
  - `frontend/packages/workflow/src/slots/WorkflowPreview.tsx`, the sapgis `ComponentMock.tsx:118-131` `WorkflowMock`
  - `frontend/packages/workflow/src/slots/TransitionAction.tsx`, the TRANSITION branch of sapgis `components/page-renderer/ActionButton.tsx:21-39`
  - `frontend/packages/workflow/src/slots/TransitionSettings.tsx`, the sapgis `pages/builder/Inspector.tsx:228-242` picker plus `PageBuilderPage.tsx:242-244` enabled rule
  - `frontend/packages/workflow/src/slots/useWorkflowFlags.ts`
  - `frontend/packages/workflow/src/module.tsx`
  - `frontend/packages/workflow/src/index.ts` (replaces Task 1's placeholder)
  - `frontend/packages/workflow/README.md`
- Tests (new):
  - `frontend/packages/workflow/src/module.test.tsx`
  - `frontend/packages/workflow/src/i18n.test.ts`
  - `frontend/packages/workflow/src/boundaries.test.ts`
  - `frontend/packages/workflow/src/slots/TransitionAction.test.tsx`
  - `frontend/packages/workflow/src/slots/TransitionSettings.test.tsx`
  - `frontend/packages/workflow/src/slots/WorkflowComponent.test.tsx`
  - `frontend/packages/workflow/src/slots/useWorkflowFlags.test.tsx`
- Do NOT touch: `package.json`, `tsconfig*.json` and `vite.config.ts` (Task 1 wrote them with `@xyflow/react` 12.11.6 and `lucide-react`), `src/test/setup.ts`, and anything outside `frontend/packages/workflow/`.

**Interfaces:**
- Consumes:
  - From `@chawpi/core`:
    - `api<T>(path, init?)`, `ApiError`;
    - `useRecord(objectName, id)`, `useObjects()`, `useRoles()` (**added to core's index by Task 1**: `export { useRoles } from './features/admin/api'`), `useObjectFlags(objectName): Record<string, boolean>`;
    - `PageHeader`, `PageRenderer`, `ActionButton`;
    - `coreModule`, `createRegistry`, `createLinks`;
    - types `ChawpiModule`, `PageComponentDefinition`, `PageActionDefinition`, `PageComponentProps { component, definition, record }`, `PageActionProps { component, objectName, recordId }`, `PageComponentSettingsProps { component, definition, objectName, onChange(patch: Partial<PageComponent>) }`, `PageComponent`, `ObjectDefinition`, `RecordItem`, `FieldMeta`, `Page`, `PageTemplate`.
  - From `@chawpi/ui`: `Button`, `Card`, `CardBody`, `CardHeader`, `CardTitle`, `Badge`, `Input`, `Label`, `Select`, `SelectContent`, `SelectItem`, `SelectTrigger`, `SelectValue`, `cn`.
  - From `@chawpi/testing` (tests only): `renderWithProviders(ui, { modules })`, `mockFetch(routes)`, `FetchMock`.
  - Core's `ActionButton` draws `pageActions[component.action].render` with `{ component, objectName, recordId }`. Core's `PageRenderer` draws `pageComponents[type].render` with `{ component, definition, record }`. Core's `ObjectEditorPage` reads `useObjectFlags(object).workflow === true`.
- Produces (`src/index.ts`):
  - `workflowModule(options?: WorkflowModuleOptions): ChawpiModule`, `interface WorkflowModuleOptions { basePath?: string }`, `WORKFLOW_MODULE_ID = 'workflow'`.
  - Route key `workflow:builder` → `/automation/workflows` by default. Nav: group `automation`, order 10, `workflow:nav.workflows`, icon `Workflow`.
  - Slots:
    - `pageComponents.WORKFLOW` (`labelKey: 'workflow:pageComponents.WORKFLOW'`, icon `Workflow`, `preview`);
    - `pageActions.TRANSITION` (`labelKey: 'workflow:pageActions.TRANSITION'`, `settings`, `defaults: { action: 'TRANSITION' }`);
    - `objectFlags: useWorkflowFlags`.
  - Components and hooks:
    - `WorkflowPanel({ objectName, recordId })`, `WorkflowBuilderPage()`;
    - `useWorkflow(objectName | undefined)` (query key `['workflow', objectName]`, GET `/objects/${objectName}/workflow`, `retry: false`, which is the shape Task 4's `useWorkflowOutline` mirrors);
    - `useSaveWorkflow()`, `useDeleteWorkflow()`;
    - `useAvailableTransitions(objectName, recordId)`, `useApplyTransition(objectName, recordId)`.
  - Types: `Workflow`, `WorkflowDefinition`, `WorkflowState`, `WorkflowTransition`, `WorkflowPayload`, `AvailableTransition`, `RecordWithState`, `StateType`, `DefinitionProblem`.
  - `workflowMessages: { es, en }`.

- [ ] **Step 1: Port the sapgis sources and tests with the port script**

Task 1 extended the script: `@/features/workflows/*` becomes a relative path inside this package, and core things (`@/lib/api`, `@/lib/queries`, `@/features/admin/api`, `@/components/layout/AppShell`, `@/types/metadata`) become `@chawpi/core`. `@/components/ui/*` and `@/lib/utils` become `@chawpi/ui`, and `@/test/render` becomes `@chawpi/testing`. The `import '@/features/workflows/i18n'` side-effect lines are dropped.

```bash
for f in types.ts api.ts workflowGraph.ts workflowGraph.test.ts WorkflowCanvas.tsx WorkflowInspector.tsx WorkflowPanel.tsx WorkflowPanel.test.tsx WorkflowBuilderPage.tsx WorkflowBuilderPage.test.tsx; do
  node frontend/tooling/port-from-sapgis.mjs "features/workflows/$f" "frontend/packages/workflow/src/$f"
done
```

Expected: ten `ported …` lines and **no** `MANUAL:` line. A `MANUAL:` line means Task 1's MODULE_MAP is missing a rule. Stop and report `BLOCKED: port-from-sapgis MODULE_MAP`.

- [ ] **Step 2: Point the ported sources at the `workflow` namespace**

Every ported component asks for the module namespace first and core's `common` second (M3). `t('common.loading')` in the builder keeps resolving from `common`. This command changes only the hook calls, so it is safe on all four files:

```bash
cd frontend/packages/workflow/src
sed -i.bak "s/useTranslation()/useTranslation(['workflow', 'common'])/g" WorkflowPanel.tsx WorkflowInspector.tsx WorkflowCanvas.tsx WorkflowBuilderPage.tsx && rm -f *.bak
grep -c "useTranslation(\['workflow', 'common'\])" WorkflowPanel.tsx WorkflowInspector.tsx WorkflowCanvas.tsx WorkflowBuilderPage.tsx
cd -
```

Expected counts: `WorkflowPanel.tsx:1`, `WorkflowInspector.tsx:5`, `WorkflowCanvas.tsx:1`, `WorkflowBuilderPage.tsx:1`.

Then check the imports the script produced. None of them needs a hand edit, but confirm:

```bash
grep -n "from '@chawpi\|from '\./\|import '" frontend/packages/workflow/src/*.ts frontend/packages/workflow/src/*.tsx | grep -v test
```

Expected, among others:
- `WorkflowInspector.tsx`: `import { useRoles } from '@chawpi/core'`;
- `WorkflowPanel.tsx`: `import { ApiError } from '@chawpi/core'` and `import { useRecord } from '@chawpi/core'` (two lines, both fine);
- `WorkflowCanvas.tsx`: `import '@xyflow/react/dist/style.css'`;
- no `@/` specifier left.

None of these files contains an in-app URL (sapgis's workflow screens link nowhere), so there are no `links` edits.

- [ ] **Step 3: Adapt the two ported component tests to a package boundary**

`vi.mock('@chawpi/core', () => ({ … }))` would replace **all** of core, including the `ChawpiProviders` that `renderWithProviders` mounts, because vitest's alias sends both imports to the same file. Every mock of `@chawpi/core` or `@chawpi/ui` therefore spreads the real module and overrides only what the test fakes. Each test also renders with `workflowModule()`, so the `workflow` namespace is loaded (M3).

In `frontend/packages/workflow/src/WorkflowPanel.test.tsx`:

1. Replace the line `import { renderWithProviders } from '@chawpi/testing'` with:

```tsx
import type { ReactElement } from 'react'
import { renderWithProviders as renderBase } from '@chawpi/testing'
import { workflowModule } from './module'

// the panel's strings live in the workflow namespace
const renderWithProviders = (ui: ReactElement) => renderBase(ui, { modules: [workflowModule()] })
```

2. Replace the block

```tsx
vi.mock('@chawpi/core', () => ({
  useRecord: () => ({ data: state.record })
}))
```

with

```tsx
vi.mock('@chawpi/core', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@chawpi/core')>()),
  useRecord: () => ({ data: state.record })
}))
```

In `frontend/packages/workflow/src/WorkflowBuilderPage.test.tsx`:

1. Replace the line `import { renderWithProviders } from '@chawpi/testing'` with the same four lines as above (`ReactNode` is already imported as a type; merge to `import type { ReactElement, ReactNode } from 'react'`).
2. In the select double, change the first line `vi.mock('@chawpi/ui', () => {` to

```tsx
vi.mock('@chawpi/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@chawpi/ui')>()
```

   and change its `return {` object so that it starts with `...actual,`. Buttons, cards and inputs stay real, and only the select is doubled:

```tsx
  return {
    ...actual,
    SelectTrigger,
```

3. The port turned the two separate mocks `@/lib/queries` and `@/features/admin/api` into two `vi.mock('@chawpi/core', …)` calls, and vitest keeps only one. Replace **both** blocks:

```tsx
vi.mock('@chawpi/core', () => ({
  useObjects: () => ({ data: [{ id: 'o-1', name: 'predio', label: 'Predio' }] })
}))

vi.mock('@chawpi/core', () => ({
  useRoles: () => ({ data: [{ name: 'SUPERVISOR', label: 'Supervisor' }] })
}))
```

with the single

```tsx
vi.mock('@chawpi/core', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@chawpi/core')>()),
  useObjects: () => ({ data: [{ id: 'o-1', name: 'predio', label: 'Predio' }] }),
  useRoles: () => ({ data: [{ name: 'SUPERVISOR', label: 'Supervisor' }] })
}))
```

`workflowGraph.test.ts` is pure and needs no edit.

Run: `yarn workspace @chawpi/workflow test src/workflowGraph.test.ts`
Expected: PASS. It depends only on `workflowGraph.ts` and `types.ts`.

- [ ] **Step 4: Write the failing tests for the module, its strings, its boundaries and its slots**

`frontend/packages/workflow/src/module.test.tsx` (Shared template T2, filled in):

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { workflowModule } from './module'

describe('workflowModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, workflowModule()])).not.toThrow()
  })

  it('mounts its builder under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, workflowModule()]))
    expect(links.to('workflow:builder')).toBe('/automation/workflows')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, workflowModule({ basePath: 'x' })]))
    expect(links.to('workflow:builder')).toBe('/x/workflows')
  })

  it('puts the builder in core automation group', () => {
    const registry = createRegistry([coreModule, workflowModule()])
    const automation = registry.navGroups.find((group) => group.id === 'automation')
    expect(automation?.items.map((item) => [item.labelKey, item.to])).toEqual([['workflow:nav.workflows', '/automation/workflows']])
  })

  it('offers the page builder a WORKFLOW component and a TRANSITION action', () => {
    const registry = createRegistry([coreModule, workflowModule()])
    expect(registry.pageComponents.WORKFLOW).toMatchObject({ labelKey: 'workflow:pageComponents.WORKFLOW' })
    expect(registry.pageComponents.WORKFLOW.icon).toBeDefined()
    expect(registry.pageComponents.WORKFLOW.preview).toBeDefined()
    expect(registry.pageActions.TRANSITION).toMatchObject({ labelKey: 'workflow:pageActions.TRANSITION', defaults: { action: 'TRANSITION' } })
    expect(registry.pageActions.TRANSITION.settings).toBeDefined()
    expect(registry.objectFlags).toHaveLength(1)
  })
})
```

`frontend/packages/workflow/src/i18n.test.ts` (Shared template T4, filled in):

```ts
import { describe, expect, it } from 'vitest'
import { workflowMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('workflow messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(workflowMessages.en)).toEqual(leaves(workflowMessages.es))
  })

  it('names its nav entry and its builder slots', () => {
    expect(leaves(workflowMessages.es)).toEqual(
      expect.arrayContaining(['nav.workflows', 'pageComponents.WORKFLOW', 'pageActions.TRANSITION', 'pages.transition', 'pages.transitionGone', 'pages.action', 'pages.mockWorkflow.initialState'])
    )
  })
})
```

`frontend/packages/workflow/src/boundaries.test.ts` is Shared template T3 verbatim, with `describe('workflow boundaries', …)` and:

```ts
const OWN = /^(@xyflow\/react)$/
```

`frontend/packages/workflow/src/slots/TransitionAction.test.tsx`. This is the TRANSITION case of sapgis `components/page-renderer/ActionButton.test.tsx`, now drawn by core's `ActionButton` through the registry, against the network instead of a mocked api:

```tsx
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ActionButton, type PageComponent } from '@chawpi/core'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { workflowModule } from '../module'

function action(extra: Partial<PageComponent>): PageComponent {
  return { type: 'ACTION', column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, ...extra }
}

const TRANSITIONS = '/objects/predio/records/r1/transitions'
const closed = [{ name: 'aprobar', label: 'Aprobar', to: 'approved', toLabel: 'Aprobado', allowed: false, reason: 'No es tu rol' }]
const open = [{ ...closed[0], allowed: true, reason: null }]

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

function draw(component: PageComponent) {
  return renderWithProviders(<ActionButton component={component} objectName="predio" recordId="r1" />, { modules: [workflowModule()] })
}

describe('TRANSITION action', () => {
  it('says why a transition is closed instead of pretending it is open', async () => {
    fetch = mockFetch([{ path: TRANSITIONS, body: closed }])
    draw(action({ action: 'TRANSITION', transition: 'aprobar', title: 'Aprobar' }))

    const button = await screen.findByTitle('No es tu rol')
    expect(button).toHaveAccessibleName('Aprobar')
    expect(button).toBeDisabled()
  })

  it('applies the transition the button names', async () => {
    fetch = mockFetch([
      { path: TRANSITIONS, body: open },
      { method: 'POST', path: `${TRANSITIONS}/aprobar`, body: { id: 'r1', createdAt: null, updatedAt: null, attributes: {}, state: 'approved' } }
    ])
    draw(action({ action: 'TRANSITION', transition: 'aprobar', style: 'PRIMARY' }))

    const button = await screen.findByRole('button', { name: 'Aprobar' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)

    await waitFor(() => expect(fetch?.calls.some((call) => call.method === 'POST' && call.path === `${TRANSITIONS}/aprobar`)).toBe(true))
  })

  it('says so when the transition left the workflow, and falls back to its technical name', async () => {
    fetch = mockFetch([{ path: TRANSITIONS, body: [] }])
    draw(action({ action: 'TRANSITION', transition: 'archivar' }))

    const button = await screen.findByRole('button', { name: 'archivar' })
    expect(button).toHaveAttribute('title', 'Esta transición ya no existe en el workflow')
    expect(button).toBeDisabled()
  })
})
```

`frontend/packages/workflow/src/slots/TransitionSettings.test.tsx` pins Review Focus 2: the picker offers transitions **only** for an enabled workflow. It uses the same native-select double as the builder test, because radix's listbox never opens in jsdom:

```tsx
import type { ReactNode } from 'react'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ObjectDefinition, PageComponent } from '@chawpi/core'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { workflowModule } from '../module'
import { TransitionSettings } from './TransitionSettings'

vi.mock('@chawpi/ui', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@chawpi/ui')>()),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children?: ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children?: ReactNode }) => <option value={value}>{children}</option>,
  Select: ({ value, onValueChange, children }: { value: string; onValueChange: (value: string) => void; children?: ReactNode }) => (
    <select aria-label="transición" value={value} onChange={(event) => onValueChange(event.target.value)}>
      <option value="" />
      {children}
    </select>
  )
}))

const definition: ObjectDefinition = { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] }
const component: PageComponent = {
  type: 'ACTION',
  column: 1,
  title: null,
  layout: 'single-column',
  children: [],
  relationship: null,
  fields: null,
  content: null,
  action: 'TRANSITION'
}

function workflow(enabled: boolean) {
  return {
    id: 'wf-1',
    objectName: 'predio',
    name: 'approval',
    label: 'Aprobación',
    enabled,
    definition: {
      states: [
        { name: 'draft', label: 'Borrador', type: 'INITIAL' },
        { name: 'review', label: 'En revisión', type: 'FINAL' }
      ],
      transitions: [
        { name: 'send', label: 'Enviar', from: 'draft', to: 'review', roles: [] },
        { name: 'approve', label: 'Aprobar', from: 'review', to: 'review', roles: [] }
      ]
    }
  }
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

function draw(onChange = vi.fn()) {
  const result = renderWithProviders(<TransitionSettings component={component} definition={definition} objectName="predio" onChange={onChange} />, {
    modules: [workflowModule()]
  })
  const settled = () => waitFor(() => expect(result.queryClient.getQueryState(['workflow', 'predio'])?.status).not.toBe('pending'))
  return { onChange, settled }
}

const offered = () =>
  within(screen.getByLabelText('transición'))
    .getAllByRole('option')
    .map((option) => option.getAttribute('value'))

describe('TRANSITION settings', () => {
  it('offers the transitions of an enabled workflow and hands the pick back', async () => {
    fetch = mockFetch([{ path: '/objects/predio/workflow', body: workflow(true) }])
    const { onChange } = draw()

    await screen.findByRole('option', { name: 'approve' })
    expect(offered()).toEqual(['', 'send', 'approve'])
    await userEvent.selectOptions(screen.getByLabelText('transición'), 'approve')
    expect(onChange).toHaveBeenCalledWith({ transition: 'approve' })
    expect(screen.getByText('Transición')).toBeInTheDocument()
  })

  it('offers nothing while the workflow is switched off: the server would refuse every one', async () => {
    fetch = mockFetch([{ path: '/objects/predio/workflow', body: workflow(false) }])
    const { settled } = draw()

    await settled()
    expect(offered()).toEqual([''])
  })

  it('offers nothing for an object without a workflow, and does not retry the 404', async () => {
    fetch = mockFetch([])
    const { settled } = draw()

    await settled()
    expect(offered()).toEqual([''])
    expect(fetch.calls.filter((call) => call.path === '/objects/predio/workflow')).toHaveLength(1)
  })
})
```

`frontend/packages/workflow/src/slots/WorkflowComponent.test.tsx`. A WORKFLOW node goes through core's `PageRenderer` and gets its builder preview:

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { coreModule, createRegistry, PageRenderer, type ObjectDefinition, type Page, type PageComponent, type RecordItem } from '@chawpi/core'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { workflowModule } from '../module'

const definition: ObjectDefinition = { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] }
const record: RecordItem = { id: 'r1', createdAt: null, updatedAt: null, attributes: {} }

function node(type: string, extra: Partial<PageComponent> = {}): PageComponent {
  return { type, column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, region: null, ...extra }
}

const page: Page = {
  id: 'p1',
  name: 'predio_record_detail',
  label: 'Detalle de predio',
  objectName: 'predio',
  kind: 'RECORD_DETAIL',
  template: { name: 'single-region', columns: 12, rows: [{ regions: [{ name: 'MAIN', span: 12 }] }] },
  generated: false,
  definition: { page: node('PAGE', { children: [node('REGION', { region: 'MAIN', children: [node('WORKFLOW')] })] }) }
}

const workflow = {
  id: 'wf-1',
  objectName: 'predio',
  name: 'approval',
  label: 'Aprobación',
  enabled: true,
  definition: {
    states: [
      { name: 'review', label: 'En revisión', type: 'INTERMEDIATE' },
      { name: 'approved', label: 'Aprobado', type: 'FINAL' }
    ],
    transitions: [{ name: 'aprobar', label: 'Aprobar', from: 'review', to: 'approved', roles: [] }]
  }
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

describe('WORKFLOW page component', () => {
  it('draws the record state and its way out where the admin placed it', async () => {
    fetch = mockFetch([
      { path: '/objects/predio/workflow', body: workflow },
      { path: '/objects/predio/records/r1/transitions', body: [{ name: 'aprobar', label: 'Aprobar', to: 'approved', toLabel: 'Aprobado', allowed: true, reason: null }] },
      { path: '/objects/predio/records/r1', body: { ...record, state: 'review' } }
    ])
    renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />, { modules: [workflowModule()] })

    expect(await screen.findByRole('heading', { name: 'Aprobación' })).toBeInTheDocument()
    expect(await screen.findByTestId('workflow-state')).toHaveTextContent('En revisión')
    expect(await screen.findByRole('button', { name: /Aprobar/ })).toBeEnabled()
  })

  it('previews a state pill in the page builder without fetching anything', () => {
    fetch = mockFetch([])
    const Preview = createRegistry([coreModule, workflowModule()]).pageComponents.WORKFLOW.preview!
    renderWithProviders(<Preview component={node('WORKFLOW')} definition={definition} />, { modules: [workflowModule()] })

    expect(screen.getByText('Estado inicial')).toBeInTheDocument()
    expect(fetch.calls).toEqual([])
  })
})
```


`frontend/packages/workflow/src/slots/useWorkflowFlags.test.tsx` checks that core's object editor sees the flag through `useObjectFlags`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useObjectFlags } from '@chawpi/core'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { workflowModule } from '../module'

function Probe() {
  return <output>{JSON.stringify(useObjectFlags('predio'))}</output>
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

describe('workflow object flag', () => {
  it('tells core an object has a workflow', async () => {
    fetch = mockFetch([{ path: '/objects/predio/workflow', body: { id: 'wf-1', objectName: 'predio', name: 'a', label: 'A', enabled: true, definition: { states: [], transitions: [] } } }])
    renderWithProviders(<Probe />, { modules: [workflowModule()] })
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('{"workflow":true}'))
  })

  it('says no for an object whose workflow answers 404', async () => {
    fetch = mockFetch([])
    renderWithProviders(<Probe />, { modules: [workflowModule()] })
    await waitFor(() => expect(fetch?.calls).toHaveLength(1))
    expect(screen.getByRole('status')).toHaveTextContent('{"workflow":false}')
  })
})
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/workflow test`
Expected: FAIL. `module.test.tsx`, `i18n.test.ts`, the four `slots/*.test.tsx` files and the two ported component tests cannot resolve `./module`, `./i18n` or `./slots/…`. `workflowGraph.test.ts` and `boundaries.test.ts` pass.

- [ ] **Step 6: Write the namespace bundle**

`frontend/packages/workflow/src/i18n.ts`. The `workflows` block is sapgis `features/workflows/i18n.ts` verbatim. `nav`, `pageComponents`, `pageActions` and `pages` are copied from core's `common.json` (`nav.workflows`, `pages.types.WORKFLOW`, `pages.actionKinds.TRANSITION`, `pages.transition`, `pages.transitionGone`, `pages.action`, `pages.mockWorkflow.initialState`). Task 10 deletes the core copies that no other code uses.

```ts
// the workflow screens own their strings, loaded under the `workflow` namespace. `pages.*` keeps the
// sapgis key paths the transition button, its picker and the builder preview were written against.
const es = {
  nav: { workflows: 'Workflows' },
  pageComponents: { WORKFLOW: 'Workflow' },
  pageActions: { TRANSITION: 'Dispara una transición' },
  pages: {
    action: 'Acción',
    transition: 'Transición',
    transitionGone: 'Esta transición ya no existe en el workflow',
    mockWorkflow: { initialState: 'Estado inicial' }
  },
  workflows: {
    title: 'Flujos de trabajo',
    subtitle: 'Estados por los que pasa un registro y quién puede moverlo',
    object: 'Objeto',
    selectObject: 'Elige un objeto',
    selectState: 'Elige un estado',
    panelTitle: 'Flujo de trabajo',
    currentState: 'Estado actual',
    unknownState: 'Sin estado',
    noTransitions: 'No hay transiciones desde este estado',
    notAllowed: 'No puedes aplicar esta transición',
    applying: 'Aplicando…',
    moveTo: 'Pasa a {{state}}',
    conflict: 'El registro cambió de estado mientras mirabas. Vuelve a cargarlo.',
    newHint: 'Este objeto todavía no tiene flujo. Este es un punto de partida: edítalo y guárdalo.',
    name: 'Nombre técnico',
    label: 'Etiqueta',
    enabled: 'Activo',
    enabledHint: 'Un flujo inactivo no muestra botones en el registro',
    addState: 'Añadir estado',
    stateName: 'Nombre',
    stateLabel: 'Etiqueta',
    stateType: 'Tipo',
    types: {
      INITIAL: 'Inicial',
      INTERMEDIATE: 'Intermedio',
      FINAL: 'Final'
    },
    from: 'Desde',
    to: 'Hasta',
    roles: 'Roles',
    anyRole: 'Cualquiera que pueda editar',
    rolesHint: 'Sin roles marcados, la transición la puede aplicar cualquiera que pueda editar el registro',
    save: 'Guardar flujo',
    delete: 'Eliminar flujo',
    confirmDelete: '¿Eliminar el flujo de {{object}}? Los registros conservarán su estado.',
    unavailable: 'Flujos de trabajo no disponibles',
    inspector: 'Detalles',
    stateSelected: 'Estado',
    transitionSelected: 'Transición',
    selectHint: 'Elige un estado o una transición en el lienzo para editarlo. Arrastra de un estado a otro para conectarlos.',
    autoLayout: 'Recolocar',
    deleteSelected: 'Eliminar lo seleccionado',
    finalHint: 'De un estado final no sale ninguna transición',
    refusals: {
      FINAL_HAS_EXIT: '«{{value}}» es un estado final: no puede tener salidas',
      UNKNOWN_STATE: 'El estado «{{value}}» no existe'
    },
    problems: {
      NO_STATES: 'Define al menos un estado',
      NO_INITIAL: 'Falta el estado inicial: marca uno como Inicial',
      MANY_INITIAL: 'Solo puede haber un estado inicial',
      DUPLICATE_STATE: 'El estado «{{value}}» está repetido',
      DUPLICATE_TRANSITION: 'La transición «{{value}}» está repetida',
      UNKNOWN_FROM: 'La transición «{{value}}» sale de un estado que no existe',
      UNKNOWN_TO: 'La transición «{{value}}» apunta a un estado que no existe'
    },
    starter: {
      workflowLabel: 'Aprobación',
      draft: 'Borrador',
      review: 'En revisión',
      approved: 'Aprobado',
      rejected: 'Rechazado',
      send: 'Enviar a revisión',
      approve: 'Aprobar',
      reject: 'Rechazar'
    }
  }
}

const en: typeof es = {
  nav: { workflows: 'Workflows' },
  pageComponents: { WORKFLOW: 'Workflow' },
  pageActions: { TRANSITION: 'Fires a transition' },
  pages: {
    action: 'Action',
    transition: 'Transition',
    transitionGone: 'This transition is no longer in the workflow',
    mockWorkflow: { initialState: 'Initial state' }
  },
  workflows: {
    title: 'Workflows',
    subtitle: 'The states a record moves through, and who may move it',
    object: 'Object',
    selectObject: 'Pick an object',
    selectState: 'Pick a state',
    panelTitle: 'Workflow',
    currentState: 'Current state',
    unknownState: 'No state',
    noTransitions: 'Nothing leaves this state',
    notAllowed: 'You cannot apply this transition',
    applying: 'Applying…',
    moveTo: 'Moves to {{state}}',
    conflict: 'The record changed state while you were looking. Reload it.',
    newHint: 'This object has no workflow yet. Here is a starting point: edit it and save.',
    name: 'Technical name',
    label: 'Label',
    enabled: 'Enabled',
    enabledHint: 'A disabled workflow shows no buttons on the record',
    addState: 'Add state',
    stateName: 'Name',
    stateLabel: 'Label',
    stateType: 'Type',
    types: {
      INITIAL: 'Initial',
      INTERMEDIATE: 'Intermediate',
      FINAL: 'Final'
    },
    from: 'From',
    to: 'To',
    roles: 'Roles',
    anyRole: 'Anyone who may update',
    rolesHint: 'With no role ticked, anyone who may update the record can apply the transition',
    save: 'Save workflow',
    delete: 'Delete workflow',
    confirmDelete: 'Delete the workflow of {{object}}? Records keep their state.',
    unavailable: 'Workflows unavailable',
    inspector: 'Details',
    stateSelected: 'State',
    transitionSelected: 'Transition',
    selectHint: 'Pick a state or a transition on the canvas to edit it. Drag from one state to another to connect them.',
    autoLayout: 'Rearrange',
    deleteSelected: 'Delete selection',
    finalHint: 'Nothing leaves a final state',
    refusals: {
      FINAL_HAS_EXIT: '"{{value}}" is a final state: it can have no way out',
      UNKNOWN_STATE: 'State "{{value}}" does not exist'
    },
    problems: {
      NO_STATES: 'Define at least one state',
      NO_INITIAL: 'No initial state: mark one as Initial',
      MANY_INITIAL: 'Only one state can be the initial one',
      DUPLICATE_STATE: 'State "{{value}}" is repeated',
      DUPLICATE_TRANSITION: 'Transition "{{value}}" is repeated',
      UNKNOWN_FROM: 'Transition "{{value}}" leaves a state that does not exist',
      UNKNOWN_TO: 'Transition "{{value}}" points at a state that does not exist'
    },
    starter: {
      workflowLabel: 'Approval',
      draft: 'Draft',
      review: 'In review',
      approved: 'Approved',
      rejected: 'Rejected',
      send: 'Send to review',
      approve: 'Approve',
      reject: 'Reject'
    }
  }
}

export const workflowMessages = { es, en }
```

- [ ] **Step 7: Write the slot adapters**

`frontend/packages/workflow/src/slots/WorkflowComponent.tsx`:

```tsx
import type { PageComponentProps } from '@chawpi/core'
import { WorkflowPanel } from '../WorkflowPanel'

// a WORKFLOW node on a record page: the panel of that record, of that object
export function WorkflowComponent({ definition, record }: PageComponentProps) {
  return <WorkflowPanel objectName={definition.name} recordId={record.id} />
}
```

`frontend/packages/workflow/src/slots/WorkflowPreview.tsx` (sapgis `ComponentMock.tsx:118-131`, same markup):

```tsx
import { useTranslation } from 'react-i18next'

// the builder canvas's stand-in for a WORKFLOW: a state pill and two button shapes. it fetches
// nothing and takes no click -- a real control would eat the drag
export function WorkflowPreview() {
  const { t } = useTranslation(['workflow', 'common'])
  return (
    <div className="space-y-3">
      <span className="inline-flex items-center rounded-full bg-surface-muted px-3 py-1 text-xs font-medium text-ink-muted">
        {t('pages.mockWorkflow.initialState')}
      </span>
      <div className="flex gap-2">
        <div className="h-9 w-24 rounded-md bg-surface-muted" />
        <div className="h-9 w-24 rounded-md bg-surface-muted" />
      </div>
    </div>
  )
}
```

`frontend/packages/workflow/src/slots/TransitionAction.tsx` (sapgis `ActionButton.tsx:21-39`, same markup and texts):

```tsx
import { useTranslation } from 'react-i18next'
import type { PageActionProps } from '@chawpi/core'
import { Button } from '@chawpi/ui'
import { useApplyTransition, useAvailableTransitions } from '../api'

// the one transition an admin placed as a button. WORKFLOW draws every transition at once; this
// draws the one it was told to, and says why when that one is shut.
export function TransitionAction({ component, objectName, recordId }: PageActionProps) {
  const { t } = useTranslation(['workflow', 'common'])
  const transitions = useAvailableTransitions(objectName, recordId)
  const apply = useApplyTransition(objectName, recordId)
  const found = (transitions.data ?? []).find((candidate) => candidate.name === component.transition)

  return (
    <Button
      variant={component.style === 'PRIMARY' ? 'primary' : 'secondary'}
      disabled={!found?.allowed || apply.isPending}
      // gone from the workflow says so; still there but blocked says why
      title={found ? (found.allowed ? undefined : (found.reason ?? undefined)) : t('pages.transitionGone')}
      onClick={() => apply.mutate(component.transition!)}
    >
      {component.title ?? found?.label ?? component.transition ?? t('pages.action')}
    </Button>
  )
}
```

`frontend/packages/workflow/src/slots/TransitionSettings.tsx` (sapgis `Inspector.tsx:228-242`). The picker now reads the workflow itself instead of taking a `transitions` prop from the page builder (`PageBuilderPage.tsx:242-244`):

```tsx
import { useTranslation } from 'react-i18next'
import type { PageComponentSettingsProps } from '@chawpi/core'
import { Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@chawpi/ui'
import { useWorkflow } from '../api'

// which transition a TRANSITION button fires. the page builder draws it under the kind picker.
export function TransitionSettings({ component, objectName, onChange }: PageComponentSettingsProps) {
  const { t } = useTranslation(['workflow', 'common'])
  const workflow = useWorkflow(objectName || undefined)
  // a disabled workflow accepts no transition at all; offering one the server would refuse helps no one
  const transitions = workflow.data?.enabled ? workflow.data.definition.transitions.map((transition) => transition.name) : []

  return (
    <div className="space-y-1.5">
      <Label>{t('pages.transition')}</Label>
      <Select value={component.transition ?? ''} onValueChange={(value) => onChange({ transition: value })}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {transitions.map((name) => (
            <SelectItem key={name} value={name}>
              {name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
```

`frontend/packages/workflow/src/slots/useWorkflowFlags.ts`:

```ts
import { useWorkflow } from '../api'

// core's object editor asks whether an object has a workflow (its state column's scope). an object
// without one answers 404: no data, no flag. same query as the builder, so it is cached once.
export function useWorkflowFlags(objectName: string): Record<string, boolean> {
  const { data } = useWorkflow(objectName || undefined)
  return { workflow: Boolean(data) }
}
```

- [ ] **Step 8: Write the factory and the public entry**

`frontend/packages/workflow/src/module.tsx` (Shared template T1, filled in):

```tsx
import { Workflow } from 'lucide-react'
import type { ChawpiModule } from '@chawpi/core'
import { workflowMessages } from './i18n'
import { TransitionAction } from './slots/TransitionAction'
import { TransitionSettings } from './slots/TransitionSettings'
import { WorkflowComponent } from './slots/WorkflowComponent'
import { WorkflowPreview } from './slots/WorkflowPreview'
import { useWorkflowFlags } from './slots/useWorkflowFlags'

export const WORKFLOW_MODULE_ID = 'workflow'

export interface WorkflowModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's urls
  basePath?: string
}

export function workflowModule(options: WorkflowModuleOptions = {}): ChawpiModule {
  return {
    id: WORKFLOW_MODULE_ID,
    basePath: options.basePath ?? 'automation',
    // lazy: xyflow only loads when someone opens the builder
    routes: [{ id: 'builder', path: 'workflows', lazy: () => import('./WorkflowBuilderPage').then((m) => ({ default: m.WorkflowBuilderPage })) }],
    nav: [{ group: 'automation', labelKey: 'workflow:nav.workflows', order: 10, icon: Workflow, route: 'builder' }],
    pageComponents: {
      WORKFLOW: { render: WorkflowComponent, labelKey: 'workflow:pageComponents.WORKFLOW', icon: Workflow, preview: WorkflowPreview }
    },
    pageActions: {
      TRANSITION: { render: TransitionAction, labelKey: 'workflow:pageActions.TRANSITION', settings: TransitionSettings, defaults: { action: 'TRANSITION' } }
    },
    objectFlags: useWorkflowFlags,
    i18n: workflowMessages
  }
}
```

`frontend/packages/workflow/src/index.ts` (replace Task 1's placeholder entirely):

```ts
export { WORKFLOW_MODULE_ID, workflowModule, type WorkflowModuleOptions } from './module'
export { workflowMessages } from './i18n'
export { WorkflowPanel } from './WorkflowPanel'
export { WorkflowBuilderPage } from './WorkflowBuilderPage'
export { useApplyTransition, useAvailableTransitions, useDeleteWorkflow, useSaveWorkflow, useWorkflow } from './api'
export type {
  AvailableTransition,
  DefinitionProblem,
  RecordWithState,
  StateType,
  Workflow,
  WorkflowDefinition,
  WorkflowPayload,
  WorkflowState,
  WorkflowTransition
} from './types'
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `yarn workspace @chawpi/workflow test`
Expected: PASS for every file, which includes every ported case of `WorkflowPanel.test.tsx`, `WorkflowBuilderPage.test.tsx` and `workflowGraph.test.ts`, unchanged apart from Step 3.

If a ported case fails on a missing string, the key is missing from `src/i18n.ts`. Copy it from sapgis `features/workflows/i18n.ts`, and never add it to core.

If `WorkflowComponent.test.tsx` shows core's `pages.componentUnavailable` placeholder instead of the panel, the registry did not receive `workflowModule()`. Check the `modules` option.

- [ ] **Step 10: Write the README**

`frontend/packages/workflow/README.md` (Shared template T5):

````markdown
# @chawpi/workflow

Record workflows for a Chawpi app:
- a visual builder (states, transitions, roles) on an xyflow canvas;
- a WORKFLOW component that shows a record's state and its allowed transitions on the record page;
- a TRANSITION button kind for the page builder.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```bash
yarn add @chawpi/workflow @chawpi/core @chawpi/ui
```

`@xyflow/react` comes in as a dependency of this package. Its stylesheet is imported by the canvas, so a bundler that handles CSS imports (Vite, webpack with css-loader) needs nothing else.

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { pagesModule } from '@chawpi/pages'
import { workflowModule } from '@chawpi/workflow'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'Catastro' }} modules={[pagesModule(), workflowModule()]} />
}
```

## What it adds

| Slot | Value |
|---|---|
| route `workflow:builder` | `/automation/workflows` (lazy) |
| nav | group `automation`, "Workflows" |
| page component `WORKFLOW` | the record's state and transition buttons; a state pill preview in the page builder |
| page action `TRANSITION` | a button that fires one named transition; the page builder offers the transitions of the object's workflow, and none while that workflow is disabled |
| object flag `workflow` | `true` when the object has a workflow (core's object editor shows the state column's scope) |
| i18n namespace | `workflow` (es, en) |

With `@chawpi/pages` installed, a freshly dropped ACTION defaults to TRANSITION, as it did in sapgis. Without this module, the page builder offers NAVIGATE only, and a stored TRANSITION button draws nothing.

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'automation'` | url prefix of the builder route |

## Backend

It needs the chawpi workflow backend module:
- `GET|PUT|DELETE /objects/{object}/workflow`;
- `GET /objects/{object}/records/{id}/transitions`;
- `POST /objects/{object}/records/{id}/transitions/{name}`.

An object without a workflow, or a backend without the module, must answer **404**. The panel and the buttons then draw nothing, and nothing retries.
````

- [ ] **Step 11: Format, lint, test, build, confirm nothing was committed**

```bash
yarn prettier --write frontend/packages/workflow/src frontend/packages/workflow/README.md
yarn workspace @chawpi/workflow lint
yarn workspace @chawpi/workflow test
yarn workspace @chawpi/workflow build
ls frontend/packages/workflow/dist
grep -l "@xyflow/react" frontend/packages/workflow/dist/*.js
git status --short
```

Expected:
- lint, test and build pass;
- `dist` holds `index.js` and `index.d.ts`, plus a separate chunk for the lazily loaded `WorkflowBuilderPage`;
- `@xyflow/react` appears only as an import specifier (external), never bundled.

`git status --short` lists only new or modified paths under `frontend/packages/workflow/` (plus whatever other Wave 1 tasks produced in their own directories), and no commit was made.

### Task 4: `@chawpi/automation` — rule builder and run log

**Wave 1.** Runs in parallel with Tasks 2, 3 and 5–9. It writes only `frontend/packages/automation/src/**` and `frontend/packages/automation/README.md`.

**Files:**
- Port (script, then the listed edits):
  - `sapgis features/automations/types.ts` → `frontend/packages/automation/src/types.ts` (plus two local types);
  - `api.ts` → `src/api.ts` (plus two local read hooks);
  - `automationDraft.ts` → `src/automationDraft.ts`;
  - `automationDraft.test.ts` → `src/automationDraft.test.ts`;
  - `RunTable.tsx` → `src/RunTable.tsx`;
  - `RunTable.test.tsx` → `src/RunTable.test.tsx`;
  - `AutomationRunsPage.tsx` → `src/AutomationRunsPage.tsx`;
  - `AutomationBuilderPage.tsx` → `src/AutomationBuilderPage.tsx`;
  - `i18n.ts` → `src/i18n.ts`.
- Create:
  - `src/module.tsx`, `src/module.test.tsx`;
  - `src/i18n.test.ts`;
  - `src/api.test.ts`;
  - `src/AutomationBuilderPage.test.tsx`;
  - `src/boundaries.test.ts`;
  - `README.md`.
- Replace: `src/index.ts` (Task 1 placeholder).
- Do not touch: `package.json`, `tsconfig*.json`, `vite.config.ts`, `src/test/setup.ts` (Task 1 owns them), anything outside `frontend/packages/automation/`.

**Interfaces:**
- Consumes:
  - from `@chawpi/core`:
    - `api<T>(path, init?)`, `ApiError` (`.message`, `.violations: FieldViolation[]`);
    - `PageHeader`;
    - `useObjects()`, `useObjectDefinition(name?)`;
    - `absoluteTime(iso)`, `relativeTime(iso, now?)`;
    - `ChawpiModule`, `coreModule`, `createRegistry`, `createLinks`.
  - from `@chawpi/ui`: `Button`, `Card*`, `Input`, `Label`, `Select*`, `Badge`, `Table`, `Td`, `Th`, `cn`.
  - from `@chawpi/testing`: `renderWithProviders(ui, { modules })`, `mockFetch(routes)`.
  - from Task 1: the scaffolded package and the port script's MODULE_MAP. Inside `frontend/packages/automation/src`, `@/features/automations/X` becomes `./X`; core specifiers become `@chawpi/core`; `@/features/workflows/api` stays `@/features/workflows/api` (MANUAL — automation may not import `@chawpi/workflow`) and is replaced by hand in Step 5.
- Produces (public API of `@chawpi/automation`):
  - `automationModule(options?: AutomationModuleOptions): ChawpiModule`, `AUTOMATION_MODULE_ID = 'automation'`, `interface AutomationModuleOptions { basePath?: string }`;
  - routes: key `automation:rules` at `/automation/rules`, key `automation:runs` at `/automation/runs`;
  - nav: group `automation`, `automation:nav.rules` (order 20, icon `Zap`), `automation:nav.runs` (order 30, icon `ListChecks`);
  - `AutomationBuilderPage`, `AutomationRunsPage`, `RunTable`, and every type from `src/types.ts`;
  - `useWorkflowOutline(objectName)` and `workflowOutlineQuery(objectName)`. The query key is `['workflow', objectName]`, the same key as `@chawpi/workflow`'s `useWorkflow` (M5), so the two share a cache entry.
  - `useDocumentTypeOptions(objectName)` and `documentTypeOptionsQuery(objectName)`. The query key is `['document-types', objectName]`, the same key as `@chawpi/documents`' `useDocumentTypes`.
- Ruling local to this task: the document-type read uses `retry: false`. sapgis's `useDocumentTypes` retried; here the documents module may be absent, and its 404 means "no types". This is the one behaviour difference, and it only removes the retry delay.

- [ ] **Step 1: Port the pure files and their tests**

```bash
cd /Users/jorge/IdeaProjects/chawpi
for f in types.ts api.ts automationDraft.ts automationDraft.test.ts RunTable.tsx RunTable.test.tsx AutomationRunsPage.tsx AutomationBuilderPage.tsx i18n.ts; do
  node frontend/tooling/port-from-sapgis.mjs features/automations/$f frontend/packages/automation/src/$f
done
```

Expected: nine `ported …` lines, and exactly two `MANUAL` warnings:
- `frontend/packages/automation/src/i18n.ts still imports @/lib/i18n`;
- one for `@/features/workflows/api` in `AutomationBuilderPage.tsx`, if Task 1 made the script return null for it. If the script instead rewrote it to `@chawpi/workflow`, there is no warning, and Step 5 fixes it anyway.

Check that no `@/` specifier is left anywhere except those two:

```bash
grep -rn "'@/" frontend/packages/automation/src
```

Expected: only `src/i18n.ts:1` (`@/lib/i18n`), plus `src/AutomationBuilderPage.tsx` if it still names `@/features/workflows/api`.

- [ ] **Step 2: Turn the i18n side-effect bundle into the module bundle**

In `frontend/packages/automation/src/i18n.ts`:
- Delete line 1, `import i18n from '@/lib/i18n'`.
- Replace the comment on line 3 (`// the automation screens own their strings. shared locale files stay untouched.`) with `// the automation namespace. keys keep their sapgis paths (automations.*); nav is the sidebar's.`
- Keep `const es = { … }` and `const en = { … }` byte-for-byte.
- Replace the last four lines (`i18n.addResourceBundle('es', …)`, `i18n.addResourceBundle('en', …)`, the blank line, `export default i18n`) with:

```ts
// nav.rules / nav.runs were core's common.json keys (Task 10 deletes them there)
export const automationMessages = {
  es: { ...es, nav: { rules: 'Reglas', runs: 'Ejecuciones' } },
  en: { ...en, nav: { rules: 'Rules', runs: 'Runs' } }
}
```

`common.loading` (AutomationRunsPage) stays in core's `common` namespace and is reached through the `common` fallback namespace. It is not copied.

- [ ] **Step 3: Write the failing i18n and module tests**

`frontend/packages/automation/src/i18n.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { automationMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('automation messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(automationMessages.en)).toEqual(leaves(automationMessages.es))
  })

  it('names its nav entries', () => {
    expect(leaves(automationMessages.es)).toEqual(expect.arrayContaining(['nav.rules', 'nav.runs', 'automations.title', 'automations.runsTitle']))
  })
})
```

`frontend/packages/automation/src/module.test.tsx`:

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { automationModule } from './module'

describe('automationModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, automationModule()])).not.toThrow()
  })

  it('mounts its routes under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, automationModule()]))
    expect(links.to('automation:rules')).toBe('/automation/rules')
    expect(links.to('automation:runs')).toBe('/automation/runs')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, automationModule({ basePath: 'x' })]))
    expect(links.to('automation:rules')).toBe('/x/rules')
    expect(links.to('automation:runs')).toBe('/x/runs')
  })

  it('puts rules and runs in core automation group, after workflows', () => {
    const registry = createRegistry([coreModule, automationModule()])
    const group = registry.navGroups.find((candidate) => candidate.id === 'automation')
    expect(group?.items.map((item) => [item.labelKey, item.to, item.order])).toEqual([
      ['automation:nav.rules', '/automation/rules', 20],
      ['automation:nav.runs', '/automation/runs', 30]
    ])
  })
})
```

Run: `yarn workspace @chawpi/automation test src/i18n.test.ts src/module.test.tsx`
Expected: `i18n.test.ts` PASS. `module.test.tsx` FAILS because `./module` cannot be resolved.

- [ ] **Step 4: Write the module factory and the public entry**

`frontend/packages/automation/src/module.tsx`:

```tsx
import { ListChecks, Zap } from 'lucide-react'
import type { ChawpiModule } from '@chawpi/core'
import { automationMessages } from './i18n'

export const AUTOMATION_MODULE_ID = 'automation'

export interface AutomationModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's urls (/automation/rules)
  basePath?: string
}

// the rule builder and the run log. both lazy: nothing of this module loads before its first visit
export function automationModule(options: AutomationModuleOptions = {}): ChawpiModule {
  return {
    id: AUTOMATION_MODULE_ID,
    basePath: options.basePath ?? 'automation',
    routes: [
      { id: 'rules', path: 'rules', lazy: () => import('./AutomationBuilderPage').then((m) => ({ default: m.AutomationBuilderPage })) },
      { id: 'runs', path: 'runs', lazy: () => import('./AutomationRunsPage').then((m) => ({ default: m.AutomationRunsPage })) }
    ],
    // the automation group is core's; workflow sits at 10 and the assistant at 40
    nav: [
      { group: 'automation', labelKey: 'automation:nav.rules', order: 20, icon: Zap, route: 'rules' },
      { group: 'automation', labelKey: 'automation:nav.runs', order: 30, icon: ListChecks, route: 'runs' }
    ],
    i18n: automationMessages
  }
}
```

`frontend/packages/automation/src/index.ts` (replace the whole placeholder):

```ts
export { AUTOMATION_MODULE_ID, automationModule, type AutomationModuleOptions } from './module'
export { automationMessages } from './i18n'
export { AutomationBuilderPage } from './AutomationBuilderPage'
export { AutomationRunsPage } from './AutomationRunsPage'
export { RunTable } from './RunTable'
export {
  documentTypeOptionsQuery,
  useAutomationRuns,
  useAutomations,
  useDeleteAutomation,
  useDocumentTypeOptions,
  useRecentRuns,
  useSaveAutomation,
  useWorkflowOutline,
  workflowOutlineQuery
} from './api'
export * from './types'
```

Run: `yarn workspace @chawpi/automation test src/i18n.test.ts src/module.test.tsx`
Expected: PASS (6 tests). Nothing type-checks the lazy imports yet; Step 7 does.

- [ ] **Step 5: Write the failing test for the local read hooks**

`frontend/packages/automation/src/api.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { documentTypeOptionsQuery, workflowOutlineQuery } from './api'

// both reads belong to other modules. the keys must stay theirs, or the two caches split; and an
// absent module answers 404, which is an answer: retrying it only delays the empty picker.
describe('reads borrowed from other modules', () => {
  it('reads the workflow under the same key and path as the workflow module, without retrying a 404', () => {
    const options = workflowOutlineQuery('predio')
    expect(options.queryKey).toEqual(['workflow', 'predio'])
    expect(options.retry).toBe(false)
    expect(options.enabled).toBe(true)
    expect(workflowOutlineQuery(undefined).enabled).toBe(false)
  })

  it('reads document types under the same key as the documents module, without retrying a 404', () => {
    const options = documentTypeOptionsQuery('predio')
    expect(options.queryKey).toEqual(['document-types', 'predio'])
    expect(options.retry).toBe(false)
    expect(documentTypeOptionsQuery('').enabled).toBe(false)
  })
})
```

Run: `yarn workspace @chawpi/automation test src/api.test.ts`
Expected: FAIL, `workflowOutlineQuery` is not exported.

- [ ] **Step 6: Add the local types and read hooks**

Append to `frontend/packages/automation/src/types.ts`:

```ts
// what a rule reads of the object's workflow: state and transition names for the trigger pickers.
// the full shape belongs to @chawpi/workflow, which this package never imports.
export interface WorkflowOutline {
  enabled: boolean
  definition: {
    states: { name: string; label: string }[]
    transitions: { name: string; label: string }[]
  }
}

// what GENERATE_DOCUMENT offers. the full shape belongs to @chawpi/documents
export interface DocumentTypeOption {
  id: string
  name: string
  label: string
}
```

In `frontend/packages/automation/src/api.ts`:
- Change line 1 to `import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'`.
- Change the type import (line 3, now `from './types'`) to `import type { Automation, AutomationPayload, AutomationRun, DocumentTypeOption, WorkflowOutline } from './types'`.
- Append:

```ts
// same key, path and retry as @chawpi/workflow's useWorkflow: one cache entry, no package import.
// an object without a workflow (or an app without the workflow module) answers 404: pickers stay empty.
export function workflowOutlineQuery(objectName: string | undefined) {
  return queryOptions({
    queryKey: ['workflow', objectName],
    queryFn: () => api<WorkflowOutline>(`/objects/${objectName}/workflow`),
    enabled: Boolean(objectName),
    retry: false
  })
}

export function useWorkflowOutline(objectName: string | undefined) {
  return useQuery(workflowOutlineQuery(objectName))
}

// same key and path as @chawpi/documents' useDocumentTypes. retry off: without the documents module
// the server answers 404, and that only means there is nothing to pick.
export function documentTypeOptionsQuery(objectName: string | undefined) {
  return queryOptions({
    queryKey: ['document-types', objectName],
    queryFn: () => api<DocumentTypeOption[]>(`/objects/${objectName}/document-types`),
    enabled: Boolean(objectName),
    retry: false
  })
}

export function useDocumentTypeOptions(objectName: string | undefined) {
  return useQuery(documentTypeOptionsQuery(objectName))
}
```

Run: `yarn workspace @chawpi/automation test src/api.test.ts src/automationDraft.test.ts`
Expected: PASS. The draft tests are a straight port and pass unchanged.

- [ ] **Step 7: Rewire the ported screens**

`frontend/packages/automation/src/AutomationBuilderPage.tsx` (line numbers are sapgis's for the first three edits; the port drops sapgis's line 25, the `@/features/automations/i18n` side-effect import, so match the last three by content, not by number — they land three lines earlier, at 36/41/43):
- Line 11: `import { useDocumentTypes, useObjectDefinition, useObjects } from '@chawpi/core'` → `import { useObjectDefinition, useObjects } from '@chawpi/core'`.
- Line 13: delete the line (`import { useWorkflow } from '@chawpi/workflow'` or `'@/features/workflows/api'`).
- Line 14: `import { useAutomationRuns, useAutomations, useDeleteAutomation, useSaveAutomation } from './api'` → `import { useAutomationRuns, useAutomations, useDeleteAutomation, useDocumentTypeOptions, useSaveAutomation, useWorkflowOutline } from './api'`.
- `const { t } = useTranslation()` → `const { t } = useTranslation(['automation', 'common'])`.
- `useDocumentTypes(objectName || undefined)` → `useDocumentTypeOptions(objectName || undefined)`.
- `const workflow = useWorkflow(objectName || undefined)` → `const workflow = useWorkflowOutline(objectName || undefined)`. Keep the comment above it.

`src/AutomationRunsPage.tsx` and `src/RunTable.tsx`: change `useTranslation()` to `useTranslation(['automation', 'common'])`.

Check that none is left and that every import resolves:

```bash
grep -rn "useTranslation()" frontend/packages/automation/src
grep -rn "@chawpi/workflow\|@chawpi/documents\|'@/" frontend/packages/automation/src
yarn workspace @chawpi/automation lint
```

Expected:
- both greps print nothing;
- `lint` fails only on prettier formatting (fixed in Step 12) or passes. It must report no `tsc` error.

If `tsc` reports a missing core export, stop and report `BLOCKED: @chawpi/core does not export <name>`. The grep-verified set (`absoluteTime`, `relativeTime`, `PageHeader`, `ApiError`, `api`, `useObjects`, `useObjectDefinition`) is all exported today.

- [ ] **Step 8: Give the ported RunTable test its module**

In `frontend/packages/automation/src/RunTable.test.tsx`:
- Add `import { automationModule } from './module'` below the `./RunTable` import.
- Pass the module to each of the four `renderWithProviders(...)` calls, keeping their first argument unchanged:

```tsx
renderWithProviders(<RunTable runs={[run({ steps: [{ action: 'UPDATE_FIELD', detail: 'predio.revisado = si' }] })]} />, { modules: [automationModule()] })
renderWithProviders(<RunTable runs={[run({ status: 'SKIPPED', error: 'condition not met: area GREATER_THAN 1000' })]} />, { modules: [automationModule()] })
const { rerender } = renderWithProviders(<RunTable runs={[run({})]} />, { modules: [automationModule()] })
renderWithProviders(<RunTable runs={[]} />, { modules: [automationModule()] })
```

Run: `yarn workspace @chawpi/automation test src/RunTable.test.tsx`
Expected: PASS (4 tests). The empty state proves the `automations.noRuns` string resolves from the `automation` namespace.

- [ ] **Step 9: Write the builder test (Review Focus 4 + happy path)**

sapgis has no test for this page. `frontend/packages/automation/src/AutomationBuilderPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock, type MockRoute } from '@chawpi/testing'

// radix's select cannot be driven in jsdom (pointer-events: none on its trigger), so it is doubled by
// a real <select>. the trigger's id keeps the <Label htmlFor> link; a select without one is named by
// its placeholder, the way sapgis's TemplateEditor test does it.
vi.mock('@chawpi/ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@chawpi/ui')>()
  const find = (node: unknown, prop: 'id' | 'placeholder'): string | undefined => {
    if (Array.isArray(node)) return node.map((child) => find(child, prop)).find(Boolean)
    if (!node || typeof node !== 'object') return undefined
    const props = (node as { props?: Record<string, unknown> }).props
    if (!props) return undefined
    if (typeof props[prop] === 'string') return props[prop] as string
    return find(props.children, prop)
  }
  return {
    ...actual,
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children?: ReactNode }) => <>{children}</>,
    SelectItem: ({ value, children }: { value: string; children?: ReactNode }) => <option value={value}>{children}</option>,
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (value: string) => void; children?: ReactNode }) => {
      const id = find(children, 'id')
      return (
        <select id={id} aria-label={id ? undefined : find(children, 'placeholder')} value={value} onChange={(event) => onValueChange(event.target.value)}>
          <option value="" />
          {children}
        </select>
      )
    }
  }
})

import { AutomationBuilderPage } from './AutomationBuilderPage'
import { automationModule } from './module'

const predio = { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true }
const revisado = {
  id: 'f1',
  name: 'revisado',
  label: 'Revisado',
  type: 'TEXT',
  required: false,
  unique: false,
  defaultValue: null,
  description: null,
  position: 0,
  enumOptions: null,
  relationTarget: null,
  visible: true,
  editable: true
}
const stored = {
  id: 'a1',
  objectName: 'predio',
  name: 'marca-revisado',
  label: 'Marca revisado',
  enabled: true,
  definition: {
    trigger: { type: 'STATE_ENTERED', state: 'aprobado' },
    conditions: [],
    actions: [{ type: 'UPDATE_FIELD', field: 'revisado', value: 'si' }]
  }
}
const workflow = {
  id: 'w1',
  objectName: 'predio',
  name: 'tramite',
  label: 'Trámite',
  enabled: true,
  definition: {
    states: [{ name: 'aprobado', label: 'Aprobado', type: 'FINAL' }],
    transitions: [{ name: 'aprobar', label: 'Aprobar', from: 'borrador', to: 'aprobado', roles: [] }]
  }
}

function routes(workflowRoute: MockRoute): MockRoute[] {
  return [
    { path: '/objects', body: [predio] },
    { path: '/metadata/objects/predio', body: { ...predio, fields: [revisado] } },
    { path: '/objects/predio/automations', body: [stored] },
    { path: '/objects/predio/document-types', status: 404, body: { title: 'Not Found' } },
    { method: 'PUT', path: '/objects/predio/automations/marca-revisado', body: stored },
    workflowRoute
  ]
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

async function openStoredRule() {
  renderWithProviders(<AutomationBuilderPage />, { modules: [automationModule()] })
  const objectPicker = await screen.findByRole('combobox', { name: 'Elige un objeto' })
  await screen.findByRole('option', { name: 'Predio' })
  await userEvent.selectOptions(objectPicker, 'predio')
  await userEvent.click(await screen.findByRole('button', { name: /Marca revisado/ }))
}

describe('AutomationBuilderPage', () => {
  it('leaves the state and transition pickers empty when the object has no workflow, and still saves', async () => {
    fetch = mockFetch(routes({ path: '/objects/predio/workflow', status: 404, body: { title: 'Not Found' } }))
    await openStoredRule()

    const statePicker = await screen.findByLabelText('Estado de destino')
    expect(within(statePicker).getAllByRole('option').map((option) => option.textContent)).toEqual([''])

    await userEvent.click(screen.getByRole('button', { name: 'Guardar regla' }))
    await vi.waitFor(() => expect(fetch?.calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = fetch.calls.find((call) => call.method === 'PUT')
    expect(put?.path).toBe('/objects/predio/automations/marca-revisado')
    expect(put?.body).toEqual({ name: 'marca-revisado', label: 'Marca revisado', enabled: true, definition: stored.definition })

    await userEvent.selectOptions(screen.getByLabelText('Cuándo'), 'TRANSITION_APPLIED')
    const transitionPicker = await screen.findByLabelText('Transición')
    expect(within(transitionPicker).getAllByRole('option').map((option) => option.textContent)).toEqual(['', 'Cualquier transición'])
    // a 404 is an answer: asked once, never again
    expect(fetch.calls.filter((call) => call.path === '/objects/predio/workflow')).toHaveLength(1)
  })

  it('offers the workflow states and transitions when the object has one', async () => {
    fetch = mockFetch(routes({ path: '/objects/predio/workflow', body: workflow }))
    await openStoredRule()

    const statePicker = await screen.findByLabelText('Estado de destino')
    expect(await within(statePicker).findByRole('option', { name: 'Aprobado' })).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Cuándo'), 'TRANSITION_APPLIED')
    const transitionPicker = await screen.findByLabelText('Transición')
    expect(within(transitionPicker).getByRole('option', { name: 'Aprobar' })).toBeInTheDocument()
  })
})
```

Run: `yarn workspace @chawpi/automation test src/AutomationBuilderPage.test.tsx`
Expected: PASS (2 tests).

If it fails, fix the **test's** assumptions against the ported page, not the page's behaviour. Three likely causes:
- a label string differs; take it from `src/i18n.ts`;
- the stored-rule button's accessible name also holds the trigger text, which is why the name is matched with a regex;
- core's `useObjectDefinition` path; `frontend/packages/core/src/queries/objects.ts:16` says `/metadata/objects/${name}`.

The page must keep sapgis's markup and behaviour.

- [ ] **Step 10: Add the boundary test**

`frontend/packages/automation/src/boundaries.test.ts` is Shared template T3 verbatim, with these two lines:

```ts
const OWN = /^$/
```

and `describe('automation boundaries', …)`.

Full file:

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

// a module stays installable on its own: no other module package, no other module's heavy library.
// import.meta.dirname, not new URL(.., import.meta.url): vite rewrites the latter under jsdom
const SRC = import.meta.dirname
// automation owns no heavy library
const OWN = /^$/
const HEAVY = /^(maplibre-gl|terra-draw.*|@xyflow\/.+|@tiptap\/.+|@dnd-kit\/.+)$/

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function importsOf(file: string): string[] {
  return [...readFileSync(file, 'utf8').matchAll(/from '([^']+)'|import '([^']+)'/g)].map((match) => match[1] ?? match[2])
}

describe('automation boundaries', () => {
  it('imports no other module package and no heavy library it does not own', () => {
    const offenders = sources(SRC).flatMap((file) =>
      importsOf(file)
        .map((specifier) =>
          specifier
            .split('/')
            .slice(0, specifier.startsWith('@') ? 2 : 1)
            .join('/')
        )
        .filter((pkg) => (pkg.startsWith('@chawpi/') && pkg !== '@chawpi/core' && pkg !== '@chawpi/ui') || (HEAVY.test(pkg) && !OWN.test(pkg)))
        .map((pkg) => `${relative(SRC, file)} -> ${pkg}`)
    )
    expect(offenders).toEqual([])
  })

  // REST paths passed to api() are fine; what links must build is every in-app url handed to a
  // Link, an <a> or navigate()
  it('never spells an in-app url: links build them', () => {
    const offenders = sources(SRC).filter((file) => /(?:\bto=|\bhref=|navigate\()\{?\s*[`'"]\/(?!\/)/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
})
```

Run: `yarn workspace @chawpi/automation test src/boundaries.test.ts`
Expected: PASS (2 tests). None of the ported screens links anywhere (sapgis `RunTable` shows `recordId` as text), so nothing needs `useChawpiLinks`.

- [ ] **Step 11: Write the README**

`frontend/packages/automation/README.md`:

````markdown
# @chawpi/automation

Automation rules for a chawpi app: "when something happens to a record, do something". A form-based rule
builder (trigger, conditions, actions) and the organisation-wide run log.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```bash
yarn add @chawpi/automation
```

Peers: `@chawpi/core`, `@chawpi/ui`, `react`, `react-dom`, `@tanstack/react-query`, `i18next`, `react-i18next`, `react-router`.

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { automationModule } from '@chawpi/automation'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[automationModule()]} />
}
```

## What it adds

| Slot | Value |
|---|---|
| routes | `automation:rules` → `/automation/rules` (rule builder), `automation:runs` → `/automation/runs` (run log); both lazy |
| nav | group `automation` (core's): "Reglas" (order 20), "Ejecuciones" (order 30) |
| i18n | namespace `automation` (es, en) |

It works with or without `@chawpi/workflow` and `@chawpi/documents`. It reads an object's workflow
(`GET /objects/{object}/workflow`) and its document types (`GET /objects/{object}/document-types`)
under the same query keys those modules use, so the cache is shared. When either module is absent,
its endpoint answers 404, and the state, transition and document-type pickers stay empty.

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'automation'` | url prefix of both routes |

## Backend

Needs the chawpi automation backend module: `/api/objects/{object}/automations` (GET/POST/PUT/DELETE),
`/api/objects/{object}/automations/{name}/runs` and `/api/automation-runs`. Endpoints of modules that
are not installed must answer 404 (not 403).
````

- [ ] **Step 12: Format and verify the package**

```bash
cd /Users/jorge/IdeaProjects/chawpi
yarn prettier --write frontend/packages/automation/src frontend/packages/automation/README.md
yarn workspace @chawpi/automation lint
yarn workspace @chawpi/automation test
yarn workspace @chawpi/automation build
ls frontend/packages/automation/dist
git status --short
```

Expected:
- `lint` passes (prettier check plus `tsc --noEmit`);
- `test` passes: 7 files and 22 tests (6 ported draft, 4 RunTable, 2 builder, 2 api, 4 module, 2 i18n, 2 boundary);
- `build` writes `dist/index.js` plus lazy chunks for both pages, and `dist/index.d.ts`;
- `git status --short` lists only `?? frontend/packages/automation/…` paths (or `M` for Task 1's placeholder `src/index.ts`), and nothing outside that directory. No commit is made.

### Task 5: `@chawpi/documents`

**Wave 1** (runs in parallel with Tasks 2–4 and 6–9). It writes only `frontend/packages/documents/src/**` and `frontend/packages/documents/README.md`.

**Files:**
- Port (script, then the manual edits in Step 3/4), from `sapgis/frontend/src/`:
  - `features/documents/DocumentTypesPage.tsx` → `frontend/packages/documents/src/DocumentTypesPage.tsx`
  - `features/documents/DocumentView.tsx` (+ `.test.tsx`) → `src/DocumentView.tsx` (+ `.test.tsx`)
  - `features/documents/PrintableDocumentPage.tsx` (+ `.test.tsx`) → `src/PrintableDocumentPage.tsx` (+ `.test.tsx`)
  - `features/documents/RecordDocuments.tsx` (+ `.test.tsx`) → `src/RecordDocuments.tsx` (+ `.test.tsx`)
  - `features/documents/TemplateEditor.tsx` (+ `.test.tsx`) → `src/TemplateEditor.tsx` (+ `.test.tsx`)
  - `features/documents/nodes.ts` → `src/nodes.ts` (no manual edit)
  - `features/history/IssuedDocumentLink.tsx` (+ `.test.tsx`) → `src/IssuedDocumentLink.tsx` (+ `.test.tsx`)
- Create:
  - `src/types.ts`: the document types from sapgis `types/metadata.ts:264-322`. `SapDocument` is renamed `IssuedDocument`.
  - `src/api.ts`: the hooks from sapgis `lib/queries.ts:416-469` and `features/history/api.ts:41-48`.
  - `src/i18n.ts`: the `documents` namespace.
  - `src/module.tsx`: the factory and the registry adapters.
  - `src/index.ts`: replaces Task 1's placeholder.
  - `src/print.css`: from sapgis `index.css:38-134`.
  - `README.md`
- Test (new):
  - `src/module.test.tsx`
  - `src/slots.test.tsx`
  - `src/DocumentTypesPage.test.tsx`
  - `src/i18n.test.ts`
  - `src/boundaries.test.ts`

**Interfaces:**
- Consumes (from `@chawpi/core`, all already exported by `frontend/packages/core/src/index.ts`):
  - functions: `api`, `ApiError`, `absoluteTime`, `useChawpiLinks`, `useObjects`, `useObjectDefinition`, `useObjectRelationships`, `RecordHistory`, `createRegistry`, `createLinks`, `coreModule`;
  - types: `ChawpiModule`, `HistoryEntryProps`, `RecordPanelProps`, `ObjectDefinition`, `RelatedSide`, `FieldMeta`, `AuditEntry`, `RecordItem`. `AuditEntry.documentId?: string | null` is a typed core field, so no accessor is needed.
- Consumes from `@chawpi/ui`: `Button`, `Card*`, `Dialog*`, `Input`, `Label`, `Select*`, `Table`/`Td`/`Th`/`Badge`, `cn`.
- Consumes from `@chawpi/testing` (tests only): `renderWithProviders`, `mockFetch`, `FetchMock`.
- Consumes from Task 1:
  - the package scaffold, whose `build` script is `vite build && tsc -p tsconfig.build.json && cp src/print.css dist/print.css`;
  - the `exports["./print.css"]` entry;
  - `@tiptap/core|react|starter-kit` `3` and `lucide-react` installed;
  - the port-script mapping of `@/features/documents/*` and `@/features/history/IssuedDocumentLink` to paths relative to `frontend/packages/documents/src`.
- Produces (public API of `@chawpi/documents`, for Task 10's smoke test and for apps):
  - `documentsModule(options?: DocumentsModuleOptions): ChawpiModule`
  - `DOCUMENTS_MODULE_ID = 'documents'`
  - `interface DocumentsModuleOptions { basePath?: string }` (default `''`)
  - route keys `documents:types` (`/builder/documents`) and `documents:print` (`/documents/:id/print`, chrome `bare`)
  - nav item `documents:nav.documents`, in core group `builder`, order 30
  - `historyRenderers.ISSUE = { body, labelKey: 'documents:operations.ISSUE', tone: 'info' }`
  - `recordPanels: [RecordDocumentsPanel]`
  - components `DocumentView`, `IssuedDocumentLink`, `RecordDocuments`
  - hooks `useDocumentTypes`, `useSaveDocumentType`, `useDeleteDocumentType`, `useRecordDocuments`, `useIssueDocument`, `useIssuedDocument`
  - types `TemplateNode`, `DocumentType`, `DocumentTypePayload`, `RelatedTableSnapshot`, `DocumentSnapshot`, `IssuedDocument`
  - the stylesheet `@chawpi/documents/print.css`

  `TemplateEditor` and `DocumentTypesPage` are deliberately **not** exported from `index.ts`. They are the only importers of tiptap, and exporting them would pull tiptap into every app's first bundle. They load only through the lazy `documents:types` route.

- [ ] **Step 1: Port the sources and tests with the port script**

Run from the repo root:

```bash
for f in DocumentTypesPage.tsx DocumentView.tsx DocumentView.test.tsx PrintableDocumentPage.tsx PrintableDocumentPage.test.tsx RecordDocuments.tsx RecordDocuments.test.tsx TemplateEditor.tsx TemplateEditor.test.tsx nodes.ts; do
  node frontend/tooling/port-from-sapgis.mjs "features/documents/$f" "frontend/packages/documents/src/$f"
done
node frontend/tooling/port-from-sapgis.mjs features/history/IssuedDocumentLink.tsx frontend/packages/documents/src/IssuedDocumentLink.tsx
node frontend/tooling/port-from-sapgis.mjs features/history/IssuedDocumentLink.test.tsx frontend/packages/documents/src/IssuedDocumentLink.test.tsx
sed -i '' 's/SapDocument/IssuedDocument/g; s/sapDocument/issuedDocument/g' frontend/packages/documents/src/*.ts frontend/packages/documents/src/*.tsx
```

Expected: 12 `ported …` lines, plus `MANUAL: … still imports @/lib/i18n` for `DocumentView.tsx` (Step 3 fixes it). On Linux use `sed -i` without `''`.

The script already made these rewrites:
- `@/components/ui/*` and `@/lib/utils` → `@chawpi/ui`;
- `@/lib/api`, `@/lib/queries`, `@/types/metadata`, `@/features/history/api` and `@/features/history/changes` → `@chawpi/core`;
- `@/features/documents/DocumentView` → `./DocumentView`;
- `@/test/render` → `@chawpi/testing`;
- it dropped `import '@/features/history/i18n'` from `IssuedDocumentLink.tsx` and `import '@/lib/i18n'` from `IssuedDocumentLink.test.tsx`.

Some of those `@chawpi/core` targets do not export what the code asks for. Steps 3 and 4 point them at `./api` and `./types`.

- [ ] **Step 2: Write `src/types.ts`, `src/api.ts` and `src/i18n.ts`**

`frontend/packages/documents/src/types.ts` (sapgis `types/metadata.ts:264-322`, `SapDocument` renamed):

```ts
// the editor's own document model, stored as it comes. a tree of typed nodes with text at the
// leaves -- prosemirror's shape, because that is what the editor writes. deliberately not html:
// nothing in this app renders html it was handed, and a template is not the place to start.
export interface TemplateNode {
  type: string
  attrs?: Record<string, unknown> | null
  content?: TemplateNode[] | null
  marks?: { type: string; attrs?: Record<string, unknown> | null }[] | null
  text?: string | null
}

export interface DocumentType {
  id: string
  name: string
  label: string
  // the sigla: SGTM in SGTM-2026-001
  prefix: string
  objectName: string
  template: TemplateNode
}

export interface DocumentTypePayload {
  name: string
  label: string
  prefix: string
  template: TemplateNode
}

export interface RelatedTableSnapshot {
  label: string
  columns: { name: string; label: string }[]
  rows: Record<string, unknown>[]
}

// what a record freezes at the moment it issues a document. the template is a copy, not a
// reference: editing the type afterwards never touches a document already handed out.
export interface DocumentSnapshot {
  template: TemplateNode
  values: Record<string, unknown>
  platform: Record<string, string>
  related: Record<string, RelatedTableSnapshot>
  objectName: string
  objectLabel: string
  number: string
  issuedAt: string
}

// not Document -- that name is the DOM's global and shadowing it bites.
export interface IssuedDocument {
  id: string
  number: string
  year: number
  sequence: number
  status: 'VALID' | 'ARCHIVED'
  recordId: string
  objectName: string
  issuedAt: string
  snapshot: DocumentSnapshot
}
```

`frontend/packages/documents/src/api.ts` (same query keys, paths, verbs and bodies as sapgis):

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@chawpi/core'
import type { DocumentType, DocumentTypePayload, IssuedDocument } from './types'

export function useDocumentTypes(objectName: string | undefined) {
  return useQuery({
    queryKey: ['document-types', objectName],
    queryFn: () => api<DocumentType[]>(`/objects/${objectName}/document-types`),
    enabled: Boolean(objectName)
  })
}

export function useSaveDocumentType(objectName: string) {
  const queryClient = useQueryClient()
  return useMutation({
    // a type is created once and edited after: the name is the key, so only a new one POSTs
    mutationFn: ({ existing, type }: { existing: boolean; type: DocumentTypePayload }) =>
      existing
        ? api<DocumentType>(`/objects/${objectName}/document-types/${type.name}`, {
            method: 'PUT',
            body: JSON.stringify({ label: type.label, prefix: type.prefix, template: type.template })
          })
        : api<DocumentType>(`/objects/${objectName}/document-types`, { method: 'POST', body: JSON.stringify(type) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['document-types', objectName] })
    }
  })
}

export function useDeleteDocumentType(objectName: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => api<void>(`/objects/${objectName}/document-types/${name}`, { method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['document-types', objectName] })
    }
  })
}

// newest first, valid and archived both: history, not just the current one
export function useRecordDocuments(objectName: string | undefined, recordId: string | undefined) {
  return useQuery({
    queryKey: ['documents', objectName, recordId],
    queryFn: () => api<IssuedDocument[]>(`/objects/${objectName}/records/${recordId}/documents`),
    enabled: Boolean(objectName && recordId)
  })
}

// issuing again archives the previous one: the list this invalidates is the only place that shows
export function useIssueDocument(objectName: string, recordId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (typeName: string) => api<IssuedDocument>(`/objects/${objectName}/records/${recordId}/documents/${typeName}`, { method: 'POST' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['documents', objectName, recordId] })
    }
  })
}

// one issued document by id, for the history link and the print page. a deleted one answers 404:
// an answer, not a failure, so never retried.
export function useIssuedDocument(documentId: string | null) {
  return useQuery({
    queryKey: ['history', 'document', documentId],
    queryFn: () => api<IssuedDocument>(`/documents/${documentId}`),
    enabled: Boolean(documentId),
    retry: false
  })
}
```

`frontend/packages/documents/src/i18n.ts`:
- `nav.documents` and the whole `documents` group are copied verbatim from `frontend/packages/core/src/i18n/locales/{es,en}/common.json`.
- `history.viewDocument` and `history.documentUnavailable` keep their sapgis paths (sapgis `features/history/i18n.ts`).
- `operations.ISSUE` is the badge label (M7).
- sapgis's `history.documentIssued` is not copied, because no code reads it.

```ts
// the documents namespace. core keeps no document string of its own.
export const documentsMessages = {
  es: {
    nav: { documents: 'Documentos' },
    operations: { ISSUE: 'Emisión' },
    history: {
      viewDocument: 'Ver documento',
      documentUnavailable: 'Documento no disponible'
    },
    documents: {
      title: 'Documentos',
      subtitle: 'Redacta las plantillas de documento que un registro puede emitir',
      object: 'Objeto',
      pickObject: 'Selecciona un objeto',
      types: 'Tipos',
      newType: 'Nuevo tipo',
      noTypes: 'Este objeto no tiene tipos de documento todavía.',
      pickType: 'Elige un tipo de la izquierda, o crea uno.',
      name: 'Nombre',
      label: 'Etiqueta',
      prefix: 'Sigla',
      prefixHint: 'Mayúsculas y dígitos. Es la SGTM de SGTM-2026-001, y no la puede repetir otro tipo.',
      confirmDelete: '¿Eliminar este tipo de documento?',
      bold: 'Negrita',
      italic: 'Cursiva',
      heading: 'Título',
      list: 'Lista',
      insertField: 'Campo',
      insertValue: 'Valor',
      insertTable: 'Tabla',
      values: {
        today: 'Fecha',
        now: 'Fecha y hora',
        user: 'Usuario',
        id: 'Id del registro',
        documentName: 'Nombre del documento',
        documentPrefix: 'Sigla',
        documentSerial: 'Serie',
        documentNumber: 'Número completo'
      },
      record: {
        title: 'Documentos emitidos',
        chooseType: 'Elige un tipo',
        issue: 'Emitir',
        none: 'Este registro no tiene documentos emitidos todavía.',
        number: 'Número',
        issuedAt: 'Emitido',
        status: 'Estado',
        statuses: { VALID: 'Vigente', ARCHIVED: 'Archivado' }
      },
      print: {
        link: 'Imprimir',
        action: 'Imprimir / Guardar PDF',
        archived: 'ARCHIVADO',
        unavailable: 'Documento no disponible'
      }
    }
  },
  en: {
    nav: { documents: 'Documents' },
    operations: { ISSUE: 'Issue' },
    history: {
      viewDocument: 'View document',
      documentUnavailable: 'Document unavailable'
    },
    documents: {
      title: 'Documents',
      subtitle: 'Write the document templates a record can issue',
      object: 'Object',
      pickObject: 'Pick an object',
      types: 'Types',
      newType: 'New type',
      noTypes: 'This object has no document types yet.',
      pickType: 'Pick a type on the left, or create one.',
      name: 'Name',
      label: 'Label',
      prefix: 'Sigla',
      prefixHint: 'Upper case and digits. It is the SGTM in SGTM-2026-001, and no other type may repeat it.',
      confirmDelete: 'Delete this document type?',
      bold: 'Bold',
      italic: 'Italic',
      heading: 'Heading',
      list: 'List',
      insertField: 'Field',
      insertValue: 'Value',
      insertTable: 'Table',
      values: {
        today: 'Date',
        now: 'Date and time',
        user: 'User',
        id: 'Record id',
        documentName: 'Document name',
        documentPrefix: 'Sigla',
        documentSerial: 'Serial',
        documentNumber: 'Full number'
      },
      record: {
        title: 'Issued documents',
        chooseType: 'Pick a type',
        issue: 'Issue',
        none: 'This record has no issued documents yet.',
        number: 'Number',
        issuedAt: 'Issued',
        status: 'Status',
        statuses: { VALID: 'Valid', ARCHIVED: 'Archived' }
      },
      print: {
        link: 'Print',
        action: 'Print / Save PDF',
        archived: 'ARCHIVED',
        unavailable: 'Document unavailable'
      }
    }
  }
}
```

- [ ] **Step 3: Rewrite the ported sources' seams**

All paths below are under `frontend/packages/documents/src/`.

(a) Namespaces (M3), in every component:

```bash
cd frontend/packages/documents/src && sed -i '' "s/useTranslation()/useTranslation(['documents', 'common'])/" DocumentTypesPage.tsx PrintableDocumentPage.tsx RecordDocuments.tsx TemplateEditor.tsx IssuedDocumentLink.tsx && cd -
```

(b) `DocumentView.tsx`. Pure render helpers run outside a hook, so they read the app's i18n instance (M3). `common.yes` and `common.no` live in core's default namespace. Replace the first four import lines:

```ts
import type { ReactNode } from 'react'
import i18n from '@/lib/i18n'
import { Table, Td, Th } from '@chawpi/ui'
import type { DocumentSnapshot, TemplateNode } from '@chawpi/core'
```

with:

```ts
import type { ReactNode } from 'react'
import { getI18n } from 'react-i18next'
import { Table, Td, Th } from '@chawpi/ui'
import type { DocumentSnapshot, TemplateNode } from './types'
```

and in `printable()` replace `i18n.t('common.yes') : i18n.t('common.no')` with `getI18n().t('common.yes') : getI18n().t('common.no')`.

(c) `DocumentTypesPage.tsx`. Replace:

```ts
import { useDeleteDocumentType, useDocumentTypes, useObjectDefinition, useObjectRelationships, useObjects, useSaveDocumentType } from '@chawpi/core'
```
```ts
import type { DocumentType, TemplateNode } from '@chawpi/core'
```

with:

```ts
import { useObjectDefinition, useObjectRelationships, useObjects } from '@chawpi/core'
import { useDeleteDocumentType, useDocumentTypes, useSaveDocumentType } from './api'
```
```ts
import type { DocumentType, TemplateNode } from './types'
```

(d) `TemplateEditor.tsx`. Replace `import type { ObjectDefinition, RelatedSide, TemplateNode } from '@chawpi/core'` with:

```ts
import type { ObjectDefinition, RelatedSide } from '@chawpi/core'
import type { TemplateNode } from './types'
```

(e) `PrintableDocumentPage.tsx`. Replace `import { useIssuedDocument } from '@chawpi/core'` with `import { useIssuedDocument } from './api'`.

(f) `RecordDocuments.tsx`:
- Replace `import { useDocumentTypes, useIssueDocument, useRecordDocuments } from '@chawpi/core'` with `import { useDocumentTypes, useIssueDocument, useRecordDocuments } from './api'`.
- Replace `import type { IssuedDocument } from '@chawpi/core'` with `import type { IssuedDocument } from './types'`.
- Replace `import { absoluteTime } from '@chawpi/core'` with `import { absoluteTime, useChawpiLinks } from '@chawpi/core'`.
- Add `const links = useChawpiLinks()` as the first line of the component body, right after `const { t } = useTranslation(['documents', 'common'])`.
- The print route may sit under any basePath, so links build the href. Replace `` href={`/documents/${viewing.id}/print`} `` with `href={links.to('documents:print', { id: viewing.id })}`.

(g) `IssuedDocumentLink.tsx`:
- Replace `import { useIssuedDocument } from '@chawpi/core'` with `import { useIssuedDocument } from './api'`.
- Add `import { useChawpiLinks } from '@chawpi/core'`.
- Add `const links = useChawpiLinks()` after the `useTranslation` line.
- Replace `` href={`/documents/${document.data.id}/print`} `` with `href={links.to('documents:print', { id: document.data.id })}`.
- Update the header comment to: `// the body of an ISSUE history entry (core draws it through historyRenderers.ISSUE): a link to the document it named. opens the same dialog`.

(h) Check that nothing unmapped is left:

```bash
grep -rnE "@/|SapDocument|from '@chawpi/core'.*(useDocumentTypes|useIssue|useRecordDocuments|TemplateNode|IssuedDocument)|[\`'\"]/documents/" frontend/packages/documents/src --include='*.ts' --include='*.tsx' | grep -v "api<\|\.test\."
```

Expected: no output. The REST path `/documents/${documentId}` in `api.ts` is filtered out by `api<`.

- [ ] **Step 4: Rewrite the ported tests' seams**

(a) `IssuedDocumentLink.test.tsx`:
- Replace `import type { IssuedDocument } from '@chawpi/core'` with `import type { IssuedDocument } from './types'`.
- Replace `vi.mock('@chawpi/core', () => ({` with `vi.mock('./api', () => ({`.
- Add `import { documentsModule } from './module'`.
- Replace `renderWithProviders(<IssuedDocumentLink documentId="document-1" />)` with `renderWithProviders(<IssuedDocumentLink documentId="document-1" />, { modules: [documentsModule()] })`.
- Append this case inside the `describe` (Review Focus 5):

```tsx
  it('builds the print link under the base path the app gave the module', async () => {
    renderWithProviders(<IssuedDocumentLink documentId="document-1" />, { modules: [documentsModule({ basePath: 'docs' })] })

    await userEvent.click(screen.getByRole('button', { name: /Ver documento/ }))

    expect(screen.getByRole('link', { name: /Imprimir/ })).toHaveAttribute('href', '/docs/documents/document-1/print')
  })
```

(b) `RecordDocuments.test.tsx`:
- Replace `import type { DocumentType, IssuedDocument } from '@chawpi/core'` with `import type { DocumentType, IssuedDocument } from './types'`.
- Replace `vi.mock('@chawpi/core', () => ({` with `vi.mock('./api', () => ({`.
- Add `import { documentsModule } from './module'`.
- Replace `renderWithProviders(<RecordDocuments objectName="predio" recordId="record-1" />)` with `renderWithProviders(<RecordDocuments objectName="predio" recordId="record-1" />, { modules: [documentsModule()] })`.

The expected href stays `/documents/document-1/print`, because the default basePath is `''`.

(c) `PrintableDocumentPage.test.tsx`:
- Replace the import block (through `import type { IssuedDocument } …`) with:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { documentsModule } from './module'
import { PrintableDocumentPage } from './PrintableDocumentPage'
import type { IssuedDocument } from './types'
```

- Replace `vi.mock('@chawpi/core', () => ({` with `vi.mock('./api', () => ({`.
- Replace the whole `function renderPage() { … }` with:

```tsx
// the route as documentsModule mounts it by default, so useParams hands the page its id
function renderPage() {
  return renderWithProviders(<PrintableDocumentPage />, {
    modules: [documentsModule()],
    route: '/documents/document-1/print',
    path: 'documents/:id/print'
  })
}
```

(d) `DocumentView.test.tsx`. The boolean case needs an initialised i18n instance, because `getI18n()` is undefined under a bare `render`. Keep every call site and shadow `render`:
- Replace `import { render, screen, within } from '@testing-library/react'` with:

```tsx
import { screen, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { renderWithProviders } from '@chawpi/testing'
import { documentsModule } from './module'
```

- Replace `import type { DocumentSnapshot } from '@chawpi/core'` with `import type { DocumentSnapshot } from './types'`.
- Add below the imports:

```tsx
// DocumentView prints booleans through the app's i18n instance, so it needs the providers
const render = (ui: ReactElement) => renderWithProviders(ui, { modules: [documentsModule()] })
```

(e) `TemplateEditor.test.tsx`. The port turned `vi.mock('@/components/ui/select', …)` into a mock of the whole `@chawpi/ui`, which would erase `Button`. Keep the rest of the real module:
- Replace `vi.mock('@chawpi/ui', () => {` with `vi.mock('@chawpi/ui', async (importOriginal) => {`.
- Replace the factory's `return {` (the line right before `SelectTrigger:`) with `return {\n    ...(await importOriginal<typeof import('@chawpi/ui')>()),`.
- Replace `import type { FieldMeta, ObjectDefinition, RelatedSide, TemplateNode } from '@chawpi/core'` with:

```tsx
import type { FieldMeta, ObjectDefinition, RelatedSide } from '@chawpi/core'
import type { TemplateNode } from './types'
import { documentsModule } from './module'
```

- Replace the single render call `renderWithProviders(<TemplateEditor value={value} onChange={onChange} definition={definition} sides={sides} />)` with `renderWithProviders(<TemplateEditor value={value} onChange={onChange} definition={definition} sides={sides} />, { modules: [documentsModule()] })`.

The fixtures (`FieldMeta` etc.) may miss fields that core's types added (`visible`, `editable`, index signatures). If `tsc` complains in Step 9, add the missing properties with the sapgis defaults (`visible: true, editable: true`) to the fixture helper only.

- [ ] **Step 5: Write the new failing tests**

`frontend/packages/documents/src/module.test.tsx` (T2 plus the bare print route):

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { documentsModule } from './module'

describe('documentsModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, documentsModule()])).not.toThrow()
  })

  it('mounts its routes at the sapgis urls by default', () => {
    const links = createLinks(createRegistry([coreModule, documentsModule()]))
    expect(links.to('documents:types')).toBe('/builder/documents')
    expect(links.to('documents:print', { id: 'document-1' })).toBe('/documents/document-1/print')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, documentsModule({ basePath: 'x' })]))
    expect(links.to('documents:types')).toBe('/x/builder/documents')
    expect(links.to('documents:print', { id: 'document-1' })).toBe('/x/documents/document-1/print')
  })

  it('prints on a bare page: signed in, no app shell around the sheet', () => {
    const registry = createRegistry([coreModule, documentsModule()])
    expect(registry.routes.find((route) => route.key === 'documents:print')?.chrome).toBe('bare')
    expect(registry.routes.find((route) => route.key === 'documents:types')?.chrome).toBe('shell')
  })

  it('offers the types builder in the builder group, after pages and forms', () => {
    const builder = createRegistry([coreModule, documentsModule()]).navGroups.find((group) => group.id === 'builder')
    expect(builder?.items.map((item) => [item.labelKey, item.to])).toEqual([['documents:nav.documents', '/builder/documents']])
  })
})
```

`frontend/packages/documents/src/slots.test.tsx`. It covers the ISSUE entry drawn by core's own `RecordHistory` (sapgis `RecordHistory.test.tsx` "shows a link to the document for an issue") and the record panel that sapgis's `RecordDetailPage` drew inline:

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RecordHistory, createRegistry, type AuditEntry, type ObjectDefinition, type RecordItem } from '@chawpi/core'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { documentsModule } from './module'

const { recordDocuments } = vi.hoisted(() => ({ recordDocuments: vi.fn(() => ({ data: [], isLoading: false })) }))

// the panel's own data; history comes over (mocked) http through core's hook, untouched
vi.mock('./api', () => ({
  useDocumentTypes: () => ({ data: [] }),
  useRecordDocuments: recordDocuments,
  useIssueDocument: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useIssuedDocument: () => ({ data: undefined, isLoading: false })
}))

const predio: ObjectDefinition = { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] }

const issued: AuditEntry = {
  id: 'audit-3',
  userEmail: 'ana@chawpi.test',
  objectName: 'predio',
  recordId: 'record-1',
  operation: 'ISSUE',
  occurredAt: '2026-09-17T12:00:00Z',
  changes: [],
  documentId: 'document-1'
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

function renderHistory(entries: AuditEntry[]) {
  fetch = mockFetch([{ path: '/objects/predio/records/record-1/history', body: entries }])
  return renderWithProviders(<RecordHistory objectName="predio" recordId="record-1" definition={predio} />, { modules: [documentsModule()] })
}

describe('documents in the record history', () => {
  it('shows a link to the document for an issue, under an issue badge', async () => {
    renderHistory([issued])

    expect(await screen.findByRole('button', { name: /Ver documento/ })).toBeInTheDocument()
    // sapgis drew ISSUE as bg-brand/15; the 'info' tone is that same look
    expect(screen.getByText('Emisión')).toHaveClass('bg-brand/15')
  })

  it('draws no link for an old issue row that names no document', async () => {
    renderHistory([{ ...issued, documentId: null }])

    expect(await screen.findByText('Emisión')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Ver documento/ })).not.toBeInTheDocument()
  })
})

describe('documents on the record page', () => {
  it('lists the record’s issued documents under it', () => {
    const record: RecordItem = { id: 'record-1', createdAt: null, updatedAt: null, attributes: {} }
    const [Panel] = createRegistry([documentsModule()]).recordPanels

    renderWithProviders(<Panel objectName="predio" definition={predio} record={record} />, { modules: [documentsModule()] })

    expect(screen.getByText('Documentos emitidos')).toBeInTheDocument()
    expect(screen.getByText('Este registro no tiene documentos emitidos todavía.')).toBeInTheDocument()
    expect(recordDocuments).toHaveBeenCalledWith('predio', 'record-1')
  })
})
```

`frontend/packages/documents/src/DocumentTypesPage.test.tsx`. sapgis had no test for this page. This one pins that the page draws its own namespace's strings once core's copies are gone (Task 10):

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { DocumentTypesPage } from './DocumentTypesPage'
import { documentsModule } from './module'

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

describe('DocumentTypesPage', () => {
  it('asks for an object before showing any type', async () => {
    fetch = mockFetch([{ path: '/objects', body: [] }])
    renderWithProviders(<DocumentTypesPage />, { modules: [documentsModule()] })

    expect(screen.getByRole('heading', { name: 'Documentos' })).toBeInTheDocument()
    expect(screen.getByText('Redacta las plantillas de documento que un registro puede emitir')).toBeInTheDocument()
    expect(await screen.findByText('Selecciona un objeto')).toBeInTheDocument()
  })
})
```

`frontend/packages/documents/src/i18n.test.ts`: Shared template T4 with `<name>` = `documents` and the key list `['nav.documents', 'operations.ISSUE', 'history.viewDocument', 'history.documentUnavailable', 'documents.print.link']`.

`frontend/packages/documents/src/boundaries.test.ts`: Shared template T3 with `<name>` = `documents` and `const OWN = /^(@tiptap\/.+)$/`.

Run: `yarn workspace @chawpi/documents test`
Expected: FAIL. Every suite that imports `./module` fails with `Failed to resolve import "./module"`. `boundaries.test.ts` and `i18n.test.ts` pass.

- [ ] **Step 6: Write the factory, the entry and the print stylesheet**

`frontend/packages/documents/src/module.tsx`:

```tsx
import { FileSignature } from 'lucide-react'
import type { ChawpiModule, HistoryEntryProps, RecordPanelProps } from '@chawpi/core'
import { documentsMessages } from './i18n'
import { IssuedDocumentLink } from './IssuedDocumentLink'
import { RecordDocuments } from './RecordDocuments'

export const DOCUMENTS_MODULE_ID = 'documents'

export interface DocumentsModuleOptions {
  // url prefix of every route of the module. '' keeps sapgis's /builder/documents and /documents/:id/print
  basePath?: string
}

// core hands the whole audit entry; the link needs only the document it names. old rows name none.
function IssueEntryBody({ entry }: HistoryEntryProps) {
  return entry.documentId ? <IssuedDocumentLink documentId={entry.documentId} /> : null
}

// core hands the loaded record; the panel fetches its documents by id
function RecordDocumentsPanel({ objectName, record }: RecordPanelProps) {
  return <RecordDocuments objectName={objectName} recordId={record.id} />
}

export function documentsModule(options: DocumentsModuleOptions = {}): ChawpiModule {
  return {
    id: DOCUMENTS_MODULE_ID,
    basePath: options.basePath ?? '',
    routes: [
      // lazy: the template editor is the only thing that pulls tiptap in
      { id: 'types', path: 'builder/documents', lazy: () => import('./DocumentTypesPage').then((m) => ({ default: m.DocumentTypesPage })) },
      // bare: the sheet prints without the app around it, but still needs a signed-in caller
      {
        id: 'print',
        path: 'documents/:id/print',
        chrome: 'bare',
        lazy: () => import('./PrintableDocumentPage').then((m) => ({ default: m.PrintableDocumentPage }))
      }
    ],
    nav: [{ group: 'builder', labelKey: 'documents:nav.documents', order: 30, icon: FileSignature, route: 'types' }],
    historyRenderers: {
      ISSUE: { body: IssueEntryBody, labelKey: 'documents:operations.ISSUE', tone: 'info' }
    },
    recordPanels: [RecordDocumentsPanel],
    i18n: documentsMessages
  }
}
```

`frontend/packages/documents/src/index.ts` (replaces Task 1's placeholder):

```ts
export { DOCUMENTS_MODULE_ID, documentsModule, type DocumentsModuleOptions } from './module'
export { documentsMessages } from './i18n'
export { DocumentView } from './DocumentView'
export { IssuedDocumentLink } from './IssuedDocumentLink'
export { RecordDocuments } from './RecordDocuments'
export { useDeleteDocumentType, useDocumentTypes, useIssueDocument, useIssuedDocument, useRecordDocuments, useSaveDocumentType } from './api'
export type { DocumentSnapshot, DocumentType, DocumentTypePayload, IssuedDocument, RelatedTableSnapshot, TemplateNode } from './types'
// TemplateEditor and DocumentTypesPage stay out on purpose: they import tiptap, and only the lazy
// documents:types route may load it.
```

`frontend/packages/documents/src/print.css`: copy sapgis `index.css` lines 38–134 **verbatim** (from the comment `/* the printed page itself -- unlayered, …` through the closing `}` of `@media print`):

```bash
sed -n '38,134p' /Users/jorge/IdeaProjects/sapgis/frontend/src/index.css > frontend/packages/documents/src/print.css
```

Then check that it is plain CSS. The rules use only `@page`, `.document-sheet …` selectors and `@media print`; there is no `@apply`, `@theme`, `@layer` or `var(--…)`.

```bash
head -1 frontend/packages/documents/src/print.css; tail -1 frontend/packages/documents/src/print.css; grep -cE "@apply|@theme|@layer|var\(--" frontend/packages/documents/src/print.css
```

Expected:
- first line `/* the printed page itself -- unlayered, so it beats tailwind's preflight reset on h1-h6 without`;
- last line `}`;
- count `0`.

The file must stay unlayered. The consumer imports it next to Tailwind, and unlayered rules beat the preflight layer without `!important`. The component classes `document-sheet` and `document-sheet-archived` are already in the ported `PrintableDocumentPage.tsx`.

- [ ] **Step 7: Run the tests and make sure they pass**

Run: `yarn workspace @chawpi/documents test`
Expected: PASS for every suite:
- `DocumentView` 7;
- `PrintableDocumentPage` 7;
- `RecordDocuments` 1;
- `TemplateEditor` 6;
- `IssuedDocumentLink` 2;
- `module` 5;
- `slots` 3;
- `DocumentTypesPage` 1;
- `i18n` 2;
- `boundaries` 2.

If a ported sapgis case fails only on a fixture's type (a new core field), fix the fixture, never the assertion.

- [ ] **Step 8: Write the README**

`frontend/packages/documents/README.md` (T5):

````markdown
# @chawpi/documents

Document templates for chawpi apps: an admin writes a template per object (rich text with field,
platform-value and related-table placeholders), a record issues numbered documents from it, and each
issued document has a printable page. Issuing shows up in the record's history.

## Install

```ini
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```bash
yarn add @chawpi/documents
```

`@tiptap/core`, `@tiptap/react` and `@tiptap/starter-kit` come with it. The template editor is
loaded lazily, so tiptap stays out of your first bundle.

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { documentsModule } from '@chawpi/documents'
import '@chawpi/documents/print.css'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'Catastro' }} modules={[documentsModule()]} />
}
```

Import `@chawpi/documents/print.css` once, anywhere in the app. It gives the printed sheet its
A4 page, heading sizes and table reflow, and it keeps the ARCHIVED mark visible on paper. It is
plain, unlayered CSS on purpose: it must win over Tailwind's preflight without `!important`.

## What it adds

| Slot | Contribution |
|---|---|
| route `documents:types` | `/builder/documents`: the document types builder |
| route `documents:print` | `/documents/:id/print`: the printable page (chrome `bare`, signed in, no shell) |
| nav | "Documentos" in the Builder group |
| `recordPanels` | "Documentos emitidos" under every record: issue a document, list and open issued ones |
| `historyRenderers.ISSUE` | an "Emisión" badge and a "Ver documento" link on the record's history |
| i18n | namespace `documents` (es, en) |

Links to the print page are built with `useChawpiLinks().to('documents:print', { id })`, so they
follow whatever base path you pick.

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `''` | prefix of both routes: `documentsModule({ basePath: 'docs' })` serves `/docs/builder/documents` and `/docs/documents/:id/print` |

## Backend

Needs the chawpi documents backend module:
- `/objects/{object}/document-types`
- `/objects/{object}/records/{id}/documents`
- `/documents/{id}`

When that module is not installed, those endpoints must answer 404 (not 403). The record panel
then shows no documents, and the history shows no link.
````

- [ ] **Step 9: Format, lint, test, build, and confirm nothing was committed**

```bash
yarn prettier --write frontend/packages/documents/src frontend/packages/documents/README.md
yarn workspace @chawpi/documents lint
yarn workspace @chawpi/documents test
yarn workspace @chawpi/documents build
ls frontend/packages/documents/dist/print.css frontend/packages/documents/dist/index.js frontend/packages/documents/dist/index.d.ts
grep -l "@tiptap" frontend/packages/documents/dist/index.js || echo "tiptap not in the entry chunk"
git status --short
```

Expected:
- lint, test and build all pass;
- the three `dist` files exist;
- the grep prints `tiptap not in the entry chunk`, because tiptap is imported only from the lazy `DocumentTypesPage` chunk;
- `git status --short` lists only untracked or modified paths under `frontend/packages/documents/`, with no commit made.

### Task 6: `@chawpi/pages` — page builder over registry slots

**Wave 1** (runs in parallel with Tasks 2–5 and 7–9). Writes only `frontend/packages/pages/src/**` and `frontend/packages/pages/README.md`. The package wiring (`package.json` with `@dnd-kit/core` 6.3.1 and `lucide-react`, tsconfigs, `vite.config.ts`, `src/test/setup.ts`, a placeholder `src/index.ts`) was written by Task 1. Do not edit it.

**Files:**
- Port (port script, then the edits below):
  - `src/PageBuilderPage.tsx` + `src/PageBuilderPage.test.tsx`;
  - `src/builder/{Canvas,CanvasNode,CanvasRegion,CanvasTabs,DndProvider,Inspector,Palette,Slots,TemplateDialog}.tsx`;
  - `src/builder/{openTabs,pageTree,retemplate}.ts` + `src/builder/{Canvas.test.tsx,openTabs.test.ts,pageTree.test.ts,retemplate.test.ts}`;
  - `src/builder/preview/{ComponentMock,TemplatePreview}.tsx` + `src/builder/preview/{ComponentMock,TemplatePreview}.test.tsx`.
- Not ported:
  - `features/pages/builder/templates.ts` is already core's `components/page-renderer/layout.ts`. `ROW_CLASS`, `regionKeys` and `regionStyle` come from `@chawpi/core`.
  - `templates.test.ts` duplicates core's `layout.test.ts` line for line.
- Create:
  - `src/builder/registrySlots.ts` + `src/builder/registrySlots.test.tsx` (seeds, kind order, labels from the registry);
  - `src/builder/Inspector.test.tsx`;
  - `src/test/fakeModules.tsx` (a `pin` module with a PIN page component, a `stamp` module with a STAMP action);
  - `src/test/uiDoubles.tsx` (native doubles for radix Select/Dialog);
  - `src/module.tsx`, `src/module.test.tsx`;
  - `src/i18n.ts` (generated by a script), `src/i18n.test.ts`;
  - `src/boundaries.test.ts`;
  - `README.md`.
- Replace: `src/index.ts` (Task 1 placeholder).
- Full rewrites (logic changes, full text given below): `src/builder/Palette.tsx`, `src/builder/Inspector.tsx`, `src/builder/preview/ComponentMock.tsx`, `src/builder/preview/ComponentMock.test.tsx`.
- Hunks (exact before/after below): `src/builder/pageTree.ts` (Node type), `src/builder/Canvas.tsx` (seed + ACTION default), `src/builder/DndProvider.tsx`, `src/PageBuilderPage.tsx` (workflow removal), `src/PageBuilderPage.test.tsx` (header + new cases), `src/builder/preview/TemplatePreview.test.tsx`, `src/builder/Canvas.test.tsx` (new cases).

**Interfaces:**
- Consumes (from `@chawpi/core`, all already exported by `frontend/packages/core/src/index.ts`, no Task 1 addition needed):
  - hooks: `useRegistry(): ChawpiRegistry`, whose `registry.pageComponents: Readonly<Record<string, PageComponentDefinition>>` and `registry.pageActions: Readonly<Record<string, PageActionDefinition>>` are both in registration order;
  - `PageComponentDefinition { render; labelKey: string; icon?: ComponentType<{ className?: string }>; settings?: ComponentType<PageComponentSettingsProps>; preview?: ComponentType<{ component: PageComponent; definition: ObjectDefinition }>; defaults?: Partial<PageComponent> }`;
  - `PageActionDefinition { render; labelKey: string; settings?: ComponentType<PageComponentSettingsProps>; defaults?: Partial<PageComponent> }`;
  - `PageComponentSettingsProps { component: PageComponent; definition: ObjectDefinition; objectName: string; onChange: (patch: Partial<PageComponent>) => void }`;
  - `CORE_PAGE_COMPONENT_TYPES`, `ROW_CLASS`, `regionKeys`, `regionStyle`, `ApiError`, `PageHeader`;
  - queries: `useObjects`, `useObjectDefinition`, `useObjectRelationships`, `useForms`, `useTemplates`, `useResolvedPage`, `useSavePage` (`mutateAsync({ generated, page: PagePayload })`: POST `/pages` when generated, else PUT `/pages/{name}` with `{ label, template, definition }`), `useDeletePage`;
  - types `ActionKind`, `ActionStyle`, `ChawpiModule`, `FieldMeta`, `Form`, `ObjectDefinition`, `Page`, `PageComponent`, `PageComponentType`, `PageLayout`, `PageTemplate`, `RelatedSide`;
  - link and registry helpers for tests: `coreModule`, `createRegistry`, `createLinks`.
  - From `@chawpi/ui`: `Button`, `Card*`, `Dialog*`, `Input`, `Textarea`, `Label`, `Select*`, `Badge`, `Tabs`, `cn`. From `@chawpi/testing` (tests): `renderWithProviders`.
- Produces (public, `src/index.ts`):
  - `pagesModule(options?: PagesModuleOptions): ChawpiModule`, where `PagesModuleOptions { basePath?: string }` defaults to `'builder'`. It has route `pages:builder` → `/builder/pages` and nav `builder` 10 `pages:nav.pages`.
  - `PAGES_MODULE_ID = 'pages'`;
  - `PageBuilderPage`.
  - Nothing else. In particular it produces nothing gis or workflow import: they talk to the builder only through their own `pageComponents` and `pageActions` slots.
- Contract with gis (Task 2) and workflow (Task 3), through the registry only. The builder:
  - lists `pageComponents` keys after core's content types;
  - drops them as `{ ...blank, ...defaults }`;
  - draws `settings` in the inspector with `{ component, definition, objectName, onChange }`;
  - draws `preview` on the canvas with `{ component, definition }`;
  - lists `pageActions` keys first in the ACTION kind picker, with `NAVIGATE` last;
  - gives a fresh ACTION the first kind plus that kind's `defaults`.

- [ ] **Step 1: Port every file with the port script**

Task 1's `MODULE_MAP` maps `@/features/pages/*` to a path relative to the target inside `frontend/packages/pages/src`, except `@/features/pages/builder/templates`, which goes to `@chawpi/core`. Core owners (`@/lib/queries`, `@/lib/api`, `@/types/metadata`, `@/components/layout/AppShell`) map to `@chawpi/core`, `@/components/ui/*` and `@/lib/utils` to `@chawpi/ui`, and `@/test/render` to `@chawpi/testing`.

```bash
P=frontend/packages/pages/src
for f in PageBuilderPage.tsx PageBuilderPage.test.tsx \
  builder/Canvas.tsx builder/Canvas.test.tsx builder/CanvasNode.tsx builder/CanvasRegion.tsx builder/CanvasTabs.tsx \
  builder/DndProvider.tsx builder/Inspector.tsx builder/Palette.tsx builder/Slots.tsx builder/TemplateDialog.tsx \
  builder/openTabs.ts builder/openTabs.test.ts builder/pageTree.ts builder/pageTree.test.ts \
  builder/retemplate.ts builder/retemplate.test.ts \
  builder/preview/ComponentMock.tsx builder/preview/ComponentMock.test.tsx \
  builder/preview/TemplatePreview.tsx builder/preview/TemplatePreview.test.tsx; do
  node frontend/tooling/port-from-sapgis.mjs "features/pages/$f" "$P/$f"
done
```

Expected: 22 `ported …` lines plus 4 `MANUAL:` lines (workflows/api in `PageBuilderPage.tsx` and its test; lib/geo in `Inspector.tsx` and `ComponentMock.tsx`). Those imports stay `@/…` and are removed in Steps 5, 11, 12 and 13.

The script cannot map two specifiers, because they cross into another module package (a module never imports another module), so it leaves them as `@/…` for a human to remove by hand in the steps below:
- `@/features/workflows/api`, from `useWorkflow` in `PageBuilderPage.tsx` (Step 13) and the `vi.mock('@/features/workflows/api')` in `PageBuilderPage.test.tsx` (Step 5);
- `@/lib/geo`, from `geometryFields` in `Inspector.tsx` (Step 11) and `ComponentMock.tsx` (Step 12).

- [ ] **Step 2: Mechanical rewrites (relative `templates` imports, the pages namespace)**

The port script only sees `@/` specifiers, so the sibling `./templates` and `../templates` imports stay. Point them at core. Every `useTranslation()` becomes `useTranslation(['pages', 'common'])` (M3): pages keys resolve in the module namespace first, and `common.save`, `map.selectObject` and `relationships.pick` still resolve in core's.

```bash
P=frontend/packages/pages/src
perl -pi -e "s#from '\.\.?/templates'#from '\@chawpi/core'#" $P/builder/*.ts $P/builder/*.tsx $P/builder/preview/*.tsx
perl -pi -e "s#useTranslation\(\)#useTranslation(['pages', 'common'])#g" $(grep -rl "useTranslation()" $P)
grep -rn "templates'\|useTranslation()" $P || echo clean
```

Expected: `clean`.

- [ ] **Step 3: Write the test doubles and fake modules**

`frontend/packages/pages/src/test/uiDoubles.tsx`. This is the sapgis `PageBuilderPage.test.tsx:11-72` doubles, moved to one file so the page test and the inspector test share them.

```tsx
import { Children, cloneElement, createContext, isValidElement, useContext } from 'react'
import type { ReactElement, ReactNode } from 'react'

// native stand-ins for the two radix primitives jsdom cannot drive (portal, pointer capture). tests
// spread them over the real module: vi.mock('@chawpi/ui', async (orig) => ({ ...(await orig()), ...(await import('…/uiDoubles')) }))

export function SelectTrigger({ children }: { children?: ReactNode; 'aria-label'?: string }) {
  return <>{children}</>
}

export const SelectValue = () => null

export function SelectContent({ children }: { children?: ReactNode }) {
  return <>{children}</>
}

export function SelectItem({ value, children }: { value: string; children?: ReactNode }) {
  return <option value={value}>{children}</option>
}

export function Select({
  value,
  disabled,
  onValueChange,
  children
}: {
  value: string
  disabled?: boolean
  onValueChange: (value: string) => void
  children?: ReactNode
}) {
  return (
    <select aria-label={triggerLabel(children)} value={value} disabled={disabled} onChange={(event) => onValueChange(event.target.value)}>
      <option value="" />
      {children}
    </select>
  )
}

// the accessible name lives on the trigger, which this double renders away
function triggerLabel(children: ReactNode): string | undefined {
  const trigger = Children.toArray(children).find((child) => isValidElement(child) && child.type === SelectTrigger)
  return isValidElement(trigger) ? (trigger.props as { 'aria-label'?: string })['aria-label'] : undefined
}

const DialogContext = createContext<{ open: boolean; onOpenChange: (open: boolean) => void }>({ open: false, onOpenChange: () => {} })

export function Dialog({ open, onOpenChange, children }: { open: boolean; onOpenChange: (open: boolean) => void; children?: ReactNode }) {
  return <DialogContext.Provider value={{ open, onOpenChange }}>{children}</DialogContext.Provider>
}

export function DialogTrigger({ children }: { children: ReactElement }) {
  const { onOpenChange } = useContext(DialogContext)
  return cloneElement(children, { onClick: () => onOpenChange(true) } as Record<string, unknown>)
}

// role="dialog" like the real primitive: the canvas stays mounted behind it, tests scope into it
export function DialogContent({ children }: { children?: ReactNode }) {
  const { open } = useContext(DialogContext)
  return open ? <div role="dialog">{children}</div> : null
}

export function DialogTitle({ children }: { children?: ReactNode }) {
  return <h2>{children}</h2>
}

export function DialogDescription({ children }: { children?: ReactNode }) {
  return <p>{children}</p>
}
```

`frontend/packages/pages/src/test/fakeModules.tsx`. These stand in for gis (MAP) and workflow (TRANSITION), so the builder is tested without either.

```tsx
import { MapPin } from 'lucide-react'
import type { ChawpiModule } from '@chawpi/core'

// a page component with every builder slot filled, the way gis offers MAP
export const pinModule: ChawpiModule = {
  id: 'pin',
  pageComponents: {
    PIN: {
      render: () => <p>chincheta real</p>,
      labelKey: 'pin:label',
      icon: MapPin,
      defaults: { title: 'Chincheta nueva', color: 'rojo' },
      preview: ({ component }) => <div data-testid="pin-preview">{`chincheta ${String(component.color ?? '')}`}</div>,
      settings: ({ component, definition, objectName, onChange }) => (
        <label>
          {`color de ${objectName} (${definition.fields.length} campos)`}
          <input aria-label="color" value={String(component.color ?? '')} onChange={(event) => onChange({ color: event.target.value })} />
        </label>
      )
    }
  },
  i18n: { es: { label: 'Chincheta' }, en: { label: 'Pin' } }
}

// an ACTION kind with its own settings and defaults, the way workflow offers TRANSITION
export const stampModule: ChawpiModule = {
  id: 'stamp',
  pageActions: {
    STAMP: {
      render: () => <button type="button">sellar</button>,
      labelKey: 'stamp:label',
      defaults: { style: 'PRIMARY', seal: 'oficial' },
      settings: ({ component, onChange }) => (
        <input aria-label="sello" value={String(component.seal ?? '')} onChange={(event) => onChange({ seal: event.target.value })} />
      )
    }
  },
  i18n: { es: { label: 'Sellar' }, en: { label: 'Stamp' } }
}
```

- [ ] **Step 4: Write the failing tests for the registry seams**

`frontend/packages/pages/src/builder/registrySlots.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import type { ChawpiModule } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { pagesModule } from '../module'
import { pinModule, stampModule } from '../test/fakeModules'
import { actionKinds, useSeed, useTypeLabel } from './registrySlots'

function SeedProbe({ type }: { type: string }) {
  const seed = useSeed()
  return <output>{JSON.stringify(seed(type))}</output>
}

function LabelProbe({ type }: { type: string }) {
  const label = useTypeLabel()
  return <output>{label(type)}</output>
}

const withModules = (ui: ReactElement, modules: ChawpiModule[] = []) => renderWithProviders(ui, { modules: [pagesModule(), ...modules] })

describe('actionKinds', () => {
  it('lists module kinds in registration order and NAVIGATE last', () => {
    expect(actionKinds({ STAMP: stampModule.pageActions!.STAMP, SIGN: stampModule.pageActions!.STAMP })).toEqual(['STAMP', 'SIGN', 'NAVIGATE'])
    expect(actionKinds({})).toEqual(['NAVIGATE'])
  })
})

describe('useSeed', () => {
  it('makes a fresh ACTION the first module kind, with that kind defaults', () => {
    withModules(<SeedProbe type="ACTION" />, [stampModule])
    expect(JSON.parse(screen.getByRole('status').textContent ?? '')).toEqual({ style: 'PRIMARY', seal: 'oficial', action: 'STAMP' })
  })

  it('makes a fresh ACTION a NAVIGATE when no module adds a kind', () => {
    withModules(<SeedProbe type="ACTION" />)
    expect(JSON.parse(screen.getByRole('status').textContent ?? '')).toEqual({ action: 'NAVIGATE' })
  })

  it('hands a module component its own defaults and a core one nothing', () => {
    withModules(
      <>
        <SeedProbe type="PIN" />
        <SeedProbe type="FORM" />
      </>,
      [pinModule]
    )
    const [pin, form] = screen.getAllByRole('status')
    expect(JSON.parse(pin.textContent ?? '')).toEqual({ title: 'Chincheta nueva', color: 'rojo' })
    expect(JSON.parse(form.textContent ?? '')).toEqual({})
  })
})

describe('useTypeLabel', () => {
  it('names core types from the pages bundle, module types from their labelKey, and anything else by its raw type', () => {
    withModules(
      <>
        <LabelProbe type="FORM" />
        <LabelProbe type="PIN" />
        <LabelProbe type="MAP" />
      </>,
      [pinModule]
    )
    expect(screen.getAllByRole('status').map((node) => node.textContent)).toEqual(['Formulario', 'Chincheta', 'MAP'])
  })
})
```

(`<output>` has the implicit ARIA role `status`.)

Append to `frontend/packages/pages/src/builder/Canvas.test.tsx`, after the last `it`:

```tsx
describe('applyDrop and module seeds', () => {
  it('drops an ACTION as NAVIGATE when nothing seeds it', () => {
    const next = applyDrop(scaffold(), { active: 'palette:ACTION', over: 'slot:0.0.0:1' })
    expect(nodeAt(next, [0, 0, 0])).toMatchObject({ type: 'ACTION', action: 'NAVIGATE', style: 'SECONDARY' })
  })

  it('merges the seed of a module type over the blank node', () => {
    const seed = (type: string) => (type === 'PIN' ? { title: 'Chincheta nueva', color: 'rojo' } : {})
    const next = applyDrop(scaffold(), { active: 'palette:PIN', over: 'slot:0.0.0:1' }, seed)
    expect(nodeAt(next, [0, 0, 0])).toMatchObject({ type: 'PIN', title: 'Chincheta nueva', color: 'rojo', column: 1 })
  })

  it('never lets a seed replace the type, the column or the children', () => {
    const seed = () => ({ type: 'OTHER', column: 2, children: [{ type: 'TEXT' } as never] })
    const next = applyDrop(scaffold(), { active: 'palette:SECTION', over: 'slot:0.0.0:1' }, seed)
    expect(nodeAt(next, [0, 0, 0])).toMatchObject({ type: 'SECTION', column: 1, children: [] })
  })
})
```

`frontend/packages/pages/src/builder/Inspector.test.tsx`. Its module cases pin Review Focus 1 and 2 at the unit level.

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ChawpiModule, ObjectDefinition } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { pagesModule } from '../module'
import { pinModule, stampModule } from '../test/fakeModules'
import { Inspector } from './Inspector'
import type { Node } from './pageTree'

vi.mock('@chawpi/ui', async (importOriginal) => ({ ...(await importOriginal<typeof import('@chawpi/ui')>()), ...(await import('../test/uiDoubles')) }))

const definition: ObjectDefinition = { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] }

function node(type: Node['type'], extra: Partial<Node> = {}): Node {
  return { uid: 'n1', type, column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, ...extra }
}

function inspect(target: Node, modules: ChawpiModule[] = []) {
  const onPatch = vi.fn()
  renderWithProviders(
    <Inspector
      node={target}
      parentLayout="single-column"
      definition={definition}
      objectName="predio"
      sides={[]}
      forms={[]}
      objects={['predio']}
      onPatch={onPatch}
      onRemove={() => {}}
    />,
    { modules: [pagesModule(), ...modules] }
  )
  return onPatch
}

// the kind picker is the select that offers NAVIGATE
function kindPicker(): HTMLSelectElement {
  return screen.getByRole('option', { name: 'Lleva a otro sitio' }).closest('select') as HTMLSelectElement
}

const optionTexts = (select: HTMLSelectElement) => [...select.options].map((option) => option.textContent)

describe('Inspector ACTION', () => {
  it('lists a module kind first and NAVIGATE last', () => {
    inspect(node('ACTION', { action: 'NAVIGATE', style: 'SECONDARY' }), [stampModule])
    // the leading '' is the double's own blank option
    expect(optionTexts(kindPicker())).toEqual(['', 'Sellar', 'Lleva a otro sitio'])
  })

  it('offers no transition when no module adds an action kind', () => {
    inspect(node('ACTION', { action: 'NAVIGATE', style: 'SECONDARY' }))
    expect(optionTexts(kindPicker())).toEqual(['', 'Lleva a otro sitio'])
    expect(screen.queryByText('Dispara una transición')).not.toBeInTheDocument()
  })

  it('picking a module kind merges that kind defaults into the node', async () => {
    const onPatch = inspect(node('ACTION', { action: 'NAVIGATE', style: 'SECONDARY' }), [stampModule])
    await userEvent.selectOptions(kindPicker(), 'STAMP')
    expect(onPatch).toHaveBeenCalledWith('n1', { style: 'PRIMARY', seal: 'oficial', action: 'STAMP' })
  })

  it('draws the settings the kind brings, and hands its changes back as a patch', async () => {
    const onPatch = inspect(node('ACTION', { action: 'STAMP', style: 'PRIMARY', seal: 'x' }), [stampModule])
    await userEvent.type(screen.getByLabelText('sello'), 'y')
    expect(onPatch).toHaveBeenCalledWith('n1', { seal: 'xy' })
  })

  it('keeps a stored kind whose module is missing on screen, under its raw name', () => {
    inspect(node('ACTION', { action: 'TRANSITION', transition: 'aprobar', style: 'SECONDARY' }))
    expect(kindPicker().value).toBe('TRANSITION')
    expect(optionTexts(kindPicker())).toEqual(['', 'Lleva a otro sitio', 'TRANSITION'])
  })
})

describe('Inspector module components', () => {
  it('draws a module component settings with the object in hand', async () => {
    const onPatch = inspect(node('PIN', { color: 'azul' }), [pinModule])
    expect(screen.getByRole('heading', { name: 'Chincheta' })).toBeInTheDocument()
    expect(screen.getByText('color de predio (0 campos)')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('color'), 'o')
    expect(onPatch).toHaveBeenCalledWith('n1', { color: 'azulo' })
  })

  it('names a component whose module is missing and offers only the built-in settings', () => {
    inspect(node('MAP', { geometry: 'geom' }))
    expect(screen.getByRole('heading', { name: 'MAP' })).toBeInTheDocument()
    expect(screen.getByLabelText('Título')).toBeInTheDocument()
    expect(screen.queryByLabelText('color')).not.toBeInTheDocument()
  })
})
```

Replace `frontend/packages/pages/src/builder/preview/ComponentMock.test.tsx` whole. The two MAP cases move to gis (Task 2 ports them against its own `preview`). What stays checks core types, module previews and the missing-module placeholder.

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { FieldMeta, ObjectDefinition, PageComponent, RelatedSide } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { pagesModule } from '../../module'
import { pinModule } from '../../test/fakeModules'
import { ComponentMock } from './ComponentMock'

function leaf(type: PageComponent['type'], extra: Partial<PageComponent> = {}): PageComponent {
  return { type, column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, ...extra }
}

function field(name: string, label: string): FieldMeta {
  return {
    id: name,
    name,
    label,
    type: 'TEXT',
    required: false,
    unique: false,
    defaultValue: null,
    description: null,
    position: 0,
    enumOptions: null,
    relationTarget: null,
    visible: true,
    editable: true
  }
}

const definition: ObjectDefinition = {
  id: 'o1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  fields: [field('codigo', 'Código'), field('area', 'Área')]
}

const sides: RelatedSide[] = [
  { relationship: 'predio_titular', label: 'Titular', type: 'MANY_TO_ONE', objectName: 'titular', objectLabel: 'Titular', many: false }
]

function mock(component: PageComponent, withSides: RelatedSide[] = []) {
  return renderWithProviders(<ComponentMock component={component} definition={definition} sides={withSides} />, { modules: [pagesModule(), pinModule] })
}

describe('ComponentMock', () => {
  it('draws a form with the field labels the object really has', () => {
    mock(leaf('FORM'))
    expect(screen.getByText('Código')).toBeInTheDocument()
    expect(screen.getByText('Área')).toBeInTheDocument()
  })

  it('draws only the named fields, in the order they were picked', () => {
    mock(leaf('FORM', { fields: ['area'] }))
    expect(screen.getByText('Área')).toBeInTheDocument()
    expect(screen.queryByText('Código')).not.toBeInTheDocument()
  })

  it('shows the label of the relationship a related list points at', () => {
    mock(leaf('RELATED_LIST', { relationship: 'predio_titular' }), sides)
    expect(screen.getByText('Titular')).toBeInTheDocument()
  })

  it('says when a related list points at a relationship that is gone', () => {
    mock(leaf('RELATED_LIST', { relationship: 'fantasma' }), sides)
    expect(screen.getByText(/fantasma/)).toBeInTheDocument()
  })

  it('draws the action title, not a real button', () => {
    mock(leaf('ACTION', { title: 'Aprobar', style: 'PRIMARY' }))
    expect(screen.getByText('Aprobar')).toBeInTheDocument()
  })

  it('draws the given text, or a placeholder when it is empty', () => {
    mock(leaf('TEXT', { content: 'Hola mundo' }))
    expect(screen.getByText('Hola mundo')).toBeInTheDocument()
  })

  it('draws a module component through its own preview', () => {
    mock(leaf('PIN', { color: 'verde' }))
    expect(screen.getByTestId('pin-preview')).toHaveTextContent('chincheta verde')
  })

  it('labels a component whose module is not installed instead of drawing nothing', () => {
    mock(leaf('MAP', { geometry: 'geom' }))
    expect(screen.getByText('MAP')).toBeInTheDocument()
  })

  // nothing inside a mock may fetch, mount a map, or take a click
  it('renders no interactive control at all', () => {
    const types: PageComponent['type'][] = ['FORM', 'RELATED_LIST', 'HISTORY', 'TEXT', 'ACTION', 'PIN', 'MAP']
    types.forEach((type) => {
      const { container, unmount } = mock(leaf(type, { relationship: 'predio_titular' }), sides)
      expect(container.querySelectorAll('input, button, select, textarea, a')).toHaveLength(0)
      unmount()
    })
  })
})
```

Edit `frontend/packages/pages/src/builder/preview/TemplatePreview.test.tsx`. The port already dropped `import '@/lib/i18n'`. The strings now come from the module namespace, so render through the providers.
- Replace `import { render, screen } from '@testing-library/react'` with:
  ```tsx
  import { screen } from '@testing-library/react'
  import { renderWithProviders } from '@chawpi/testing'
  import { pagesModule } from '../../module'
  ```
- Replace every `render(` call in the file with `renderWithProviders(` and add a second argument `{ modules: [pagesModule()] }`. For example, `render(<TemplatePreview template={sidebar} labels />)` becomes `renderWithProviders(<TemplatePreview template={sidebar} labels />, { modules: [pagesModule()] })`.

- [ ] **Step 5: Rewrite the page test's header and add the module cases (Review Focus 1 and 2)**

In `frontend/packages/pages/src/PageBuilderPage.test.tsx`, replace everything from line 1 down to and including the line `const { PageBuilderPage, removeNode } = await import('./PageBuilderPage')`. That span covers the imports, both radix `vi.mock` doubles, `vi.hoisted`, `objectDefinition`, the queries mock and the workflows mock. The replacement is below.

The port turned `vi.mock('@/lib/queries')` into a full `vi.mock('@chawpi/core')`, which would wipe out `ChawpiProviders` and break `renderWithProviders`. It also turned both ui doubles into full `vi.mock('@chawpi/ui')` factories that drop `Button`/`Card`. Both become partial mocks. The workflow mock goes: pages no longer reads workflows.

```tsx
import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChawpiModule, FieldMeta, ObjectDefinition, Page, PageComponent, PageTemplate } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { pagesModule } from './module'
import { pinModule, stampModule } from './test/fakeModules'

// radix select and dialog need a layout and pointer capture jsdom lacks: native doubles, every other ui export real
vi.mock('@chawpi/ui', async (importOriginal) => ({ ...(await importOriginal<typeof import('@chawpi/ui')>()), ...(await import('./test/uiDoubles')) }))

const { state } = vi.hoisted(() => ({
  state: {
    page: null as Page | null,
    save: vi.fn(),
    remove: vi.fn(),
    templates: [] as PageTemplate[],
    fields: [] as FieldMeta[]
  }
}))

// a bare object: no fields, enough for FormMock and the inspector to render
const objectDefinition: ObjectDefinition = {
  id: 'o-1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  fields: []
}

// only the queries the builder reads: the providers renderWithProviders mounts stay real
vi.mock('@chawpi/core', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@chawpi/core')>()),
  useObjects: () => ({ data: [{ id: 'o-1', name: 'predio', label: 'Predio' }] }),
  useObjectDefinition: () => ({ data: { ...objectDefinition, fields: state.fields }, isLoading: false }),
  useObjectRelationships: () => ({ data: [] }),
  useForms: () => ({ data: [] }),
  useTemplates: () => ({ data: state.templates }),
  useResolvedPage: () => ({ data: state.page, isLoading: false }),
  useSavePage: () => ({ mutateAsync: state.save, isPending: false }),
  useDeletePage: () => ({ mutateAsync: state.remove, isPending: false })
}))

const { PageBuilderPage, removeNode } = await import('./PageBuilderPage')
```

In the same file, replace the `open()` helper (sapgis lines 204–211, starting with the comment `// the object picker carries no aria-label`) with:

```tsx
// the object picker carries no aria-label anywhere in this codebase's builder pages, so
// grab it by structure: before an object is chosen, it is the only select on the page.
async function open(modules: ChawpiModule[] = []) {
  const rendered = renderWithProviders(<PageBuilderPage />, { modules: [pagesModule(), ...modules] })
  const objectSelect = rendered.container.querySelector('select') as HTMLSelectElement
  await userEvent.selectOptions(objectSelect, 'predio')
  return rendered
}

// the one region's children as the save call received them
function savedRegion(): PageComponent {
  const [[{ page }]] = state.save.mock.calls as [[{ page: { definition: { page: PageComponent } } }]]
  return page.definition.page.children[0]
}

// dnd-kit leaves a capture-phase click blocker on the document for 50ms after a drag ends. real time:
// it is a real timeout, and 120ms is margin over it, not against it
const afterDrag = () => new Promise((resolve) => setTimeout(resolve, 120))
```

Then add this `describe` block inside `describe('PageBuilderPage', …)`, right after the test `'saving sends the tree, with no client-only ids leaking onto the wire'`:

```tsx
  describe('components and actions modules add', () => {
    // stored while gis and workflow were installed, opened in an app that has neither
    const orphanPage: Page = {
      ...flatPage,
      definition: {
        page: pageNode([
          regionNode('MAIN', [
            leaf('MAP', { title: 'Ubicación', geometry: 'geom' }),
            leaf('ACTION', { action: 'TRANSITION', transition: 'aprobar', style: 'PRIMARY' })
          ])
        ])
      }
    }

    it('keeps a component whose module is missing', async () => {
      state.page = orphanPage
      await open()
      // a labelled placeholder, not a blank hole: the admin can still see, move or delete it
      expect(await screen.findByText('MAP')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(state.save).toHaveBeenCalledWith(expect.objectContaining({ page: expect.objectContaining({ definition: orphanPage.definition }) }))
    })

    it('drops an ACTION as NAVIGATE when no module adds a kind', async () => {
      state.page = emptyPage
      await open()
      dragOnto(screen.getByRole('button', { name: 'Acción' }), await screen.findByText('Arrastra un componente aquí'))
      await afterDrag()
      await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(savedRegion().children).toEqual([expect.objectContaining({ type: 'ACTION', action: 'NAVIGATE', style: 'SECONDARY' })])
    })

    it('drops an ACTION as the first kind a module adds, with that kind defaults', async () => {
      state.page = emptyPage
      await open([stampModule])
      dragOnto(screen.getByRole('button', { name: 'Acción' }), await screen.findByText('Arrastra un componente aquí'))
      await afterDrag()
      await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(savedRegion().children).toEqual([expect.objectContaining({ type: 'ACTION', action: 'STAMP', style: 'PRIMARY', seal: 'oficial' })])
    })

    it('offers a module component in the palette and drops it with its defaults and preview', async () => {
      state.page = emptyPage
      await open([pinModule])
      dragOnto(screen.getByRole('button', { name: 'Chincheta' }), await screen.findByText('Arrastra un componente aquí'))
      expect(await screen.findByTestId('pin-preview')).toHaveTextContent('chincheta rojo')
      await afterDrag()
      await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))
      expect(savedRegion().children).toEqual([expect.objectContaining({ type: 'PIN', title: 'Chincheta nueva', color: 'rojo' })])
    })

    it('offers neither MAP nor WORKFLOW in the palette when no module adds them', async () => {
      await open()
      await screen.findByText('hola')
      expect(screen.queryByRole('button', { name: 'Mapa' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Workflow' })).not.toBeInTheDocument()
    })
  })
```

- [ ] **Step 6: Write the module, i18n and boundary tests**

`frontend/packages/pages/src/module.test.tsx` (Shared template T2, filled in):

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { pagesModule } from './module'

describe('pagesModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, pagesModule()])).not.toThrow()
  })

  it('mounts its routes under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, pagesModule()]))
    expect(links.to('pages:builder')).toBe('/builder/pages')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, pagesModule({ basePath: 'x' })]))
    expect(links.to('pages:builder')).toBe('/x/pages')
  })

  it('puts its entry first in the builder group', () => {
    const builder = createRegistry([coreModule, pagesModule()]).navGroups.find((group) => group.id === 'builder')
    expect(builder?.items[0]).toMatchObject({ labelKey: 'pages:nav.pages', to: '/builder/pages' })
  })
})
```

`frontend/packages/pages/src/i18n.test.ts` (Shared template T4, filled in, plus one line that keeps gis and workflow strings out):

```ts
import { describe, expect, it } from 'vitest'
import { pagesMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('pages messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(pagesMessages.en)).toEqual(leaves(pagesMessages.es))
  })

  it('names its nav entries', () => {
    expect(leaves(pagesMessages.es)).toEqual(expect.arrayContaining(['nav.pages', 'pages.title', 'pages.types.ACTION', 'pages.actionKinds.NAVIGATE']))
  })

  // MAP, WORKFLOW and TRANSITION strings belong to gis and workflow, which ship their own
  it('carries no string of a module component or action', () => {
    expect(leaves(pagesMessages.es).filter((key) => /MAP|WORKFLOW|TRANSITION|eometr|mockMap|mockWorkflow|transition/.test(key))).toEqual([])
  })
})
```

`frontend/packages/pages/src/boundaries.test.ts`: Shared template T3 verbatim, with `<name>` = `pages` and `const OWN = /^@dnd-kit\/core$/`. Add this third `it` inside the `describe`:

```ts
  // the builder offers module components only through registry slots, never by name
  it('never names a module component, action or concept', () => {
    const offenders = sources(SRC).filter((file) => /'(MAP|WORKFLOW|TRANSITION)'|useWorkflow|geometr/i.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
```

- [ ] **Step 7: Run the tests to watch them fail**

Run: `yarn workspace @chawpi/pages test`
Expected: FAIL.
- `registrySlots.test.tsx`, `module.test.tsx` and `i18n.test.ts` fail to import (`./registrySlots`, `./module` and `./i18n` do not exist yet).
- `boundaries.test.ts` fails in the pages-specific "never names a module component…" test, listing `builder/Inspector.tsx`, `builder/preview/ComponentMock.tsx` and `PageBuilderPage.tsx` (their imports are still `@/…`, not yet mapped to `@chawpi/gis`/`@chawpi/workflow`); suites that import them fail to resolve `@/lib/geo` / `@/features/workflows/api`.

- [ ] **Step 8: Write `registrySlots.ts` (seeds, kind order, labels)**

`frontend/packages/pages/src/builder/registrySlots.ts`:

```ts
import { CORE_PAGE_COMPONENT_TYPES, useRegistry, type PageActionDefinition, type PageComponent, type PageComponentType } from '@chawpi/core'
import { useTranslation } from 'react-i18next'

// core's own kind. it goes last, so a module's kind is the default a fresh ACTION gets: with
// workflow installed that is TRANSITION, which is what sapgis always defaulted to
export const NAVIGATE = 'NAVIGATE'

const CORE_TYPES: readonly string[] = CORE_PAGE_COMPONENT_TYPES

export function isCoreType(type: string): boolean {
  return CORE_TYPES.includes(type)
}

export function actionKinds(pageActions: Readonly<Record<string, PageActionDefinition>>): string[] {
  return [...Object.keys(pageActions), NAVIGATE]
}

// starting values for a node dropped from the palette, merged over the blank one
export type Seed = (type: PageComponentType) => Partial<PageComponent>

export const NO_SEED: Seed = () => ({})

export function useSeed(): Seed {
  const { pageComponents, pageActions } = useRegistry()
  return (type) => {
    if (type !== 'ACTION') return pageComponents[type]?.defaults ?? {}
    const kind = actionKinds(pageActions)[0]
    // the kind last: a module's defaults must not pick a different kind than the one listed first
    return { ...(pageActions[kind]?.defaults ?? {}), action: kind }
  }
}

// core types by the pages bundle, module types by their labelKey. a type nobody registered (its
// module is not installed) keeps its raw name: a guess from a stale core string would lie
export function useTypeLabel(): (type: string) => string {
  const { t } = useTranslation(['pages', 'common'])
  const { pageComponents } = useRegistry()
  return (type) => {
    const definition = pageComponents[type]
    if (definition) return t(definition.labelKey)
    return isCoreType(type) ? t(`pages.types.${type}`) : type
  }
}

export function useActionKindLabel(): (kind: string) => string {
  const { t } = useTranslation(['pages', 'common'])
  const { pageActions } = useRegistry()
  return (kind) => {
    if (kind === NAVIGATE) return t('pages.actionKinds.NAVIGATE')
    const definition = pageActions[kind]
    return definition ? t(definition.labelKey) : kind
  }
}
```

- [ ] **Step 9: Fix `Node`, seed `applyDrop`, default ACTION to NAVIGATE, feed the seed from DndProvider**

`frontend/packages/pages/src/builder/pageTree.ts`. Core's `PageComponent` carries an index signature (`[extension: string]: unknown`), and `Omit<>` over an index-signature type drops every named key, which would leave `node.type` as `unknown`. Extend the interface instead, because `Node[]` is a valid `PageComponent[]`:

```diff
-export interface Node extends Omit<PageComponent, 'children'> {
+// extends, never Omit<>: core's PageComponent has an index signature, and Omit over one drops every named key
+export interface Node extends PageComponent {
   uid: string
   children: Node[]
 }
```

`frontend/packages/pages/src/builder/Canvas.tsx`. The imports after Step 1 and 2 are `import { ROW_CLASS, regionStyle } from '@chawpi/core'` and `import type { PageComponentType, PageTemplate } from '@chawpi/core'`. Add one import below `import { parseTabHandle } from './openTabs'`:

```diff
 import { parseTabHandle } from './openTabs'
+import { NO_SEED, type Seed } from './registrySlots'
```

Change the signature and the palette branch of `applyDrop`:

```diff
-// one place where a drop becomes a tree. the canvas renders; DndProvider decides when to call this.
-export function applyDrop(tree: Node[], drop: Drop): Node[] {
+// one place where a drop becomes a tree. the canvas renders; DndProvider decides when to call this.
+// seed: a module's starting values for a fresh node (gis MAP, workflow's ACTION kind), from the registry
+export function applyDrop(tree: Node[], drop: Drop, seed: Seed = NO_SEED): Node[] {
```

```diff
-    const fresh: Node = { ...blank(type), column }
+    const fresh: Node = { ...blank(type), ...seeded(seed, type), column }
```

Replace the `blank()` comment and body's ACTION line. Without a module, a fresh ACTION is NAVIGATE, and the seed turns it into the first module kind:

```diff
-// a fresh node, every field defaulted. a tab strip with no tabs is nothing to look at, so it gets one.
-// an ACTION with no action/style is a node the inspector shows as "Transition"/"Secondary" but the
-// server refuses to save: radix does not fire onValueChange for a re-pick of the value already
-// shown, so the field the inspector displays must already be the field the node carries.
+// a fresh node, every field defaulted. a tab strip with no tabs is nothing to look at, so it gets one.
+// an ACTION with no action/style is a node the inspector shows with a kind and a style but the
+// server refuses to save: radix does not fire onValueChange for a re-pick of the value already
+// shown, so the field the inspector displays must already be the field the node carries. the seed
+// swaps NAVIGATE for a module's kind when one is installed.
 function blank(type: PageComponentType): Node {
```

```diff
-    ...(type === 'ACTION' ? { action: 'TRANSITION', style: 'SECONDARY' } : {})
+    ...(type === 'ACTION' ? { action: 'NAVIGATE', style: 'SECONDARY' } : {})
   }
 }
+
+// a seed fills settings only: the node's type, its column and its children stay the builder's
+function seeded(seed: Seed, type: PageComponentType): Partial<Node> {
+  const { type: _type, column: _column, children: _children, ...settings } = seed(type)
+  return settings as Partial<Node>
+}
```

`frontend/packages/pages/src/builder/DndProvider.tsx`. The text below is the file after Step 2:

```diff
-import { useTranslation } from 'react-i18next'
 import { applyDrop, pathOf } from './Canvas'
 import { nodeAt } from './pageTree'
 import type { Node } from './pageTree'
+import { useSeed, useTypeLabel } from './registrySlots'
```

```diff
-  const { t } = useTranslation(['pages', 'common'])
+  const seed = useSeed()
+  const typeLabel = useTypeLabel()
   const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }), useSensor(KeyboardSensor))
   const [activeId, setActiveId] = useState<string | null>(null)

-  const label = dragLabel(tree, activeId, (key) => t(key))
+  const label = dragLabel(tree, activeId, typeLabel)
```

```diff
-        onChange(applyDrop(tree, { active: String(event.active.id), over: event.over ? String(event.over.id) : null }))
+        onChange(applyDrop(tree, { active: String(event.active.id), over: event.over ? String(event.over.id) : null }, seed))
```

```diff
-function dragLabel(tree: Node[], activeId: string | null, t: (key: string) => string): string | null {
+function dragLabel(tree: Node[], activeId: string | null, typeLabel: (type: string) => string): string | null {
   if (!activeId) return null
-  if (activeId.startsWith('palette:')) return t(`pages.types.${activeId.slice('palette:'.length)}`)
+  if (activeId.startsWith('palette:')) return typeLabel(activeId.slice('palette:'.length))
```

```diff
-  return node ? (node.title ?? t(`pages.types.${node.type}`)) : null
+  return node ? (node.title ?? typeLabel(node.type)) : null
```

`CanvasNode.tsx` keeps `t(\`pages.types.${node.type}\`)`: that line only labels containers, and every container type is core's.

Run: `yarn workspace @chawpi/pages test src/builder/registrySlots.test.tsx src/builder/Canvas.test.tsx src/builder/pageTree.test.ts src/builder/retemplate.test.ts src/builder/openTabs.test.ts`
Expected: `registrySlots.test.tsx` still fails to import `../module` (Step 14 writes it), and the other four PASS. If you want the Canvas cases green now, that is fine: they do not need the module.

- [ ] **Step 10: Rewrite `Palette.tsx` (core types first, then registered module types)**

Replace `frontend/packages/pages/src/builder/Palette.tsx` whole. MAP and WORKFLOW leave the hardcoded list. Registered `pageComponents` follow core's content types in registration order, with their own `icon` (or `Puzzle` when a module gave none) and their `labelKey`.

```tsx
import { useDraggable } from '@dnd-kit/core'
import { useRegistry, type FieldMeta, type ObjectDefinition, type PageComponentType } from '@chawpi/core'
import { cn, Tabs } from '@chawpi/ui'
import { ClipboardList, FormInput, History as HistoryIcon, LayoutPanelTop, List, MousePointerClick, Puzzle, Rows3, SquareStack, Type as TypeIcon } from 'lucide-react'
import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { useTypeLabel } from './registrySlots'

type Icon = ComponentType<{ className?: string }>

// what an admin may drag from the components tab. PAGE and REGION come from the template, and a
// FIELD comes from the fields tab, named after a real field -- none of the three is a type you pick.
const CONTAINERS: PageComponentType[] = ['TABS', 'TAB', 'SECTION']
// core's own content. module types (gis: MAP, workflow: WORKFLOW) follow, in registration order
const CONTENT: PageComponentType[] = ['FORM', 'DYNAMIC_FORM', 'RELATED_LIST', 'HISTORY', 'TEXT']
const ACTIONS: PageComponentType[] = ['ACTION']

const ICONS: Record<string, Icon> = {
  TABS: LayoutPanelTop,
  TAB: SquareStack,
  SECTION: Rows3,
  FORM: ClipboardList,
  DYNAMIC_FORM: FormInput,
  RELATED_LIST: List,
  HISTORY: HistoryIcon,
  TEXT: TypeIcon,
  ACTION: MousePointerClick
}

export interface PaletteProps {
  definition: ObjectDefinition
}

// two tabs: the component types the canvas can draw, and the object's own fields. a field is not a
// type -- it is one named column of this object -- so it gets a tab rather than a group.
export function Palette({ definition }: PaletteProps) {
  const { t } = useTranslation(['pages', 'common'])
  return (
    <div className="p-4">
      <Tabs
        label={t('pages.palette')}
        tabs={[
          { id: 'components', label: t('pages.palette'), render: () => <Components /> },
          { id: 'fields', label: t('pages.paletteFields'), render: () => <Fields fields={definition.fields} /> }
        ]}
      />
    </div>
  )
}

function Components() {
  const { t } = useTranslation(['pages', 'common'])
  const { pageComponents } = useRegistry()
  return (
    <div className="space-y-5 pt-4">
      <PaletteGroup label={t('pages.paletteContainers')} types={CONTAINERS} />
      <PaletteGroup label={t('pages.paletteContent')} types={[...CONTENT, ...Object.keys(pageComponents)]} />
      <PaletteGroup label={t('pages.paletteActions')} types={ACTIONS} />
    </div>
  )
}

// every field the object has, whatever its type: a form may place one wherever the admin wants it
function Fields({ fields }: { fields: FieldMeta[] }) {
  const { t } = useTranslation(['pages', 'common'])
  if (fields.length === 0) {
    return <p className="pt-4 text-xs text-ink-muted">{t('pages.paletteNoFields')}</p>
  }
  return (
    <div className="flex flex-col gap-2 pt-4">
      {fields.map((field) => (
        <FieldItem key={field.name} field={field} />
      ))}
    </div>
  )
}

function PaletteGroup({ label, types }: { label: string; types: PageComponentType[] }) {
  return (
    <div className="space-y-2">
      <p className="text-xs font-medium uppercase text-ink-muted">{label}</p>
      <div className="flex flex-col gap-2">
        {types.map((type) => (
          <PaletteItem key={type} type={type} />
        ))}
      </div>
    </div>
  )
}

const ITEM_CLASS =
  'flex w-full cursor-grab items-center gap-1.5 rounded-md border border-border bg-surface px-2.5 py-1.5 text-left text-xs font-medium text-ink hover:bg-surface-muted'

function PaletteItem({ type }: { type: PageComponentType }) {
  const { pageComponents } = useRegistry()
  const typeLabel = useTypeLabel()
  const Icon = pageComponents[type]?.icon ?? ICONS[type] ?? Puzzle
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: 'palette:' + type })

  return (
    <button ref={setNodeRef} type="button" {...listeners} {...attributes} className={cn(ITEM_CLASS, isDragging && 'opacity-50')}>
      <Icon className="h-3.5 w-3.5 text-ink-muted" />
      {typeLabel(type)}
    </button>
  )
}

// a button like the others, not a div: a sweep in the builder tests counts div draggables, and the
// component items have always been buttons anyway.
function FieldItem({ field }: { field: FieldMeta }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: 'field:' + field.name })

  return (
    <button ref={setNodeRef} type="button" {...listeners} {...attributes} className={cn(ITEM_CLASS, isDragging && 'opacity-50')}>
      <FormInput className="h-3.5 w-3.5 text-ink-muted" />
      <span className="truncate">{field.label}</span>
    </button>
  )
}
```

Deviation (M6, accepted): with gis and workflow installed, the content group reads FORM, DYNAMIC_FORM, RELATED_LIST, HISTORY, TEXT, MAP, WORKFLOW. sapgis interleaved MAP after DYNAMIC_FORM and WORKFLOW after HISTORY.

- [ ] **Step 11: Rewrite `Inspector.tsx` (module settings, ACTION kinds from the registry)**

Replace `frontend/packages/pages/src/builder/Inspector.tsx` whole. Compared with sapgis:
- the `transitions` prop is gone and `objectName` is new;
- the MAP geometry picker is gone (it is gis's `pageComponents.MAP.settings`);
- the TRANSITION branch is gone (it is workflow's `pageActions.TRANSITION.settings`);
- the header label goes through `useTypeLabel`;
- a registered component's `settings` draws after the built-in controls.

Everything else is sapgis markup, unchanged.

```tsx
import { useRegistry, type ActionStyle, type Form, type ObjectDefinition, type PageLayout, type RelatedSide } from '@chawpi/core'
import { Button, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea } from '@chawpi/ui'
import { Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { Node } from './pageTree'
import { actionKinds, NAVIGATE, useActionKindLabel, useTypeLabel } from './registrySlots'

const LAYOUTS: PageLayout[] = ['single-column', 'two-column']
const ACTION_STYLES: ActionStyle[] = ['PRIMARY', 'SECONDARY']
// radix select has no empty value, so "none of these" needs a sentinel of its own
const NO_FORM = '__none__'

export interface InspectorProps {
  node: Node | null
  // the layout of whatever holds the selected node: its parent container, or the page itself
  // at the root. column 2 only means something when that container actually has one.
  parentLayout: PageLayout
  definition: ObjectDefinition
  // module settings editors need it (workflow: which transitions the object's workflow offers)
  objectName: string
  sides: RelatedSide[]
  forms: Form[]
  objects: string[]
  onPatch: (uid: string, patch: Partial<Node>) => void
  onRemove: (uid: string) => void
}

// the selected node's own fields, and only the ones its type actually uses
export function Inspector({ node, parentLayout, definition, objectName, sides, forms, objects, onPatch, onRemove }: InspectorProps) {
  const { t } = useTranslation(['pages', 'common'])
  const { pageComponents } = useRegistry()
  const typeLabel = useTypeLabel()

  if (!node) {
    return <p className="p-4 text-sm text-ink-muted">{t('pages.nothingSelected')}</p>
  }

  const patch = (fragment: Partial<Node>) => onPatch(node.uid, fragment)
  // the server refuses column 2 unless the parent is two-column: never offer what it would refuse
  const columns = parentLayout === 'two-column' ? [1, 2] : [1]
  // a region is the template's, not the admin's. it has no bin (deleting it invalidates the page),
  // no title (its name comes from the template vocabulary) and no column of its own (the server
  // normalises it to 1). all it owns is how its own content splits.
  const scaffold = node.type === 'REGION'
  // a field placement has no title of its own -- its label comes from the object -- and no layout,
  // because it has no children to lay out. offering either is offering a control that does nothing.
  const placement = node.type === 'FIELD'
  // a module's own component (gis: MAP) brings its own settings. one whose module is not installed
  // keeps the built-in ones only, so the admin can still retitle, move or delete it
  const ModuleSettings = pageComponents[node.type]?.settings

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink">{typeLabel(node.type)}</h3>
        {scaffold ? null : (
          <Button variant="ghost" size="icon" aria-label={t('common.delete')} onClick={() => onRemove(node.uid)}>
            <Trash2 className="h-4 w-4" />
          </Button>
        )}
      </div>

      {scaffold || placement ? null : (
        <div className="space-y-1.5">
          <Label htmlFor="inspector-title">{t('pages.componentTitle')}</Label>
          <Input id="inspector-title" value={node.title ?? ''} onChange={(event) => patch({ title: event.target.value || null })} />
        </div>
      )}

      <div className={scaffold || placement ? 'space-y-3' : 'grid grid-cols-2 gap-3'}>
        {placement ? null : (
          <div className="space-y-1.5">
            <Label>{t('pages.layout')}</Label>
            <Select value={node.layout} onValueChange={(value) => patch({ layout: value as PageLayout })}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {LAYOUTS.map((layout) => (
                  <SelectItem key={layout} value={layout}>
                    {t(`pages.layouts.${layout}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
        {scaffold ? null : (
          <div className="space-y-1.5">
            <Label>{t('pages.column')}</Label>
            <Select value={String(node.column)} onValueChange={(value) => patch({ column: Number(value) })}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {columns.map((column) => (
                  <SelectItem key={column} value={String(column)}>
                    {column}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
      </div>

      {node.type === 'FIELD' ? <FieldPlacement node={node} definition={definition} patch={patch} /> : null}

      {node.type === 'RELATED_LIST' ? (
        <div className="space-y-1.5">
          <Label>{t('pages.relationship')}</Label>
          <Select value={node.relationship ?? ''} onValueChange={(value) => patch({ relationship: value })}>
            <SelectTrigger>
              <SelectValue placeholder={t('relationships.pick')} />
            </SelectTrigger>
            <SelectContent>
              {sides.map((side) => (
                <SelectItem key={side.relationship} value={side.relationship}>
                  {side.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {/* the relationship the page names may no longer exist: say so instead of a blank select */}
          {node.relationship && !sides.some((side) => side.relationship === node.relationship) ? (
            <p className="text-xs text-danger">{t('pages.relationshipGone')}</p>
          ) : null}
        </div>
      ) : null}

      {node.type === 'FORM' ? (
        <>
          <div className="space-y-1.5">
            <Label>{t('pages.form')}</Label>
            <Select value={node.form ?? NO_FORM} onValueChange={(value) => patch(value === NO_FORM ? { form: null } : { form: value, fields: null })}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_FORM}>{t('pages.noForm')}</SelectItem>
                {forms.map((form) => (
                  <SelectItem key={form.id || form.name} value={form.name}>
                    {form.label || form.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-ink-muted">{t('pages.formHint')}</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="inspector-fields">{t('pages.fields')}</Label>
            <Input
              id="inspector-fields"
              value={(node.fields ?? []).join(', ')}
              disabled={Boolean(node.form)}
              onChange={(event) => patch({ fields: parseFields(event.target.value) })}
            />
            <p className="text-xs text-ink-muted">{t('pages.fieldsHint')}</p>
          </div>
        </>
      ) : null}

      {node.type === 'TEXT' ? (
        <div className="space-y-1.5">
          <Label htmlFor="inspector-content">{t('pages.content')}</Label>
          <Textarea id="inspector-content" value={node.content ?? ''} onChange={(event) => patch({ content: event.target.value || null })} />
        </div>
      ) : null}

      {node.type === 'ACTION' ? <ActionSettings node={node} definition={definition} objectName={objectName} objects={objects} patch={patch} /> : null}

      {ModuleSettings ? <ModuleSettings component={node} definition={definition} objectName={objectName} onChange={(fragment) => patch(fragment as Partial<Node>)} /> : null}
    </div>
  )
}

// what an ACTION does. NAVIGATE is core's and edited here; any other kind is a module's (workflow:
// TRANSITION) and brings its own settings editor.
function ActionSettings({
  node,
  definition,
  objectName,
  objects,
  patch
}: {
  node: Node
  definition: ObjectDefinition
  objectName: string
  objects: string[]
  patch: (fragment: Partial<Node>) => void
}) {
  const { t } = useTranslation(['pages', 'common'])
  const { pageActions } = useRegistry()
  const kindLabel = useActionKindLabel()
  const kinds = actionKinds(pageActions)
  const kind = node.action ?? kinds[0]
  // a stored kind whose module is gone stays on screen under its raw name instead of blanking the select
  const offered = kinds.includes(kind) ? kinds : [...kinds, kind]
  const KindSettings = pageActions[kind]?.settings

  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label>{t('pages.actionKind')}</Label>
        {/* the kind last: a module's defaults never pick a kind other than the one clicked */}
        <Select value={kind} onValueChange={(value) => patch({ ...(pageActions[value]?.defaults ?? {}), action: value } as Partial<Node>)}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {offered.map((option) => (
              <SelectItem key={option} value={option}>
                {kindLabel(option)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {kind === NAVIGATE ? (
        <>
          <div className="space-y-1.5">
            <Label>{t('pages.targetObject')}</Label>
            <Select value={node.target ?? ''} onValueChange={(value) => patch({ target: value })}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {objects.map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="inspector-url">{t('pages.url')}</Label>
            <Input id="inspector-url" value={node.url ?? ''} onChange={(event) => patch({ url: event.target.value || null })} />
          </div>
        </>
      ) : KindSettings ? (
        <KindSettings component={node} definition={definition} objectName={objectName} onChange={(fragment) => patch(fragment as Partial<Node>)} />
      ) : null}

      <div className="space-y-1.5">
        <Label>{t('pages.style')}</Label>
        <Select value={node.style ?? 'SECONDARY'} onValueChange={(value) => patch({ style: value as ActionStyle })}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ACTION_STYLES.map((style) => (
              <SelectItem key={style} value={style}>
                {t(`pages.styles.${style}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}

// empty means "every field", which is null on the wire, not an empty list
function parseFields(value: string): string[] | null {
  const names = value
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
  return names.length > 0 ? names : null
}
```

After `parseFields`, append the sapgis `FieldPlacement` function **unchanged**: the text from `// what a FIELD placement may say about its field.` to the end of the ported file. Copy it from the ported file before you overwrite it, or from `sapgis/frontend/src/features/pages/builder/Inspector.tsx:276-312`, with `useTranslation()` replaced by `useTranslation(['pages', 'common'])`.

Both settings editors receive `onChange={(fragment) => patch(fragment as Partial<Node>)}`. The cast is safe because an editor speaks `Partial<PageComponent>` and never sets `children`.

- [ ] **Step 12: Rewrite `ComponentMock.tsx` (module previews, a labelled placeholder)**

Replace `frontend/packages/pages/src/builder/preview/ComponentMock.tsx` whole. `MapMock` and `WorkflowMock` leave the package. gis's `pageComponents.MAP.preview` and workflow's `pageComponents.WORKFLOW.preview` carry that markup now (Tasks 2 and 3). Every non-core type goes through `ModuleMock`.

```tsx
import { useRegistry, type FieldMeta, type ObjectDefinition, type PageComponent, type RelatedSide } from '@chawpi/core'
import { cn } from '@chawpi/ui'
import { useTranslation } from 'react-i18next'
import { isCoreType, useTypeLabel } from '../registrySlots'

export interface ComponentMockProps {
  component: PageComponent
  definition: ObjectDefinition
  sides: RelatedSide[]
}

// draws what a leaf WOULD show, from metadata the builder already has in hand. it sits inside a
// draggable canvas node, so nothing here fetches, mounts a map, or takes a click — a real control
// would eat the drag. containers (TABS/TAB/SECTION) are drawn by the canvas itself, not here.
export function ComponentMock({ component, definition, sides }: ComponentMockProps) {
  switch (component.type) {
    case 'FIELD':
      return <FieldMock component={component} definition={definition} />
    case 'FORM':
      return <FormMock component={component} definition={definition} />
    case 'RELATED_LIST':
      return <RelatedListMock component={component} sides={sides} />
    case 'HISTORY':
      return <HistoryMock />
    case 'TEXT':
      return <TextMock component={component} />
    case 'ACTION':
      return <ActionMock component={component} />
    default:
      return <ModuleMock component={component} definition={definition} />
  }
}

// a module's component draws its own mock. one with none, or whose module is not installed, gets a
// labelled box: the admin still sees it sits there, and can move or delete it
function ModuleMock({ component, definition }: { component: PageComponent; definition: ObjectDefinition }) {
  const { pageComponents } = useRegistry()
  const typeLabel = useTypeLabel()
  if (isCoreType(component.type)) return null
  const Preview = pageComponents[component.type]?.preview
  if (Preview) return <Preview component={component} definition={definition} />
  return (
    <div className="flex h-16 items-center justify-center rounded border border-dashed border-border bg-surface-muted text-center text-xs text-ink-muted">
      {typeLabel(component.type)}
    </div>
  )
}
```

Below `ModuleMock`, append these functions from the ported file **unchanged** (sapgis `ComponentMock.tsx`): `FormMock`, `RelatedListMock`, `HistoryMock`, `TextMock`, `ActionMock` and `FieldMock`. Leave out `MapMock` and `WorkflowMock`. `ActionMock` keeps `component.title ?? component.transition ?? component.target ?? t('pages.action')`: `transition` is a typed core field of `PageComponent`, which keeps the sapgis label. Their `useTranslation()` calls were already rewritten in Step 2.

- [ ] **Step 13: Drop the workflow read from `PageBuilderPage.tsx`**

`frontend/packages/pages/src/PageBuilderPage.tsx` (text after Steps 1–2). Delete the import the port mapped to another module:

```diff
-import { useWorkflow } from '@/features/workflows/api'
```

Delete the query:

```diff
   const forms = useForms(objectName || undefined)
-  const workflow = useWorkflow(objectName || undefined)
   const save = useSavePage()
```

Replace the `transitions` prop on `<Inspector …>` with the object name. Workflow's TRANSITION settings fetch the transitions themselves:

```diff
                       forms={forms.data ?? []}
-                      // a disabled workflow accepts no transition at all; offering one the server
-                      // will refuse is worse than offering none.
-                      transitions={workflow.data?.enabled ? workflow.data.definition.transitions.map((transition) => transition.name) : []}
+                      objectName={objectName}
                       objects={objects.map((item) => item.name)}
```

Everything else in the file stays as ported:
- the queries come from `@chawpi/core`;
- `t('map.selectObject')` and `t('relationships.pick')` resolve in core's `common` namespace (core keeps both keys);
- the save body is unchanged (`{ generated, page: { objectName, name, label, kind, template: template.name, definition: { page } } }`).

- [ ] **Step 14: Write the i18n bundle, the factory and the entry**

Generate `frontend/packages/pages/src/i18n.ts` from core's `common.json`. The bundle keeps the sapgis key paths (M3) and takes:
- all of `pages.*`, **minus** `componentUnavailable` (the renderer's, and core keeps it) and the gis/workflow strings (`types.MAP`, `types.WORKFLOW`, `actionKinds.TRANSITION`, `geometry`, `allGeometries`, `transition`, `transitionGone`, `mockMap`, `mockWorkflow`);
- from `tabs`, only the two keys the builder draws (`builderLabel`, `page`), because core keeps the rest for the renderer;
- `nav.pages`.

```bash
node --input-type=module -e "
import { readFileSync, writeFileSync } from 'node:fs'
const DROP = ['componentUnavailable', 'geometry', 'allGeometries', 'transition', 'transitionGone', 'mockMap', 'mockWorkflow']
const bundle = (lang) => {
  const common = JSON.parse(readFileSync('frontend/packages/core/src/i18n/locales/' + lang + '/common.json', 'utf8'))
  const pages = structuredClone(common.pages)
  for (const key of DROP) delete pages[key]
  delete pages.types.MAP
  delete pages.types.WORKFLOW
  delete pages.actionKinds.TRANSITION
  pages.tabs = { builderLabel: common.pages.tabs.builderLabel, page: common.pages.tabs.page }
  return { nav: { pages: common.nav.pages }, pages }
}
const header = '// the pages namespace. keys keep their sapgis paths (pages.*), copied out of core common.json.\n' +
  '// no MAP, WORKFLOW or TRANSITION strings: gis and workflow ship their own.\n'
writeFileSync('frontend/packages/pages/src/i18n.ts', header + 'export const pagesMessages = ' + JSON.stringify({ es: bundle('es'), en: bundle('en') }, null, 2) + '\n')
"
yarn prettier --write frontend/packages/pages/src/i18n.ts
grep -c "Dispara una transición\|mockMap\|\"MAP\"\|\"WORKFLOW\"" frontend/packages/pages/src/i18n.ts || true
```

Expected: the last command prints `0`.

`frontend/packages/pages/src/module.tsx` (Shared template T1, filled in):

```tsx
import { LayoutTemplate } from 'lucide-react'
import type { ChawpiModule } from '@chawpi/core'
import { pagesMessages } from './i18n'

export const PAGES_MODULE_ID = 'pages'

export interface PagesModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's urls (/builder/pages)
  basePath?: string
}

// the page builder. module components and actions (gis MAP, workflow WORKFLOW/TRANSITION) reach
// it only through the registry, so it works with any set of modules installed, or none
export function pagesModule(options: PagesModuleOptions = {}): ChawpiModule {
  return {
    id: PAGES_MODULE_ID,
    basePath: options.basePath ?? 'builder',
    routes: [{ id: 'builder', path: 'pages', lazy: () => import('./PageBuilderPage').then((module) => ({ default: module.PageBuilderPage })) }],
    nav: [{ group: 'builder', labelKey: 'pages:nav.pages', order: 10, icon: LayoutTemplate, route: 'builder' }],
    i18n: pagesMessages
  }
}
```

Replace `frontend/packages/pages/src/index.ts` (Task 1's placeholder) with:

```ts
export { PAGES_MODULE_ID, pagesModule, type PagesModuleOptions } from './module'
export { PageBuilderPage } from './PageBuilderPage'
```

- [ ] **Step 15: Run the whole suite**

Run: `yarn workspace @chawpi/pages test`
Expected: PASS, every file green:
- the ported `pageTree`, `retemplate`, `openTabs` and `Canvas` suites (sapgis counts plus the 3 new seed cases);
- `ComponentMock` (9 cases) and `TemplatePreview` (4);
- `PageBuilderPage` (sapgis cases plus the 5 new module cases);
- `Inspector` (7), `registrySlots` (5), `module` (4), `i18n` (3), `boundaries` (3).

If a sapgis `PageBuilderPage` case fails on a label, check that it now reads from the `pages` namespace: `renderWithProviders` must get `modules: [pagesModule(), …]`, which `open()` does.

Then confirm no forbidden name survived:

Run: `grep -rnE "'(MAP|WORKFLOW|TRANSITION)'|useWorkflow|geometr|@chawpi/(gis|workflow)" frontend/packages/pages/src --include='*.ts' --include='*.tsx' | grep -v '\.test\.' || echo clean`
Expected: `clean`.

- [ ] **Step 16: README**

`frontend/packages/pages/README.md`:

````markdown
# @chawpi/pages

Drag-and-drop builder for the record detail page of every object: a palette of components, a canvas laid out by the page template, and an inspector for the selected component. Pages it saves are drawn by `@chawpi/core`'s record detail page.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```bash
yarn add @chawpi/pages @chawpi/core @chawpi/ui
```

It brings `@dnd-kit/core`. It needs no other chawpi module.

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { pagesModule } from '@chawpi/pages'
import { gisModule } from '@chawpi/gis'
import { workflowModule } from '@chawpi/workflow'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'Catastro' }} modules={[pagesModule(), gisModule(), workflowModule()]} />
}
```

With gis and workflow installed, the palette also offers MAP and WORKFLOW, and an ACTION can be a TRANSITION. Without them, the builder offers only core's components and NAVIGATE.

## What it adds

| Slot | Value |
|---|---|
| route `pages:builder` | `/builder/pages` (lazy) |
| nav | group `builder`, order 10, label `pages:nav.pages` |
| i18n namespace | `pages` |

## Module components and actions

The builder knows no module by name. It reads the registry:

| Registry slot | Builder use |
|---|---|
| `pageComponents[type].labelKey`, `.icon` | palette entry (after core's content types, in module order) |
| `pageComponents[type].defaults` | merged over a freshly dropped node |
| `pageComponents[type].settings` | drawn in the inspector with `{ component, definition, objectName, onChange }` |
| `pageComponents[type].preview` | drawn on the canvas with `{ component, definition }`; absent = a labelled box |
| `pageActions[kind].labelKey` | ACTION kind picker (module kinds first, NAVIGATE last) |
| `pageActions[kind].defaults` | merged when a fresh ACTION takes that kind (the first listed kind is the default) |
| `pageActions[kind].settings` | drawn under the kind picker |

A stored page that uses a component or action whose module is not installed still opens. The node shows a labelled placeholder and is saved back untouched.

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'builder'` | url prefix of the builder route |

## Backend

It needs the chawpi `pages` backend module:
- `GET /api/objects/{object}/pages/record-detail` and `GET /api/metadata/page-templates`;
- `POST /api/pages` and `PUT`/`DELETE /api/pages/{name}`.

Without that module those endpoints must answer 404, and core's record page then falls back to the page it builds from metadata.
````

- [ ] **Step 17: Format, lint, test, build, confirm nothing committed**

```bash
yarn prettier --write frontend/packages/pages/src frontend/packages/pages/README.md
yarn workspace @chawpi/pages lint
yarn workspace @chawpi/pages test
yarn workspace @chawpi/pages build
git status --short
```

Expected:
- lint, test and build exit 0;
- `frontend/packages/pages/dist/index.js` and `dist/index.d.ts` exist, and `dist/index.js` keeps `@dnd-kit/core` and `@chawpi/core` as bare imports (externals);
- `git status --short` lists only untracked or modified paths under `frontend/packages/pages/`, and nothing is committed.

### Task 7: `@chawpi/views` — list view builder

**Wave 1, runs in parallel with Tasks 2–6, 8, 9.** Write only inside `frontend/packages/views/src/**` and `frontend/packages/views/README.md`. Task 1 already wrote `package.json` (deps: `lucide-react`; peers: `@chawpi/core`, `@chawpi/ui`, react, react-dom, @tanstack/react-query, i18next, react-i18next, react-router), `tsconfig*.json`, `vite.config.ts`, `src/test/setup.ts` and a placeholder `src/index.ts`. Do not edit those except `src/index.ts`.

**Files:**
- Port: `sapgis/frontend/src/features/views/ViewBuilderPage.tsx` → `frontend/packages/views/src/ViewBuilderPage.tsx`
- Create: `frontend/packages/views/src/module.tsx`
- Create: `frontend/packages/views/src/i18n.ts`
- Modify (replace placeholder): `frontend/packages/views/src/index.ts`
- Create: `frontend/packages/views/src/test/nativeSelect.tsx` (test-only double; `src/test` is excluded from the build)
- Test: `frontend/packages/views/src/ViewBuilderPage.test.tsx`
- Test: `frontend/packages/views/src/module.test.tsx`
- Test: `frontend/packages/views/src/i18n.test.ts`
- Test: `frontend/packages/views/src/boundaries.test.ts`
- Create: `frontend/packages/views/README.md`

Not ported: `sapgis/frontend/src/features/views/viewColumns.ts` (+ test). P4 put it in core as `features/records/viewColumns.ts`, and `fallbackView`/`pickView` come from `@chawpi/core`.

**Interfaces:**
- Consumes (all from `@chawpi/core`, already exported by `frontend/packages/core/src/index.ts`):
  - `PageHeader`, `ApiError`;
  - `useObjects()`, `useObjectDefinition(name)`, `useViews(objectName)`, `useSaveView(objectName)` (mutation arg `{ generated: boolean; view: ViewPayload }`: POST `/objects/{o}/views` with the whole payload when `generated`, else PUT `/objects/{o}/views/{name}` with `{ label, isDefault, definition }`), `useDeleteView(objectName)` (DELETE `/objects/{o}/views/{name}`);
  - `fallbackView(objectName, fields)`, `pickView(views, name)`;
  - types `FieldMeta`, `SortDirection`, `View`, `ChawpiModule`;
  - for tests: `coreModule`, `createRegistry`, `createLinks`.
- From `@chawpi/ui`: `Button`, `Card`, `CardBody`, `CardHeader`, `CardTitle`, `Input`, `Label`, `Select*`, `Badge`.
- From `@chawpi/testing` (tests only, through the vitest alias): `renderWithProviders`, `mockFetch`, `type FetchMock`.
- Produces (public API, `src/index.ts`):
  - `VIEWS_MODULE_ID = 'views'`;
  - `interface ViewsModuleOptions { basePath?: string }`;
  - `viewsModule(options?: ViewsModuleOptions): ChawpiModule` (id `views`, basePath default `'builder'`, route `builder` → `views` (lazy), nav item in core's `builder` group, order 40, `views:nav.views`, icon `Columns3`, i18n `viewsMessages`);
  - `viewsMessages`;
  - `ViewBuilderPage`.
  - Route key `views:builder`, default URL `/builder/views` (sapgis parity).

- [ ] **Step 1: Write the i18n bundle and its parity test (T4)**

Create `frontend/packages/views/src/i18n.ts`. The strings are copied verbatim from `frontend/packages/core/src/i18n/locales/{es,en}/common.json` (`nav.views` and `views.*` except `views.selector`, which only core's record list uses). Task 10 deletes the core copies that only this module uses.

```ts
// the view builder's own strings, loaded under the namespace 'views'. key paths match sapgis, so
// the ported page keeps calling t('views.title').
export const viewsMessages = {
  es: {
    nav: { views: 'Vistas' },
    views: {
      title: 'Vistas',
      subtitle: 'Guarda configuraciones de lista: columnas, filtros, orden y tamaño de página',
      object: 'Objeto',
      view: 'Vista',
      newView: 'Nueva vista',
      pick: 'Selecciona una vista',
      name: 'Nombre técnico',
      nameHint: 'minúsculas, sin espacios. Ej: predios_activos',
      label: 'Etiqueta',
      stored: 'Guardada',
      generated: 'Por defecto',
      generatedHint: 'Esta vista se generó a partir de la metadata. Al guardar se convierte en guardada.',
      isDefault: 'Vista por defecto',
      pageSize: 'Registros por página',
      columns: 'Columnas',
      addColumn: 'Añadir columna',
      noColumns: 'Esta vista no tiene columnas. Se muestran las del objeto.',
      filters: 'Filtros',
      addFilter: 'Añadir filtro',
      filterValue: 'Valor',
      noFilters: 'Sin filtros.',
      sort: 'Orden',
      sortField: 'Campo',
      sortDirection: 'Dirección',
      noSort: 'Sin orden',
      directions: { ASC: 'Ascendente', DESC: 'Descendente' },
      moveUp: 'Subir',
      moveDown: 'Bajar',
      reset: 'Restablecer',
      confirmReset: '¿Eliminar esta vista? Se vuelve a la vista por defecto.'
    }
  },
  en: {
    nav: { views: 'Views' },
    views: {
      title: 'Views',
      subtitle: 'Save list configurations: columns, filters, sort and page size',
      object: 'Object',
      view: 'View',
      newView: 'New view',
      pick: 'Pick a view',
      name: 'Technical name',
      nameHint: 'lower case, no spaces. e.g. predios_activos',
      label: 'Label',
      stored: 'Saved',
      generated: 'Default',
      generatedHint: 'This view was generated from metadata. Saving turns it into a saved one.',
      isDefault: 'Default view',
      pageSize: 'Records per page',
      columns: 'Columns',
      addColumn: 'Add column',
      noColumns: "This view has no columns. The object's own are shown.",
      filters: 'Filters',
      addFilter: 'Add filter',
      filterValue: 'Value',
      noFilters: 'No filters.',
      sort: 'Sort',
      sortField: 'Field',
      sortDirection: 'Direction',
      noSort: 'No sort',
      directions: { ASC: 'Ascending', DESC: 'Descending' },
      moveUp: 'Move up',
      moveDown: 'Move down',
      reset: 'Reset to default',
      confirmReset: 'Delete this view? The list goes back to the default view.'
    }
  }
}
```

Create `frontend/packages/views/src/i18n.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { viewsMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('views messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(viewsMessages.en)).toEqual(leaves(viewsMessages.es))
  })

  it('names its nav entry and every string the builder draws', () => {
    expect(leaves(viewsMessages.es)).toEqual(
      expect.arrayContaining(['nav.views', 'views.title', 'views.pick', 'views.addColumn', 'views.directions.ASC', 'views.directions.DESC', 'views.confirmReset'])
    )
  })
})
```

- [ ] **Step 2: Write the module wiring test (T2)**

Create `frontend/packages/views/src/module.test.tsx`:

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { viewsModule } from './module'

describe('viewsModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, viewsModule()])).not.toThrow()
  })

  it('mounts its route under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, viewsModule()]))
    expect(links.to('views:builder')).toBe('/builder/views')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, viewsModule({ basePath: 'x' })]))
    expect(links.to('views:builder')).toBe('/x/views')
  })

  it('puts its entry in core builder group, after pages and forms', () => {
    const builder = createRegistry([coreModule, viewsModule()]).navGroups.find((group) => group.id === 'builder')
    expect(builder?.items).toEqual([expect.objectContaining({ labelKey: 'views:nav.views', to: '/builder/views', order: 40 })])
  })
})
```

- [ ] **Step 3: Write the boundary test (T3)**

Create `frontend/packages/views/src/boundaries.test.ts`:

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

// a module stays installable on its own: no other module package, no other module's heavy library.
// import.meta.dirname, not new URL(.., import.meta.url): vite rewrites the latter under jsdom
const SRC = import.meta.dirname
// views owns no heavy library
const OWN = /^$/
const HEAVY = /^(maplibre-gl|terra-draw.*|@xyflow\/.+|@tiptap\/.+|@dnd-kit\/.+)$/

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function importsOf(file: string): string[] {
  return [...readFileSync(file, 'utf8').matchAll(/from '([^']+)'|import '([^']+)'/g)].map((match) => match[1] ?? match[2])
}

describe('views boundaries', () => {
  it('imports no other module package and no heavy library it does not own', () => {
    const offenders = sources(SRC).flatMap((file) =>
      importsOf(file)
        .map((specifier) =>
          specifier
            .split('/')
            .slice(0, specifier.startsWith('@') ? 2 : 1)
            .join('/')
        )
        .filter((pkg) => (pkg.startsWith('@chawpi/') && pkg !== '@chawpi/core' && pkg !== '@chawpi/ui') || (HEAVY.test(pkg) && !OWN.test(pkg)))
        .map((pkg) => `${relative(SRC, file)} -> ${pkg}`)
    )
    expect(offenders).toEqual([])
  })

  // REST paths passed to api() are fine; what links must build is every in-app url handed to a
  // Link, an <a> or navigate()
  it('never spells an in-app url: links build them', () => {
    const offenders = sources(SRC).filter((file) => /(?:\bto=|\bhref=|navigate\()\{?\s*[`'"]\/(?!\/)/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
})
```

- [ ] **Step 4: Write the native select double and the failing page test**

Radix opens its listbox in a portal behind pointer capture, and jsdom does not implement that. sapgis's `PageBuilderPage.test.tsx` swaps the select for a native one, and this is the same double as a test helper. Create `frontend/packages/views/src/test/nativeSelect.tsx`:

```tsx
import { Children, isValidElement, type ReactNode } from 'react'

// radix opens its listbox in a portal behind pointer capture jsdom does not implement. a native
// select answers the same question: which value did the page receive.
export const SelectTrigger = ({ children }: { children?: ReactNode; 'aria-label'?: string }) => <>{children}</>
export const SelectValue = (_: { placeholder?: string }) => null
export const SelectContent = ({ children }: { children?: ReactNode }) => <>{children}</>
export const SelectItem = ({ value, children }: { value: string; children?: ReactNode }) => <option value={value}>{children}</option>

// the accessible name lives on the trigger, which this double renders away
function triggerLabel(children: ReactNode): string | undefined {
  const trigger = Children.toArray(children).find((child) => isValidElement(child) && child.type === SelectTrigger)
  return isValidElement(trigger) ? (trigger.props as { 'aria-label'?: string })['aria-label'] : undefined
}

export function Select({
  value,
  disabled,
  onValueChange,
  children
}: {
  value: string
  disabled?: boolean
  onValueChange: (value: string) => void
  children?: ReactNode
}) {
  return (
    <select aria-label={triggerLabel(children)} value={value} disabled={disabled} onChange={(event) => onValueChange(event.target.value)}>
      <option value="" />
      {children}
    </select>
  )
}
```

Create `frontend/packages/views/src/ViewBuilderPage.test.tsx`. The REST bodies are the ones core's `useSaveView` sends, which are byte-identical to sapgis `lib/queries.ts` `useSaveView`.

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import type { FieldMeta, ObjectDefinition, View } from '@chawpi/core'
import { viewsModule } from './module'
import { ViewBuilderPage } from './ViewBuilderPage'

vi.mock('@chawpi/ui', async (importOriginal) => ({ ...(await importOriginal<typeof import('@chawpi/ui')>()), ...(await import('./test/nativeSelect')) }))

function field(name: string, label: string, position: number): FieldMeta {
  return {
    id: `f-${name}`,
    name,
    label,
    type: 'TEXT',
    required: false,
    unique: false,
    defaultValue: null,
    description: null,
    position,
    enumOptions: null,
    relationTarget: null,
    visible: true,
    editable: true
  }
}

const predio: ObjectDefinition = {
  id: 'o1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  fields: [field('codigo', 'Código', 0), field('area', 'Área', 1)]
}

const generated: View = {
  id: '',
  name: 'default',
  label: '',
  objectName: 'predio',
  isDefault: true,
  generated: true,
  definition: { columns: ['codigo'], filters: {}, sort: null, pageSize: 25 }
}

const stored: View = {
  id: 'v1',
  name: 'activos',
  label: 'Activos',
  objectName: 'predio',
  isDefault: false,
  generated: false,
  definition: { columns: ['codigo', 'area'], filters: {}, sort: null, pageSize: 10 }
}

let fetch: FetchMock | null = null
afterEach(() => {
  fetch?.restore()
  vi.restoreAllMocks()
})

function serve(views: View[]) {
  fetch = mockFetch([
    { path: '/objects', body: [predio] },
    { path: '/metadata/objects/predio', body: predio },
    { path: '/objects/predio/views', body: views },
    { method: 'POST', path: '/objects/predio/views', body: { ...generated, id: 'v2', name: 'predios_activos', generated: false } },
    { method: 'PUT', path: '/objects/predio/views/activos', body: stored },
    { method: 'DELETE', path: '/objects/predio/views/activos', status: 204 }
  ])
  return fetch
}

async function openPredio() {
  renderWithProviders(<ViewBuilderPage />, { modules: [viewsModule()] })
  // the first select is the object picker: its trigger has no label in sapgis either
  await userEvent.selectOptions((await screen.findAllByRole('combobox'))[0], 'predio')
  await screen.findByLabelText('Nombre técnico')
}

describe('ViewBuilderPage', () => {
  it('saves a generated view as a new one with the columns the admin added', async () => {
    const calls = serve([generated])
    await openPredio()

    await userEvent.clear(screen.getByLabelText('Nombre técnico'))
    await userEvent.type(screen.getByLabelText('Nombre técnico'), 'predios_activos')
    await userEvent.type(screen.getByLabelText('Etiqueta'), 'Predios activos')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Añadir columna' }), 'area')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = calls.calls.find((call) => call.method === 'POST')
    expect(post?.path).toBe('/objects/predio/views')
    expect(post?.body).toEqual({
      name: 'predios_activos',
      label: 'Predios activos',
      isDefault: true,
      definition: { columns: ['codigo', 'area'], filters: {}, sort: null, pageSize: 25 }
    })
  })

  it('updates a stored view in place with its reordered columns and keeps its name', async () => {
    const calls = serve([stored])
    await openPredio()

    expect(screen.getByLabelText('Nombre técnico')).toBeDisabled()
    await userEvent.click(screen.getAllByRole('button', { name: 'Bajar' })[0])
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = calls.calls.find((call) => call.method === 'PUT')
    expect(put?.path).toBe('/objects/predio/views/activos')
    expect(put?.body).toEqual({ label: 'Activos', isDefault: false, definition: { columns: ['area', 'codigo'], filters: {}, sort: null, pageSize: 10 } })
  })

  it('resets a stored view by deleting it once the admin confirms', async () => {
    const calls = serve([stored])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await openPredio()

    await userEvent.click(screen.getByRole('button', { name: /Restablecer/ }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'DELETE')).toBe(true))
    expect(calls.calls.find((call) => call.method === 'DELETE')?.path).toBe('/objects/predio/views/activos')
  })
})
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/views test`
Expected: FAIL. The failures are `Failed to resolve import "./module"` and `"./ViewBuilderPage"`. `i18n.test.ts` and `boundaries.test.ts` pass.

- [ ] **Step 6: Port the page**

Run: `node frontend/tooling/port-from-sapgis.mjs features/views/ViewBuilderPage.tsx frontend/packages/views/src/ViewBuilderPage.tsx`
Expected: `ported features/views/ViewBuilderPage.tsx -> frontend/packages/views/src/ViewBuilderPage.tsx` with no `MANUAL:` line.

The script rewrites these imports:
- `@/components/layout/AppShell`, `@/features/views/viewColumns`, `@/lib/api`, `@/lib/queries` and `@/types/metadata` → `@chawpi/core`;
- `@/components/ui/*` → `@chawpi/ui`.

Several `import … from '@chawpi/core'` and `'@chawpi/ui'` lines are legal. Merge them into one import per package so the file reads cleanly:

```tsx
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowDown, ArrowUp, RotateCcw, Trash2 } from 'lucide-react'
import { ApiError, PageHeader, fallbackView, pickView, useDeleteView, useObjectDefinition, useObjects, useSaveView, useViews } from '@chawpi/core'
import type { FieldMeta, SortDirection, View } from '@chawpi/core'
import { Badge, Button, Card, CardBody, CardHeader, CardTitle, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@chawpi/ui'
```

Then make the one manual edit (M3):

```tsx
// before
  const { t } = useTranslation()
// after: the builder's strings live in its own namespace; common.* and map.selectObject stay core's
  const { t } = useTranslation(['views', 'common'])
```

Nothing else changes. Keep the markup, classes, `NEW`/`NONE` sentinels, the `useEffect` reload, `labelOf` and `without` exactly as sapgis has them. The page has no in-app link.

- [ ] **Step 7: Write the module factory**

Create `frontend/packages/views/src/module.tsx`:

```tsx
import type { ChawpiModule } from '@chawpi/core'
import { Columns3 } from 'lucide-react'
import { viewsMessages } from './i18n'

export const VIEWS_MODULE_ID = 'views'

export interface ViewsModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's /builder/views
  basePath?: string
}

export function viewsModule(options: ViewsModuleOptions = {}): ChawpiModule {
  return {
    id: VIEWS_MODULE_ID,
    basePath: options.basePath ?? 'builder',
    routes: [{ id: 'builder', path: 'views', lazy: () => import('./ViewBuilderPage').then((m) => ({ default: m.ViewBuilderPage })) }],
    // the builder group is core's; pages 10, forms 20, documents 30, views 40 as in sapgis's sidebar
    nav: [{ group: 'builder', labelKey: 'views:nav.views', order: 40, icon: Columns3, route: 'builder' }],
    i18n: viewsMessages
  }
}
```

Replace `frontend/packages/views/src/index.ts` entirely with:

```ts
export { VIEWS_MODULE_ID, viewsModule, type ViewsModuleOptions } from './module'
export { viewsMessages } from './i18n'
export { ViewBuilderPage } from './ViewBuilderPage'
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `yarn workspace @chawpi/views test`
Expected: PASS. Four files: module 4, i18n 2, boundaries 2, ViewBuilderPage 3.

If the POST test fails because the name input still reads `default`, check that `userEvent.clear` ran on the enabled input: the draft is the generated view, so the input is enabled. Do not change the page.

- [ ] **Step 9: Write the README (T5)**

Create `frontend/packages/views/README.md`:

````markdown
# @chawpi/views

The list view builder: pick an object, then save list configurations for it (columns, filters, sort and page size). Core's record list already reads stored views and falls back to a generated one, so this package only adds the screen that edits them.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```
yarn add @chawpi/core @chawpi/ui @chawpi/views
```

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { viewsModule } from '@chawpi/views'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[viewsModule()]} />
}
```

Tailwind: the app's stylesheet already has `@import "@chawpi/ui/theme.css"` and `@source "../node_modules/@chawpi"` (see `@chawpi/core`), which covers this package's classes too.

## What it adds

| Slot | Value |
|---|---|
| route `views:builder` | `/builder/views` → `ViewBuilderPage` (lazy) |
| nav | "Vistas" / "Views" in core's builder group, order 40 |
| i18n | namespace `views` (es, en) |

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'builder'` | url prefix of the route: `viewsModule({ basePath: 'config' })` serves `/config/views` |

## Backend

It needs the chawpi views backend module (`GET/POST /objects/{object}/views`, `PUT/DELETE /objects/{object}/views/{name}`). Without it, the builder has nothing to edit, and core's record list keeps working on its generated view. An absent backend module must answer 404.
````

- [ ] **Step 10: Format, lint, test, build**

Run:
```bash
yarn prettier --write frontend/packages/views/src frontend/packages/views/README.md
yarn workspace @chawpi/views lint
yarn workspace @chawpi/views test
yarn workspace @chawpi/views build
git status --short
```
Expected:
- lint, test and build all pass;
- `frontend/packages/views/dist/index.js` and `dist/index.d.ts` exist;
- `git status --short` lists only paths under `frontend/packages/views/` (plus whatever other tasks left uncommitted), and nothing is committed.

### Task 8: `@chawpi/forms` — form builder

**Wave 1, runs in parallel with Tasks 2–7, 9.** Write only inside `frontend/packages/forms/src/**` and `frontend/packages/forms/README.md`. Task 1 already wrote `package.json` (deps: `lucide-react`; peers: `@chawpi/core`, `@chawpi/ui`, react, react-dom, @tanstack/react-query, i18next, react-i18next, react-router), `tsconfig*.json`, `vite.config.ts`, `src/test/setup.ts` and a placeholder `src/index.ts`. Do not edit those except `src/index.ts`.

**Files:**
- Port: `sapgis/frontend/src/features/forms/FormBuilderPage.tsx` → `frontend/packages/forms/src/FormBuilderPage.tsx`
- Create: `frontend/packages/forms/src/module.tsx`
- Create: `frontend/packages/forms/src/i18n.ts`
- Modify (replace placeholder): `frontend/packages/forms/src/index.ts`
- Create: `frontend/packages/forms/src/test/nativeSelect.tsx` (test-only double; `src/test` is excluded from the build)
- Test: `frontend/packages/forms/src/FormBuilderPage.test.tsx` (sapgis has none, so this one is new)
- Test: `frontend/packages/forms/src/module.test.tsx`
- Test: `frontend/packages/forms/src/i18n.test.ts`
- Test: `frontend/packages/forms/src/boundaries.test.ts`
- Create: `frontend/packages/forms/README.md`

**Interfaces:**
- Consumes (all from `@chawpi/core`, already exported by `frontend/packages/core/src/index.ts`):
  - `PageHeader`, `ApiError`;
  - `useObjects()`, `useObjectDefinition(name)`, `useForms(objectName)`;
  - `useSaveStoredForm(objectName)`: **renamed in core from sapgis `useSaveForm`**. The mutation arg is `{ generated: boolean; form: FormPayload }`. When `generated`, it POSTs `/objects/{o}/forms` with the whole payload; otherwise it PUTs `/objects/{o}/forms/{name}` with `{ label, definition }`.
  - `useDeleteForm(objectName)` (DELETE `/objects/{o}/forms/{name}`);
  - types `FieldMeta`, `Form`, `FormSection`, `ChawpiModule`;
  - for tests: `coreModule`, `createRegistry`, `createLinks`.
- From `@chawpi/ui`: `Button`, `Card`, `CardBody`, `CardHeader`, `CardTitle`, `Input`, `Label`, `Select*`, `Badge`.
- From `@chawpi/testing` (tests only, through the vitest alias): `renderWithProviders`, `mockFetch`, `type FetchMock`.
- Produces (public API, `src/index.ts`):
  - `FORMS_MODULE_ID = 'forms'`;
  - `interface FormsModuleOptions { basePath?: string }`;
  - `formsModule(options?: FormsModuleOptions): ChawpiModule` (id `forms`, basePath default `'builder'`, route `builder` → `forms` (lazy), nav item in core's `builder` group, order 20, `forms:nav.forms`, icon `FileText`, i18n `formsMessages`);
  - `formsMessages`;
  - `FormBuilderPage`.
  - Route key `forms:builder`, default URL `/builder/forms` (sapgis parity).

- [ ] **Step 1: Write the i18n bundle and its parity test (T4)**

Create `frontend/packages/forms/src/i18n.ts`. The strings are copied verbatim from `frontend/packages/core/src/i18n/locales/{es,en}/common.json` (`nav.forms` and the whole `forms.*` group). Task 10 deletes the core copies.

```ts
// the form builder's own strings, loaded under the namespace 'forms'. key paths match sapgis, so
// the ported page keeps calling t('forms.title').
export const formsMessages = {
  es: {
    nav: { forms: 'Formularios' },
    forms: {
      title: 'Formularios',
      subtitle: 'Agrupa los campos de un objeto en secciones con título',
      object: 'Objeto',
      form: 'Formulario',
      newForm: 'Nuevo formulario',
      pick: 'Selecciona un formulario',
      name: 'Nombre técnico',
      nameHint: 'minúsculas, sin espacios. Ej: predio_alta',
      label: 'Etiqueta',
      stored: 'Guardado',
      generated: 'Por defecto',
      generatedHint: 'Este formulario se generó a partir de la metadata. Al guardar se convierte en guardado.',
      sections: 'Secciones',
      addSection: 'Añadir sección',
      sectionTitle: 'Título de la sección',
      removeSection: 'Eliminar sección',
      noSections: 'Este formulario no tiene secciones.',
      addField: 'Añadir campo',
      noFields: 'Sección sin campos.',
      moveUp: 'Subir',
      moveDown: 'Bajar',
      reset: 'Restablecer',
      confirmReset: '¿Eliminar este formulario? Se vuelve al formulario por defecto.'
    }
  },
  en: {
    nav: { forms: 'Forms' },
    forms: {
      title: 'Forms',
      subtitle: "Group an object's fields into titled sections",
      object: 'Object',
      form: 'Form',
      newForm: 'New form',
      pick: 'Pick a form',
      name: 'Technical name',
      nameHint: 'lower case, no spaces. e.g. predio_alta',
      label: 'Label',
      stored: 'Saved',
      generated: 'Default',
      generatedHint: 'This form was generated from metadata. Saving turns it into a saved one.',
      sections: 'Sections',
      addSection: 'Add section',
      sectionTitle: 'Section title',
      removeSection: 'Remove section',
      noSections: 'This form has no sections.',
      addField: 'Add field',
      noFields: 'Section without fields.',
      moveUp: 'Move up',
      moveDown: 'Move down',
      reset: 'Reset to default',
      confirmReset: 'Delete this form? It goes back to the default form.'
    }
  }
}
```

Create `frontend/packages/forms/src/i18n.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formsMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('forms messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(formsMessages.en)).toEqual(leaves(formsMessages.es))
  })

  it('names its nav entry and every string the builder draws', () => {
    expect(leaves(formsMessages.es)).toEqual(expect.arrayContaining(['nav.forms', 'forms.title', 'forms.pick', 'forms.addSection', 'forms.addField', 'forms.confirmReset']))
  })
})
```

- [ ] **Step 2: Write the module wiring test (T2)**

Create `frontend/packages/forms/src/module.test.tsx`:

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { formsModule } from './module'

describe('formsModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, formsModule()])).not.toThrow()
  })

  it('mounts its route under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, formsModule()]))
    expect(links.to('forms:builder')).toBe('/builder/forms')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, formsModule({ basePath: 'x' })]))
    expect(links.to('forms:builder')).toBe('/x/forms')
  })

  it('puts its entry in core builder group, second after pages', () => {
    const builder = createRegistry([coreModule, formsModule()]).navGroups.find((group) => group.id === 'builder')
    expect(builder?.items).toEqual([expect.objectContaining({ labelKey: 'forms:nav.forms', to: '/builder/forms', order: 20 })])
  })
})
```

- [ ] **Step 3: Write the boundary test (T3)**

Create `frontend/packages/forms/src/boundaries.test.ts`:

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

// a module stays installable on its own: no other module package, no other module's heavy library.
// import.meta.dirname, not new URL(.., import.meta.url): vite rewrites the latter under jsdom
const SRC = import.meta.dirname
// forms owns no heavy library
const OWN = /^$/
const HEAVY = /^(maplibre-gl|terra-draw.*|@xyflow\/.+|@tiptap\/.+|@dnd-kit\/.+)$/

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function importsOf(file: string): string[] {
  return [...readFileSync(file, 'utf8').matchAll(/from '([^']+)'|import '([^']+)'/g)].map((match) => match[1] ?? match[2])
}

describe('forms boundaries', () => {
  it('imports no other module package and no heavy library it does not own', () => {
    const offenders = sources(SRC).flatMap((file) =>
      importsOf(file)
        .map((specifier) =>
          specifier
            .split('/')
            .slice(0, specifier.startsWith('@') ? 2 : 1)
            .join('/')
        )
        .filter((pkg) => (pkg.startsWith('@chawpi/') && pkg !== '@chawpi/core' && pkg !== '@chawpi/ui') || (HEAVY.test(pkg) && !OWN.test(pkg)))
        .map((pkg) => `${relative(SRC, file)} -> ${pkg}`)
    )
    expect(offenders).toEqual([])
  })

  // REST paths passed to api() are fine; what links must build is every in-app url handed to a
  // Link, an <a> or navigate()
  it('never spells an in-app url: links build them', () => {
    const offenders = sources(SRC).filter((file) => /(?:\bto=|\bhref=|navigate\()\{?\s*[`'"]\/(?!\/)/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
})
```

- [ ] **Step 4: Write the native select double and the failing page test**

Radix opens its listbox in a portal behind pointer capture, and jsdom does not implement that. sapgis's `PageBuilderPage.test.tsx` swaps the select for a native one, and this is the same double as a test helper. Create `frontend/packages/forms/src/test/nativeSelect.tsx`:

```tsx
import { Children, isValidElement, type ReactNode } from 'react'

// radix opens its listbox in a portal behind pointer capture jsdom does not implement. a native
// select answers the same question: which value did the page receive.
export const SelectTrigger = ({ children }: { children?: ReactNode; 'aria-label'?: string }) => <>{children}</>
export const SelectValue = (_: { placeholder?: string }) => null
export const SelectContent = ({ children }: { children?: ReactNode }) => <>{children}</>
export const SelectItem = ({ value, children }: { value: string; children?: ReactNode }) => <option value={value}>{children}</option>

// the accessible name lives on the trigger, which this double renders away
function triggerLabel(children: ReactNode): string | undefined {
  const trigger = Children.toArray(children).find((child) => isValidElement(child) && child.type === SelectTrigger)
  return isValidElement(trigger) ? (trigger.props as { 'aria-label'?: string })['aria-label'] : undefined
}

export function Select({
  value,
  disabled,
  onValueChange,
  children
}: {
  value: string
  disabled?: boolean
  onValueChange: (value: string) => void
  children?: ReactNode
}) {
  return (
    <select aria-label={triggerLabel(children)} value={value} disabled={disabled} onChange={(event) => onValueChange(event.target.value)}>
      <option value="" />
      {children}
    </select>
  )
}
```

Create `frontend/packages/forms/src/FormBuilderPage.test.tsx`. The REST bodies are the ones core's `useSaveStoredForm` sends, which are byte-identical to sapgis `lib/queries.ts` `useSaveForm`.

```tsx
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import type { FieldMeta, Form, ObjectDefinition } from '@chawpi/core'
import { FormBuilderPage } from './FormBuilderPage'
import { formsModule } from './module'

vi.mock('@chawpi/ui', async (importOriginal) => ({ ...(await importOriginal<typeof import('@chawpi/ui')>()), ...(await import('./test/nativeSelect')) }))

function field(name: string, label: string, position: number): FieldMeta {
  return {
    id: `f-${name}`,
    name,
    label,
    type: 'TEXT',
    required: false,
    unique: false,
    defaultValue: null,
    description: null,
    position,
    enumOptions: null,
    relationTarget: null,
    visible: true,
    editable: true
  }
}

const predio: ObjectDefinition = {
  id: 'o1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  fields: [field('codigo', 'Código', 0), field('area', 'Área', 1)]
}

const stored: Form = {
  id: 'fm1',
  name: 'alta',
  label: 'Alta',
  objectName: 'predio',
  generated: false,
  definition: { sections: [{ title: 'Datos', fields: ['codigo'] }] }
}

let fetch: FetchMock | null = null
afterEach(() => {
  fetch?.restore()
  vi.restoreAllMocks()
})

function serve(forms: Form[]) {
  fetch = mockFetch([
    { path: '/objects', body: [predio] },
    { path: '/metadata/objects/predio', body: predio },
    { path: '/objects/predio/forms', body: forms },
    { method: 'POST', path: '/objects/predio/forms', body: { ...stored, id: 'fm2', name: 'predio_alta' } },
    { method: 'PUT', path: '/objects/predio/forms/alta', body: stored },
    { method: 'DELETE', path: '/objects/predio/forms/alta', status: 204 }
  ])
  return fetch
}

async function openPredio() {
  renderWithProviders(<FormBuilderPage />, { modules: [formsModule()] })
  // the first select is the object picker: its trigger has no label in sapgis either
  await userEvent.selectOptions((await screen.findAllByRole('combobox'))[0], 'predio')
  await screen.findByLabelText('Nombre técnico')
}

describe('FormBuilderPage', () => {
  it('creates a new form from a blank one with a titled section and its fields', async () => {
    const calls = serve([stored])
    await openPredio()

    // second select: the form picker. its last option starts a blank form
    await userEvent.selectOptions(screen.getAllByRole('combobox')[1], '__new__')
    await userEvent.type(screen.getByLabelText('Nombre técnico'), 'predio_alta')
    await userEvent.type(screen.getByLabelText('Etiqueta'), 'Alta de predio')
    await userEvent.type(screen.getByLabelText('Título de la sección'), 'Datos generales')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Añadir campo 1' }), 'area')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Añadir campo 1' }), 'codigo')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = calls.calls.find((call) => call.method === 'POST')
    expect(post?.path).toBe('/objects/predio/forms')
    expect(post?.body).toEqual({
      name: 'predio_alta',
      label: 'Alta de predio',
      definition: { sections: [{ title: 'Datos generales', fields: ['area', 'codigo'] }] }
    })
  })

  it('keeps a field in one section only and updates a stored form in place', async () => {
    const calls = serve([stored])
    await openPredio()

    expect(screen.getByLabelText('Nombre técnico')).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /Añadir sección/ }))
    const second = screen.getByRole('combobox', { name: 'Añadir campo 2' })
    // codigo already sits in the first section
    expect(within(second).queryByRole('option', { name: 'Código' })).not.toBeInTheDocument()
    await userEvent.selectOptions(second, 'area')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = calls.calls.find((call) => call.method === 'PUT')
    expect(put?.path).toBe('/objects/predio/forms/alta')
    expect(put?.body).toEqual({
      label: 'Alta',
      definition: {
        sections: [
          { title: 'Datos', fields: ['codigo'] },
          { title: null, fields: ['area'] }
        ]
      }
    })
  })

  it('resets a stored form by deleting it once the admin confirms', async () => {
    const calls = serve([stored])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await openPredio()

    await userEvent.click(screen.getByRole('button', { name: /Restablecer/ }))

    await vi.waitFor(() => expect(calls.calls.some((call) => call.method === 'DELETE')).toBe(true))
    expect(calls.calls.find((call) => call.method === 'DELETE')?.path).toBe('/objects/predio/forms/alta')
  })
})
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/forms test`
Expected: FAIL. The failures are `Failed to resolve import "./module"` and `"./FormBuilderPage"`. `i18n.test.ts` and `boundaries.test.ts` pass.

- [ ] **Step 6: Port the page**

Run: `node frontend/tooling/port-from-sapgis.mjs features/forms/FormBuilderPage.tsx frontend/packages/forms/src/FormBuilderPage.tsx`
Expected: `ported features/forms/FormBuilderPage.tsx -> frontend/packages/forms/src/FormBuilderPage.tsx` with no `MANUAL:` line.

The script rewrites these imports:
- `@/components/layout/AppShell`, `@/lib/api`, `@/lib/queries` and `@/types/metadata` → `@chawpi/core`;
- `@/components/ui/*` → `@chawpi/ui`.

Merge the imports into one per package, and **rename `useSaveForm` to `useSaveStoredForm`**. That is core's name for the same hook, with the same REST calls:

```tsx
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowDown, ArrowUp, Plus, RotateCcw, Trash2 } from 'lucide-react'
import { ApiError, PageHeader, useDeleteForm, useForms, useObjectDefinition, useObjects, useSaveStoredForm } from '@chawpi/core'
import type { FieldMeta, Form, FormSection } from '@chawpi/core'
import { Badge, Button, Card, CardBody, CardHeader, CardTitle, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@chawpi/ui'
```

Make exactly these edits in the body:

```tsx
// before
  const { t } = useTranslation()
// after: the builder's strings live in its own namespace; common.* and map.selectObject stay core's
  const { t } = useTranslation(['forms', 'common'])
```

```tsx
// before
  const save = useSaveForm(objectName)
// after
  const save = useSaveStoredForm(objectName)
```

Nothing else changes. Keep the markup, classes, the `NEW` sentinel, `blankForm`, `labelOf`, and the one-section-per-field filter (`assigned`) exactly as sapgis has them. The page has no in-app link.

- [ ] **Step 7: Write the module factory**

Create `frontend/packages/forms/src/module.tsx`:

```tsx
import type { ChawpiModule } from '@chawpi/core'
import { FileText } from 'lucide-react'
import { formsMessages } from './i18n'

export const FORMS_MODULE_ID = 'forms'

export interface FormsModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's /builder/forms
  basePath?: string
}

export function formsModule(options: FormsModuleOptions = {}): ChawpiModule {
  return {
    id: FORMS_MODULE_ID,
    basePath: options.basePath ?? 'builder',
    routes: [{ id: 'builder', path: 'forms', lazy: () => import('./FormBuilderPage').then((m) => ({ default: m.FormBuilderPage })) }],
    // the builder group is core's; pages 10, forms 20, documents 30, views 40 as in sapgis's sidebar
    nav: [{ group: 'builder', labelKey: 'forms:nav.forms', order: 20, icon: FileText, route: 'builder' }],
    i18n: formsMessages
  }
}
```

Replace `frontend/packages/forms/src/index.ts` entirely with:

```ts
export { FORMS_MODULE_ID, formsModule, type FormsModuleOptions } from './module'
export { formsMessages } from './i18n'
export { FormBuilderPage } from './FormBuilderPage'
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `yarn workspace @chawpi/forms test`
Expected: PASS. Four files: module 4, i18n 2, boundaries 2, FormBuilderPage 3.

- [ ] **Step 9: Write the README (T5)**

Create `frontend/packages/forms/README.md`:

````markdown
# @chawpi/forms

The form builder: group an object's fields into titled sections and save the layout under a name. Core's `DynamicForm` and the page renderer's FORM component already draw stored forms, so this package only adds the screen that edits them.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```
yarn add @chawpi/core @chawpi/ui @chawpi/forms
```

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { formsModule } from '@chawpi/forms'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[formsModule()]} />
}
```

Tailwind: the app's stylesheet already has `@import "@chawpi/ui/theme.css"` and `@source "../node_modules/@chawpi"` (see `@chawpi/core`), which covers this package's classes too.

## What it adds

| Slot | Value |
|---|---|
| route `forms:builder` | `/builder/forms` → `FormBuilderPage` (lazy) |
| nav | "Formularios" / "Forms" in core's builder group, order 20 |
| i18n | namespace `forms` (es, en) |

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'builder'` | url prefix of the route: `formsModule({ basePath: 'config' })` serves `/config/forms` |

## Backend

It needs the chawpi forms backend module (`GET/POST /objects/{object}/forms`, `PUT/DELETE /objects/{object}/forms/{name}`). Saving or resetting a form invalidates the cached record pages, because pages embed forms by name. An absent backend module must answer 404.
````

- [ ] **Step 10: Format, lint, test, build**

Run:
```bash
yarn prettier --write frontend/packages/forms/src frontend/packages/forms/README.md
yarn workspace @chawpi/forms lint
yarn workspace @chawpi/forms test
yarn workspace @chawpi/forms build
git status --short
```
Expected:
- lint, test and build all pass;
- `frontend/packages/forms/dist/index.js` and `dist/index.d.ts` exist;
- `git status --short` lists only paths under `frontend/packages/forms/` (plus whatever other tasks left uncommitted), and nothing is committed.

### Task 9: `@chawpi/agent` — assistant

**Wave 1, runs in parallel with Tasks 2–8.** Write only inside `frontend/packages/agent/src/**` and `frontend/packages/agent/README.md`. Task 1 already wrote `package.json` (deps: `lucide-react`; peers: `@chawpi/core`, `@chawpi/ui`, react, react-dom, @tanstack/react-query, i18next, react-i18next, react-router), `tsconfig*.json`, `vite.config.ts`, `src/test/setup.ts` and a placeholder `src/index.ts`. Do not edit those except `src/index.ts`.

**Files** (sapgis sources are under `/Users/jorge/IdeaProjects/sapgis/frontend/src/features/assistant/`):
- Port: `AssistantPage.tsx` → `frontend/packages/agent/src/AssistantPage.tsx`
- Port: `AssistantPage.test.tsx` → `frontend/packages/agent/src/AssistantPage.test.tsx`
- Port: `api.ts` → `frontend/packages/agent/src/api.ts`
- Port: `transcript.ts` → `frontend/packages/agent/src/transcript.ts`
- Port: `transcript.test.ts` → `frontend/packages/agent/src/transcript.test.ts`
- Port: `types.ts` → `frontend/packages/agent/src/types.ts`
- Not ported: `i18n.ts`, a side-effect `addResourceBundle`. Its strings become `src/i18n.ts` (M3).
- Create: `frontend/packages/agent/src/module.tsx`
- Create: `frontend/packages/agent/src/i18n.ts`
- Modify (replace placeholder): `frontend/packages/agent/src/index.ts`
- Test: `frontend/packages/agent/src/module.test.tsx`
- Test: `frontend/packages/agent/src/i18n.test.ts`
- Test: `frontend/packages/agent/src/boundaries.test.ts`
- Create: `frontend/packages/agent/README.md`

**Interfaces:**
- Consumes from `@chawpi/core` (already exported): `PageHeader`, `ApiError` (same constructor as sapgis: `(status, message, violations = [])`), `api<T>(path, init?)`, type `ChawpiModule`; for tests `coreModule`, `createRegistry`, `createLinks`.
- From `@chawpi/ui`: `Button`, `Card`, `CardBody`, `Badge`, `Input`.
- From `@chawpi/testing` (tests only, through the vitest alias): `renderWithProviders`.
- REST (unchanged from sapgis `api.ts`):
  - `useAgentStatus()`: GET `/agent/status`, queryKey `['agent', 'status']`, `retry: false`;
  - `useAskAgent()`: POST `/agent/ask` with `{ question }`.
- Produces (public API, `src/index.ts`):
  - `AGENT_MODULE_ID = 'agent'`;
  - `interface AgentModuleOptions { basePath?: string }`;
  - `agentModule(options?: AgentModuleOptions): ChawpiModule` (id `agent`, basePath default `'automation'`, route `assistant` → `assistant` (lazy), nav item in core's `automation` group, order 40, `agent:nav.assistant`, icon `Sparkles`, i18n `agentMessages`);
  - `agentMessages`;
  - `AssistantPage`;
  - `useAgentStatus`, `useAskAgent`;
  - types `AgentAnswer`, `AgentStatus`, `AgentStep`.
  - Route key `agent:assistant`, default URL `/automation/assistant` (sapgis parity).

- [ ] **Step 1: Write the i18n bundle and its parity test (T4)**

Create `frontend/packages/agent/src/i18n.ts`. The `assistant.*` strings come verbatim from sapgis `features/assistant/i18n.ts`, with the rename rule applied (`SAPGIS` → `Chawpi`, in `emptyHint` and `disabledHint`). `nav.assistant` comes from `frontend/packages/core/src/i18n/locales/{es,en}/common.json`. Task 10 deletes the core copy.

```ts
// the assistant's own strings, loaded under the namespace 'agent'. key paths match sapgis, so the
// ported page keeps calling t('assistant.title').
export const agentMessages = {
  es: {
    nav: { assistant: 'Asistente' },
    assistant: {
      title: 'Asistente',
      scope: 'Responde sobre los datos de tu organización y solo ve lo que tu usuario tiene permiso de ver.',
      model: 'Modelo',
      placeholder: 'Pregunta sobre tus objetos, registros o geometrías…',
      send: 'Preguntar',
      sending: 'Consultando…',
      you: 'Tú',
      agent: 'Asistente',
      thinking: 'Consultando los datos…',
      emptyTitle: 'Todavía no has preguntado nada',
      emptyHint: 'El asistente consulta la API de Chawpi con tu sesión y muestra las herramientas que usó.',
      suggestionsTitle: 'Para empezar',
      suggestionObjects: '¿Qué objetos hay definidos y cuántos campos tiene cada uno?',
      suggestionRecords: '¿Cuántos registros hay en cada objeto?',
      suggestionGeometry: '¿Qué objetos tienen geometría y en qué SRID?',
      showSteps: 'Ver los pasos ({{n}})',
      hideSteps: 'Ocultar los pasos',
      stepsTitle: 'Herramientas usadas',
      tool: 'Herramienta',
      noArguments: 'Sin argumentos',
      noSteps: 'El asistente respondió sin consultar datos.',
      truncated: 'Respuesta incompleta',
      truncatedHint: 'El asistente alcanzó su límite de pasos y se detuvo. Revisa las herramientas usadas antes de dar el resultado por bueno.',
      failed: 'La consulta falló',
      disabled: 'El asistente no está configurado',
      disabledHint: 'Falta la configuración del modelo en el servidor. El resto de Chawpi sigue funcionando con normalidad.'
    }
  },
  en: {
    nav: { assistant: 'Assistant' },
    assistant: {
      title: 'Assistant',
      scope: 'It answers about your organization data and only sees what your user is allowed to see.',
      model: 'Model',
      placeholder: 'Ask about your objects, records or geometries…',
      send: 'Ask',
      sending: 'Asking…',
      you: 'You',
      agent: 'Assistant',
      thinking: 'Querying the data…',
      emptyTitle: 'You have not asked anything yet',
      emptyHint: 'The assistant queries the Chawpi API with your session and shows the tools it used.',
      suggestionsTitle: 'To get started',
      suggestionObjects: 'Which objects are defined and how many fields does each one have?',
      suggestionRecords: 'How many records are there in each object?',
      suggestionGeometry: 'Which objects have geometry and in which SRID?',
      showSteps: 'Show the steps ({{n}})',
      hideSteps: 'Hide the steps',
      stepsTitle: 'Tools used',
      tool: 'Tool',
      noArguments: 'No arguments',
      noSteps: 'The assistant answered without querying any data.',
      truncated: 'Incomplete answer',
      truncatedHint: 'The assistant hit its step limit and stopped. Check the tools it used before taking the result as final.',
      failed: 'The request failed',
      disabled: 'The assistant is not configured',
      disabledHint: 'The model configuration is missing on the server. The rest of Chawpi keeps working normally.'
    }
  }
}
```

Create `frontend/packages/agent/src/i18n.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { agentMessages } from './i18n'

function leaves(tree: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(tree)
    .flatMap(([key, value]) => (typeof value === 'object' && value !== null ? leaves(value as Record<string, unknown>, `${prefix}${key}.`) : [`${prefix}${key}`]))
    .sort()
}

describe('agent messages', () => {
  it('has the same keys in spanish and english', () => {
    expect(leaves(agentMessages.en)).toEqual(leaves(agentMessages.es))
  })

  it('names its nav entry and the strings the page draws', () => {
    expect(leaves(agentMessages.es)).toEqual(expect.arrayContaining(['nav.assistant', 'assistant.title', 'assistant.send', 'assistant.showSteps', 'assistant.disabledHint']))
  })

  it('carries no sapgis name', () => {
    expect(JSON.stringify(agentMessages)).not.toMatch(/sapgis/i)
  })
})
```

- [ ] **Step 2: Write the module wiring test (T2)**

Create `frontend/packages/agent/src/module.test.tsx`:

```tsx
import { coreModule, createLinks, createRegistry } from '@chawpi/core'
import { describe, expect, it } from 'vitest'
import { agentModule } from './module'

describe('agentModule', () => {
  it('registers next to core without a conflict', () => {
    expect(() => createRegistry([coreModule, agentModule()])).not.toThrow()
  })

  it('mounts its route under the default base path', () => {
    const links = createLinks(createRegistry([coreModule, agentModule()]))
    expect(links.to('agent:assistant')).toBe('/automation/assistant')
  })

  it('moves every route when the app picks another base path', () => {
    const links = createLinks(createRegistry([coreModule, agentModule({ basePath: 'x' })]))
    expect(links.to('agent:assistant')).toBe('/x/assistant')
  })

  it('puts its entry last in core automation group', () => {
    const automation = createRegistry([coreModule, agentModule()]).navGroups.find((group) => group.id === 'automation')
    expect(automation?.items).toEqual([expect.objectContaining({ labelKey: 'agent:nav.assistant', to: '/automation/assistant', order: 40 })])
  })
})
```

- [ ] **Step 3: Write the boundary test (T3)**

Create `frontend/packages/agent/src/boundaries.test.ts`:

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

// a module stays installable on its own: no other module package, no other module's heavy library.
// import.meta.dirname, not new URL(.., import.meta.url): vite rewrites the latter under jsdom
const SRC = import.meta.dirname
// agent owns no heavy library
const OWN = /^$/
const HEAVY = /^(maplibre-gl|terra-draw.*|@xyflow\/.+|@tiptap\/.+|@dnd-kit\/.+)$/

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function importsOf(file: string): string[] {
  return [...readFileSync(file, 'utf8').matchAll(/from '([^']+)'|import '([^']+)'/g)].map((match) => match[1] ?? match[2])
}

describe('agent boundaries', () => {
  it('imports no other module package and no heavy library it does not own', () => {
    const offenders = sources(SRC).flatMap((file) =>
      importsOf(file)
        .map((specifier) =>
          specifier
            .split('/')
            .slice(0, specifier.startsWith('@') ? 2 : 1)
            .join('/')
        )
        .filter((pkg) => (pkg.startsWith('@chawpi/') && pkg !== '@chawpi/core' && pkg !== '@chawpi/ui') || (HEAVY.test(pkg) && !OWN.test(pkg)))
        .map((pkg) => `${relative(SRC, file)} -> ${pkg}`)
    )
    expect(offenders).toEqual([])
  })

  // REST paths passed to api() are fine; what links must build is every in-app url handed to a
  // Link, an <a> or navigate()
  it('never spells an in-app url: links build them', () => {
    const offenders = sources(SRC).filter((file) => /(?:\bto=|\bhref=|navigate\()\{?\s*[`'"]\/(?!\/)/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })

  it('never registers strings by side effect: the module hands them to the registry', () => {
    const offenders = sources(SRC).filter((file) => /addResourceBundle|import '[^']*i18n'/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((file) => relative(SRC, file))).toEqual([])
  })
})
```

- [ ] **Step 4: Port the sapgis tests**

Run:
```bash
node frontend/tooling/port-from-sapgis.mjs features/assistant/transcript.test.ts frontend/packages/agent/src/transcript.test.ts
node frontend/tooling/port-from-sapgis.mjs features/assistant/AssistantPage.test.tsx frontend/packages/agent/src/AssistantPage.test.tsx
```
Expected: two `ported …` lines and no `MANUAL:` line. The script rewrites:
- `@/lib/api` → `@chawpi/core`;
- `@/test/render` → `@chawpi/testing`;
- `@/features/assistant/AssistantPage` → `./AssistantPage`;
- `@/features/assistant/types` → `./types`;
- `vi.mock('@/features/assistant/api', …)` → `vi.mock('./api', …)`.

`transcript.test.ts` needs nothing more.

In `AssistantPage.test.tsx`, load the module's namespace in every render (M3). Add the import under the existing ones:

```tsx
import { agentModule } from './module'
```

Then replace each of the 8 `renderWithProviders(<AssistantPage />)` calls with:

```tsx
renderWithProviders(<AssistantPage />, { modules: [agentModule()] })
```

A one-liner does it: `sed -i '' 's#renderWithProviders(<AssistantPage />)#renderWithProviders(<AssistantPage />, { modules: [agentModule()] })#' frontend/packages/agent/src/AssistantPage.test.tsx`. That is macOS sed; on GNU sed drop the `''`. Check with `grep -c "modules: \[agentModule()\]" frontend/packages/agent/src/AssistantPage.test.tsx`, which must print `8`.

- [ ] **Step 5: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/agent test`
Expected: FAIL with `Failed to resolve import "./module"`, `"./AssistantPage"` and `"./transcript"`. `i18n.test.ts` and `boundaries.test.ts` pass.

- [ ] **Step 6: Port the sources**

Run:
```bash
node frontend/tooling/port-from-sapgis.mjs features/assistant/types.ts frontend/packages/agent/src/types.ts
node frontend/tooling/port-from-sapgis.mjs features/assistant/api.ts frontend/packages/agent/src/api.ts
node frontend/tooling/port-from-sapgis.mjs features/assistant/transcript.ts frontend/packages/agent/src/transcript.ts
node frontend/tooling/port-from-sapgis.mjs features/assistant/AssistantPage.tsx frontend/packages/agent/src/AssistantPage.tsx
```
Expected: four `ported …` lines and no `MANUAL:` line. The script:
- drops `import '@/features/assistant/i18n'` (i18n side effect);
- rewrites `@/lib/api` and `@/components/layout/AppShell` → `@chawpi/core`;
- rewrites `@/components/ui/*` → `@chawpi/ui`;
- rewrites `@/features/assistant/{api,transcript,types}` → `./api`, `./transcript`, `./types`.

`types.ts`, `api.ts` and `transcript.ts` need no manual edit.

In `AssistantPage.tsx`, merge the `@chawpi/ui` imports into one line:

```tsx
import { Badge, Button, Card, CardBody, Input } from '@chawpi/ui'
```

The file has two `useTranslation()` calls, one in `AssistantPage` and one in `TurnView`. Change both (M3):

```tsx
// before
  const { t } = useTranslation()
// after: the assistant's strings live in its own namespace; common.loading stays core's
  const { t } = useTranslation(['agent', 'common'])
```

Nothing else changes. The page has no in-app link: the agent answers in text. Keep the markup, classes, `data-testid`s and the `nextId`/`end` refs exactly as sapgis has them.

- [ ] **Step 7: Write the module factory**

Create `frontend/packages/agent/src/module.tsx`:

```tsx
import type { ChawpiModule } from '@chawpi/core'
import { Sparkles } from 'lucide-react'
import { agentMessages } from './i18n'

export const AGENT_MODULE_ID = 'agent'

export interface AgentModuleOptions {
  // url prefix of every route of the module. default keeps sapgis's /automation/assistant
  basePath?: string
}

export function agentModule(options: AgentModuleOptions = {}): ChawpiModule {
  return {
    id: AGENT_MODULE_ID,
    basePath: options.basePath ?? 'automation',
    routes: [{ id: 'assistant', path: 'assistant', lazy: () => import('./AssistantPage').then((m) => ({ default: m.AssistantPage })) }],
    // the automation group is core's; workflows 10, rules 20, runs 30, assistant 40 as in sapgis's sidebar
    nav: [{ group: 'automation', labelKey: 'agent:nav.assistant', order: 40, icon: Sparkles, route: 'assistant' }],
    i18n: agentMessages
  }
}
```

Replace `frontend/packages/agent/src/index.ts` entirely with:

```ts
export { AGENT_MODULE_ID, agentModule, type AgentModuleOptions } from './module'
export { agentMessages } from './i18n'
export { AssistantPage } from './AssistantPage'
export { useAgentStatus, useAskAgent } from './api'
export type { AgentAnswer, AgentStatus, AgentStep } from './types'
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `yarn workspace @chawpi/agent test`
Expected: PASS. Five files: module 4, i18n 3, boundaries 3, transcript 12, AssistantPage 8.

- [ ] **Step 9: Write the README (T5)**

Create `frontend/packages/agent/README.md`:

````markdown
# @chawpi/agent

The assistant screen. Ask about your objects, records and geometries in plain language. The backend agent answers as the signed-in user (it sees only what that user may see) and lists the tools it used on the way.

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```
yarn add @chawpi/core @chawpi/ui @chawpi/agent
```

## Usage

```tsx
import { ChawpiApp } from '@chawpi/core'
import { agentModule } from '@chawpi/agent'

export function App() {
  return <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'My App' }} modules={[agentModule()]} />
}
```

Tailwind: the app's stylesheet already has `@import "@chawpi/ui/theme.css"` and `@source "../node_modules/@chawpi"` (see `@chawpi/core`), which covers this package's classes too.

## What it adds

| Slot | Value |
|---|---|
| route `agent:assistant` | `/automation/assistant` → `AssistantPage` (lazy) |
| nav | "Asistente" / "Assistant" in core's automation group, order 40 |
| i18n | namespace `agent` (es, en) |

It also exports `useAgentStatus()` and `useAskAgent()`, for an app that wants to ask from its own screens.

## Options

| Option | Default | Meaning |
|---|---|---|
| `basePath` | `'automation'` | url prefix of the route: `agentModule({ basePath: 'ai' })` serves `/ai/assistant` |

## Backend

It needs the chawpi agent backend module (`GET /agent/status`, `POST /agent/ask` with `{ question }`). If the status endpoint answers an error or `enabled: false`, the page says the assistant is not configured and blocks the input. The rest of the app is unaffected.
````

- [ ] **Step 10: Format, lint, test, build**

Run:
```bash
yarn prettier --write frontend/packages/agent/src frontend/packages/agent/README.md
yarn workspace @chawpi/agent lint
yarn workspace @chawpi/agent test
yarn workspace @chawpi/agent build
git status --short
```
Expected:
- lint, test and build all pass;
- `frontend/packages/agent/dist/index.js` and `dist/index.d.ts` exist;
- `git status --short` lists only paths under `frontend/packages/agent/` (plus whatever other tasks left uncommitted), and nothing is committed.

## Wave 2

### Task 10: Integration — drop the moved keys from core, cross-package smoke test, full verification

Runs alone, after Tasks 2–9 are all reviewed. It is the last shared edit.

**Files:**
- Modify: `frontend/packages/core/src/i18n/locales/es/common.json`, `frontend/packages/core/src/i18n/locales/en/common.json`
- Create: `frontend/packages/smoke/package.json`, `frontend/packages/smoke/tsconfig.json`, `frontend/packages/smoke/vite.config.ts`, `frontend/packages/smoke/src/test/setup.ts`, `frontend/packages/smoke/src/allModules.smoke.test.tsx`
- Verify only (no edit expected): `.github/workflows/publish.yml`, `frontend/packages/core/src/boundaries.test.ts`

**Interfaces:**
- Consumes:
  - `ChawpiApp` and `ChawpiAppProps` from `@chawpi/core`;
  - `mockFetch` and `FetchMock` from `@chawpi/testing`;
  - the eight factories from Tasks 2–9: `gisModule`, `workflowModule`, `automationModule`, `documentsModule`, `pagesModule`, `viewsModule`, `formsModule`, `agentModule`. Each is called with no arguments.
- Produces: a core `common.json` without module-only keys, and a private `@chawpi/smoke` workspace (never published, no `build` script) whose `test` mounts the whole platform.

- [ ] **Step 1: Write the smoke test**

Every module already exists at this point, so this test is expected to pass on its first run. Its job is to guard Step 3.

Create `frontend/packages/smoke/package.json`. Its `devDependencies` block is a verbatim copy of the one in `frontend/packages/core/package.json`:

```json
{
    "name": "@chawpi/smoke",
    "version": "0.1.0",
    "private": true,
    "description": "Cross-package smoke test: ChawpiApp with core and all eight modules. Never published.",
    "type": "module",
    "scripts": {
        "lint": "prettier --check src package.json tsconfig.json vite.config.ts && tsc --noEmit -p tsconfig.json",
        "test": "vitest run"
    },
    "devDependencies": {
        "@tanstack/react-query": "5.103.1",
        "@testing-library/dom": "10.4.1",
        "@testing-library/jest-dom": "7.0.1",
        "@testing-library/react": "16.3.3",
        "@testing-library/user-event": "14.6.1",
        "@types/node": "24.10.1",
        "@types/react": "19.3.0",
        "@types/react-dom": "19.3.0",
        "@vitejs/plugin-react": "6.1.1",
        "i18next": "26.4.2",
        "jsdom": "30.1.0",
        "prettier": "3.8.2",
        "react": "19.3.0",
        "react-dom": "19.3.0",
        "react-i18next": "17.0.14",
        "react-router": "8.4.0",
        "typescript": "5.9.3",
        "vite": "8.3.0",
        "vitest": "5.0.1"
    }
}
```

The `version` must equal core's current `version`. Check it with `node -p "require('./frontend/packages/core/package.json').version"`. `set-version.mjs` keeps it in lockstep from then on. It is not added to release-please, because it is never published.

Create `frontend/packages/smoke/tsconfig.json`:

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "noEmit": true,
        "types": ["node", "vitest/globals", "@testing-library/jest-dom"],
        "paths": {
            "@chawpi/ui": ["../ui/src/index.ts"],
            "@chawpi/core": ["../core/src/index.ts"],
            "@chawpi/testing": ["../testing/src/index.ts"],
            "@chawpi/gis": ["../gis/src/index.ts"],
            "@chawpi/workflow": ["../workflow/src/index.ts"],
            "@chawpi/automation": ["../automation/src/index.ts"],
            "@chawpi/documents": ["../documents/src/index.ts"],
            "@chawpi/pages": ["../pages/src/index.ts"],
            "@chawpi/views": ["../views/src/index.ts"],
            "@chawpi/forms": ["../forms/src/index.ts"],
            "@chawpi/agent": ["../agent/src/index.ts"]
        }
    },
    "include": ["src", "vite.config.ts"]
}
```

Create `frontend/packages/smoke/vite.config.ts`:

```ts
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))
const PACKAGES = ['ui', 'core', 'testing', 'gis', 'workflow', 'automation', 'documents', 'pages', 'views', 'forms', 'agent']

// test-only workspace: every package from its sources, nothing built first
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    alias: Object.fromEntries(PACKAGES.map((name) => [`@chawpi/${name}`, here(`../${name}/src/index.ts`)]))
  }
})
```

Create `frontend/packages/smoke/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

Create `frontend/packages/smoke/src/allModules.smoke.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { agentModule } from '@chawpi/agent'
import { automationModule } from '@chawpi/automation'
import { ChawpiApp, type ChawpiModule } from '@chawpi/core'
import { documentsModule } from '@chawpi/documents'
import { formsModule } from '@chawpi/forms'
import { gisModule } from '@chawpi/gis'
import { pagesModule } from '@chawpi/pages'
import { mockFetch, type FetchMock } from '@chawpi/testing'
import { viewsModule } from '@chawpi/views'
import { workflowModule } from '@chawpi/workflow'

// the smoke never draws a map; jsdom has no webgl, so the map libraries are stand-ins
vi.mock('maplibre-gl', () => ({ setWorkerUrl: () => {} }))
vi.mock('terra-draw', () => ({}))
vi.mock('terra-draw-maplibre-gl-adapter', () => ({}))

const ana = { id: 'u1', email: 'ana@chawpi.test', displayName: 'Ana', organizationId: 'o1', roles: ['ADMIN'] }
const allModules = (): ChawpiModule[] => [
  gisModule(),
  workflowModule(),
  automationModule(),
  documentsModule(),
  pagesModule(),
  viewsModule(),
  formsModule(),
  agentModule()
]

let fetch: FetchMock | null = null
beforeEach(() => {
  localStorage.clear()
  window.history.pushState({}, '', '/')
})
afterEach(() => fetch?.restore())

function signIn() {
  localStorage.setItem('smoke.token', 't')
  localStorage.setItem('smoke.user', JSON.stringify(ana))
  fetch = mockFetch([
    { path: '/auth/me/permissions', body: { admin: true, objects: {} } },
    { path: '/objects', body: [] }
  ])
}

describe('ChawpiApp with every module', () => {
  it('registers all eight modules next to core and lists each one in the sidebar', async () => {
    signIn()
    render(<ChawpiApp config={{ storagePrefix: 'smoke' }} modules={allModules()} />)

    expect(await screen.findByRole('heading', { name: 'Inicio' })).toBeInTheDocument()
    for (const group of ['GIS', 'App Builder', 'Automatización']) expect(screen.getByText(group)).toBeInTheDocument()
    for (const name of ['Mapas', 'Capas', 'Páginas', 'Formularios', 'Documentos', 'Vistas', 'Workflows', 'Reglas', 'Ejecuciones', 'Asistente']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument()
    }
  })

  it('draws the module strings in english too, from the modules own bundles', async () => {
    signIn()
    localStorage.setItem('smoke.lang', 'en')
    render(<ChawpiApp config={{ storagePrefix: 'smoke' }} modules={allModules()} />)

    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument()
    for (const name of ['Maps', 'Layers', 'Pages', 'Forms', 'Documents', 'Views', 'Workflows', 'Rules', 'Runs', 'Assistant']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument()
    }
  })

  it('opens a lazy module page from the sidebar at its sapgis url', async () => {
    signIn()
    render(<ChawpiApp config={{ storagePrefix: 'smoke' }} modules={allModules()} />)

    await userEvent.click(await screen.findByRole('link', { name: 'Vistas' }))
    expect(await screen.findByRole('heading', { name: 'Vistas' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/builder/views')
  })

  it('sends a signed-out visit to the bare print page to login', async () => {
    fetch = mockFetch([])
    window.history.pushState({}, '', '/documents/document-1/print')
    render(<ChawpiApp config={{ storagePrefix: 'smoke' }} modules={allModules()} />)

    expect(await screen.findByLabelText('Correo')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Link the workspace and run the smoke test**

Run: `yarn install`
Expected: success. `ls node_modules/@chawpi` now also lists `smoke`, and `yarn.lock` is unchanged (smoke has no dependency of its own).

Run: `yarn workspace @chawpi/smoke test`
Expected: PASS, 4 tests. Every module string still resolves: either from the module bundle, or from core's copy of the same key, which is not deleted yet.

These two cases have fixes that do not touch smoke's assertions:
- `vi.mock` fails because the gis package imports a named maplibre export at module load: add that export to the mock factory as a no-op class or function.
- A nav link is missing: the owning module's `nav` is wrong, so send the task back to its owner. Do not edit that module from this task.

- [ ] **Step 3: Remove the module-only keys from core's `common.json`**

First keep a copy of the current strings, so a key a module forgot can be recovered in Step 4. HEAD predates P4, so `git show` cannot recover them:

Run: `for l in es en; do cp frontend/packages/core/src/i18n/locales/$l/common.json "${TMPDIR:-/tmp}/p5-common.$l.json"; done`

Then run the script below from the repo root. It deletes the listed keys from both languages, **but first refuses** if any core source still names one of them:

```bash
node --input-type=module <<'EOF'
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const CORE = 'frontend/packages/core/src'
// moved to the modules (P4 ledger list + Tasks 2-9). a group name alone means the whole group,
// minus the keys in KEEP that core itself still draws
const DROP = [
  'nav.gis', 'nav.maps', 'nav.layers', 'nav.mapViews', 'nav.pages', 'nav.forms', 'nav.views',
  'nav.workflows', 'nav.rules', 'nav.runs', 'nav.assistant', 'nav.documents',
  'objects.geometry', 'objects.noGeometry', 'objects.crs', 'objects.addGeometry',
  'records.noGeometry', 'dashboard.geoObjects',
  'map', 'pages', 'views', 'forms', 'documents'
]
const KEEP = ['map.selectObject', 'pages.componentUnavailable', 'pages.tabs', 'views.pick', 'views.selector']

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? sources(path) : /\.tsx?$/.test(name) ? [path] : []
  })
}
function leaves(tree, prefix = '') {
  return Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'object' && value !== null ? leaves(value, `${prefix}${key}.`) : [`${prefix}${key}`])
}
const under = (key, root) => key === root || key.startsWith(`${root}.`)
const doomed = (key) => DROP.some((root) => under(key, root)) && !KEEP.some((root) => under(key, root))

const es = JSON.parse(readFileSync(`${CORE}/i18n/locales/es/common.json`, 'utf8'))
const removed = leaves(es).filter(doomed)
const code = sources(CORE).map((file) => [file, readFileSync(file, 'utf8')])
const stillUsed = removed.filter((key) => code.some(([, text]) => text.includes(`'${key}'`) || text.includes(`"${key}"`) || text.includes(`\`${key}`)))
if (stillUsed.length) {
  console.error('core still uses:', stillUsed.join(', '))
  process.exit(1)
}

function prune(tree, prefix = '') {
  for (const [key, value] of Object.entries(tree)) {
    const path = `${prefix}${key}`
    if (typeof value === 'object' && value !== null) {
      prune(value, `${path}.`)
      if (Object.keys(value).length === 0) delete tree[key]
    } else if (doomed(path)) delete tree[key]
  }
  return tree
}
for (const lang of ['es', 'en']) {
  const file = `${CORE}/i18n/locales/${lang}/common.json`
  writeFileSync(file, `${JSON.stringify(prune(JSON.parse(readFileSync(file, 'utf8'))), null, 4)}\n`)
}
console.log(`removed ${removed.length} keys per language`)
EOF
```

Expected: `removed <N> keys per language`, with N > 100. If it prints `core still uses: …` instead, stop and report `BLOCKED`. Either the list is wrong or core has a leftover module string, and a human decides which.

Run: `yarn prettier --write frontend/packages/core/src/i18n/locales/es/common.json frontend/packages/core/src/i18n/locales/en/common.json`

Run: `node -e "const f=(t,p='')=>Object.entries(t).flatMap(([k,v])=>typeof v==='object'?f(v,p+k+'.'):[p+k]).sort();const a=f(require('./frontend/packages/core/src/i18n/locales/es/common.json')),b=f(require('./frontend/packages/core/src/i18n/locales/en/common.json'));console.log(JSON.stringify(a)===JSON.stringify(b)?'parity ok':'PARITY BROKEN')"`
Expected: `parity ok`

- [ ] **Step 4: Re-run every test now that core no longer carries module strings**

Run: `yarn test`
Expected:
- PASS in every workspace (ui, core, testing, the eight modules, smoke);
- the smoke test's Spanish and English nav assertions now prove that each module's own bundle supplies its strings.

A failure that shows a raw key such as `layers.title` in the DOM means the owning module forgot to copy that key. The fix is in that module's `src/i18n.ts`, and this task may make that one edit because Wave 2 runs alone. Add the key to both `es` and `en` with the exact strings from `${TMPDIR:-/tmp}/p5-common.<lang>.json`. Never add it back to core.

- [ ] **Step 5: Root lint, build and the boundary checks**

Run: `yarn lint`
Expected: PASS in every workspace.

Run: `yarn build`
Expected:
- dependency order `ui → core → testing →` the eight modules (any order among them);
- smoke is skipped, because it has no `build` script;
- `frontend/packages/documents/dist/print.css` exists.

Run: `yarn workspace @chawpi/core test src/boundaries.test.ts`
Expected: PASS. Core still imports no heavy library and no `@chawpi/*` package except `@chawpi/ui`, and names no gis concept.

Run: `grep -rlE "from [\"'](maplibre-gl|terra-draw|@xyflow|@tiptap|@dnd-kit)" frontend/packages/core/dist frontend/packages/ui/dist frontend/packages/testing/dist; echo "exit=$?"`
Expected: no file listed, `exit=1`. The built foundation bundles no heavy library. (The build emits double-quoted `from "..."` specifiers, not single-quoted; the pattern matches either.)

Run: `for p in gis workflow documents pages; do echo "$p: $(grep -lE "from [\"'](maplibre-gl|terra-draw|@xyflow|@tiptap|@dnd-kit)" frontend/packages/$p/dist/*.js | wc -l)"; done; for p in automation views forms agent; do grep -lE "from [\"'](maplibre-gl|terra-draw|@xyflow|@tiptap|@dnd-kit)" frontend/packages/$p/dist/*.js && echo "LEAK in $p"; done; true`
Expected:
- each of gis, workflow, documents and pages reports at least 1, because each imports its own library (as an external import, not bundled);
- no `LEAK` line appears.

Run: `for p in gis workflow automation documents pages views forms agent; do grep -c "@chawpi/\(gis\|workflow\|automation\|documents\|pages\|views\|forms\|agent\)" frontend/packages/$p/dist/*.js; done`
Expected: `0` for every chunk of all eight (the entry `index.js` and any lazy chunk). No module imports another module.

Run: `yarn test:tooling && yarn format:check`
Expected: PASS.

- [ ] **Step 6: Verify the publish workflow needs no change**

Run: `sed -n '/Publish every public/,/done/p' .github/workflows/publish.yml`
Expected:
- the loop `for dir in frontend/packages/*/` publishes every package;
- it skips `private === true`, which covers `@chawpi/smoke`;
- `set-version.mjs` pins the `"*"` internal ranges, including the new modules' `@chawpi/core` and `@chawpi/ui` peers, before `npm publish`.

No edit. If the loop differs from this description, report it and leave the file alone.

Run: `node -e "const s=new Set(require('./release-please-config.json').packages['.']['extra-files'].map(e=>e.path));for(const p of ['gis','workflow','automation','documents','pages','views','forms','agent'])if(!s.has('frontend/packages/'+p+'/package.json'))console.log('missing',p)"`
Expected: no output.

- [ ] **Step 7: Confirm nothing was committed**

Run: `git status --short`
Expected: modified or untracked files only, and no new commits.
