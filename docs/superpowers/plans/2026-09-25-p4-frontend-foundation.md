# P4 — Frontend foundation (`@chawpi/ui`, `@chawpi/core`, `@chawpi/testing`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the sapgis React app's shared layer and core features into three published npm packages: `@chawpi/ui`, `@chawpi/core` and `@chawpi/testing`. An app gets the platform with `<ChawpiApp config modules />`. Later packages (P5) plug GIS, workflow, documents and builders in through a module registry, and core never imports them.

**Architecture:** Code is copied from `/Users/jorge/IdeaProjects/sapgis/frontend/src` by a small port script that rewrites `@/…` imports. A few seams are rewritten by hand:
- a `ChawpiModule` contract and a validated registry (routes, nav, field renderers, page components, history renderers, panels);
- a configurable api client;
- a per-app i18n instance;
- a route/link builder;
- auth with caller permissions.

Packages build with Vite library mode (ESM) plus `tsc --emitDeclarationOnly`. In dev and test, packages resolve each other's sources through tsconfig `paths` and vitest `test.alias`. Builds run in dependency order.

**Tech Stack:** React 19.3, react-router 8.4, @tanstack/react-query 5.103, i18next 26.4 + react-i18next 17.0, react-hook-form 7.88 + zod 4.6, radix-ui, class-variance-authority, tailwind-merge, lucide-react, Tailwind 4.3 (consumer side), Vite 8.3, vitest 5.0 + jsdom 30 + Testing Library, TypeScript 5.9.3, yarn 1.22 workspaces, Node 26.

**Spec:** `docs/superpowers/specs/2026-09-25-chawpi-libraries-design.md` (sections "Frontend design", "Commits, versioning and releases", "Tests").

## Global Constraints

- **NEVER run `git commit`, `git push` or `git stash`.** Leave every change uncommitted in the working tree. Every task ends with `git status --short` to confirm nothing was committed.
- sapgis (`/Users/jorge/IdeaProjects/sapgis`) is READ-ONLY. Copy from it, never edit it. Do not touch `backend/` (P1/P2 run in parallel).
- `release-please-config.json` and root `package.json` are shared repo-root files while P2 runs. Edit them in place (append to `extra-files`, replace only the `build` script); never rewrite the whole file.
- Formatting: the repo `.editorconfig` (TS/TSX 2 spaces, 160 columns) + root `.prettierrc.json`. Every task ends with `yarn prettier --write <touched files>` and then `yarn workspace <pkg> lint`, `yarn workspace <pkg> test`, `yarn workspace <pkg> build` for every package it touched. All must pass.
- Same UI and behaviour as sapgis: same markup and Tailwind classes, same texts, same REST calls (paths, verbs, bodies). Differences are allowed only where a ruling below says so.
- No new runtime dependencies. Versions are copied from `/Users/jorge/IdeaProjects/sapgis/frontend/package.json` exactly. The only new dev dependency is `@types/node` (already in sapgis) for the boundary test. No `vite-plugin-dts` (sapgis does not have it; `tsc` emits declarations).
- No hardcoded routes, URLs or storage keys in core. The only path literals allowed are `CORE_ROUTE_PATHS` (`src/links/links.ts`) and REST paths passed to `api()`. The only storage keys are the ones built by `storageKeys(prefix)`.
- `@chawpi/core` never imports `maplibre-gl`, `terra-draw*`, `@xyflow/*`, `@tiptap/*`, `@dnd-kit/*` or any other `@chawpi/*` package except `@chawpi/ui`. Its non-test sources never contain the words geometry/geometries/srid/EPSG/maplibre/GeoJson. Task 15's boundary test enforces this.
- Identifiers, comments and test names in English. Comments are caveman style: short, say why, never restate the code. Spanish UI strings and Spanish test fixtures copied from sapgis stay as they are.
- Rename rules for copied text: `sapgis`→`chawpi`, `Sapgis`→`Chawpi`, `SAPGIS`→`Chawpi` (the port script applies them).
- `@chawpi/*` packages never list another `@chawpi/*` package in `devDependencies`. `set-version.mjs` does not bump devDependencies, so a stale range there would break `yarn install` after a release. Sibling sources are reached through tsconfig `paths` and vitest `test.alias` instead.
- Internal `@chawpi/*` ranges in `dependencies`/`peerDependencies` are `"*"`; `publish.yml` runs `frontend/tooling/set-version.mjs` to pin exact versions before `npm publish`. Prerelease versions (e.g. `0.1.0-beta.1`) are never used: yarn 1 does not link a prerelease workspace through `"*"`.
- Commands run from the repo root `/Users/jorge/IdeaProjects/chawpi` unless a step says otherwise.

## Design rulings (binding for every task)

- **R1: package wiring.**
  - Each package has `src/index.ts`. `package.json` `exports` point at `dist/` (`types` + `import`).
  - core's and testing's `tsconfig.json` are used for lint and tests (`noEmit`). Its `paths` map `@chawpi/ui`, `@chawpi/core` and `@chawpi/testing` to sibling `src/index.ts`, and `vite.config.ts` `test.alias` does the same. So `yarn lint` and `yarn test` never need a build.
  - `tsconfig.build.json` has no `paths`, so declarations resolve siblings through `node_modules` (their `dist`).
  - The root `build` runs `frontend/tooling/run-ordered.mjs build`: dependencies first (`dependencies` + `peerDependencies`), `ui → core → testing`.
  - Core tests import `@chawpi/testing` through the alias only (no package.json entry), which keeps the build graph acyclic.
- **R2: module contract.** `ChawpiModule` (Task 4, full code) is the only way a module adds anything. The slots are:
  - `routes`, `navGroups`, `nav`;
  - `fieldRenderers`, `pageComponents`, `pageActions`;
  - `recordPanels`, `recordListActions`;
  - `historyRenderers`, `auditValueFormatters`, `auditFieldLabels`;
  - `dashboardCards`, `objectColumns`, `objectTileDetails`;
  - `objectFlags`, `recordQueryKeys`;
  - `providers`, `i18n`.

  `createRegistry(modules)` validates everything once and throws `RegistryError`. It rejects:
  - duplicate module ids;
  - a claim on a core field type, page component type or history operation;
  - two modules claiming the same type;
  - duplicate route keys or paths;
  - nav pointing at an unknown group or route.

  Modules are read once, at mount.
- **R3: GIS stays out of core types.**
  - `FieldType` and `PageComponentType` are open unions: `CoreFieldType | (string & {})`.
  - `FieldMeta`, `ObjectSummary`, `RecordItem` and `PageComponent` get an index signature `[extension: string]: unknown`. Module keys (`geometry`, `geometries`, a MAP's `geometry`) travel untouched, and core never reads them.
  - This matches the backend's flattened "sections" (P1 R5), so the JSON is identical. There is no `sections` wrapper on the client.
  - A module field type is **section-bound**: its `FieldRenderer.section` names the record key that holds its values (gis: `geometries`).
  - `DynamicForm` keeps those fields out of react-hook-form and zod, and draws `renderer.input` full width where the field sits.
  - It submits `{ attributes, [section]: values }`. Every registered section is always present, initialised from `record[section] ?? {}`. With gis this is byte-identical to sapgis (`geometries: {}` always sent). Without gis there is no `geometries` key.
- **R4: i18n.**
  - `createChawpiI18n({ languages, storageKey, modules })` builds one i18next instance per app (`i18next.createInstance()` + `initReactI18next`). No global side effects, no `addResourceBundle` at import time.
  - Core's namespace is `common` and is the default namespace, so every ported `t('x.y')` works unchanged.
  - Each module's resources load under the namespace `module.id`. Module code uses `useTranslation(moduleId)` or qualified keys (`'gis:nav.maps'`). Nav label keys from modules must be qualified.
  - The feature bundles sapgis added by side effect (history, admin) become plain exported objects, merged into `common` through `src/i18n/coreBundles.ts`.
  - P4 copies sapgis `locales/{es,en}/common.json` verbatim (renamed). Keys only P5 modules use move out in P5.
  - The language is persisted at `<prefix>.lang`. A stored language that is not in `config.languages` is ignored and the first configured language wins.
  - Pure helpers (history `changes.ts`) use `getI18n()` from react-i18next, which is the instance the app initialised.
- **R5: routing and links.**
  - A route is `{ id, path, component | lazy, chrome }`. Its key is `<moduleId>:<id>`. Its full path is `'/' + module.basePath + path`.
  - `chrome` is `shell` (inside AppShell, signed in; the default), `bare` (signed in, no shell, e.g. the print page) or `public` (e.g. login).
  - `ChawpiRoutes` mounts them. `*` redirects home, and signed-out visits redirect to login.
  - Core paths live only in `CORE_ROUTE_PATHS`. `useChawpiLinks()` gives `home/login/objects/newObject/editObject/relationships/records/newRecord/record` plus a generic `to(key, params, search)` and `has(key)` for module routes.
  - Every former literal (`/data/objects/...`, `/`, `/login`) goes through links. `/gis/map` and `/documents/:id/print` leave core (P5 builds them with `links.to('gis:map' …)`).
- **R6: api client.**
  - `createApiClient({ baseUrl, storagePrefix })` returns `{ baseUrl, keys, request, getToken, setToken }`. The keys are `<prefix>.token|user|lang`.
  - `ChawpiProviders` activates its client (`setActiveApiClient`) in a `useState` initialiser, so it runs before any child effect. The module-level `api()`, `getToken()` and `setToken()` delegate to the active client, so ported code and P5 modules keep calling `api()`.
  - This means one `ChawpiApp` per page, and it is documented.
- **R7: auth.**
  - `AuthProvider` and `useAuth` move to core (this breaks app↔layout). They keep sapgis sign-in/sign-out.
  - They add `permissions` (GET `/api/auth/me/permissions`, `{ admin, objects: { [object]: actions[] } }`), `isAdmin` and `can(object, action)`.
  - Nav items may declare `visible(permissions)`. Core's own nav items do not, because sapgis shows them to everyone.
  - `initialUser` and `initialPermissions` props exist for tests. When given, nothing is read or fetched.
- **R8: optional backend modules.** Pages, views and forms are separate backend modules (P2). Core keeps calling their read endpoints (same API), and:
  - Views: a 404 or empty list already falls back to `fallbackView` (sapgis code).
  - Pages: a failed `/objects/{o}/pages/record-detail` makes `RecordDetailPage` draw `fallbackPage(definition, sides)`. That is one region holding FORM (all fields), one RELATED_LIST per relationship and HISTORY.
  - Pages' `ROW_CLASS`/`regionStyle` move into core (`components/page-renderer/layout.ts`). P5's page builder imports them from core.
- **R9: cycles broken.**
  - history↔documents: `historyRenderers['ISSUE']`, so `IssuedDocumentLink` goes to P5 documents.
  - page-renderer→features: `pageComponents` (MAP, WORKFLOW) and `pageActions` (TRANSITION), with pages layout helpers in core.
  - app↔layout: auth is in core.
  - objects→workflows: `objectFlags` hook (workflow answers `{ workflow: true }`).
  - queries→gis: `recordQueryKeys` (gis answers `[['features', object]]`).
  - Dashboard/Objects/RecordList geometry bits become `dashboardCards`, `objectColumns`, `objectTileDetails` and `recordListActions`.
- **R10: not ported in P4 (P5 owns them, do not copy):**
  - `components/map/*`, `lib/geo.ts`, `lib/wms.ts`, `features/{map,layers,workflows,automations,documents,pages,views/ViewBuilderPage.tsx,forms,assistant}`;
  - `features/history/IssuedDocumentLink.tsx` (+ test), `components/page-renderer/ActionButton.test.tsx`'s TRANSITION case;
  - the document print styles in `index.css`;
  - the query hooks `useFeatures`, `useDocumentTypes`, `useSaveDocumentType`, `useDeleteDocumentType`, `useRecordDocuments`, `useIssueDocument`;
  - `useIssuedDocument` from history api;
  - the document/GIS types (`GeometryType`, `GeometryMeta`, `GeoJsonGeometry`, `Feature`, `FeatureCollection`, `TemplateNode`, `DocumentType*`, `RelatedTableSnapshot`, `DocumentSnapshot`, `SapDocument`).
- **R11: tests.**
  - Every sapgis test of ported code is ported with the port script (imports, `vi.mock` paths, renames).
  - Cases that exercised GIS or documents through core are rewritten against a fake module (`SKETCH` field type in section `sketches`, fake ISSUE renderer, fake `NOTE` page component). The real GIS/document cases move to P5.
  - `renderWithProviders` now mounts the full provider stack (registry, api client, i18n `es`, auth with a signed-in admin), so tests that mocked `@/app/auth` pass `user` instead.

## Review Focus

1. **Registry conflicts at startup.** Two modules claiming one field/page type, a module claiming a core type, duplicate module ids or route paths, or nav pointing at a missing route. `createRegistry` must throw a `RegistryError` naming both parties before anything renders. A silent last-one-wins must not happen. Pinned in Task 4 (`src/registry/createRegistry.test.tsx`).
2. **Core-only app (no modules, no pages/views backend).**
   - The record list works on `fallbackView`.
   - The detail page draws `fallbackPage` on a 404.
   - Saving sends `{ attributes }` without a `geometries` key.
   - The AppShell shows no empty nav groups.

   Pinned in Task 9 (DynamicForm payload), Task 12 (RecordDetailPage 404 fallback), Task 8 (empty groups hidden).
3. **Two apps / two prefixes on one origin.**
   - A token saved under prefix `a` is not sent by a client with prefix `b`.
   - A 401 clears only its own prefix.
   - `apiBaseUrl` with a trailing slash does not produce `//`.

   Pinned in Task 3 (`client.test.ts`).
4. **Language edge cases.**
   - A stored language not in `config.languages` falls back to the first configured one.
   - The toggle cycles through the configured languages only.
   - A single-language app shows no toggle.

   Pinned in Task 5 (`createI18n.test.ts`) and Task 8 (`AppShell.test.tsx`).
5. **Deep links and odd params.**
   - Signed out on a module route: redirected to login.
   - An unknown path: sent home.
   - Record ids with reserved characters are percent-encoded in links.
   - A missing param throws instead of producing `/data/objects/undefined/records`.

   Pinned in Task 4 (`links.test.ts`) and Task 15 (`ChawpiApp.smoke.test.tsx`).

## File structure

```
frontend/tooling/
  run-ordered.mjs (+ .test.mjs)        dependency-ordered `yarn run <script>` over workspaces
  port-from-sapgis.mjs (+ .test.mjs)   copy one sapgis file, rewrite @/ imports, rename, drop i18n side-effect imports
frontend/packages/ui/                  @chawpi/ui
  src/{index.ts, cn.ts, button.tsx, card.tsx, dialog.tsx, input.tsx, label.tsx, select.tsx, table.tsx, tabs.tsx, theme.css}
frontend/packages/core/                @chawpi/core
  src/index.ts                         public api (grows per task)
  src/app/{config.ts, context.tsx, ChawpiProviders.tsx, ChawpiRoutes.tsx, ChawpiApp.tsx, AuthGate.tsx, coreModule.ts}
  src/api/client.ts
  src/auth/{AuthProvider.tsx, LoginPage.tsx}
  src/i18n/{createI18n.ts, coreBundles.ts, locales/{es,en}/common.json}
  src/registry/{contract.ts, createRegistry.ts, hooks.ts}
  src/links/{paths.ts, links.ts}
  src/types/{metadata.ts, audit.ts, auth.ts}
  src/lib/metadata-to-zod.ts
  src/queries/{index.ts, objects.ts, records.ts, relationships.ts, pages.ts, views.ts, forms.ts, moduleQueries.ts}
  src/shell/{AppShell.tsx, PageHeader.tsx}
  src/components/{data-table, dynamic-form(+fields), related, page-renderer}
  src/features/{dashboard, objects, relationships, records, history, admin}
  src/test/setup.ts, src/boundaries.test.ts
frontend/packages/testing/             @chawpi/testing
  src/{index.ts, render.tsx, fetch.ts}
```

---

### Task 1: Workspace tooling — ordered runner and sapgis port script

**Files:**
- Create: `frontend/tooling/run-ordered.mjs`, `frontend/tooling/run-ordered.test.mjs`
- Create: `frontend/tooling/port-from-sapgis.mjs`, `frontend/tooling/port-from-sapgis.test.mjs`
- Modify: `package.json` (root) — `build` script

**Interfaces:**
- Consumes: root `package.json` `workspaces` globs (`frontend/packages/*`, `examples/*/web`).
- Produces:
  - `node frontend/tooling/run-ordered.mjs <script>` runs `yarn run <script>` in every workspace that has the script, `@chawpi` dependencies first. It exports `expandWorkspaces(root, patterns): string[]`, `readWorkspaces(root, patterns): {name, dir, manifest}[]` and `buildOrder(packages): {name, dir, manifest}[]`, which throws `workspace cycle: a -> b -> a`.
  - `node frontend/tooling/port-from-sapgis.mjs <path under sapgis/frontend/src> <repo-relative target>` exports `mapSpecifier(specifier, targetFile): string | null` and `portSource(source, targetFile): { text, unmapped: string[] }`. It prints `MANUAL: <target> still imports <specifier>` for each specifier it could not map. Every later task uses it.

- [ ] **Step 1: Write the failing tests**

`frontend/tooling/run-ordered.test.mjs`:

```js
// fixture workspaces on disk: the order must follow dependencies + peerDependencies, never
// devDependencies, and a real cycle must stop the build instead of looping.
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { buildOrder, expandWorkspaces, readWorkspaces } from './run-ordered.mjs'

function writePackage(root, dir, manifest) {
  mkdirSync(join(root, dir), { recursive: true })
  writeFileSync(join(root, dir, 'package.json'), JSON.stringify(manifest))
}

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'run-ordered-'))
  writePackage(root, 'frontend/packages/testing', { name: '@chawpi/testing', peerDependencies: { '@chawpi/core': '*' } })
  writePackage(root, 'frontend/packages/core', { name: '@chawpi/core', dependencies: { '@chawpi/ui': '*', react: '19.3.0' } })
  writePackage(root, 'frontend/packages/ui', { name: '@chawpi/ui', devDependencies: { '@chawpi/testing': '*' } })
  writePackage(root, 'examples/simple-sample/web', { name: 'simple-sample-web', dependencies: { '@chawpi/core': '*' } })
  // a folder without package.json is not a workspace
  mkdirSync(join(root, 'examples/gis-sample/web'), { recursive: true })
  return root
}

test('expands the workspace globs and skips folders without package.json', () => {
  const root = fixture()
  try {
    const dirs = expandWorkspaces(root, ['frontend/packages/*', 'examples/*/web']).map((dir) => dir.slice(root.length + 1))
    assert.deepEqual(dirs.sort(), ['examples/simple-sample/web', 'frontend/packages/core', 'frontend/packages/testing', 'frontend/packages/ui'])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('orders dependencies first and ignores devDependencies', () => {
  const root = fixture()
  try {
    const order = buildOrder(readWorkspaces(root, ['frontend/packages/*', 'examples/*/web'])).map((pkg) => pkg.name)
    assert.ok(order.indexOf('@chawpi/ui') < order.indexOf('@chawpi/core'))
    assert.ok(order.indexOf('@chawpi/core') < order.indexOf('@chawpi/testing'))
    assert.ok(order.indexOf('@chawpi/core') < order.indexOf('simple-sample-web'))
    assert.equal(order.length, 4)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('refuses a dependency cycle', () => {
  const packages = [
    { name: 'a', dir: 'a', manifest: { name: 'a', dependencies: { b: '1' } } },
    { name: 'b', dir: 'b', manifest: { name: 'b', peerDependencies: { a: '1' } } }
  ]
  assert.throws(() => buildOrder(packages), /workspace cycle: a -> b -> a/)
})
```

`frontend/tooling/port-from-sapgis.test.mjs`:

```js
import assert from 'node:assert/strict'
import { join } from 'node:path'
import { test } from 'node:test'

import { REPO_ROOT, mapSpecifier, portSource } from './port-from-sapgis.mjs'

const inCore = join(REPO_ROOT, 'frontend/packages/core/src/features/objects/ObjectsPage.tsx')
const inGis = join(REPO_ROOT, 'frontend/packages/gis/src/MapPage.tsx')

test('maps sapgis locations to paths relative to the target inside core', () => {
  assert.equal(mapSpecifier('@/lib/queries', inCore), '../../queries')
  assert.equal(mapSpecifier('@/types/metadata', inCore), '../../types/metadata')
  assert.equal(mapSpecifier('@/lib/api', inCore), '../../api/client')
  assert.equal(mapSpecifier('@/app/auth', inCore), '../../auth/AuthProvider')
  assert.equal(mapSpecifier('@/components/layout/AppShell', inCore), '../../shell/PageHeader')
  assert.equal(mapSpecifier('@/features/history/types', inCore), '../../types/audit')
  assert.equal(mapSpecifier('@/features/objects/objectDraft', inCore), './objectDraft')
  assert.equal(mapSpecifier('@/components/dynamic-form/DynamicForm', inCore), '../../components/dynamic-form/DynamicForm')
})

test('maps shared code to packages', () => {
  assert.equal(mapSpecifier('@/components/ui/button', inCore), '@chawpi/ui')
  assert.equal(mapSpecifier('@/lib/utils', inCore), '@chawpi/ui')
  assert.equal(mapSpecifier('@/test/render', inCore), '@chawpi/testing')
  assert.equal(mapSpecifier('@/lib/queries', inGis), '@chawpi/core')
})

test('leaves what it cannot place for a human', () => {
  assert.equal(mapSpecifier('@/lib/geo', inCore), null)
  const { text, unmapped } = portSource("import { featureIdOf } from '@/lib/geo'\n", inCore)
  assert.equal(text, "import { featureIdOf } from '@/lib/geo'\n")
  assert.deepEqual(unmapped, ['@/lib/geo'])
})

test('rewrites vi.mock paths, drops i18n side-effect imports and renames sapgis', () => {
  const source = [
    "import { Button } from '@/components/ui/button'",
    "import '@/features/history/i18n'",
    "import './i18n'",
    "vi.mock('@/lib/queries', async (importOriginal) => importOriginal<typeof import('@/lib/queries')>())",
    "const email = 'ana@sapgis.test' // SAPGIS Sapgis",
    ''
  ].join('\n')
  const { text, unmapped } = portSource(source, inCore)
  assert.deepEqual(unmapped, [])
  assert.equal(
    text,
    [
      "import { Button } from '@chawpi/ui'",
      "vi.mock('../../queries', async (importOriginal) => importOriginal<typeof import('../../queries')>())",
      "const email = 'ana@chawpi.test' // Chawpi Chawpi",
      ''
    ].join('\n')
  )
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `node --test frontend/tooling/run-ordered.test.mjs frontend/tooling/port-from-sapgis.test.mjs`
Expected: FAIL with `Cannot find module '.../run-ordered.mjs'` (and the same for the port script).

- [ ] **Step 3: Write `frontend/tooling/run-ordered.mjs`**

```js
#!/usr/bin/env node
// runs one package script in every workspace, dependencies first. yarn 1 `workspaces run` goes in
// folder order, but a package's declaration build reads its @chawpi deps' dist, so order matters.
// usage: node frontend/tooling/run-ordered.mjs <script>
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
// devDependencies never order a build: core tests reach @chawpi/testing through an alias
const ORDER_FIELDS = ['dependencies', 'peerDependencies']

function subdirs(dir) {
  try {
    return readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => join(dir, entry.name))
  } catch {
    return []
  }
}

// only `*` segments, which is all the root package.json uses
export function expandWorkspaces(root, patterns) {
  const found = []
  for (const pattern of patterns) {
    let current = [root]
    for (const segment of pattern.split('/')) {
      current = current.flatMap((base) => (segment === '*' ? subdirs(base) : existsSync(join(base, segment)) ? [join(base, segment)] : []))
    }
    found.push(...current.filter((dir) => existsSync(join(dir, 'package.json'))))
  }
  return found
}

export function readWorkspaces(root, patterns) {
  return expandWorkspaces(root, patterns).map((dir) => {
    const manifest = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
    return { name: manifest.name, dir, manifest }
  })
}

// depth-first, alphabetical among equals so the order is stable run to run
export function buildOrder(packages) {
  const byName = new Map(packages.map((pkg) => [pkg.name, pkg]))
  const state = new Map()
  const order = []

  const visit = (pkg, trail) => {
    if (state.get(pkg.name) === 'done') return
    if (state.get(pkg.name) === 'visiting') throw new Error(`workspace cycle: ${[...trail, pkg.name].join(' -> ')}`)
    state.set(pkg.name, 'visiting')
    for (const field of ORDER_FIELDS) {
      for (const dep of Object.keys(pkg.manifest[field] ?? {}).sort()) {
        const target = byName.get(dep)
        if (target) visit(target, [...trail, pkg.name])
      }
    }
    state.set(pkg.name, 'done')
    order.push(pkg)
  }

  for (const pkg of [...packages].sort((a, b) => a.name.localeCompare(b.name))) visit(pkg, [])
  return order
}

function main([script]) {
  if (!script) {
    console.error('usage: run-ordered.mjs <script>')
    process.exit(1)
  }
  const root = JSON.parse(readFileSync(join(REPO_ROOT, 'package.json'), 'utf8'))
  for (const pkg of buildOrder(readWorkspaces(REPO_ROOT, root.workspaces))) {
    if (!pkg.manifest.scripts?.[script]) continue
    console.log(`\n> ${pkg.name}: yarn run ${script}`)
    execFileSync('yarn', ['run', script], { cwd: pkg.dir, stdio: 'inherit' })
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main(process.argv.slice(2))
```

- [ ] **Step 4: Write `frontend/tooling/port-from-sapgis.mjs`**

```js
#!/usr/bin/env node
// copies one sapgis frontend file into a chawpi package and rewrites what moved: `@/` specifiers
// (imports, vi.mock, typeof import), sapgis names, and the i18n side-effect imports the registry
// replaced. anything it cannot place is left alone and reported as MANUAL.
// usage: node frontend/tooling/port-from-sapgis.mjs <path under sapgis/frontend/src> <repo-relative target>
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const SAPGIS_SRC = process.env.SAPGIS_SRC ?? resolve(REPO_ROOT, '../sapgis/frontend/src')
const CORE_SRC = join(REPO_ROOT, 'frontend/packages/core/src')

// shared code that became its own package
const PACKAGE_MAP = [
  [/^@\/components\/ui\/.+$/, '@chawpi/ui'],
  [/^@\/lib\/utils$/, '@chawpi/ui'],
  [/^@\/test\/render$/, '@chawpi/testing']
]

// sapgis location -> location under core/src. first match wins, so specific rules go first.
const CORE_MAP = [
  [/^@\/lib\/api$/, 'api/client'],
  [/^@\/lib\/queries$/, 'queries'],
  [/^@\/lib\/metadata-to-zod$/, 'lib/metadata-to-zod'],
  [/^@\/types\/metadata$/, 'types/metadata'],
  [/^@\/app\/auth$/, 'auth/AuthProvider'],
  [/^@\/components\/layout\/AppShell$/, 'shell/PageHeader'],
  [/^@\/features\/history\/types$/, 'types/audit'],
  [/^@\/features\/views\/viewColumns$/, 'features/records/viewColumns'],
  [/^@\/features\/pages\/builder\/templates$/, 'components/page-renderer/layout'],
  [/^@\/components\/(.+)$/, 'components/$1'],
  [/^@\/features\/(.+)$/, 'features/$1']
]

// translation bundles load through the registry now, never by importing a file for its side effect
const I18N_SIDE_EFFECT = /^import '(?:@\/lib\/i18n|@\/features\/[^']+\/i18n|\.\/i18n)'\n/gm
const RENAMES = [
  [/sapgis/g, 'chawpi'],
  [/Sapgis/g, 'Chawpi'],
  [/SAPGIS/g, 'Chawpi']
]

export function mapSpecifier(specifier, targetFile) {
  for (const [pattern, replacement] of PACKAGE_MAP) if (pattern.test(specifier)) return replacement
  const insideCore = !relative(CORE_SRC, targetFile).startsWith('..')
  for (const [pattern, replacement] of CORE_MAP) {
    if (!pattern.test(specifier)) continue
    // outside core (P5 packages) everything core owns comes from its public api
    if (!insideCore) return '@chawpi/core'
    const path = relative(dirname(targetFile), join(CORE_SRC, specifier.replace(pattern, replacement)))
    return path.startsWith('.') ? path : `./${path}`
  }
  return null
}

export function portSource(source, targetFile) {
  const unmapped = []
  let text = source.replace(I18N_SIDE_EFFECT, '')
  text = text.replace(/(['"])(@\/[^'"]+)\1/g, (whole, quote, specifier) => {
    const mapped = mapSpecifier(specifier, targetFile)
    if (mapped === null) {
      unmapped.push(specifier)
      return whole
    }
    return `${quote}${mapped}${quote}`
  })
  for (const [pattern, replacement] of RENAMES) text = text.replace(pattern, replacement)
  return { text, unmapped }
}

function main([from, to]) {
  if (!from || !to) {
    console.error('usage: port-from-sapgis.mjs <path under sapgis/frontend/src> <repo-relative target>')
    process.exit(1)
  }
  const target = resolve(REPO_ROOT, to)
  const { text, unmapped } = portSource(readFileSync(join(SAPGIS_SRC, from), 'utf8'), target)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, text)
  for (const specifier of unmapped) console.warn(`MANUAL: ${to} still imports ${specifier}`)
  console.log(`ported ${from} -> ${to}`)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main(process.argv.slice(2))
```

- [ ] **Step 5: Point the root `build` at the ordered runner**

In the root `package.json`, replace the `build` script only (`lint` and `test` keep `yarn workspaces run`; they need no build because of the source aliases):

```json
        "build": "node frontend/tooling/run-ordered.mjs build"
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `yarn test:tooling`
Expected: PASS, including the existing `set-version` tests and the 7 new ones.

- [ ] **Step 7: Format, check, confirm nothing was committed**

```bash
yarn prettier --write frontend/tooling package.json
yarn format:check
yarn build   # no packages yet: prints nothing and exits 0
git status --short
```
Expected: format check clean, build exits 0, `git status` lists the new/modified files as uncommitted.

---

### Task 2: `@chawpi/ui` — primitives, `cn`, `theme.css`

**Files:**
- Create: `frontend/packages/ui/{package.json, tsconfig.json, tsconfig.build.json, vite.config.ts, README.md}`
- Create: `frontend/packages/ui/src/{index.ts, theme.css, test/setup.ts}`
- Create (ported): `frontend/packages/ui/src/{cn.ts, button.tsx, card.tsx, dialog.tsx, input.tsx, label.tsx, select.tsx, table.tsx, tabs.tsx}`
- Test: `frontend/packages/ui/src/cn.test.ts`, `frontend/packages/ui/src/button.test.tsx`
- Modify: `release-please-config.json`
- Delete: `frontend/packages/.gitkeep`

**Interfaces:**
- Consumes: `frontend/tooling/port-from-sapgis.mjs` (Task 1), `frontend/tsconfig.base.json` (P0).
- Produces: package `@chawpi/ui` exporting `cn`, `Button`, `Card`, `CardHeader`, `CardTitle`, `CardBody`, `Dialog`, `DialogTrigger`, `DialogTitle`, `DialogDescription`, `DialogContent`, `Input`, `Textarea`, `Label`, `Select`, `SelectValue`, `SelectTrigger`, `SelectContent`, `SelectItem`, `Table`, `Th`, `Td`, `Badge`, `Tabs`, `type TabSpec`, and the stylesheet `@chawpi/ui/theme.css`. Every later package copies this package's config layout: `package.json` scripts, the two tsconfigs and `vite.config.ts`.

- [ ] **Step 1: Package manifest** — `frontend/packages/ui/package.json`

```json
{
    "name": "@chawpi/ui",
    "version": "0.1.0",
    "description": "Chawpi UI primitives, cn() and the Tailwind 4 theme tokens",
    "type": "module",
    "files": ["dist", "README.md"],
    "sideEffects": ["**/*.css"],
    "main": "./dist/index.js",
    "module": "./dist/index.js",
    "types": "./dist/index.d.ts",
    "exports": {
        ".": {
            "types": "./dist/index.d.ts",
            "import": "./dist/index.js"
        },
        "./theme.css": "./dist/theme.css",
        "./package.json": "./package.json"
    },
    "publishConfig": {
        "registry": "https://npm.pkg.github.com"
    },
    "scripts": {
        "lint": "prettier --check src package.json tsconfig.json tsconfig.build.json vite.config.ts && tsc --noEmit -p tsconfig.json",
        "test": "vitest run",
        "build": "vite build && tsc -p tsconfig.build.json && cp src/theme.css dist/theme.css"
    },
    "dependencies": {
        "@radix-ui/react-dialog": "1.1.23",
        "@radix-ui/react-label": "2.1.15",
        "@radix-ui/react-select": "2.3.7",
        "@radix-ui/react-slot": "1.3.3",
        "class-variance-authority": "0.7.1",
        "clsx": "2.1.1",
        "lucide-react": "1.47.0",
        "tailwind-merge": "3.7.0"
    },
    "peerDependencies": {
        "react": "^19.3.0",
        "react-dom": "^19.3.0",
        "react-i18next": "^17.0.14"
    },
    "devDependencies": {
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
        "typescript": "5.9.3",
        "vite": "8.3.0",
        "vitest": "5.0.1"
    }
}
```

`lint` lists its paths explicitly because prettier reads `.prettierignore` only from the cwd. A bare `.` would check `dist/`.

- [ ] **Step 2: TypeScript and Vite config**

`frontend/packages/ui/tsconfig.json` (lint + tests, never emits):

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "noEmit": true,
        "types": ["node", "vitest/globals", "@testing-library/jest-dom"]
    },
    "include": ["src", "vite.config.ts"]
}
```

`frontend/packages/ui/tsconfig.build.json` (declarations only):

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "declaration": true,
        "emitDeclarationOnly": true,
        "outDir": "dist",
        "rootDir": "src",
        "types": []
    },
    "include": ["src"],
    "exclude": ["src/**/*.test.ts", "src/**/*.test.tsx", "src/test"]
}
```

`frontend/packages/ui/vite.config.ts`:

```ts
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import pkg from './package.json' with { type: 'json' }

// every dependency stays an import in dist: the app's bundler resolves and dedupes it
const external = [...Object.keys(pkg.dependencies), ...Object.keys(pkg.peerDependencies)]

export default defineConfig({
  plugins: [react()],
  build: {
    lib: { entry: fileURLToPath(new URL('./src/index.ts', import.meta.url)), formats: ['es'], fileName: 'index' },
    rolldownOptions: {
      external: (id: string) => external.some((dep) => id === dep || id.startsWith(`${dep}/`))
    },
    sourcemap: true
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts'
  }
})
```

`frontend/packages/ui/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

- [ ] **Step 3: Port the primitives**

```bash
P=frontend/packages/ui/src
node frontend/tooling/port-from-sapgis.mjs lib/utils.ts $P/cn.ts
for f in button card dialog input label select table tabs; do
  node frontend/tooling/port-from-sapgis.mjs components/ui/$f.tsx $P/$f.tsx
done
# inside ui itself cn is a sibling file, not the package
sed -i '' "s#from '@chawpi/ui'#from './cn'#" $P/*.tsx
grep -n "@chawpi\|@/" $P/*.ts $P/*.tsx || echo "imports clean"
```
Expected: `imports clean`. No other edits: the files stay byte-identical to sapgis apart from the `cn` import.

- [ ] **Step 4: Write `frontend/packages/ui/src/theme.css`**

Copy `@theme { … }` and `@layer base { … }` from `/Users/jorge/IdeaProjects/sapgis/frontend/src/index.css` (lines 3–37) verbatim, under this header. Do NOT copy `@import 'tailwindcss'`, since the app imports Tailwind once itself. Do NOT copy the `@page`/`.document-sheet` print rules, which belong to `@chawpi/documents` in P5.

```css
/* chawpi design tokens. the app imports tailwind first, then this file, and adds
   `@source` over node_modules/@chawpi so the classes used inside the packages get generated. */
@theme {
    --color-surface: oklch(99% 0.002 260);
    --color-surface-muted: oklch(97% 0.004 260);
    --color-border: oklch(91% 0.006 260);
    --color-ink: oklch(24% 0.02 265);
    --color-ink-muted: oklch(52% 0.015 265);
    --color-brand: oklch(52% 0.16 262);
    --color-brand-strong: oklch(44% 0.17 262);
    --color-brand-soft: oklch(96% 0.02 262);
    --color-shell: oklch(24% 0.03 265);
    --color-shell-muted: oklch(72% 0.02 265);
    --color-danger: oklch(55% 0.19 25);
    --color-success: oklch(58% 0.13 155);
    --radius-card: 0.75rem;
}

@layer base {
    html,
    body,
    #root {
        height: 100%;
    }

    body {
        background-color: var(--color-surface-muted);
        color: var(--color-ink);
        font-feature-settings: 'cv02', 'cv03', 'cv04';
    }

    *:focus-visible {
        outline: 2px solid var(--color-brand);
        outline-offset: 2px;
    }
}
```

- [ ] **Step 5: Public api** — `frontend/packages/ui/src/index.ts`

```ts
export { cn } from './cn'
export { Button } from './button'
export { Card, CardBody, CardHeader, CardTitle } from './card'
export { Dialog, DialogContent, DialogDescription, DialogTitle, DialogTrigger } from './dialog'
export { Input, Textarea } from './input'
export { Label } from './label'
export { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './select'
export { Badge, Table, Td, Th } from './table'
export { Tabs, type TabSpec } from './tabs'
```

- [ ] **Step 6: Write the tests**

`frontend/packages/ui/src/cn.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { cn } from './cn'

describe('cn', () => {
  it('lets the later tailwind class win a conflict', () => {
    expect(cn('px-2 text-sm', 'px-4')).toBe('text-sm px-4')
  })

  it('drops falsy parts', () => {
    expect(cn('a', false, null, undefined, 'b')).toBe('a b')
  })
})
```

`frontend/packages/ui/src/button.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Button } from './button'

describe('Button', () => {
  it('is a primary medium button unless told otherwise', () => {
    render(<Button>Guardar</Button>)
    const button = screen.getByRole('button', { name: 'Guardar' })
    expect(button).toHaveClass('bg-brand', 'h-9')
  })

  it('lends its look to its child with asChild', () => {
    render(
      <Button asChild variant="secondary">
        <a href="/x">Ir</a>
      </Button>
    )
    expect(screen.getByRole('link', { name: 'Ir' })).toHaveClass('border-border')
  })
})
```

- [ ] **Step 7: README** — `frontend/packages/ui/README.md`

````markdown
# @chawpi/ui

Primitives shared by every chawpi package: `Button`, `Card*`, `Dialog*`, `Input`, `Textarea`, `Label`,
`Select*`, `Table`/`Th`/`Td`/`Badge`, `Tabs`, the `cn()` class merger, and the Tailwind 4 theme
tokens (`theme.css`).

Peer dependencies: `react`, `react-dom`, `react-i18next` (the dialog's close label reads `common.close`).

## Install

```
# .npmrc
@chawpi:registry=https://npm.pkg.github.com
```

```
yarn add @chawpi/ui
```

## Tailwind (required)

The packages ship class names, not compiled CSS. Your app's Tailwind 4 build must see them:

```css
/* src/index.css */
@import 'tailwindcss';
@import '@chawpi/ui/theme.css';
@source '../node_modules/@chawpi';
```

`@source` is relative to the css file. Point it at the `node_modules/@chawpi` folder your app
resolves (in a monorepo, often the root `node_modules`).
````

- [ ] **Step 8: Release wiring**

Delete `frontend/packages/.gitkeep`. In `release-please-config.json`, append to the `extra-files` array of package `"."`:

```json
{ "type": "json", "path": "frontend/packages/ui/package.json", "jsonpath": "$.version" }
```

- [ ] **Step 9: Install, verify, format**

```bash
yarn install
yarn prettier --write frontend/packages/ui release-please-config.json
yarn workspace @chawpi/ui lint
yarn workspace @chawpi/ui test
yarn workspace @chawpi/ui build
ls frontend/packages/ui/dist
git status --short
```
Expected: lint clean, 4 tests pass. `dist` holds `index.js`, `index.js.map`, `index.d.ts`, one `.d.ts` per source file and `theme.css`. `git status` shows only uncommitted changes (`yarn.lock` included).

---

### Task 3: `@chawpi/core` scaffold — config, types, api client, metadata-to-zod

**Files:**
- Create: `frontend/packages/core/{package.json, tsconfig.json, tsconfig.build.json, vite.config.ts}`
- Create: `frontend/packages/core/src/{index.ts, test/setup.ts, app/config.ts, api/client.ts, types/metadata.ts}`
- Create (ported): `frontend/packages/core/src/lib/metadata-to-zod.ts`, `frontend/packages/core/src/lib/metadata-to-zod.test.ts`
- Test: `frontend/packages/core/src/api/client.test.ts`, `frontend/packages/core/src/app/config.test.ts`
- Modify: `release-please-config.json`

**Interfaces:**
- Consumes: `@chawpi/ui` (Task 2), port script (Task 1).
- Produces (all exported from `@chawpi/core`):
  - `interface ChawpiConfig { apiBaseUrl: string; storagePrefix: string; languages: string[]; appName?: string; appTagline?: string; basename?: string; defaultLoginEmail: string }`, `DEFAULT_CONFIG`, `resolveConfig(config?: Partial<ChawpiConfig>): ChawpiConfig`, `interface StorageKeys { token; user; lang }`, `storageKeys(prefix: string): StorageKeys`.
  - `class ApiError(status: number, message: string, violations: FieldViolation[])`, `interface FieldViolation { field; message }`, `interface ApiClient { baseUrl; keys: StorageKeys; request<T>(path, init?): Promise<T>; getToken(): string | null; setToken(token: string | null): void }`, `createApiClient({ baseUrl, storagePrefix }): ApiClient`, `setActiveApiClient(client)`, `getActiveApiClient()`, `api<T>(path, init?)`, `getToken()`, `setToken(token)`.
  - Types: `CORE_FIELD_TYPES`, `CoreFieldType`, `FieldType`, `FieldMeta`, `SystemFieldScope`, `SystemField`, `ObjectSummary`, `ObjectDefinition`, `RecordItem`, `RecordSection`, `RecordPayload`, `recordSection(record, section)`, `Paged<T>`, `RelationshipType`, `Relationship`, `RelatedSide`, `CORE_PAGE_COMPONENT_TYPES`, `CorePageComponentType`, `PageComponentType`, `PageLayout`, `ActionKind`, `ActionStyle`, `TemplateRegion`, `TemplateRow`, `PageTemplate`, `PageComponent`, `Page`, `PagePayload`, `SortDirection`, `ViewDefinition`, `View`, `ViewPayload`, `FormSection`, `Form`, `FormPayload`.
  - `buildRecordSchema`, `toAttributes`, `toFormValues` (unchanged sapgis signatures).

- [ ] **Step 1: Manifest and config** — `frontend/packages/core/package.json`

```json
{
    "name": "@chawpi/core",
    "version": "0.1.0",
    "description": "Chawpi app shell, module registry, api client, auth, i18n and the core metadata features",
    "type": "module",
    "files": ["dist", "README.md"],
    "sideEffects": false,
    "main": "./dist/index.js",
    "module": "./dist/index.js",
    "types": "./dist/index.d.ts",
    "exports": {
        ".": {
            "types": "./dist/index.d.ts",
            "import": "./dist/index.js"
        },
        "./package.json": "./package.json"
    },
    "publishConfig": {
        "registry": "https://npm.pkg.github.com"
    },
    "scripts": {
        "lint": "prettier --check src package.json tsconfig.json tsconfig.build.json vite.config.ts && tsc --noEmit -p tsconfig.json",
        "test": "vitest run",
        "build": "vite build && tsc -p tsconfig.build.json"
    },
    "dependencies": {
        "@chawpi/ui": "*",
        "@hookform/resolvers": "5.9.1",
        "lucide-react": "1.47.0",
        "react-hook-form": "7.88.0",
        "zod": "4.6.5"
    },
    "peerDependencies": {
        "@tanstack/react-query": "^5.103.1",
        "i18next": "^26.4.2",
        "react": "^19.3.0",
        "react-dom": "^19.3.0",
        "react-i18next": "^17.0.14",
        "react-router": "^8.4.0"
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

`frontend/packages/core/tsconfig.json`. The `paths` send sibling packages to their sources for lint and tests. `@chawpi/testing` exists from Task 6, and an unused mapping is harmless.

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "noEmit": true,
        "types": ["node", "vitest/globals", "@testing-library/jest-dom"],
        "paths": {
            "@chawpi/ui": ["../ui/src/index.ts"],
            "@chawpi/core": ["./src/index.ts"],
            "@chawpi/testing": ["../testing/src/index.ts"]
        }
    },
    "include": ["src", "vite.config.ts"]
}
```

`frontend/packages/core/tsconfig.build.json`: identical to ui's (Task 2 Step 2). It has no `paths`, so `@chawpi/ui` types come from `node_modules/@chawpi/ui/dist`.

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "declaration": true,
        "emitDeclarationOnly": true,
        "outDir": "dist",
        "rootDir": "src",
        "types": []
    },
    "include": ["src"],
    "exclude": ["src/**/*.test.ts", "src/**/*.test.tsx", "src/test"]
}
```

`frontend/packages/core/vite.config.ts`:

```ts
import { fileURLToPath, URL } from 'node:url'
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
      external: (id: string) => external.some((dep) => id === dep || id.startsWith(`${dep}/`))
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
      '@chawpi/core': here('./src/index.ts'),
      '@chawpi/testing': here('../testing/src/index.ts')
    }
  }
})
```

`frontend/packages/core/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

- [ ] **Step 2: Write the failing tests**

`frontend/packages/core/src/app/config.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { DEFAULT_CONFIG, resolveConfig, storageKeys } from './config'

describe('resolveConfig', () => {
  it('fills what the app left out with the defaults', () => {
    expect(resolveConfig({ appName: 'Catastro' })).toEqual({ ...DEFAULT_CONFIG, appName: 'Catastro' })
  })

  it('ignores keys passed as undefined instead of wiping the default', () => {
    expect(resolveConfig({ apiBaseUrl: undefined }).apiBaseUrl).toBe('/api')
  })

  it('drops trailing slashes from the api base url', () => {
    expect(resolveConfig({ apiBaseUrl: 'https://host/api//' }).apiBaseUrl).toBe('https://host/api')
  })

  it('refuses an app with no language or no storage prefix', () => {
    expect(() => resolveConfig({ languages: [] })).toThrow(/languages/)
    expect(() => resolveConfig({ storagePrefix: ' ' })).toThrow(/storagePrefix/)
  })
})

describe('storageKeys', () => {
  it('namespaces every key under the prefix', () => {
    expect(storageKeys('catastro')).toEqual({ token: 'catastro.token', user: 'catastro.user', lang: 'catastro.lang' })
  })
})
```

`frontend/packages/core/src/api/client.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, createApiClient, getActiveApiClient, setActiveApiClient } from './client'

function answer(status: number, body?: unknown) {
  return vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }))
}

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('createApiClient', () => {
  it('joins the base url and the path without doubling the slash', async () => {
    const fetch = answer(200, { ok: true })
    vi.stubGlobal('fetch', fetch)
    await createApiClient({ baseUrl: 'https://host/api/', storagePrefix: 'a' }).request('/objects')
    expect(fetch).toHaveBeenCalledWith('https://host/api/objects', expect.anything())
  })

  it('sends the token of its own prefix only', async () => {
    const fetch = answer(200, {})
    vi.stubGlobal('fetch', fetch)
    createApiClient({ baseUrl: '/api', storagePrefix: 'a' }).setToken('token-a')

    await createApiClient({ baseUrl: '/api', storagePrefix: 'b' }).request('/objects')
    const headers = (fetch.mock.calls[0] as unknown as [string, RequestInit])[1].headers as Headers
    expect(headers.get('Authorization')).toBeNull()
    expect(localStorage.getItem('a.token')).toBe('token-a')
  })

  it('sends the bearer token and json content type', async () => {
    const fetch = answer(200, {})
    vi.stubGlobal('fetch', fetch)
    const client = createApiClient({ baseUrl: '/api', storagePrefix: 'a' })
    client.setToken('t1')
    await client.request('/objects', { method: 'POST', body: '{}' })
    const init = (fetch.mock.calls[0] as unknown as [string, RequestInit])[1]
    expect(init.method).toBe('POST')
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer t1')
    expect((init.headers as Headers).get('Content-Type')).toBe('application/json')
  })

  it('turns problem+json into an ApiError with its violations', async () => {
    vi.stubGlobal('fetch', answer(400, { title: 'Bad Request', detail: 'Invalid record', errors: [{ field: 'codigo', message: 'required' }] }))
    const failure = createApiClient({ baseUrl: '/api', storagePrefix: 'a' }).request('/objects/x/records')
    await expect(failure).rejects.toBeInstanceOf(ApiError)
    await expect(failure).rejects.toMatchObject({ status: 400, message: 'Invalid record', violations: [{ field: 'codigo', message: 'required' }] })
  })

  it('forgets only its own token on a 401', async () => {
    vi.stubGlobal('fetch', answer(401, { title: 'Unauthorized' }))
    const a = createApiClient({ baseUrl: '/api', storagePrefix: 'a' })
    const b = createApiClient({ baseUrl: '/api', storagePrefix: 'b' })
    a.setToken('token-a')
    b.setToken('token-b')
    await expect(a.request('/auth/me/permissions')).rejects.toMatchObject({ status: 401 })
    expect(a.getToken()).toBeNull()
    expect(b.getToken()).toBe('token-b')
  })

  it('answers undefined for a 204', async () => {
    vi.stubGlobal('fetch', answer(204))
    await expect(createApiClient({ baseUrl: '/api', storagePrefix: 'a' }).request('/objects/x', { method: 'DELETE' })).resolves.toBeUndefined()
  })
})

describe('api()', () => {
  it('goes through whichever client is active', async () => {
    const fetch = answer(200, [])
    vi.stubGlobal('fetch', fetch)
    const previous = getActiveApiClient()
    setActiveApiClient(createApiClient({ baseUrl: '/other', storagePrefix: 'o' }))
    try {
      await api('/objects')
      expect(fetch).toHaveBeenCalledWith('/other/objects', expect.anything())
    } finally {
      setActiveApiClient(previous)
    }
  })
})
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
yarn install
yarn workspace @chawpi/core test
```
Expected: FAIL, `Failed to resolve import "./config"` / `"./client"`.

- [ ] **Step 4: Write `frontend/packages/core/src/app/config.ts`**

```ts
export interface ChawpiConfig {
  // where the REST api lives: '/api', 'https://host/api'. a trailing slash is dropped
  apiBaseUrl: string
  // localStorage keys become `<prefix>.token|user|lang`. two apps on one origin need two prefixes
  storagePrefix: string
  // first one is the default and the fallback
  languages: string[]
  // shell header. unset falls back to the `app.name` / `app.tagline` strings
  appName?: string
  appTagline?: string
  // router basename when the app is not served from '/'
  basename?: string
  // login form prefill. empty for real apps, examples put a demo account here
  defaultLoginEmail: string
}

export const DEFAULT_CONFIG: ChawpiConfig = {
  apiBaseUrl: '/api',
  storagePrefix: 'chawpi',
  languages: ['es', 'en'],
  defaultLoginEmail: ''
}

export interface StorageKeys {
  token: string
  user: string
  lang: string
}

export function storageKeys(prefix: string): StorageKeys {
  return { token: `${prefix}.token`, user: `${prefix}.user`, lang: `${prefix}.lang` }
}

export function resolveConfig(config: Partial<ChawpiConfig> = {}): ChawpiConfig {
  // `{ apiBaseUrl: undefined }` means "not set", not "set to nothing"
  const given = Object.fromEntries(Object.entries(config).filter(([, value]) => value !== undefined)) as Partial<ChawpiConfig>
  const merged: ChawpiConfig = { ...DEFAULT_CONFIG, ...given }
  if (merged.languages.length === 0) throw new Error('chawpi: config.languages needs at least one language')
  if (!merged.storagePrefix.trim()) throw new Error('chawpi: config.storagePrefix must not be empty')
  return { ...merged, apiBaseUrl: merged.apiBaseUrl.replace(/\/+$/, '') }
}
```

- [ ] **Step 5: Write `frontend/packages/core/src/api/client.ts`**

```ts
import { DEFAULT_CONFIG, storageKeys, type StorageKeys } from '../app/config'

export interface FieldViolation {
  field: string
  message: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly violations: FieldViolation[] = []
  ) {
    super(message)
  }
}

export interface ApiClientOptions {
  baseUrl: string
  storagePrefix: string
}

export interface ApiClient {
  readonly baseUrl: string
  readonly keys: StorageKeys
  request<T>(path: string, init?: RequestInit): Promise<T>
  getToken(): string | null
  setToken(token: string | null): void
}

export function createApiClient({ baseUrl, storagePrefix }: ApiClientOptions): ApiClient {
  const base = baseUrl.replace(/\/+$/, '')
  const keys = storageKeys(storagePrefix)

  const getToken = () => localStorage.getItem(keys.token)
  const setToken = (token: string | null) => {
    if (token) localStorage.setItem(keys.token, token)
    else localStorage.removeItem(keys.token)
  }

  // every call goes through here: bearer token in, problem+json out as ApiError.
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Content-Type', 'application/json')
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)

    const response = await fetch(`${base}${path}`, { ...init, headers })

    if (response.status === 204) return undefined as T
    const text = await response.text()
    const body = text ? JSON.parse(text) : null

    if (!response.ok) {
      if (response.status === 401) setToken(null)
      throw new ApiError(response.status, body?.detail ?? body?.title ?? response.statusText, body?.errors ?? [])
    }
    return body as T
  }

  return { baseUrl: base, keys, request, getToken, setToken }
}

// one app per page. ChawpiProviders makes its client the active one before any child renders,
// so hooks and module code keep calling plain api().
let active: ApiClient = createApiClient({ baseUrl: DEFAULT_CONFIG.apiBaseUrl, storagePrefix: DEFAULT_CONFIG.storagePrefix })

export function setActiveApiClient(client: ApiClient): void {
  active = client
}

export function getActiveApiClient(): ApiClient {
  return active
}

export function api<T>(path: string, init?: RequestInit): Promise<T> {
  return active.request<T>(path, init)
}

export function getToken(): string | null {
  return active.getToken()
}

export function setToken(token: string | null): void {
  active.setToken(token)
}
```

- [ ] **Step 6: Write `frontend/packages/core/src/types/metadata.ts`**

This replaces sapgis `types/metadata.ts` (R3). The GIS and document types listed in R10 are gone. Module keys ride on index signatures.

```ts
// what the metadata api answers. modules add keys of their own to these payloads (gis puts its
// shape settings on fields and objects, and its shapes on records). core carries them through the
// index signatures untouched and never reads them.

export const CORE_FIELD_TYPES = ['TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION'] as const

export type CoreFieldType = (typeof CORE_FIELD_TYPES)[number]

// open: a module adds types through its field renderers. `string & {}` keeps editor hints for the core ones
export type FieldType = CoreFieldType | (string & {})

export interface FieldMeta {
  id: string
  name: string
  label: string
  type: FieldType
  required: boolean
  unique: boolean
  defaultValue: string | null
  description: string | null
  position: number
  enumOptions: string[] | null
  relationTarget: string | null
  visible: boolean
  editable: boolean
  [extension: string]: unknown
}

// where a system column lives: on every table, only once a workflow is attached, or nowhere yet
// — reserved for later.
export type SystemFieldScope = 'ALWAYS' | 'WORKFLOW' | 'RESERVED'

export interface SystemField {
  name: string
  // what to print beside the name, not something the field form can take
  type: string | null
  scope: SystemFieldScope
}

export interface ObjectSummary {
  id: string
  name: string
  label: string
  pluralLabel: string
  description: string | null
  enabled: boolean
  [extension: string]: unknown
}

export interface ObjectDefinition extends ObjectSummary {
  fields: FieldMeta[]
}

export interface RecordItem {
  id: string
  createdAt: string | null
  updatedAt: string | null
  attributes: Record<string, unknown>
  // module sections, flattened by the server next to attributes, keyed by field name
  [section: string]: unknown
}

export type RecordSection = Record<string, unknown>

// what a save sends: attributes plus one object per registered section
export interface RecordPayload {
  attributes: Record<string, unknown>
  [section: string]: Record<string, unknown>
}

// a section the record does not carry reads as empty, never as undefined
export function recordSection(record: RecordItem | undefined, section: string): RecordSection {
  const value = record?.[section]
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as RecordSection) : {}
}

export interface Paged<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type RelationshipType = 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY'

export interface Relationship {
  id: string
  name: string
  label: string
  inverseLabel: string | null
  type: RelationshipType
  source: string
  target: string
  fieldName: string | null
  joinTable: string | null
}

// a relationship as seen from one object: who is on the other end, and whether it is a list
export interface RelatedSide {
  relationship: string
  label: string
  type: RelationshipType
  objectName: string
  objectLabel: string
  many: boolean
}

export const CORE_PAGE_COMPONENT_TYPES = [
  'PAGE',
  'REGION',
  'TABS',
  'TAB',
  'SECTION',
  'FORM',
  'DYNAMIC_FORM',
  'FIELD',
  'RELATED_LIST',
  'TEXT',
  'HISTORY',
  'ACTION'
] as const

export type CorePageComponentType = (typeof CORE_PAGE_COMPONENT_TYPES)[number]

// open: modules draw more through their page components
export type PageComponentType = CorePageComponentType | (string & {})

export type PageLayout = 'single-column' | 'two-column'

// NAVIGATE is core; other kinds come from modules' page actions
export type ActionKind = 'NAVIGATE' | (string & {})

export type ActionStyle = 'PRIMARY' | 'SECONDARY'

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
  // only a FIELD carries these: which field it places, and what this placement shows of it.
  // null means "whatever the object says", not "true".
  field?: string | null
  visible?: boolean | null
  editable?: boolean | null
  content: string | null
  action?: ActionKind | null
  transition?: string | null
  target?: string | null
  url?: string | null
  style?: ActionStyle | null
  // only a REGION carries this: which of the template's regions it fills
  region?: string | null
  // module components keep their own settings here
  [extension: string]: unknown
}

// the record detail layout an admin configured. `generated` means nobody configured one and the
// server derived it from the object's metadata.
export interface Page {
  id: string
  name: string
  label: string
  objectName: string
  kind: 'RECORD_DETAIL'
  template: PageTemplate
  generated: boolean
  definition: { page: PageComponent }
}

export interface PagePayload {
  objectName: string
  name: string
  label: string
  kind: 'RECORD_DETAIL'
  template: string
  definition: { page: PageComponent }
}

export type SortDirection = 'ASC' | 'DESC'

export interface ViewDefinition {
  columns: string[]
  // field name -> exact value
  filters: Record<string, string>
  sort: { field: string; direction: SortDirection } | null
  pageSize: number
}

// a saved list configuration. `generated` means nobody saved one and the server derived it.
export interface View {
  id: string
  name: string
  label: string
  objectName: string
  isDefault: boolean
  generated: boolean
  definition: ViewDefinition
}

export interface ViewPayload {
  name: string
  label: string
  isDefault: boolean
  definition: ViewDefinition
}

export interface FormSection {
  title: string | null
  fields: string[]
}

// a named form layout. same generated/stored story as views and pages.
export interface Form {
  id: string
  name: string
  label: string
  objectName: string
  generated: boolean
  definition: { sections: FormSection[] }
}

export interface FormPayload {
  name: string
  label: string
  definition: { sections: FormSection[] }
}
```

- [ ] **Step 7: Port metadata-to-zod and its test**

```bash
C=frontend/packages/core/src
node frontend/tooling/port-from-sapgis.mjs lib/metadata-to-zod.ts $C/lib/metadata-to-zod.ts
node frontend/tooling/port-from-sapgis.mjs lib/metadata-to-zod.test.ts $C/lib/metadata-to-zod.test.ts
```
Expected: no `MANUAL:` lines. No hand edits. The test fixture's `geometry: null` compiles through the index signature.

- [ ] **Step 8: Public api** — `frontend/packages/core/src/index.ts`

```ts
export { DEFAULT_CONFIG, resolveConfig, storageKeys, type ChawpiConfig, type StorageKeys } from './app/config'
export {
  ApiError,
  api,
  createApiClient,
  getActiveApiClient,
  getToken,
  setActiveApiClient,
  setToken,
  type ApiClient,
  type ApiClientOptions,
  type FieldViolation
} from './api/client'
export * from './types/metadata'
export { buildRecordSchema, toAttributes, toFormValues } from './lib/metadata-to-zod'
```

- [ ] **Step 9: Release wiring**

Append to `extra-files` in `release-please-config.json`. Internal `@chawpi/*` ranges stay `"*"`: yarn links a workspace whenever the range is satisfied, and `publish.yml` runs `set-version.mjs`, which pins the exact release version before `npm publish`.

```json
{ "type": "json", "path": "frontend/packages/core/package.json", "jsonpath": "$.version" }
```

- [ ] **Step 10: Verify, format**

```bash
yarn install
yarn prettier --write frontend/packages/core release-please-config.json
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn build
git status --short
```
Expected: lint clean; `config.test.ts` (5), `client.test.ts` (7) and the ported metadata-to-zod tests (8) pass. `yarn build` builds `@chawpi/ui` and then `@chawpi/core`, and `frontend/packages/core/dist/index.d.ts` exists. Nothing is committed.

---

### Task 4: Module contract, registry and links

**Files:**
- Create: `frontend/packages/core/src/types/{auth.ts, audit.ts}`
- Create: `frontend/packages/core/src/registry/{contract.ts, createRegistry.ts}`
- Create: `frontend/packages/core/src/links/{paths.ts, links.ts}`
- Test: `frontend/packages/core/src/registry/createRegistry.test.tsx`, `frontend/packages/core/src/links/links.test.ts`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: `FieldMeta`, `ObjectDefinition`, `ObjectSummary`, `PageComponent`, `RecordItem`, `CORE_FIELD_TYPES`, `CORE_PAGE_COMPONENT_TYPES` (Task 3).
- Produces (exported from `@chawpi/core`):
  - `AuthUser`, `CallerPermissions` (`types/auth.ts`); `AuditOperation`, `AuditChange`, `AuditEntry`, `AuditEntryPayload`, `ChangeDescription` (without sapgis's `geometry` flag), `AuditFilters` (`types/audit.ts`).
  - The contract types: `ChawpiModule`, `RouteChrome`, `RouteContribution`, `NavGroupContribution`, `NavContribution`, `FieldRenderer`, `FieldInputProps`, `FieldSettingsProps`, `PageComponentProps`, `PageActionProps`, `RecordPanelProps`, `RecordListActionProps`, `HistoryEntryProps`, `AuditValueFormatter`, `DashboardCardProps`, `ObjectColumn`, `ObjectDetailProps`, `ObjectFlagsHook`.
  - `createRegistry(modules: ChawpiModule[]): ChawpiRegistry`, `RegistryError`, `ChawpiRegistry`, `ResolvedRoute { key; moduleId; path; chrome; Component }`, `ResolvedNavGroup { id; labelKey; items: ResolvedNavItem[] }`, `ResolvedNavItem { key; labelKey; to: string | null; icon?; disabled; visible?; order }`, `CORE_HISTORY_OPERATIONS`.
  - `joinPath(...parts): string`, `fillPath(pattern, params?): string`.
  - `CORE_MODULE_ID = 'core'`, `CORE_ROUTE_PATHS`, `CoreRouteId`, `ChawpiLinks`, `createLinks(registry): ChawpiLinks`.

- [ ] **Step 1: Write the types the contract needs**

`frontend/packages/core/src/types/auth.ts`:

```ts
export interface AuthUser {
  id: string
  email: string
  displayName: string
  organizationId: string
  roles: string[]
}

// GET /auth/me/permissions: record actions per object the caller may read. asking grants nothing,
// every write is still checked by the server.
export interface CallerPermissions {
  admin: boolean
  objects: Record<string, string[]>
}
```

`frontend/packages/core/src/types/audit.ts` (sapgis `features/history/types.ts`, minus the geometry flag on `ChangeDescription`):

```ts
export type AuditOperation = 'CREATE' | 'UPDATE' | 'DELETE' | 'ISSUE'

export interface AuditChange {
  field: string
  before: unknown
  after: unknown
}

export interface AuditEntry {
  id: string
  userEmail: string | null
  objectName: string
  recordId: string | null
  operation: AuditOperation
  occurredAt: string
  // server may not send it yet. api layer defaults it to [].
  changes: AuditChange[]
  // set only on ISSUE, the document that entry is about. null for every other operation.
  documentId: string | null
}

// what the server sends today: `changes` is not there yet, and old rows carry no `documentId`.
export type AuditEntryPayload = Omit<AuditEntry, 'changes' | 'documentId'> & { changes?: AuditChange[]; documentId?: string | null }

// one change, ready to paint: label resolved, values already strings.
export interface ChangeDescription {
  field: string
  label: string
  before: string
  after: string
}

export interface AuditFilters {
  objectName?: string
  recordId?: string
  operation?: AuditOperation
  limit?: number
}
```

- [ ] **Step 2: Write the contract** — `frontend/packages/core/src/registry/contract.ts`

```ts
import type { QueryKey } from '@tanstack/react-query'
import type { ComponentType, ReactNode } from 'react'
import type { AuditEntry } from '../types/audit'
import type { CallerPermissions } from '../types/auth'
import type { FieldMeta, ObjectDefinition, ObjectSummary, PageComponent, RecordItem } from '../types/metadata'

// shell: inside the app shell, signed in. bare: signed in, no shell (a print sheet). public: no auth.
export type RouteChrome = 'shell' | 'bare' | 'public'

export interface RouteContribution {
  // unique inside the module. the route's key is `<moduleId>:<id>`; links and nav name it by that key
  id: string
  // react-router pattern relative to the module's basePath. '' is the module's index
  path: string
  // exactly one of the two. lazy keeps a heavy page out of the first bundle
  component?: ComponentType
  lazy?: () => Promise<{ default: ComponentType }>
  chrome?: RouteChrome
}

export interface NavGroupContribution {
  id: string
  // i18n key, namespace-qualified for modules ('gis:nav.gis')
  labelKey: string
  // sidebar position, lower first. core uses 10 data, 30 builder, 40 automation, 50 administration
  order: number
}

export interface NavContribution {
  group: string
  labelKey: string
  order: number
  // route key. a bare id means a route of the same module. none = a disabled placeholder
  route?: string
  icon?: ComponentType<{ className?: string }>
  disabled?: boolean
  // hide the entry from callers who would only be refused. absent = everyone sees it
  visible?: (permissions: CallerPermissions | null) => boolean
}

export interface FieldInputProps {
  field: FieldMeta
  value: unknown
  onChange: (value: unknown) => void
}

export interface FieldSettingsProps {
  settings: Record<string, string>
  onChange: (patch: Record<string, string>) => void
}

// a field type a module adds. its values live in record[section][field.name], never in attributes,
// and the form hands them back under the same key.
export interface FieldRenderer {
  section: string
  // drawn full width where the field sits; it draws its own label
  input: ComponentType<FieldInputProps>
  // false hides the unique toggle in the object editor
  uniqueAllowed?: boolean
  // extra inputs in the object builder's field form, kept as strings in the draft
  settings?: {
    defaults: Record<string, string>
    editor: ComponentType<FieldSettingsProps>
    toPayload: (settings: Record<string, string>) => Record<string, unknown>
  }
}

export interface PageComponentProps {
  component: PageComponent
  definition: ObjectDefinition
  record: RecordItem
}

export interface PageActionProps {
  component: PageComponent
  objectName: string
  recordId: string
}

export interface RecordPanelProps {
  objectName: string
  definition: ObjectDefinition
  record: RecordItem
}

export interface RecordListActionProps {
  objectName: string
  definition: ObjectDefinition
}

export interface HistoryEntryProps {
  entry: AuditEntry
}

// names an audit value the module recognises instead of dumping it
export interface AuditValueFormatter {
  matches: (value: unknown) => boolean
  // i18n key, namespace-qualified
  labelKey: string
}

export interface DashboardCardProps {
  objects: ObjectSummary[]
  loading: boolean
}

export interface ObjectDetailProps {
  object: ObjectSummary
}

export interface ObjectColumn {
  id: string
  headerKey: string
  cell: ComponentType<ObjectDetailProps>
}

// a hook: yes/no facts a module knows about an object. called on every render of the page that
// asks, so it must follow the rules of hooks.
export type ObjectFlagsHook = (objectName: string) => Record<string, boolean>

// what a module hands the app. every slot is optional: a module fills only what it needs.
export interface ChawpiModule {
  // lowercase, unique. also the module's i18n namespace and the prefix of its route keys
  id: string
  // url prefix of every route of the module. '' (default) mounts at the root
  basePath?: string
  routes?: RouteContribution[]
  navGroups?: NavGroupContribution[]
  nav?: NavContribution[]
  // field types the module adds (gis: its shape type). core types cannot be claimed
  fieldRenderers?: Record<string, FieldRenderer>
  // page component types the module draws (gis: MAP, workflow: WORKFLOW)
  pageComponents?: Record<string, ComponentType<PageComponentProps>>
  // ACTION kinds beyond NAVIGATE (workflow: TRANSITION)
  pageActions?: Record<string, ComponentType<PageActionProps>>
  // drawn under the record detail page (documents: issued documents)
  recordPanels?: ComponentType<RecordPanelProps>[]
  // buttons in the record list header (gis: open on the map)
  recordListActions?: ComponentType<RecordListActionProps>[]
  // body of a history entry whose operation core does not draw (documents: ISSUE)
  historyRenderers?: Record<string, ComponentType<HistoryEntryProps>>
  auditValueFormatters?: AuditValueFormatter[]
  // audit field name -> i18n key, for columns that are not object fields
  auditFieldLabels?: Record<string, string>
  dashboardCards?: ComponentType<DashboardCardProps>[]
  objectColumns?: ObjectColumn[]
  // extra line on each object tile of the dashboard
  objectTileDetails?: ComponentType<ObjectDetailProps>[]
  objectFlags?: ObjectFlagsHook
  // query keys the module caches per object. a record or field write makes them stale (gis: features)
  recordQueryKeys?: (objectName: string) => QueryKey[]
  // wrap the whole app, outside the router
  providers?: ComponentType<{ children: ReactNode }>[]
  // language -> resources, loaded under the namespace `id`
  i18n?: Record<string, Record<string, unknown>>
}
```

- [ ] **Step 3: Write the failing tests**

`frontend/packages/core/src/registry/createRegistry.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest'
import { createRegistry, RegistryError } from './createRegistry'
import type { ChawpiModule, FieldRenderer } from './contract'

const Page = () => <div />
const Input = () => <div />
const renderer: FieldRenderer = { section: 'sketches', input: Input }

describe('createRegistry', () => {
  it('mounts module routes under the module base path, keyed by module id', () => {
    const registry = createRegistry([{ id: 'gis', basePath: '/gis/', routes: [{ id: 'map', path: 'map', component: Page }] }])
    expect(registry.routes).toEqual([{ key: 'gis:map', moduleId: 'gis', path: '/gis/map', chrome: 'shell', Component: Page }])
  })

  it('gives a lazy route a component the router can mount', () => {
    const registry = createRegistry([{ id: 'gis', routes: [{ id: 'map', path: 'map', lazy: async () => ({ default: Page }) }] }])
    expect(registry.routes[0].Component).toBeTruthy()
    expect(registry.routes[0].path).toBe('/map')
  })

  it('refuses a route with both or neither of component and lazy', () => {
    expect(() => createRegistry([{ id: 'a', routes: [{ id: 'x', path: 'x' }] }])).toThrow(/exactly one of component or lazy/)
    expect(() => createRegistry([{ id: 'a', routes: [{ id: 'x', path: 'x', component: Page, lazy: async () => ({ default: Page }) }] }])).toThrow(RegistryError)
  })

  it('refuses a module id twice, a reserved id and a malformed id', () => {
    expect(() => createRegistry([{ id: 'gis' }, { id: 'gis' }])).toThrow(/'gis' is registered twice/)
    expect(() => createRegistry([{ id: 'common' }])).toThrow(/reserved/)
    expect(() => createRegistry([{ id: 'Gis' }])).toThrow(/must match/)
  })

  it('refuses two modules claiming one field type, naming both', () => {
    const a: ChawpiModule = { id: 'a', fieldRenderers: { SKETCH: renderer } }
    const b: ChawpiModule = { id: 'b', fieldRenderers: { SKETCH: renderer } }
    expect(() => createRegistry([a, b])).toThrow(/field type 'SKETCH' is claimed by both 'a' and 'b'/)
  })

  it('refuses a module claiming a core type', () => {
    expect(() => createRegistry([{ id: 'a', fieldRenderers: { TEXT: renderer } }])).toThrow(/cannot claim core field type 'TEXT'/)
    expect(() => createRegistry([{ id: 'a', pageComponents: { FORM: Page } }])).toThrow(/core page component 'FORM'/)
    expect(() => createRegistry([{ id: 'a', pageActions: { NAVIGATE: Page } }])).toThrow(/core page action 'NAVIGATE'/)
    expect(() => createRegistry([{ id: 'a', historyRenderers: { UPDATE: Page } }])).toThrow(/core history operation 'UPDATE'/)
  })

  it('refuses two routes on one path and one route id twice', () => {
    const one: ChawpiModule = { id: 'a', routes: [{ id: 'x', path: 'data/x', component: Page }] }
    const two: ChawpiModule = { id: 'b', routes: [{ id: 'y', path: '/data/x/', component: Page }] }
    expect(() => createRegistry([one, two])).toThrow(/'a:x' and 'b:y' both use path '\/data\/x'/)
    expect(() =>
      createRegistry([
        {
          id: 'a',
          routes: [
            { id: 'x', path: 'x', component: Page },
            { id: 'x', path: 'y', component: Page }
          ]
        }
      ])
    ).toThrow(/route 'a:x' is declared twice/)
  })

  it('builds nav groups in order, items in order, and resolves routes to paths', () => {
    const core: ChawpiModule = {
      id: 'core',
      routes: [{ id: 'objects', path: 'data/objects', component: Page }],
      navGroups: [
        { id: 'administration', labelKey: 'nav.administration', order: 50 },
        { id: 'data', labelKey: 'nav.data', order: 10 }
      ],
      nav: [
        { group: 'data', labelKey: 'nav.records', order: 20 },
        { group: 'data', labelKey: 'nav.objects', order: 10, route: 'objects' }
      ]
    }
    const gis: ChawpiModule = {
      id: 'gis',
      basePath: 'gis',
      routes: [{ id: 'map', path: 'map', component: Page }],
      navGroups: [{ id: 'gis', labelKey: 'gis:nav.gis', order: 20 }],
      nav: [{ group: 'gis', labelKey: 'gis:nav.maps', order: 10, route: 'map' }]
    }

    const groups = createRegistry([core, gis]).navGroups
    expect(groups.map((group) => group.id)).toEqual(['data', 'gis', 'administration'])
    expect(groups[0].items.map((item) => [item.labelKey, item.to, item.disabled])).toEqual([
      ['nav.objects', '/data/objects', false],
      ['nav.records', null, true]
    ])
    expect(groups[1].items[0].to).toBe('/gis/map')
  })

  it('lets a module put an entry in a group another module declared', () => {
    const core: ChawpiModule = { id: 'core', navGroups: [{ id: 'builder', labelKey: 'nav.builder', order: 30 }] }
    const pages: ChawpiModule = { id: 'pages', routes: [{ id: 'builder', path: 'builder/pages', component: Page }], nav: [{ group: 'builder', labelKey: 'pages:nav.pages', order: 10, route: 'builder' }] }
    expect(createRegistry([core, pages]).navGroups[0].items[0].to).toBe('/builder/pages')
  })

  it('refuses nav into a missing group, at a missing route, or at a route with parameters', () => {
    expect(() => createRegistry([{ id: 'a', nav: [{ group: 'nope', labelKey: 'x', order: 1 }] }])).toThrow(/unknown nav group 'nope'/)
    const grouped = (extra: Partial<ChawpiModule>): ChawpiModule => ({ id: 'a', navGroups: [{ id: 'g', labelKey: 'g', order: 1 }], ...extra })
    expect(() => createRegistry([grouped({ nav: [{ group: 'g', labelKey: 'x', order: 1, route: 'core:ghost' }] })])).toThrow(/unknown route 'core:ghost'/)
    expect(() =>
      createRegistry([grouped({ routes: [{ id: 'r', path: 'r/:id', component: Page }], nav: [{ group: 'g', labelKey: 'x', order: 1, route: 'r' }] })])
    ).toThrow(/needs parameters/)
    expect(() => createRegistry([grouped({}), { id: 'b', navGroups: [{ id: 'g', labelKey: 'g', order: 2 }] }])).toThrow(/nav group 'g' is declared by both 'a' and 'b'/)
  })

  it('collects the list slots in module order and merges audit field labels', () => {
    const PanelA = () => <div />
    const PanelB = () => <div />
    const registry = createRegistry([
      { id: 'a', recordPanels: [PanelA], auditFieldLabels: { geom: 'a:history.geom' }, recordQueryKeys: (object) => [['a', object]] },
      { id: 'b', recordPanels: [PanelB], auditFieldLabels: { other: 'b:x' } }
    ])
    expect(registry.recordPanels).toEqual([PanelA, PanelB])
    expect(registry.auditFieldLabels).toEqual({ geom: 'a:history.geom', other: 'b:x' })
    expect(registry.recordQueryKeys.map((keysOf) => keysOf('predio'))).toEqual([[['a', 'predio']]])
  })
})
```

`frontend/packages/core/src/links/links.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { createRegistry } from '../registry/createRegistry'
import { createLinks, CORE_ROUTE_PATHS } from './links'
import { fillPath, joinPath } from './paths'

const Page = () => null
const links = createLinks(createRegistry([{ id: 'gis', basePath: 'gis', routes: [{ id: 'map', path: 'map', component: Page }] }]))

describe('core links', () => {
  it('builds the same urls sapgis hardcoded', () => {
    expect(links.home()).toBe('/')
    expect(links.login()).toBe('/login')
    expect(links.objects()).toBe('/data/objects')
    expect(links.newObject()).toBe('/data/objects/new')
    expect(links.editObject('predio')).toBe('/data/objects/predio/edit')
    expect(links.relationships()).toBe('/data/relationships')
    expect(links.records('predio')).toBe('/data/objects/predio/records')
    expect(links.newRecord('predio')).toBe('/data/objects/predio/records/new')
    expect(links.record('predio', 'r1')).toBe('/data/objects/predio/records/r1')
  })

  it('percent-encodes parameters instead of letting them break the path', () => {
    expect(links.record('predio', 'a/b?c')).toBe('/data/objects/predio/records/a%2Fb%3Fc')
  })

  it('refuses a missing parameter instead of linking to undefined', () => {
    expect(() => links.records('')).toThrow(/needs :object/)
  })

  it('keeps every core path in one table', () => {
    expect(CORE_ROUTE_PATHS.records).toBe('data/objects/:object/records')
  })
})

describe('module links', () => {
  it('builds a registered route with a query string', () => {
    expect(links.to('gis:map', {}, { object: 'predio' })).toBe('/gis/map?object=predio')
    expect(links.has('gis:map')).toBe(true)
  })

  it('says so when a route is not registered', () => {
    expect(links.has('documents:print')).toBe(false)
    expect(() => links.to('documents:print', { id: '1' })).toThrow(/no route 'documents:print'/)
  })
})

describe('paths', () => {
  it('joins parts with single slashes and always starts at the root', () => {
    expect(joinPath('', '')).toBe('/')
    expect(joinPath('/gis/', '/map')).toBe('/gis/map')
  })

  it('fills named segments only', () => {
    expect(fillPath('/a/:x/b', { x: '1' })).toBe('/a/1/b')
  })
})
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/registry src/links`
Expected: FAIL, `Failed to resolve import "./createRegistry"` / `"./links"`.

- [ ] **Step 5: Write `frontend/packages/core/src/links/paths.ts`**

```ts
// '/' + the non-empty parts, each without its own leading/trailing slashes
export function joinPath(...parts: string[]): string {
  return `/${parts
    .map((part) => part.replace(/^\/+|\/+$/g, ''))
    .filter(Boolean)
    .join('/')}`
}

// react-router pattern -> url. a missing parameter is a bug in the caller, not an empty segment
export function fillPath(pattern: string, params: Record<string, string> = {}): string {
  return pattern
    .split('/')
    .map((segment) => {
      if (!segment.startsWith(':')) return segment
      const name = segment.slice(1)
      const value = params[name]
      if (value === undefined || value === '') throw new Error(`chawpi links: '${pattern}' needs :${name}`)
      return encodeURIComponent(value)
    })
    .join('/')
}
```

- [ ] **Step 6: Write `frontend/packages/core/src/registry/createRegistry.ts`**

```ts
import { lazy, type ComponentType, type ReactNode } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import { joinPath } from '../links/paths'
import { CORE_FIELD_TYPES, CORE_PAGE_COMPONENT_TYPES } from '../types/metadata'
import type { CallerPermissions } from '../types/auth'
import type {
  AuditValueFormatter,
  ChawpiModule,
  DashboardCardProps,
  FieldRenderer,
  HistoryEntryProps,
  NavGroupContribution,
  ObjectColumn,
  ObjectDetailProps,
  ObjectFlagsHook,
  PageActionProps,
  PageComponentProps,
  RecordListActionProps,
  RecordPanelProps,
  RouteChrome
} from './contract'

export class RegistryError extends Error {}

// operations core draws itself (UPDATE lists its changes; CREATE and DELETE need nothing)
export const CORE_HISTORY_OPERATIONS = ['CREATE', 'UPDATE', 'DELETE'] as const

export interface ResolvedRoute {
  key: string
  moduleId: string
  // full react-router pattern, always starting with '/'
  path: string
  chrome: RouteChrome
  Component: ComponentType
}

export interface ResolvedNavItem {
  key: string
  labelKey: string
  to: string | null
  icon?: ComponentType<{ className?: string }>
  disabled: boolean
  visible?: (permissions: CallerPermissions | null) => boolean
  order: number
}

export interface ResolvedNavGroup {
  id: string
  labelKey: string
  items: ResolvedNavItem[]
}

export interface ChawpiRegistry {
  modules: readonly ChawpiModule[]
  routes: readonly ResolvedRoute[]
  navGroups: readonly ResolvedNavGroup[]
  fieldRenderers: Readonly<Record<string, FieldRenderer>>
  pageComponents: Readonly<Record<string, ComponentType<PageComponentProps>>>
  pageActions: Readonly<Record<string, ComponentType<PageActionProps>>>
  historyRenderers: Readonly<Record<string, ComponentType<HistoryEntryProps>>>
  recordPanels: readonly ComponentType<RecordPanelProps>[]
  recordListActions: readonly ComponentType<RecordListActionProps>[]
  dashboardCards: readonly ComponentType<DashboardCardProps>[]
  objectColumns: readonly ObjectColumn[]
  objectTileDetails: readonly ComponentType<ObjectDetailProps>[]
  auditValueFormatters: readonly AuditValueFormatter[]
  auditFieldLabels: Readonly<Record<string, string>>
  objectFlags: readonly ObjectFlagsHook[]
  recordQueryKeys: readonly ((objectName: string) => QueryKey[])[]
  providers: readonly ComponentType<{ children: ReactNode }>[]
}

const MODULE_ID = /^[a-z][a-z0-9-]*$/
// 'common' is core's i18n namespace
const RESERVED_IDS = ['common']

// validated once, at mount. a conflict is a wiring bug: fail before anything renders.
export function createRegistry(modules: ChawpiModule[]): ChawpiRegistry {
  checkIds(modules)
  const routes = resolveRoutes(modules)
  const paths = new Map(routes.map((route) => [route.key, route.path]))

  return {
    modules,
    routes,
    navGroups: resolveNav(modules, paths),
    fieldRenderers: claim(modules, 'field type', (module) => module.fieldRenderers, CORE_FIELD_TYPES),
    pageComponents: claim(modules, 'page component', (module) => module.pageComponents, CORE_PAGE_COMPONENT_TYPES),
    pageActions: claim(modules, 'page action', (module) => module.pageActions, ['NAVIGATE']),
    historyRenderers: claim(modules, 'history operation', (module) => module.historyRenderers, CORE_HISTORY_OPERATIONS),
    recordPanels: modules.flatMap((module) => module.recordPanels ?? []),
    recordListActions: modules.flatMap((module) => module.recordListActions ?? []),
    dashboardCards: modules.flatMap((module) => module.dashboardCards ?? []),
    objectColumns: modules.flatMap((module) => module.objectColumns ?? []),
    objectTileDetails: modules.flatMap((module) => module.objectTileDetails ?? []),
    auditValueFormatters: modules.flatMap((module) => module.auditValueFormatters ?? []),
    auditFieldLabels: Object.assign({}, ...modules.map((module) => module.auditFieldLabels ?? {})),
    objectFlags: modules.flatMap((module) => (module.objectFlags ? [module.objectFlags] : [])),
    recordQueryKeys: modules.flatMap((module) => (module.recordQueryKeys ? [module.recordQueryKeys] : [])),
    providers: modules.flatMap((module) => module.providers ?? [])
  }
}

function checkIds(modules: ChawpiModule[]) {
  const seen = new Set<string>()
  for (const module of modules) {
    if (!MODULE_ID.test(module.id)) throw new RegistryError(`module id '${module.id}' must match ${MODULE_ID}`)
    if (RESERVED_IDS.includes(module.id)) throw new RegistryError(`module id '${module.id}' is reserved`)
    if (seen.has(module.id)) throw new RegistryError(`module '${module.id}' is registered twice`)
    seen.add(module.id)
  }
}

// one owner per name; core names are not up for grabs
function claim<T>(modules: ChawpiModule[], what: string, pick: (module: ChawpiModule) => Record<string, T> | undefined, reserved: readonly string[]) {
  const owners = new Map<string, string>()
  const claimed: Record<string, T> = {}
  for (const module of modules) {
    for (const [name, value] of Object.entries(pick(module) ?? {})) {
      if (reserved.includes(name)) throw new RegistryError(`module '${module.id}' cannot claim core ${what} '${name}'`)
      const owner = owners.get(name)
      if (owner) throw new RegistryError(`${what} '${name}' is claimed by both '${owner}' and '${module.id}'`)
      owners.set(name, module.id)
      claimed[name] = value
    }
  }
  return claimed
}

function resolveRoutes(modules: ChawpiModule[]): ResolvedRoute[] {
  const keyByPath = new Map<string, string>()
  const keys = new Set<string>()
  const routes: ResolvedRoute[] = []

  for (const module of modules) {
    for (const route of module.routes ?? []) {
      const key = `${module.id}:${route.id}`
      if (route.id.includes(':')) throw new RegistryError(`route id '${route.id}' of '${module.id}' must not contain ':'`)
      if (keys.has(key)) throw new RegistryError(`route '${key}' is declared twice`)
      if (Boolean(route.component) === Boolean(route.lazy)) throw new RegistryError(`route '${key}' needs exactly one of component or lazy`)
      const path = joinPath(module.basePath ?? '', route.path)
      const clash = keyByPath.get(path)
      if (clash) throw new RegistryError(`routes '${clash}' and '${key}' both use path '${path}'`)
      keys.add(key)
      keyByPath.set(path, key)
      routes.push({ key, moduleId: module.id, path, chrome: route.chrome ?? 'shell', Component: route.component ?? lazy(route.lazy!) })
    }
  }
  return routes
}

// a bare route id means "one of mine"
function qualify(moduleId: string, route: string): string {
  return route.includes(':') ? route : `${moduleId}:${route}`
}

function resolveNav(modules: ChawpiModule[], paths: Map<string, string>): ResolvedNavGroup[] {
  const groups = new Map<string, { group: NavGroupContribution; owner: string; items: ResolvedNavItem[] }>()
  for (const module of modules) {
    for (const group of module.navGroups ?? []) {
      const existing = groups.get(group.id)
      if (existing) throw new RegistryError(`nav group '${group.id}' is declared by both '${existing.owner}' and '${module.id}'`)
      groups.set(group.id, { group, owner: module.id, items: [] })
    }
  }

  for (const module of modules) {
    for (const item of module.nav ?? []) {
      const target = groups.get(item.group)
      if (!target) throw new RegistryError(`module '${module.id}' puts '${item.labelKey}' in unknown nav group '${item.group}'`)
      const routeKey = item.route ? qualify(module.id, item.route) : null
      const to = routeKey ? paths.get(routeKey) : undefined
      if (routeKey && !to) throw new RegistryError(`nav item '${item.labelKey}' points at unknown route '${routeKey}'`)
      if (to?.includes(':')) throw new RegistryError(`nav item '${item.labelKey}' points at '${routeKey}', which needs parameters`)
      target.items.push({
        key: `${module.id}:${item.labelKey}`,
        labelKey: item.labelKey,
        to: to ?? null,
        icon: item.icon,
        disabled: item.disabled ?? !to,
        visible: item.visible,
        order: item.order
      })
    }
  }

  return [...groups.values()]
    .sort((a, b) => a.group.order - b.group.order)
    .map(({ group, items }) => ({ id: group.id, labelKey: group.labelKey, items: items.sort((a, b) => a.order - b.order) }))
}
```

- [ ] **Step 7: Write `frontend/packages/core/src/links/links.ts`**

```ts
import type { ChawpiRegistry } from '../registry/createRegistry'
import { fillPath, joinPath } from './paths'

export const CORE_MODULE_ID = 'core'

// the only place core spells its urls. coreModule mounts these, links build from these.
export const CORE_ROUTE_PATHS = {
  home: '',
  login: 'login',
  objects: 'data/objects',
  newObject: 'data/objects/new',
  editObject: 'data/objects/:object/edit',
  relationships: 'data/relationships',
  records: 'data/objects/:object/records',
  newRecord: 'data/objects/:object/records/new',
  record: 'data/objects/:object/records/:id',
  users: 'admin/users',
  roles: 'admin/roles',
  permissions: 'admin/permissions',
  audit: 'admin/audit'
} as const

export type CoreRouteId = keyof typeof CORE_ROUTE_PATHS

export interface ChawpiLinks {
  // any registered route by key (`gis:map`). params fill `:name` segments, search becomes ?a=b
  to(route: string, params?: Record<string, string>, search?: Record<string, string>): string
  has(route: string): boolean
  home(): string
  login(): string
  objects(): string
  newObject(): string
  editObject(object: string): string
  relationships(): string
  records(object: string): string
  newRecord(object: string): string
  record(object: string, id: string): string
}

function withSearch(path: string, search?: Record<string, string>): string {
  const query = search ? new URLSearchParams(search).toString() : ''
  return query ? `${path}?${query}` : path
}

export function createLinks(registry: ChawpiRegistry): ChawpiLinks {
  const paths = new Map(registry.routes.map((route) => [route.key, route.path]))
  const core = (id: CoreRouteId, params?: Record<string, string>) => fillPath(joinPath(CORE_ROUTE_PATHS[id]), params)

  return {
    to(route, params, search) {
      const pattern = paths.get(route)
      if (!pattern) throw new Error(`chawpi links: no route '${route}' is registered`)
      return withSearch(fillPath(pattern, params), search)
    },
    has: (route) => paths.has(route),
    home: () => core('home'),
    login: () => core('login'),
    objects: () => core('objects'),
    newObject: () => core('newObject'),
    editObject: (object) => core('editObject', { object }),
    relationships: () => core('relationships'),
    records: (object) => core('records', { object }),
    newRecord: (object) => core('newRecord', { object }),
    record: (object, id) => core('record', { object, id })
  }
}
```

- [ ] **Step 8: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export type { AuthUser, CallerPermissions } from './types/auth'
export type { AuditChange, AuditEntry, AuditEntryPayload, AuditFilters, AuditOperation, ChangeDescription } from './types/audit'
export type * from './registry/contract'
export {
  CORE_HISTORY_OPERATIONS,
  RegistryError,
  createRegistry,
  type ChawpiRegistry,
  type ResolvedNavGroup,
  type ResolvedNavItem,
  type ResolvedRoute
} from './registry/createRegistry'
export { fillPath, joinPath } from './links/paths'
export { CORE_MODULE_ID, CORE_ROUTE_PATHS, createLinks, type ChawpiLinks, type CoreRouteId } from './links/links'
```

- [ ] **Step 9: Run the tests to verify they pass, then verify the package**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green: 11 registry tests and 8 links tests on top of Task 3's. Nothing is committed.

---

### Task 5: i18n factory, app context, auth, `ChawpiProviders`

**Files:**
- Create (ported): `frontend/packages/core/src/i18n/locales/es/common.json`, `frontend/packages/core/src/i18n/locales/en/common.json`
- Create: `frontend/packages/core/src/i18n/{createI18n.ts, coreBundles.ts}`
- Create: `frontend/packages/core/src/app/{context.tsx, ChawpiProviders.tsx}`
- Create: `frontend/packages/core/src/auth/AuthProvider.tsx` (replaces sapgis `app/auth.tsx`)
- Create: `frontend/packages/core/src/registry/hooks.ts`
- Test: `frontend/packages/core/src/i18n/createI18n.test.ts`, `frontend/packages/core/src/auth/AuthProvider.test.tsx`, `frontend/packages/core/src/app/ChawpiProviders.test.tsx`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: `ChawpiConfig`, `resolveConfig`, `ApiClient`, `createApiClient`, `setActiveApiClient` (Task 3); `ChawpiModule`, `ChawpiRegistry`, `createRegistry`, `createLinks`, `ChawpiLinks`, `AuthUser`, `CallerPermissions` (Task 4).
- Produces:
  - `createChawpiI18n({ languages: string[]; storageKey: string; modules: readonly ChawpiModule[] }): i18n`, `changeLanguage(instance: i18n, storageKey: string, language: string): Promise<void>`, `CORE_NAMESPACE = 'common'`.
  - `coreBundles: Record<string, Record<string, unknown>>[]`, where each entry is `{ es: {...}, en: {...} }`. Tasks 11 and 14 append to it.
  - `ChawpiContext`, `useChawpi(): { config; registry; apiClient }`, `useChawpiConfig()`, `useRegistry()`, `useApiClient()`, `useChawpiLinks(): ChawpiLinks`.
  - `AuthProvider({ children, initialUser?, initialPermissions? })`, `useAuth(): AuthContextValue` = `{ user; permissions; isAdmin; can(objectName, action); signIn(email, password); signOut() }`.
  - `ChawpiProviders(props: ChawpiProvidersProps)`, where `ChawpiProvidersProps = { config: ChawpiConfig; registry: ChawpiRegistry; apiClient: ApiClient; i18n: i18n; queryClient: QueryClient; initialUser?: AuthUser | null; initialPermissions?: CallerPermissions | null; children: ReactNode }`.
  - `useObjectFlags(objectName: string): Record<string, boolean>`.

- [ ] **Step 1: Port the locale files**

```bash
C=frontend/packages/core/src
node frontend/tooling/port-from-sapgis.mjs locales/es/common.json $C/i18n/locales/es/common.json
node frontend/tooling/port-from-sapgis.mjs locales/en/common.json $C/i18n/locales/en/common.json
grep -n '"name": "Chawpi"' $C/i18n/locales/*/common.json
```
Expected: exactly 2 lines, one per file. Keys that only P5 modules use (`map`, `views`, `forms`, `documents`, `pages`, `nav.maps`, …) stay for now (R4).

- [ ] **Step 2: Write the failing tests**

`frontend/packages/core/src/i18n/createI18n.test.ts`:

```ts
import { beforeEach, describe, expect, it } from 'vitest'
import { changeLanguage, createChawpiI18n } from './createI18n'

const KEY = 'i18n-test.lang'
const plans = { id: 'plans', i18n: { es: { nav: { sheets: 'Hojas' } }, en: { nav: { sheets: 'Sheets' } } } }

beforeEach(() => localStorage.clear())

describe('createChawpiI18n', () => {
  it('serves core strings from the default namespace, first language first', () => {
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [] })
    expect(i18n.language).toBe('es')
    expect(i18n.t('common.save')).toBe('Guardar')
  })

  it('serves each module under its own namespace', () => {
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [plans] })
    expect(i18n.t('plans:nav.sheets')).toBe('Hojas')
    expect(i18n.t('nav.sheets')).toBe('nav.sheets')
  })

  it('starts on the language the user picked last time', () => {
    localStorage.setItem(KEY, 'en')
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [plans] })
    expect(i18n.t('common.save')).toBe('Save')
    expect(i18n.t('plans:nav.sheets')).toBe('Sheets')
  })

  it('ignores a stored language the app does not offer', () => {
    localStorage.setItem(KEY, 'fr')
    expect(createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [] }).language).toBe('es')
    localStorage.setItem(KEY, 'es')
    expect(createChawpiI18n({ languages: ['en'], storageKey: KEY, modules: [] }).language).toBe('en')
  })

  it('keeps two apps apart: each instance has its own language', async () => {
    const one = createChawpiI18n({ languages: ['es', 'en'], storageKey: 'one.lang', modules: [] })
    const two = createChawpiI18n({ languages: ['es', 'en'], storageKey: 'two.lang', modules: [] })
    await changeLanguage(one, 'one.lang', 'en')
    expect(one.language).toBe('en')
    expect(two.language).toBe('es')
    expect(localStorage.getItem('one.lang')).toBe('en')
  })
})
```

`frontend/packages/core/src/auth/AuthProvider.test.tsx`:

```tsx
import { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from '../api/client'
import { resolveConfig } from '../app/config'
import { ChawpiProviders } from '../app/ChawpiProviders'
import { createChawpiI18n } from '../i18n/createI18n'
import { createRegistry } from '../registry/createRegistry'
import type { AuthUser } from '../types/auth'
import { useAuth } from './AuthProvider'

const ana: AuthUser = { id: 'u1', email: 'ana@chawpi.test', displayName: 'Ana', organizationId: 'o1', roles: ['EDITOR'] }

function Probe() {
  const auth = useAuth()
  return (
    <div>
      <p data-testid="user">{auth.user?.email ?? 'nobody'}</p>
      <p data-testid="rights">{`${auth.can('predio', 'READ')}/${auth.can('predio', 'DELETE')}/${auth.isAdmin}`}</p>
      <button onClick={() => void auth.signIn('ana@chawpi.test', 'secret')}>in</button>
      <button onClick={auth.signOut}>out</button>
    </div>
  )
}

function mount(children: ReactNode) {
  const config = resolveConfig({ storagePrefix: 'auth-test' })
  const apiClient = createApiClient({ baseUrl: config.apiBaseUrl, storagePrefix: config.storagePrefix })
  const registry = createRegistry([])
  const i18n = createChawpiI18n({ languages: config.languages, storageKey: apiClient.keys.lang, modules: [] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <ChawpiProviders config={config} registry={registry} apiClient={apiClient} i18n={i18n} queryClient={queryClient}>
      {children}
    </ChawpiProviders>
  )
}

const fetch = vi.fn(async (url: string, _init?: RequestInit) => {
  if (url === '/api/auth/login') return new Response(JSON.stringify({ token: 'tok', expiresAt: '2026-12-31T00:00:00Z', user: ana }), { status: 200 })
  if (url === '/api/auth/me/permissions') return new Response(JSON.stringify({ admin: false, objects: { predio: ['READ', 'CREATE'] } }), { status: 200 })
  return new Response(null, { status: 404 })
})

beforeEach(() => {
  localStorage.clear()
  fetch.mockClear()
  vi.stubGlobal('fetch', fetch)
})
afterEach(() => vi.unstubAllGlobals())

describe('AuthProvider', () => {
  it('signs in, keeps token and user under its prefix, then loads the caller permissions', async () => {
    mount(<Probe />)
    expect(screen.getByTestId('user')).toHaveTextContent('nobody')

    await userEvent.click(screen.getByRole('button', { name: 'in' }))

    expect(await screen.findByText('ana@chawpi.test')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST', body: JSON.stringify({ email: 'ana@chawpi.test', password: 'secret' }) }))
    expect(localStorage.getItem('auth-test.token')).toBe('tok')
    expect(JSON.parse(localStorage.getItem('auth-test.user') ?? 'null')).toEqual(ana)
    await waitFor(() => expect(screen.getByTestId('rights')).toHaveTextContent('true/false/false'))
  })

  it('comes back signed in from its own prefix only', () => {
    localStorage.setItem('other.user', JSON.stringify(ana))
    mount(<Probe />)
    expect(screen.getByTestId('user')).toHaveTextContent('nobody')
  })

  it('forgets token and user on sign out', async () => {
    localStorage.setItem('auth-test.user', JSON.stringify(ana))
    localStorage.setItem('auth-test.token', 'tok')
    mount(<Probe />)
    expect(screen.getByTestId('user')).toHaveTextContent('ana@chawpi.test')

    await userEvent.click(screen.getByRole('button', { name: 'out' }))

    expect(screen.getByTestId('user')).toHaveTextContent('nobody')
    expect(localStorage.getItem('auth-test.token')).toBeNull()
    expect(localStorage.getItem('auth-test.user')).toBeNull()
  })

  it('refuses to be used outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Probe />)).toThrow(/useAuth must be used inside AuthProvider/)
  })
})
```

`frontend/packages/core/src/app/ChawpiProviders.test.tsx`:

```tsx
import { QueryClient } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { createApiClient, getActiveApiClient } from '../api/client'
import { createChawpiI18n } from '../i18n/createI18n'
import type { ChawpiModule } from '../registry/contract'
import { createRegistry } from '../registry/createRegistry'
import { useObjectFlags } from '../registry/hooks'
import { ChawpiProviders } from './ChawpiProviders'
import { resolveConfig } from './config'
import { useChawpiLinks, useRegistry } from './context'

function mount(modules: ChawpiModule[], children: ReactNode) {
  const config = resolveConfig({ storagePrefix: 'providers-test' })
  const apiClient = createApiClient({ baseUrl: '/custom', storagePrefix: config.storagePrefix })
  const registry = createRegistry(modules)
  const i18n = createChawpiI18n({ languages: config.languages, storageKey: apiClient.keys.lang, modules })
  const view = render(
    <ChawpiProviders config={config} registry={registry} apiClient={apiClient} i18n={i18n} queryClient={new QueryClient()} initialUser={null} initialPermissions={null}>
      {children}
    </ChawpiProviders>
  )
  return { ...view, apiClient }
}

function Outer({ children }: { children: ReactNode }) {
  return <section data-testid="outer">{children}</section>
}

function Inner({ children }: { children: ReactNode }) {
  return <article data-testid="inner">{children}</article>
}

describe('ChawpiProviders', () => {
  it('wraps the app in module providers, first module outermost', () => {
    mount([{ id: 'a', providers: [Outer] }, { id: 'b', providers: [Inner] }], <p>app</p>)
    expect(screen.getByTestId('outer')).toContainElement(screen.getByTestId('inner'))
    expect(screen.getByTestId('inner')).toHaveTextContent('app')
  })

  it('makes its api client the one plain api() calls use', () => {
    const { apiClient } = mount([], <p>app</p>)
    expect(getActiveApiClient()).toBe(apiClient)
  })

  it('hands the registry and links to any screen below it', () => {
    function Screen() {
      const links = useChawpiLinks()
      return <p>{`${useRegistry().modules.length} ${links.records('predio')}`}</p>
    }
    mount([{ id: 'a' }], <Screen />)
    expect(screen.getByText('1 /data/objects/predio/records')).toBeInTheDocument()
  })

  it('merges what every module knows about an object', () => {
    function Flags() {
      return <p>{JSON.stringify(useObjectFlags('predio'))}</p>
    }
    mount(
      [
        { id: 'a', objectFlags: (object) => ({ workflow: object === 'predio' }) },
        { id: 'b', objectFlags: () => ({ spatial: false }) }
      ],
      <Flags />
    )
    expect(screen.getByText('{"workflow":true,"spatial":false}')).toBeInTheDocument()
  })

  it('says where chawpi hooks belong when used outside', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Screen() {
      useRegistry()
      return null
    }
    expect(() => render(<Screen />)).toThrow(/inside ChawpiApp/)
  })
})
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/i18n src/auth src/app`
Expected: FAIL, unresolved imports `./createI18n`, `./AuthProvider`, `./ChawpiProviders`.

- [ ] **Step 4: Write `frontend/packages/core/src/i18n/coreBundles.ts`**

```ts
// strings a core feature keeps outside common.json, deep-merged into `common` when an app starts.
// a feature adds its bundle here instead of registering it by an import side effect.
export const coreBundles: Record<string, Record<string, unknown>>[] = []
```

- [ ] **Step 5: Write `frontend/packages/core/src/i18n/createI18n.ts`**

```ts
import i18next, { type i18n as I18n } from 'i18next'
import { initReactI18next } from 'react-i18next'
import type { ChawpiModule } from '../registry/contract'
import { coreBundles } from './coreBundles'
import en from './locales/en/common.json'
import es from './locales/es/common.json'

export const CORE_NAMESPACE = 'common'

const CORE_LOCALES: Record<string, Record<string, unknown>> = { es, en }

export interface ChawpiI18nOptions {
  languages: string[]
  storageKey: string
  modules: readonly ChawpiModule[]
}

// a language the app does not offer (another app on this origin, an old build) is ignored
function startLanguage(storageKey: string, languages: string[]): string {
  const stored = localStorage.getItem(storageKey)
  return stored && languages.includes(stored) ? stored : languages[0]
}

// one instance per app. core strings under `common`, the default namespace, so t('x.y') needs no
// prefix. each module under its own id: t('gis:nav.maps').
export function createChawpiI18n({ languages, storageKey, modules }: ChawpiI18nOptions): I18n {
  const resources: Record<string, Record<string, Record<string, unknown>>> = {}
  for (const language of languages) {
    resources[language] = { [CORE_NAMESPACE]: CORE_LOCALES[language] ?? {} }
    for (const module of modules) {
      const strings = module.i18n?.[language]
      if (strings) resources[language][module.id] = strings
    }
  }

  const instance = i18next.createInstance()
  void instance.use(initReactI18next).init({
    resources,
    lng: startLanguage(storageKey, languages),
    fallbackLng: languages[0],
    ns: [CORE_NAMESPACE, ...modules.map((module) => module.id)],
    defaultNS: CORE_NAMESPACE,
    interpolation: { escapeValue: false },
    // resources are inline: init synchronously so the first render already has strings
    initAsync: false
  })

  for (const bundle of coreBundles) {
    for (const language of languages) {
      const strings = bundle[language]
      if (strings) instance.addResourceBundle(language, CORE_NAMESPACE, strings, true, true)
    }
  }
  return instance
}

export async function changeLanguage(instance: I18n, storageKey: string, language: string): Promise<void> {
  localStorage.setItem(storageKey, language)
  await instance.changeLanguage(language)
}
```

- [ ] **Step 6: Write `frontend/packages/core/src/app/context.tsx`**

```tsx
import { createContext, use, useMemo } from 'react'
import type { ApiClient } from '../api/client'
import { createLinks, type ChawpiLinks } from '../links/links'
import type { ChawpiRegistry } from '../registry/createRegistry'
import type { ChawpiConfig } from './config'

export interface ChawpiContextValue {
  config: ChawpiConfig
  registry: ChawpiRegistry
  apiClient: ApiClient
}

export const ChawpiContext = createContext<ChawpiContextValue | null>(null)

export function useChawpi(): ChawpiContextValue {
  const value = use(ChawpiContext)
  if (!value) throw new Error('chawpi hooks must be used inside ChawpiApp (or ChawpiProviders)')
  return value
}

export function useChawpiConfig(): ChawpiConfig {
  return useChawpi().config
}

export function useRegistry(): ChawpiRegistry {
  return useChawpi().registry
}

export function useApiClient(): ApiClient {
  return useChawpi().apiClient
}

// every in-app url goes through here, so a module mounted elsewhere is still found
export function useChawpiLinks(): ChawpiLinks {
  const registry = useRegistry()
  return useMemo(() => createLinks(registry), [registry])
}
```

- [ ] **Step 7: Write `frontend/packages/core/src/auth/AuthProvider.tsx`**

This is sapgis `app/auth.tsx` with the storage key from the client and the caller permissions added (R7).

```tsx
import { useQuery } from '@tanstack/react-query'
import { createContext, use, useCallback, useMemo, useState, type ReactNode } from 'react'
import { useApiClient } from '../app/context'
import type { AuthUser, CallerPermissions } from '../types/auth'

interface LoginResponse {
  token: string
  expiresAt: string
  user: AuthUser
}

export interface AuthContextValue {
  user: AuthUser | null
  // null while loading and when signed out. hiding by permission is a courtesy, the server decides
  permissions: CallerPermissions | null
  isAdmin: boolean
  can: (objectName: string, action: string) => boolean
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function storedUser(key: string): AuthUser | null {
  const raw = localStorage.getItem(key)
  return raw ? (JSON.parse(raw) as AuthUser) : null
}

export interface AuthProviderProps {
  children: ReactNode
  // tests and embedders: given means "use this", nothing is read from storage or fetched
  initialUser?: AuthUser | null
  initialPermissions?: CallerPermissions | null
}

export function AuthProvider({ children, initialUser, initialPermissions }: AuthProviderProps) {
  const client = useApiClient()
  const [user, setUser] = useState<AuthUser | null>(() => (initialUser !== undefined ? initialUser : storedUser(client.keys.user)))
  const loaded = useQuery({
    queryKey: ['auth', 'permissions', user?.id ?? null],
    queryFn: () => client.request<CallerPermissions>('/auth/me/permissions'),
    enabled: user !== null && initialPermissions === undefined
  })
  const permissions = initialPermissions !== undefined ? initialPermissions : user ? (loaded.data ?? null) : null

  const signIn = useCallback(
    async (email: string, password: string) => {
      const response = await client.request<LoginResponse>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password })
      })
      client.setToken(response.token)
      localStorage.setItem(client.keys.user, JSON.stringify(response.user))
      setUser(response.user)
    },
    [client]
  )

  const signOut = useCallback(() => {
    client.setToken(null)
    localStorage.removeItem(client.keys.user)
    setUser(null)
  }, [client])

  const value = useMemo<AuthContextValue>(() => {
    const isAdmin = permissions?.admin === true
    return {
      user,
      permissions,
      isAdmin,
      can: (objectName, action) => isAdmin || (permissions?.objects[objectName] ?? []).includes(action),
      signIn,
      signOut
    }
  }, [user, permissions, signIn, signOut])

  return <AuthContext value={value}>{children}</AuthContext>
}

export function useAuth(): AuthContextValue {
  const context = use(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
```

- [ ] **Step 8: Write `frontend/packages/core/src/app/ChawpiProviders.tsx`**

```tsx
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import type { i18n as I18n } from 'i18next'
import { useMemo, useState, type ReactNode } from 'react'
import { I18nextProvider } from 'react-i18next'
import { setActiveApiClient, type ApiClient } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import type { ChawpiRegistry } from '../registry/createRegistry'
import type { AuthUser, CallerPermissions } from '../types/auth'
import type { ChawpiConfig } from './config'
import { ChawpiContext } from './context'

export interface ChawpiProvidersProps {
  config: ChawpiConfig
  registry: ChawpiRegistry
  apiClient: ApiClient
  i18n: I18n
  queryClient: QueryClient
  initialUser?: AuthUser | null
  initialPermissions?: CallerPermissions | null
  children: ReactNode
}

// everything a chawpi screen needs above it. ChawpiApp adds the router, tests add a MemoryRouter.
export function ChawpiProviders({ config, registry, apiClient, i18n, queryClient, initialUser, initialPermissions, children }: ChawpiProvidersProps) {
  // during the first render, not in an effect: children's query effects run before a parent's effects
  useState(() => setActiveApiClient(apiClient))
  const value = useMemo(() => ({ config, registry, apiClient }), [config, registry, apiClient])
  // first module's provider outermost
  const wrapped = registry.providers.reduceRight<ReactNode>((inner, Provider) => <Provider>{inner}</Provider>, children)

  return (
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18n}>
        <ChawpiContext value={value}>
          <AuthProvider initialUser={initialUser} initialPermissions={initialPermissions}>
            {wrapped}
          </AuthProvider>
        </ChawpiContext>
      </I18nextProvider>
    </QueryClientProvider>
  )
}
```

- [ ] **Step 9: Write `frontend/packages/core/src/registry/hooks.ts`**

```ts
import { useRegistry } from '../app/context'

// what every module knows about one object, merged. the registry fixes how many hooks run, so the
// rules of hooks hold.
export function useObjectFlags(objectName: string): Record<string, boolean> {
  const registry = useRegistry()
  const flags: Record<string, boolean> = {}
  for (const useFlags of registry.objectFlags) Object.assign(flags, useFlags(objectName))
  return flags
}
```

- [ ] **Step 10: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { CORE_NAMESPACE, changeLanguage, createChawpiI18n, type ChawpiI18nOptions } from './i18n/createI18n'
export { ChawpiContext, useApiClient, useChawpi, useChawpiConfig, useChawpiLinks, useRegistry, type ChawpiContextValue } from './app/context'
export { AuthProvider, useAuth, type AuthContextValue, type AuthProviderProps } from './auth/AuthProvider'
export { ChawpiProviders, type ChawpiProvidersProps } from './app/ChawpiProviders'
export { useObjectFlags } from './registry/hooks'
```

- [ ] **Step 11: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green. The new tests are 5 i18n, 4 auth and 5 providers. Nothing is committed.

---

### Task 6: `@chawpi/testing` — `renderWithProviders`, fetch mocks

**Files:**
- Create: `frontend/packages/testing/{package.json, tsconfig.json, tsconfig.build.json, vite.config.ts, README.md}`
- Create: `frontend/packages/testing/src/{index.ts, render.tsx, fetch.ts, test/setup.ts}`
- Test: `frontend/packages/testing/src/render.test.tsx`, `frontend/packages/testing/src/fetch.test.ts`
- Modify: `release-please-config.json`

**Interfaces:**
- Consumes: `ChawpiProviders`, `createRegistry`, `createApiClient`, `createChawpiI18n`, `resolveConfig`, `useAuth`, `useRegistry`, `api`, `ApiError`, types `ChawpiModule`, `ChawpiConfig`, `AuthUser`, `CallerPermissions` (Tasks 3–5).
- Produces (package `@chawpi/testing`):
  - `renderWithProviders(ui: ReactElement, options?: ChawpiRenderOptions): ChawpiRenderResult`, where `ChawpiRenderOptions = { modules?: ChawpiModule[]; config?: Partial<ChawpiConfig>; route?: string; path?: string; user?: AuthUser | null; permissions?: CallerPermissions | null; language?: string }` and `ChawpiRenderResult = RenderResult & { queryClient: QueryClient }`. It wraps with `wrapper`, so `rerender` keeps the providers.
  - `TEST_USER: AuthUser` (`tester@chawpi.test`, role ADMIN), `TEST_PERMISSIONS: CallerPermissions` (`{ admin: true, objects: {} }`).
  - `mockFetch(routes: MockRoute[], options?: { baseUrl?: string }): FetchMock`, where `MockRoute = { method?: string; path: string | RegExp; status?: number; body?: unknown }`, `FetchMock = { calls: RecordedCall[]; restore(): void }` and `RecordedCall = { method; url; path; body }`.
  - `jsonResponse(body: unknown, status?: number): Response`.
  - Core tests from Task 7 on import `renderWithProviders` from `@chawpi/testing` (alias, R1).

- [ ] **Step 1: Package files**

`frontend/packages/testing/package.json`:

```json
{
    "name": "@chawpi/testing",
    "version": "0.1.0",
    "description": "Test helpers for chawpi apps and modules: renderWithProviders and fetch mocks",
    "type": "module",
    "files": ["dist", "README.md"],
    "sideEffects": false,
    "main": "./dist/index.js",
    "module": "./dist/index.js",
    "types": "./dist/index.d.ts",
    "exports": {
        ".": {
            "types": "./dist/index.d.ts",
            "import": "./dist/index.js"
        },
        "./package.json": "./package.json"
    },
    "publishConfig": {
        "registry": "https://npm.pkg.github.com"
    },
    "scripts": {
        "lint": "prettier --check src package.json tsconfig.json tsconfig.build.json vite.config.ts && tsc --noEmit -p tsconfig.json",
        "test": "vitest run",
        "build": "vite build && tsc -p tsconfig.build.json"
    },
    "peerDependencies": {
        "@chawpi/core": "*",
        "@tanstack/react-query": "^5.103.1",
        "@testing-library/react": "^16.3.3",
        "react": "^19.3.0",
        "react-dom": "^19.3.0",
        "react-router": "^8.4.0"
    },
    "devDependencies": {
        "@hookform/resolvers": "5.9.1",
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
        "lucide-react": "1.47.0",
        "prettier": "3.8.2",
        "react": "19.3.0",
        "react-dom": "19.3.0",
        "react-hook-form": "7.88.0",
        "react-i18next": "17.0.14",
        "react-router": "8.4.0",
        "typescript": "5.9.3",
        "vite": "8.3.0",
        "vitest": "5.0.1",
        "zod": "4.6.5"
    }
}
```

(The devDependencies include core's own runtime deps because this package's tests and lint compile core from source.)

`frontend/packages/testing/tsconfig.json`:

```json
{
    "extends": "../../tsconfig.base.json",
    "compilerOptions": {
        "noEmit": true,
        "types": ["node", "vitest/globals", "@testing-library/jest-dom"],
        "paths": {
            "@chawpi/ui": ["../ui/src/index.ts"],
            "@chawpi/core": ["../core/src/index.ts"],
            "@chawpi/testing": ["./src/index.ts"]
        }
    },
    "include": ["src", "vite.config.ts"]
}
```

`frontend/packages/testing/tsconfig.build.json`: same content as core's (Task 3 Step 1).

`frontend/packages/testing/vite.config.ts`:

```ts
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import pkg from './package.json' with { type: 'json' }

const external = Object.keys(pkg.peerDependencies)
const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

export default defineConfig({
  plugins: [react()],
  build: {
    lib: { entry: here('./src/index.ts'), formats: ['es'], fileName: 'index' },
    rolldownOptions: {
      external: (id: string) => external.some((dep) => id === dep || id.startsWith(`${dep}/`))
    },
    sourcemap: true
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    alias: {
      '@chawpi/ui': here('../ui/src/index.ts'),
      '@chawpi/core': here('../core/src/index.ts'),
      '@chawpi/testing': here('./src/index.ts')
    }
  }
})
```

`frontend/packages/testing/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

- [ ] **Step 2: Write the failing tests**

`frontend/packages/testing/src/render.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router'
import { describe, expect, it } from 'vitest'
import { useAuth, useRegistry } from '@chawpi/core'
import { renderWithProviders } from './render'

function Who() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const registry = useRegistry()
  return <p>{`${user?.email ?? 'nobody'} ${t('common.save')} ${registry.modules.map((module) => module.id).join(',')}`}</p>
}

describe('renderWithProviders', () => {
  it('renders as a signed-in admin, in spanish, with no modules', () => {
    renderWithProviders(<Who />)
    expect(screen.getByText('tester@chawpi.test Guardar')).toBeInTheDocument()
  })

  it('takes modules, a signed-out caller and a language', () => {
    renderWithProviders(<Who />, { modules: [{ id: 'gis' }], user: null, language: 'en' })
    expect(screen.getByText('nobody Save gis')).toBeInTheDocument()
  })

  it('mounts the ui on a route pattern so params resolve', () => {
    function Param() {
      return <p>{useParams().object}</p>
    }
    renderWithProviders(<Param />, { route: '/data/objects/predio/edit', path: 'data/objects/:object/edit' })
    expect(screen.getByText('predio')).toBeInTheDocument()
  })

  it('keeps the providers on rerender', () => {
    const { rerender } = renderWithProviders(<Who />)
    rerender(<Who />)
    expect(screen.getByText('tester@chawpi.test Guardar')).toBeInTheDocument()
  })
})
```

`frontend/packages/testing/src/fetch.test.ts`:

```ts
import { afterEach, describe, expect, it } from 'vitest'
import { ApiError, createApiClient } from '@chawpi/core'
import { jsonResponse, mockFetch, type FetchMock } from './fetch'

let mock: FetchMock | null = null
afterEach(() => mock?.restore())

const client = createApiClient({ baseUrl: '/api', storagePrefix: 'fetch-test' })

describe('mockFetch', () => {
  it('answers a matching route with json and records the call', async () => {
    mock = mockFetch([{ method: 'POST', path: '/objects/predio/records', status: 201, body: { id: 'r1' } }])
    await expect(client.request('/objects/predio/records', { method: 'POST', body: JSON.stringify({ attributes: { codigo: 'A' } }) })).resolves.toEqual({ id: 'r1' })
    expect(mock.calls).toEqual([{ method: 'POST', url: '/api/objects/predio/records', path: '/objects/predio/records', body: { attributes: { codigo: 'A' } } }])
  })

  it('answers anything unmatched with a 404 problem so the test fails loudly', async () => {
    mock = mockFetch([])
    await expect(client.request('/objects')).rejects.toBeInstanceOf(ApiError)
    await expect(client.request('/objects')).rejects.toMatchObject({ status: 404, message: 'no mock for GET /objects' })
  })

  it('matches a string route on the path alone and a regexp on path plus query', async () => {
    mock = mockFetch([
      { path: '/objects/predio/records', body: { content: [] } },
      { path: /^\/audit\?.*operation=UPDATE/, body: [] }
    ])
    await expect(client.request('/objects/predio/records?page=0')).resolves.toEqual({ content: [] })
    await expect(client.request('/audit?limit=100&operation=UPDATE')).resolves.toEqual([])
  })

  it('answers 204 with no body', async () => {
    mock = mockFetch([{ method: 'DELETE', path: '/objects/predio', status: 204 }])
    await expect(client.request('/objects/predio', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('puts the real fetch back', () => {
    const original = globalThis.fetch
    mockFetch([]).restore()
    expect(globalThis.fetch).toBe(original)
  })
})

describe('jsonResponse', () => {
  it('builds a json response with a status', async () => {
    const response = jsonResponse({ a: 1 }, 409)
    expect(response.status).toBe(409)
    await expect(response.json()).resolves.toEqual({ a: 1 })
  })
})
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
yarn install
yarn workspace @chawpi/testing test
```
Expected: FAIL, `Failed to resolve import "./render"` / `"./fetch"`.

- [ ] **Step 4: Write `frontend/packages/testing/src/render.tsx`**

```tsx
import { QueryClient } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import {
  ChawpiProviders,
  createApiClient,
  createChawpiI18n,
  createRegistry,
  resolveConfig,
  type AuthUser,
  type CallerPermissions,
  type ChawpiConfig,
  type ChawpiModule
} from '@chawpi/core'

export const TEST_USER: AuthUser = {
  id: 'u-test',
  email: 'tester@chawpi.test',
  displayName: 'Tester',
  organizationId: 'org-test',
  roles: ['ADMIN']
}

export const TEST_PERMISSIONS: CallerPermissions = { admin: true, objects: {} }

export interface ChawpiRenderOptions {
  // only these: ChawpiApp adds core itself, a test adds coreModule when it needs core routes or nav
  modules?: ChawpiModule[]
  config?: Partial<ChawpiConfig>
  // initial url
  route?: string
  // route pattern the ui is mounted on, so useParams works ('data/objects/:object/edit')
  path?: string
  // undefined = TEST_USER / TEST_PERMISSIONS, null = signed out / none. never fetched
  user?: AuthUser | null
  permissions?: CallerPermissions | null
  // undefined = the first configured language, whatever an earlier test picked
  language?: string
}

export interface ChawpiRenderResult extends RenderResult {
  queryClient: QueryClient
}

// the same providers ChawpiApp mounts, around a MemoryRouter
export function renderWithProviders(ui: ReactElement, options: ChawpiRenderOptions = {}): ChawpiRenderResult {
  const config = resolveConfig({ storagePrefix: 'chawpi-test', ...options.config })
  const registry = createRegistry(options.modules ?? [])
  const apiClient = createApiClient({ baseUrl: config.apiBaseUrl, storagePrefix: config.storagePrefix })
  if (options.language) localStorage.setItem(apiClient.keys.lang, options.language)
  else localStorage.removeItem(apiClient.keys.lang)
  const i18n = createChawpiI18n({ languages: config.languages, storageKey: apiClient.keys.lang, modules: registry.modules })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const user = options.user === undefined ? TEST_USER : options.user
  const permissions = options.permissions === undefined ? TEST_PERMISSIONS : options.permissions

  // one wrapper per call, so rerender keeps the same providers and cache
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ChawpiProviders
        config={config}
        registry={registry}
        apiClient={apiClient}
        i18n={i18n}
        queryClient={queryClient}
        initialUser={user}
        initialPermissions={permissions}
      >
        <MemoryRouter initialEntries={[options.route ?? '/']}>
          {options.path ? (
            <Routes>
              <Route path={options.path} element={children} />
            </Routes>
          ) : (
            children
          )}
        </MemoryRouter>
      </ChawpiProviders>
    )
  }

  return { ...render(ui, { wrapper: Wrapper }), queryClient }
}
```

- [ ] **Step 5: Write `frontend/packages/testing/src/fetch.ts`**

```ts
export interface MockRoute {
  method?: string
  // a string matches the path without its query string; a RegExp is tested on path + query
  path: string | RegExp
  status?: number
  body?: unknown
}

export interface RecordedCall {
  method: string
  url: string
  // url minus the api base url
  path: string
  body: unknown
}

export interface FetchMock {
  calls: RecordedCall[]
  restore: () => void
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// swaps global fetch for a table of canned answers. first matching route wins. anything unmatched
// answers a 404 problem, so a test that forgot a route fails on it instead of hanging on the network.
export function mockFetch(routes: MockRoute[], options: { baseUrl?: string } = {}): FetchMock {
  const baseUrl = (options.baseUrl ?? '/api').replace(/\/+$/, '')
  const original = globalThis.fetch
  const calls: RecordedCall[] = []

  globalThis.fetch = async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const method = (init.method ?? 'GET').toUpperCase()
    const path = url.startsWith(baseUrl) ? url.slice(baseUrl.length) : url
    const body = typeof init.body === 'string' && init.body ? JSON.parse(init.body) : null
    calls.push({ method, url, path, body })

    const bare = path.split('?')[0]
    const route = routes.find(
      (candidate) => (candidate.method ?? 'GET').toUpperCase() === method && (typeof candidate.path === 'string' ? candidate.path === bare : candidate.path.test(path))
    )
    if (!route) return jsonResponse({ title: 'Not Found', detail: `no mock for ${method} ${path}` }, 404)
    if (route.status === 204) return new Response(null, { status: 204 })
    return jsonResponse(route.body ?? null, route.status ?? 200)
  }

  return {
    calls,
    restore: () => {
      globalThis.fetch = original
    }
  }
}
```

- [ ] **Step 6: Public api and README**

`frontend/packages/testing/src/index.ts`:

```ts
export { renderWithProviders, TEST_PERMISSIONS, TEST_USER, type ChawpiRenderOptions, type ChawpiRenderResult } from './render'
export { jsonResponse, mockFetch, type FetchMock, type MockRoute, type RecordedCall } from './fetch'
```

`frontend/packages/testing/README.md`:

````markdown
# @chawpi/testing

Test helpers for apps and modules built on `@chawpi/core`. Framework-agnostic (no vitest import),
used with vitest + jsdom + Testing Library.

Peer dependencies: `@chawpi/core`, `@tanstack/react-query`, `@testing-library/react`, `react`,
`react-dom`, `react-router`.

## renderWithProviders

Mounts the same providers `ChawpiApp` does (registry, api client, i18n, react-query, auth) around
a `MemoryRouter`. Defaults: signed in as `TEST_USER` with admin `TEST_PERMISSIONS`, spanish, no
modules, storage prefix `chawpi-test`.

```tsx
import { coreModule } from '@chawpi/core'
import { renderWithProviders } from '@chawpi/testing'
import { myModule } from './module'

renderWithProviders(<MyScreen />, {
  modules: [coreModule, myModule], // core routes/nav only when you add coreModule
  route: '/data/objects/predio/edit',
  path: 'data/objects/:object/edit', // so useParams() resolves
  user: null, // signed out
  language: 'en'
})
```

## mockFetch

```ts
const fetch = mockFetch([
  { path: '/objects', body: [] },
  { method: 'POST', path: '/objects/predio/records', status: 201, body: { id: 'r1' } }
])
// ... render, interact ...
expect(fetch.calls.map((call) => call.path)).toContain('/objects')
fetch.restore()
```

Paths are matched after the api base url (`/api` unless `mockFetch(routes, { baseUrl })`). Anything
unmatched answers `404` with a problem body naming the request.
````

- [ ] **Step 7: Release wiring**

Append to `extra-files` in `release-please-config.json`:

```json
{ "type": "json", "path": "frontend/packages/testing/package.json", "jsonpath": "$.version" }
```

- [ ] **Step 8: Verify**

```bash
yarn install
yarn prettier --write frontend/packages/testing release-please-config.json
yarn workspace @chawpi/testing lint
yarn workspace @chawpi/testing test
yarn build
git status --short
```
Expected: lint clean and 10 tests pass. `yarn build` runs ui, then core, then testing (the runner's order), and `frontend/packages/testing/dist/index.d.ts` exists. Nothing is committed.

---

### Task 7: Per-domain query hooks (split `lib/queries.ts`)

**Files:**
- Create: `frontend/packages/core/src/queries/{index.ts, moduleQueries.ts, objects.ts, records.ts, relationships.ts, pages.ts, views.ts, forms.ts}`
- Test: `frontend/packages/core/src/queries/queries.test.tsx`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: `api` (Task 3), `useRegistry` (Task 5), types (Task 3), `renderWithProviders`, `mockFetch` (Task 6).
- Produces:
  - The barrel `src/queries/index.ts`, re-exported from `@chawpi/core`. Every hook keeps its sapgis name, parameters and query keys:
    - objects: `useObjects`, `useObjectDefinition`, `useSystemFields`, `useCreateObject`, `useUpdateObject`, `useDeleteObject`, `useAddField`, `useUpdateField`, `useDeleteField`;
    - records: `useRecords`, `useRecord`, `useSaveRecord` (takes `RecordPayload`), `useDeleteRecord`;
    - relationships: `useRelationships`, `useObjectRelationships`, `useRelatedRecords`, `useCreateRelationship`, `useUpdateRelationship`, `useDeleteRelationship`, `useLinkRelated`;
    - pages: `useResolvedPage`, `useTemplates`, `usePages`, `useSavePage`, `useDeletePage`;
    - views: `useViews`, `useView`, `useSaveView`, `useDeleteView`;
    - forms: `useForms`, `useForm`, `useSaveForm`, `useDeleteForm`.
  - `useModuleQueryInvalidation(): (objectName: string) => void`.
  - **Rule for every later task:** components and features import hooks only from the barrel (`../../queries`), never from `queries/objects` directly. That way one `vi.mock('<rel>/queries')` in a test replaces them all.

- [ ] **Step 1: Write the failing test** — `frontend/packages/core/src/queries/queries.test.tsx`

```tsx
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import type { ChawpiModule } from '../registry/contract'
import type { RecordPayload } from '../types/metadata'
import { useDeleteField, useResolvedPage, useSaveRecord } from './index'

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

const sketches: ChawpiModule = { id: 'sketch', recordQueryKeys: (object) => [['sketch-tiles', object]] }

function Save({ payload }: { payload: RecordPayload }) {
  const save = useSaveRecord('predio')
  return <button onClick={() => save.mutate(payload)}>{save.isSuccess ? 'saved' : 'save'}</button>
}

function DropField() {
  const drop = useDeleteField('predio')
  return <button onClick={() => drop.mutate('area')}>{drop.isSuccess ? 'dropped' : 'drop'}</button>
}

function PageName() {
  const page = useResolvedPage('predio')
  return <p>{page.data?.name ?? '…'}</p>
}

function invalidatedKeys(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls.map((call) => (call[0] as { queryKey: unknown[] }).queryKey)
}

describe('record queries', () => {
  it('posts the payload as it is, sections included', async () => {
    fetch = mockFetch([{ method: 'POST', path: '/objects/predio/records', status: 201, body: { id: 'r1' } }])
    renderWithProviders(<Save payload={{ attributes: { codigo: 'A' }, sketches: {} }} />, { modules: [sketches] })

    await userEvent.click(screen.getByRole('button', { name: 'save' }))

    await screen.findByText('saved')
    expect(fetch.calls[0].body).toEqual({ attributes: { codigo: 'A' }, sketches: {} })
  })

  it('makes what modules cache about the object stale after a save', async () => {
    fetch = mockFetch([{ method: 'POST', path: '/objects/predio/records', status: 201, body: { id: 'r1' } }])
    const { queryClient } = renderWithProviders(<Save payload={{ attributes: {} }} />, { modules: [sketches] })
    const spy = vi.spyOn(queryClient, 'invalidateQueries')

    await userEvent.click(screen.getByRole('button', { name: 'save' }))

    await waitFor(() => expect(invalidatedKeys(spy)).toContainEqual(['sketch-tiles', 'predio']))
    expect(invalidatedKeys(spy)).toContainEqual(['records', 'predio'])
    expect(invalidatedKeys(spy)).toContainEqual(['related'])
  })

  it('invalidates only core keys when no module caches anything', async () => {
    fetch = mockFetch([{ method: 'POST', path: '/objects/predio/records', status: 201, body: { id: 'r1' } }])
    const { queryClient } = renderWithProviders(<Save payload={{ attributes: {} }} />)
    const spy = vi.spyOn(queryClient, 'invalidateQueries')

    await userEvent.click(screen.getByRole('button', { name: 'save' }))

    await screen.findByText('saved')
    expect(invalidatedKeys(spy)).toEqual([['records', 'predio'], ['related']])
  })
})

describe('field queries', () => {
  it('drops the object, its records and module caches after a field delete', async () => {
    fetch = mockFetch([{ method: 'DELETE', path: '/metadata/objects/predio/fields/area', status: 204 }])
    const { queryClient } = renderWithProviders(<DropField />, { modules: [sketches] })
    const spy = vi.spyOn(queryClient, 'invalidateQueries')

    await userEvent.click(screen.getByRole('button', { name: 'drop' }))

    await screen.findByText('dropped')
    expect(invalidatedKeys(spy)).toEqual([['objects', 'predio'], ['records', 'predio'], ['sketch-tiles', 'predio']])
  })
})

describe('page queries', () => {
  it('asks for the resolved record detail page', async () => {
    fetch = mockFetch([{ path: '/objects/predio/pages/record-detail', body: { name: 'predio_record_detail' } }])
    renderWithProviders(<PageName />)
    expect(await screen.findByText('predio_record_detail')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `yarn workspace @chawpi/core test src/queries`
Expected: FAIL, `Failed to resolve import "./index"`.

- [ ] **Step 3: Write `frontend/packages/core/src/queries/moduleQueries.ts`**

```ts
import { useQueryClient } from '@tanstack/react-query'
import { useRegistry } from '../app/context'

// modules cache per-object data of their own (gis: features). a record or field write makes it
// stale; core does not know those keys, each module lists its own.
export function useModuleQueryInvalidation(): (objectName: string) => void {
  const queryClient = useQueryClient()
  const registry = useRegistry()
  return (objectName: string) => {
    for (const keysOf of registry.recordQueryKeys) {
      for (const queryKey of keysOf(objectName)) void queryClient.invalidateQueries({ queryKey })
    }
  }
}
```

- [ ] **Step 4: Write `frontend/packages/core/src/queries/objects.ts`**

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { FieldMeta, ObjectDefinition, ObjectSummary, SystemField } from '../types/metadata'
import { useModuleQueryInvalidation } from './moduleQueries'

export function useObjects() {
  return useQuery({
    queryKey: ['objects'],
    queryFn: () => api<ObjectSummary[]>('/objects')
  })
}

export function useObjectDefinition(name: string | undefined) {
  return useQuery({
    queryKey: ['objects', name],
    queryFn: () => api<ObjectDefinition>(`/metadata/objects/${name}`),
    enabled: Boolean(name)
  })
}

// the names the platform keeps for itself. they do not change while the app is open.
export function useSystemFields() {
  return useQuery({
    queryKey: ['system-fields'],
    queryFn: () => api<SystemField[]>('/metadata/system-fields'),
    staleTime: Infinity
  })
}

export function useCreateObject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: unknown) => api<ObjectDefinition>('/objects', { method: 'POST', body: JSON.stringify(payload) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['objects'] })
  })
}

export function useUpdateObject(name: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: unknown) => api<ObjectSummary>(`/objects/${name}`, { method: 'PUT', body: JSON.stringify(payload) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['objects'] })
    }
  })
}

export function useDeleteObject() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => api<void>(`/objects/${name}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['objects'] })
  })
}

// a field change reshapes the table, the form and whatever modules cache per object: drop all of it
function useObjectInvalidation() {
  const queryClient = useQueryClient()
  const invalidateModules = useModuleQueryInvalidation()
  return (objectName: string) => {
    void queryClient.invalidateQueries({ queryKey: ['objects', objectName] })
    void queryClient.invalidateQueries({ queryKey: ['records', objectName] })
    invalidateModules(objectName)
  }
}

export function useAddField(objectName: string) {
  const invalidate = useObjectInvalidation()
  return useMutation({
    mutationFn: (payload: unknown) => api<FieldMeta>(`/metadata/objects/${objectName}/fields`, { method: 'POST', body: JSON.stringify(payload) }),
    onSuccess: () => invalidate(objectName)
  })
}

export function useUpdateField(objectName: string) {
  const invalidate = useObjectInvalidation()
  return useMutation({
    mutationFn: ({ field, payload }: { field: string; payload: unknown }) =>
      api<FieldMeta>(`/metadata/objects/${objectName}/fields/${field}`, { method: 'PUT', body: JSON.stringify(payload) }),
    onSuccess: () => invalidate(objectName)
  })
}

export function useDeleteField(objectName: string) {
  const invalidate = useObjectInvalidation()
  return useMutation({
    mutationFn: (field: string) => api<void>(`/metadata/objects/${objectName}/fields/${field}`, { method: 'DELETE' }),
    onSuccess: () => invalidate(objectName)
  })
}
```

- [ ] **Step 5: Write `frontend/packages/core/src/queries/records.ts`**

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Paged, RecordItem, RecordPayload } from '../types/metadata'
import { useModuleQueryInvalidation } from './moduleQueries'

export function useRecords(objectName: string | undefined, params: Record<string, string>) {
  const search = new URLSearchParams(Object.entries(params).filter(([, value]) => value !== '' && value !== undefined)).toString()
  return useQuery({
    queryKey: ['records', objectName, search],
    queryFn: () => api<Paged<RecordItem>>(`/objects/${objectName}/records?${search}`),
    enabled: Boolean(objectName)
  })
}

export function useRecord(objectName: string | undefined, id: string | undefined) {
  return useQuery({
    queryKey: ['record', objectName, id],
    queryFn: () => api<RecordItem>(`/objects/${objectName}/records/${id}`),
    enabled: Boolean(objectName && id)
  })
}

// a section left out is left alone by the server; one value sent as null is cleared
export function useSaveRecord(objectName: string, id?: string) {
  const queryClient = useQueryClient()
  const invalidateModules = useModuleQueryInvalidation()
  return useMutation({
    mutationFn: (payload: RecordPayload) =>
      api<RecordItem>(id ? `/objects/${objectName}/records/${id}` : `/objects/${objectName}/records`, {
        method: id ? 'PUT' : 'POST',
        body: JSON.stringify(payload)
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['records', objectName] })
      if (id) void queryClient.invalidateQueries({ queryKey: ['record', objectName, id] })
      // a relation field is a link: related lists on both sides are now stale
      void queryClient.invalidateQueries({ queryKey: ['related'] })
      invalidateModules(objectName)
    }
  })
}

export function useDeleteRecord(objectName: string) {
  const queryClient = useQueryClient()
  const invalidateModules = useModuleQueryInvalidation()
  return useMutation({
    mutationFn: (id: string) => api<void>(`/objects/${objectName}/records/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['records', objectName] })
      void queryClient.invalidateQueries({ queryKey: ['related'] })
      invalidateModules(objectName)
    }
  })
}
```

- [ ] **Step 6: Copy the remaining hooks verbatim**

Copy each function below from `/Users/jorge/IdeaProjects/sapgis/frontend/src/lib/queries.ts` exactly as it is, with the comment line above it, under the header shown. Change nothing except the file it lands in.

`frontend/packages/core/src/queries/relationships.ts`: `useRelationships`, `useObjectRelationships`, `useRelatedRecords`, `useCreateRelationship`, `useUpdateRelationship`, `useDeleteRelationship`, `useLinkRelated`.

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Paged, RecordItem, RelatedSide, Relationship } from '../types/metadata'
```

`frontend/packages/core/src/queries/pages.ts`: `useResolvedPage`, `useTemplates`, `usePages`, `useSavePage`, `useDeletePage`. Replace the comment above `useResolvedPage` (`// never 404s: …`) with:

```ts
// 404s when the backend has no pages module; RecordDetailPage then draws fallbackPage (R8)
```

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Page, PagePayload, PageTemplate } from '../types/metadata'
```

`frontend/packages/core/src/queries/views.ts`: `useViews`, `useView`, `useSaveView`, `useDeleteView`.

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { View, ViewPayload } from '../types/metadata'
```

`frontend/packages/core/src/queries/forms.ts`: `useForms`, `useForm`, `useSaveForm`, `useDeleteForm`.

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Form, FormPayload } from '../types/metadata'
```

Do NOT copy `useFeatures`, the `RecordPayload` interface, the document hooks, or `invalidateObject` (R10; replaced in Steps 4–5). If `tsc` reports an unused type import in one of these headers, remove that name only.

- [ ] **Step 7: Barrel** — `frontend/packages/core/src/queries/index.ts`

```ts
export * from './objects'
export * from './records'
export * from './relationships'
export * from './pages'
export * from './views'
export * from './forms'
export { useModuleQueryInvalidation } from './moduleQueries'
```

Append to `frontend/packages/core/src/index.ts`:

```ts
export * from './queries'
```

- [ ] **Step 8: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green, including the 5 new query tests. Nothing is committed.

---

### Task 8: App shell, routes, login, dashboard, `coreModule`, `ChawpiApp`

**Files:**
- Create: `frontend/packages/core/src/shell/{PageHeader.tsx, AppShell.tsx}` (replace sapgis `components/layout/AppShell.tsx`)
- Create: `frontend/packages/core/src/app/{AuthGate.tsx, ChawpiRoutes.tsx, coreModule.ts, ChawpiApp.tsx}` (replace sapgis `app/App.tsx`, `main.tsx`)
- Create (ported + edited): `frontend/packages/core/src/auth/LoginPage.tsx`
- Create: `frontend/packages/core/src/features/dashboard/DashboardPage.tsx`
- Test: `frontend/packages/core/src/shell/AppShell.test.tsx`, `frontend/packages/core/src/app/ChawpiRoutes.test.tsx`, `frontend/packages/core/src/features/dashboard/DashboardPage.test.tsx`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: registry, links, context hooks, `useAuth`, `changeLanguage`, `ChawpiProviders`, `createChawpiI18n`, `createApiClient`, `resolveConfig` (Tasks 3–5); `useObjects` (Task 7); `renderWithProviders`, `mockFetch` (Task 6).
- Produces:
  - `PageHeader({ title: string; subtitle?: string; actions?: ReactNode })`. It is sapgis's, and every ported page imports it from `shell/PageHeader` (the port script maps `@/components/layout/AppShell` there).
  - `AppShell()`, `AuthGate({ children })`, `ChawpiRoutes()`.
  - `coreModule: ChawpiModule` with id `'core'` and nav groups `data` (10), `builder` (30), `automation` (40), `administration` (50). Tasks 12–14 append routes and nav entries to its `routes`/`nav` arrays.
  - `ChawpiApp({ config?: Partial<ChawpiConfig>; modules?: ChawpiModule[] })`.
  - `LoginPage()`, `DashboardPage()`.

- [ ] **Step 1: Write the failing tests**

`frontend/packages/core/src/shell/AppShell.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import { coreModule } from '../app/coreModule'
import type { ChawpiModule } from '../registry/contract'
import { AppShell } from './AppShell'

const Sheets = () => <p>hojas</p>
const plans: ChawpiModule = {
  id: 'plans',
  basePath: 'plans',
  routes: [{ id: 'sheets', path: 'sheets', component: Sheets }],
  navGroups: [{ id: 'plans', labelKey: 'plans:nav.group', order: 20 }],
  nav: [
    { group: 'plans', labelKey: 'plans:nav.sheets', order: 10, route: 'sheets' },
    { group: 'plans', labelKey: 'plans:nav.secret', order: 20, route: 'sheets', visible: (permissions) => permissions?.admin === true }
  ],
  i18n: { es: { nav: { group: 'Planos', sheets: 'Hojas', secret: 'Secreto' } }, en: { nav: { group: 'Plans', sheets: 'Sheets', secret: 'Secret' } } }
}

describe('AppShell', () => {
  it('builds the sidebar from the registered modules, in group order', () => {
    renderWithProviders(<AppShell />, { modules: [coreModule, plans] })

    const headings = screen.getAllByText(/^(Datos|Planos)$/).map((node) => node.textContent)
    expect(headings).toEqual(['Datos', 'Planos'])
    expect(screen.getByRole('link', { name: 'Hojas' })).toHaveAttribute('href', '/plans/sheets')
    expect(screen.getByRole('link', { name: 'Inicio' })).toHaveAttribute('href', '/')
  })

  it('shows a placeholder entry as disabled text, not as a link', () => {
    renderWithProviders(<AppShell />, { modules: [coreModule] })
    expect(screen.getByText('Registros')).toHaveAttribute('title', 'Registros')
    expect(screen.queryByRole('link', { name: 'Registros' })).not.toBeInTheDocument()
  })

  it('leaves out a group nobody put anything in', () => {
    renderWithProviders(<AppShell />, { modules: [coreModule] })
    expect(screen.queryByText('Automatización')).not.toBeInTheDocument()
  })

  it('hides an entry the caller may not use', () => {
    renderWithProviders(<AppShell />, { modules: [coreModule, plans], permissions: { admin: false, objects: {} } })
    expect(screen.queryByText('Secreto')).not.toBeInTheDocument()
    expect(screen.getByText('Hojas')).toBeInTheDocument()
  })

  it('offers the next configured language and remembers the choice', async () => {
    renderWithProviders(<AppShell />, { modules: [coreModule] })

    await userEvent.click(screen.getByRole('button', { name: 'EN' }))

    expect(await screen.findByRole('button', { name: 'ES' })).toBeInTheDocument()
    expect(screen.getByText('Data')).toBeInTheDocument()
    expect(localStorage.getItem('chawpi-test.lang')).toBe('en')
  })

  it('has no language toggle in a one-language app', () => {
    renderWithProviders(<AppShell />, { modules: [coreModule], config: { languages: ['es'] } })
    expect(screen.queryByRole('button', { name: 'EN' })).not.toBeInTheDocument()
  })

  it('names the app from config, or from the strings when config says nothing', () => {
    const { unmount } = renderWithProviders(<AppShell />, { modules: [coreModule], config: { appName: 'Catastro' } })
    expect(screen.getByText('Catastro')).toBeInTheDocument()
    unmount()
    renderWithProviders(<AppShell />, { modules: [coreModule] })
    expect(screen.getByText('Chawpi')).toBeInTheDocument()
  })
})
```

`frontend/packages/core/src/app/ChawpiRoutes.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import type { ChawpiModule } from '../registry/contract'
import { ChawpiRoutes } from './ChawpiRoutes'
import { coreModule } from './coreModule'

const Sheet = () => <p>hoja suelta</p>
const Plans = () => <p>planos cargados</p>
const plans: ChawpiModule = {
  id: 'plans',
  basePath: 'plans',
  routes: [
    { id: 'sheet', path: 'sheet/:id', component: Sheet, chrome: 'bare' },
    { id: 'list', path: '', lazy: async () => ({ default: Plans }) }
  ]
}

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

function mount(route: string, signedIn = true) {
  fetch = mockFetch([
    { path: '/objects', body: [] },
    { method: 'POST', path: '/auth/login', body: { token: 't', expiresAt: '2026-12-31T00:00:00Z', user: { id: 'u1', email: 'ana@chawpi.test', displayName: 'Ana', organizationId: 'o1', roles: [] } } },
    { path: '/auth/me/permissions', body: { admin: true, objects: {} } }
  ])
  return renderWithProviders(<ChawpiRoutes />, { modules: [coreModule, plans], route, user: signedIn ? undefined : null, permissions: signedIn ? undefined : null })
}

describe('ChawpiRoutes', () => {
  it('opens the dashboard at the root, inside the shell', async () => {
    mount('/')
    expect(await screen.findByRole('heading', { name: 'Inicio' })).toBeInTheDocument()
    expect(screen.getByText('Datos')).toBeInTheDocument()
  })

  it('sends a signed-out visitor of any shell route to the login page', async () => {
    mount('/plans', false)
    expect(await screen.findByRole('button', { name: 'Iniciar sesión' })).toBeInTheDocument()
  })

  it('sends an unknown path home', async () => {
    mount('/nowhere/at/all')
    expect(await screen.findByRole('heading', { name: 'Inicio' })).toBeInTheDocument()
  })

  it('draws a bare route without the shell', async () => {
    mount('/plans/sheet/7')
    expect(await screen.findByText('hoja suelta')).toBeInTheDocument()
    expect(screen.queryByText('Datos')).not.toBeInTheDocument()
  })

  it('loads a lazy route inside the shell', async () => {
    mount('/plans')
    expect(await screen.findByText('planos cargados')).toBeInTheDocument()
    expect(screen.getByText('Datos')).toBeInTheDocument()
  })

  it('lands on the dashboard after signing in', async () => {
    mount('/login', false)
    await userEvent.type(await screen.findByLabelText('Contraseña'), 'secret')
    await userEvent.type(screen.getByLabelText('Correo'), 'ana@chawpi.test')
    await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(await screen.findByRole('heading', { name: 'Inicio' })).toBeInTheDocument()
    expect(fetch?.calls.find((call) => call.path === '/auth/login')?.body).toEqual({ email: 'ana@chawpi.test', password: 'secret' })
  })
})
```

`frontend/packages/core/src/features/dashboard/DashboardPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import type { ChawpiModule } from '../../registry/contract'
import type { ObjectSummary } from '../../types/metadata'
import { DashboardPage } from './DashboardPage'

const objects: ObjectSummary[] = [
  { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true },
  { id: 'o2', name: 'nota', label: 'Nota', pluralLabel: 'Notas', description: null, enabled: true }
]

vi.mock('../../queries', () => ({ useObjects: () => ({ data: objects, isLoading: false }) }))

const extras: ChawpiModule = {
  id: 'extras',
  dashboardCards: [({ objects: all }) => <p>{`con plano: ${all.length}`}</p>],
  objectTileDetails: [({ object }) => <span>{`detalle ${object.name}`}</span>]
}

describe('DashboardPage', () => {
  it('counts the objects and links each tile to its records', () => {
    renderWithProviders(<DashboardPage />)
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Predios/ })).toHaveAttribute('href', '/data/objects/predio/records')
  })

  it('draws what modules add: cards beside the count, a line on each tile', () => {
    renderWithProviders(<DashboardPage />, { modules: [extras] })
    expect(screen.getByText('con plano: 2')).toBeInTheDocument()
    expect(screen.getByText('detalle predio')).toBeInTheDocument()
    expect(screen.getByText('detalle nota')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/shell src/app src/features/dashboard`
Expected: FAIL, unresolved `./AppShell`, `./ChawpiRoutes`, `./coreModule`, `./DashboardPage`.

- [ ] **Step 3: Write `frontend/packages/core/src/shell/PageHeader.tsx`** (sapgis `PageHeader`, moved out of AppShell)

```tsx
import type { ReactNode } from 'react'

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border bg-surface px-8 py-5">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-ink">{title}</h1>
        {subtitle ? <p className="mt-0.5 text-sm text-ink-muted">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </div>
  )
}
```

- [ ] **Step 4: Write `frontend/packages/core/src/shell/AppShell.tsx`**

The markup and classes are sapgis's. The groups come from the registry, the language cycles through `config.languages`, and the name comes from config.

```tsx
import { Home, LogOut } from 'lucide-react'
import { Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, Outlet } from 'react-router'
import { Button, cn } from '@chawpi/ui'
import { useApiClient, useChawpiConfig, useChawpiLinks, useRegistry } from '../app/context'
import { useAuth } from '../auth/AuthProvider'
import { changeLanguage } from '../i18n/createI18n'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  cn('flex items-center gap-2.5 rounded-md px-3 py-2 text-sm', isActive ? 'bg-white/12 text-white' : 'text-shell-muted hover:bg-white/8 hover:text-white')

// the sidebar is what the registered modules contribute. what is not built yet shows as disabled, not missing.
export function AppShell() {
  const { t, i18n } = useTranslation()
  const { user, permissions, signOut } = useAuth()
  const config = useChawpiConfig()
  const registry = useRegistry()
  const links = useChawpiLinks()
  const { keys } = useApiClient()

  const languages = config.languages
  const next = languages[(languages.indexOf(i18n.language) + 1) % languages.length]
  // a group whose every entry is hidden or missing would be a heading over nothing
  const groups = registry.navGroups
    .map((group) => ({ ...group, items: group.items.filter((item) => item.visible?.(permissions) ?? true) }))
    .filter((group) => group.items.length > 0)

  return (
    <div className="flex h-full">
      <aside className="flex w-60 shrink-0 flex-col bg-shell text-white">
        <div className="px-5 py-5">
          <p className="text-lg font-semibold tracking-tight">{config.appName ?? t('app.name')}</p>
          <p className="mt-0.5 text-[11px] leading-tight text-shell-muted">{config.appTagline ?? t('app.tagline')}</p>
        </div>

        <nav className="flex-1 space-y-5 overflow-y-auto px-3 pb-4">
          <NavLink to={links.home()} end className={linkClass}>
            <Home className="h-4 w-4" />
            {t('nav.home')}
          </NavLink>

          {groups.map((group) => (
            <div key={group.id}>
              <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-shell-muted/70">{t(group.labelKey)}</p>
              <div className="space-y-0.5">
                {group.items.map((item) => {
                  const Icon = item.icon
                  const icon = Icon ? <Icon className="h-4 w-4" /> : <span className="h-4 w-4" />
                  if (!item.to || item.disabled) {
                    return (
                      <span
                        key={item.key}
                        className="flex cursor-not-allowed items-center gap-2.5 rounded-md px-3 py-2 text-sm text-shell-muted/45"
                        title={t(item.labelKey)}
                      >
                        {icon}
                        {t(item.labelKey)}
                      </span>
                    )
                  }
                  return (
                    <NavLink key={item.key} to={item.to} className={linkClass}>
                      {icon}
                      {t(item.labelKey)}
                    </NavLink>
                  )
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="border-t border-white/10 px-4 py-3">
          <p className="truncate text-xs text-white">{user?.displayName}</p>
          <p className="truncate text-[11px] text-shell-muted">{user?.email}</p>
          <div className="mt-2 flex items-center gap-1">
            {languages.length > 1 ? (
              <Button variant="ghost" size="sm" className="text-shell-muted hover:text-white" onClick={() => void changeLanguage(i18n, keys.lang, next)}>
                {next.toUpperCase()}
              </Button>
            ) : null}
            <Button variant="ghost" size="sm" className="text-shell-muted hover:text-white" onClick={signOut}>
              <LogOut className="h-4 w-4" />
              {t('auth.signOut')}
            </Button>
          </div>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">
        {/* a lazy module page loads here while the sidebar stays */}
        <Suspense fallback={<p className="p-8 text-sm text-ink-muted">{t('common.loading')}</p>}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}
```

- [ ] **Step 5: Port and edit the login page**

```bash
node frontend/tooling/port-from-sapgis.mjs features/auth/LoginPage.tsx frontend/packages/core/src/auth/LoginPage.tsx
```

Then make exactly these edits in `frontend/packages/core/src/auth/LoginPage.tsx`:
1. Add the import `import { useChawpiConfig, useChawpiLinks } from '../app/context'`.
2. After `const navigate = useNavigate()`, add:
   ```tsx
     const config = useChawpiConfig()
     const links = useChawpiLinks()
   ```
3. `useState('admin@chawpi.local')` → `useState(config.defaultLoginEmail)`.
4. `<Navigate to="/" replace />` → `<Navigate to={links.home()} replace />`, and `void navigate('/')` → `void navigate(links.home())`.
5. `{t('app.name')}` → `{config.appName ?? t('app.name')}`, and `{t('app.tagline')}` → `{config.appTagline ?? t('app.tagline')}`.

The import of `useAuth` must read `from './AuthProvider'` (the port script does this).

- [ ] **Step 6: Write `frontend/packages/core/src/features/dashboard/DashboardPage.tsx`**

The sapgis page without its geometry card and per-object geometry line. Those come back through `dashboardCards`/`objectTileDetails` from `@chawpi/gis` (R9).

```tsx
import { Boxes } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Card, CardBody } from '@chawpi/ui'
import { useChawpiLinks, useRegistry } from '../../app/context'
import { useObjects } from '../../queries'
import { PageHeader } from '../../shell/PageHeader'

export function DashboardPage() {
  const { t } = useTranslation()
  const links = useChawpiLinks()
  const { dashboardCards, objectTileDetails } = useRegistry()
  const { data: objects = [], isLoading } = useObjects()

  return (
    <>
      <PageHeader title={t('dashboard.title')} subtitle={t('dashboard.welcome')} />
      <div className="grid gap-4 p-8 sm:grid-cols-2 lg:grid-cols-3">
        <Card>
          <CardBody>
            <div className="flex items-center gap-2 text-ink-muted">
              <Boxes className="h-4 w-4" />
              <span className="text-sm">{t('dashboard.objects')}</span>
            </div>
            <p className="mt-2 text-3xl font-semibold text-ink">{isLoading ? '—' : objects.length}</p>
          </CardBody>
        </Card>
        {dashboardCards.map((DashboardCard, index) => (
          <DashboardCard key={index} objects={objects} loading={isLoading} />
        ))}
      </div>

      <div className="grid gap-3 px-8 pb-8 sm:grid-cols-2 lg:grid-cols-3">
        {objects.map((item) => (
          <Link
            key={item.id}
            to={links.records(item.name)}
            className="rounded-card border border-border bg-surface px-5 py-4 transition-colors hover:border-brand"
          >
            <p className="font-medium text-ink">{item.pluralLabel}</p>
            {objectTileDetails.map((Detail, index) => (
              <Detail key={index} object={item} />
            ))}
          </Link>
        ))}
      </div>
    </>
  )
}
```

- [ ] **Step 7: Write the routing pieces**

`frontend/packages/core/src/app/AuthGate.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { useAuth } from '../auth/AuthProvider'
import { useChawpiLinks } from './context'

// every signed-in route needs the same check, shell or not: share the check, not the layout
export function AuthGate({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const links = useChawpiLinks()
  return user ? children : <Navigate to={links.login()} replace />
}
```

`frontend/packages/core/src/app/ChawpiRoutes.tsx`:

```tsx
import { Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, Route, Routes } from 'react-router'
import type { RouteChrome } from '../registry/contract'
import { AppShell } from '../shell/AppShell'
import { AuthGate } from './AuthGate'
import { useChawpiLinks, useRegistry } from './context'

// every registered route, grouped by chrome. unknown paths go home.
export function ChawpiRoutes() {
  const { t } = useTranslation()
  const registry = useRegistry()
  const links = useChawpiLinks()

  const mount = (chrome: RouteChrome) =>
    registry.routes
      .filter((route) => route.chrome === chrome)
      .map((route) => <Route key={route.key} path={route.path} element={<route.Component />} />)

  return (
    // shell routes suspend inside the shell; this catches public and bare ones
    <Suspense fallback={<p className="p-8 text-sm text-ink-muted">{t('common.loading')}</p>}>
      <Routes>
        {mount('public')}
        {/* bare: a printed sheet has no sidebar, so the browser's pdf has no chrome in it */}
        <Route
          element={
            <AuthGate>
              <Outlet />
            </AuthGate>
          }
        >
          {mount('bare')}
        </Route>
        <Route
          element={
            <AuthGate>
              <AppShell />
            </AuthGate>
          }
        >
          {mount('shell')}
        </Route>
        <Route path="*" element={<Navigate to={links.home()} replace />} />
      </Routes>
    </Suspense>
  )
}
```

`frontend/packages/core/src/app/coreModule.ts`:

```ts
import { Database } from 'lucide-react'
import { LoginPage } from '../auth/LoginPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { CORE_MODULE_ID, CORE_ROUTE_PATHS } from '../links/links'
import type { ChawpiModule } from '../registry/contract'

// core is a module like the others: its screens, its sidebar. ChawpiApp registers it first.
// the builder and automation groups are declared here so every module that fills them shares one.
export const coreModule: ChawpiModule = {
  id: CORE_MODULE_ID,
  navGroups: [
    { id: 'data', labelKey: 'nav.data', order: 10 },
    { id: 'builder', labelKey: 'nav.builder', order: 30 },
    { id: 'automation', labelKey: 'nav.automation', order: 40 },
    { id: 'administration', labelKey: 'nav.administration', order: 50 }
  ],
  routes: [
    { id: 'login', path: CORE_ROUTE_PATHS.login, component: LoginPage, chrome: 'public' },
    { id: 'home', path: CORE_ROUTE_PATHS.home, component: DashboardPage }
  ],
  nav: [{ group: 'data', labelKey: 'nav.records', order: 20, icon: Database, disabled: true }]
}
```

`frontend/packages/core/src/app/ChawpiApp.tsx`:

```tsx
import { QueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter } from 'react-router'
import { createApiClient } from '../api/client'
import { createChawpiI18n } from '../i18n/createI18n'
import type { ChawpiModule } from '../registry/contract'
import { createRegistry } from '../registry/createRegistry'
import { ChawpiProviders } from './ChawpiProviders'
import { ChawpiRoutes } from './ChawpiRoutes'
import { resolveConfig, type ChawpiConfig } from './config'
import { coreModule } from './coreModule'

export interface ChawpiAppProps {
  config?: Partial<ChawpiConfig>
  modules?: ChawpiModule[]
}

// the whole app from config + modules. both are read once, at mount: they are wiring, not state.
export function ChawpiApp({ config, modules = [] }: ChawpiAppProps) {
  const [app] = useState(() => {
    const resolved = resolveConfig(config)
    const registry = createRegistry([coreModule, ...modules])
    const apiClient = createApiClient({ baseUrl: resolved.apiBaseUrl, storagePrefix: resolved.storagePrefix })
    const i18n = createChawpiI18n({ languages: resolved.languages, storageKey: apiClient.keys.lang, modules: registry.modules })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
    return { config: resolved, registry, apiClient, i18n, queryClient }
  })

  return (
    <ChawpiProviders config={app.config} registry={app.registry} apiClient={app.apiClient} i18n={app.i18n} queryClient={app.queryClient}>
      <BrowserRouter basename={app.config.basename}>
        <ChawpiRoutes />
      </BrowserRouter>
    </ChawpiProviders>
  )
}
```

- [ ] **Step 8: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { PageHeader } from './shell/PageHeader'
export { AppShell } from './shell/AppShell'
export { AuthGate } from './app/AuthGate'
export { ChawpiRoutes } from './app/ChawpiRoutes'
export { coreModule } from './app/coreModule'
export { ChawpiApp, type ChawpiAppProps } from './app/ChawpiApp'
export { LoginPage } from './auth/LoginPage'
export { DashboardPage } from './features/dashboard/DashboardPage'
```

- [ ] **Step 9: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green: 7 AppShell tests, 6 route tests and 2 dashboard tests are new. Nothing is committed.

---

### Task 9: Data table, dynamic form (field renderers via registry), related list

**Files:**
- Create (ported, no edits): `frontend/packages/core/src/components/data-table/{DataTable.tsx, DataTable.test.tsx}`, `frontend/packages/core/src/components/dynamic-form/fields/{FieldInput.tsx, RelationField.tsx}`
- Create: `frontend/packages/core/src/components/dynamic-form/DynamicForm.tsx` (rewrites sapgis's)
- Create (ported + edited): `frontend/packages/core/src/components/dynamic-form/DynamicForm.test.tsx`, `frontend/packages/core/src/components/related/RelatedList.tsx`
- Create: `frontend/packages/core/src/test/fakeModules.tsx`
- Test: `frontend/packages/core/src/components/related/RelatedList.test.tsx`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes:
  - `useRegistry`, `useChawpiLinks` (Task 5); `FieldRenderer` contract (Task 4);
  - `buildRecordSchema`/`toAttributes`/`toFormValues`, `recordSection`, `RecordPayload` (Task 3);
  - `useObjectDefinition`, `useRecords`, `useRelatedRecords`, `useLinkRelated` (Task 7).
- Produces:
  - `DynamicForm(props: DynamicFormProps)`, where `DynamicFormProps = { definition: ObjectDefinition; form?: Form; record?: RecordItem; submitting?: boolean; error?: string | null; onSubmit: (payload: RecordPayload) => void; onCancel?: () => void; readOnly?: boolean }`.
  - `DataTable`, `DataTableProps`, `FieldInput`, `RelationField`, `RelatedList` (sapgis signatures).
  - Test-only fakes in `src/test/fakeModules.tsx`, used by Tasks 10–13:
    - `sketchModule` (field type `SKETCH`, section `sketches`, widget `data-testid="sketch-field"` with a button `dibujar <field>` that sets `'drawn'`);
    - `noteModule` (page component `NOTE` rendering `nota <title> <recordId>`, page action `STAMP` rendering a button `sellar <title>`);
    - `issueModule` (history renderer `ISSUE` rendering a button `Ver documento <documentId>`, audit formatter for values with a `strokes` key → `boceto actualizado`, audit field label `sketch` → `Boceto`).

- [ ] **Step 1: Port the files that move unchanged**

```bash
C=frontend/packages/core/src
node frontend/tooling/port-from-sapgis.mjs components/data-table/DataTable.tsx $C/components/data-table/DataTable.tsx
node frontend/tooling/port-from-sapgis.mjs components/data-table/DataTable.test.tsx $C/components/data-table/DataTable.test.tsx
node frontend/tooling/port-from-sapgis.mjs components/dynamic-form/fields/FieldInput.tsx $C/components/dynamic-form/fields/FieldInput.tsx
node frontend/tooling/port-from-sapgis.mjs components/dynamic-form/fields/RelationField.tsx $C/components/dynamic-form/fields/RelationField.tsx
node frontend/tooling/port-from-sapgis.mjs components/related/RelatedList.tsx $C/components/related/RelatedList.tsx
node frontend/tooling/port-from-sapgis.mjs components/dynamic-form/DynamicForm.test.tsx $C/components/dynamic-form/DynamicForm.test.tsx
```
Expected: no `MANUAL:` lines. `FieldInput` needs no change: module field types never reach it (DynamicForm routes them to their renderer). Its `default` branch still draws a text input for any type nobody registered.

- [ ] **Step 2: Write the fakes** — `frontend/packages/core/src/test/fakeModules.tsx`

```tsx
import type { ChawpiModule } from '../registry/contract'

// stand-ins for P5 modules, so core tests exercise every extension point without GIS or documents.

// a section-bound field type, the way gis adds GEOMETRY
export const sketchModule: ChawpiModule = {
  id: 'sketch',
  fieldRenderers: {
    SKETCH: {
      section: 'sketches',
      uniqueAllowed: false,
      input: ({ field, value, onChange }) => (
        <div data-testid="sketch-field">
          <label>{field.label}</label>
          <span>{String(value ?? '')}</span>
          <button type="button" onClick={() => onChange('drawn')}>{`dibujar ${field.name}`}</button>
        </div>
      ),
      settings: {
        defaults: { strokeWidth: '2' },
        editor: ({ settings, onChange }) => (
          <input aria-label="grosor" value={settings.strokeWidth ?? ''} onChange={(event) => onChange({ strokeWidth: event.target.value })} />
        ),
        toPayload: (settings) => ({ strokeWidth: Number(settings.strokeWidth) || 2 })
      }
    }
  }
}

// a page component and an action kind, the way gis adds MAP and workflow adds TRANSITION
export const noteModule: ChawpiModule = {
  id: 'note',
  pageComponents: { NOTE: ({ component, record }) => <p data-testid="note">{`nota ${component.title ?? ''} ${record.id}`}</p> },
  pageActions: { STAMP: ({ component }) => <button type="button">{`sellar ${component.title ?? ''}`}</button> }
}

// a history entry body, an audit value and an audit column, the way documents and gis add theirs
export const issueModule: ChawpiModule = {
  id: 'issue',
  historyRenderers: { ISSUE: ({ entry }) => <button type="button">{`Ver documento ${entry.documentId ?? ''}`}</button> },
  auditValueFormatters: [{ matches: (value) => typeof value === 'object' && value !== null && 'strokes' in value, labelKey: 'issue:history.sketchUpdated' }],
  auditFieldLabels: { sketch: 'issue:history.sketch' },
  i18n: {
    es: { history: { sketchUpdated: 'boceto actualizado', sketch: 'Boceto' } },
    en: { history: { sketchUpdated: 'sketch updated', sketch: 'Sketch' } }
  }
}
```

- [ ] **Step 3: Edit the ported DynamicForm test to the registry world**

In `frontend/packages/core/src/components/dynamic-form/DynamicForm.test.tsx`:
1. Delete the `vi.mock('.../components/map/GeometryField', …)` block and its comment. No map component exists in core.
2. Add the import `import { sketchModule } from '../../test/fakeModules'`.
3. In `'submits attributes with decimals coerced to numbers'`, replace the expected payload with the following. With no module registered, no section goes out (R3).
   ```tsx
       expect(onSubmit.mock.calls[0][0]).toEqual({
         attributes: { codigo: 'P-001', area: 850.5, uso: null }
       })
   ```
4. Replace the whole test `'shows one geometry widget per geometry field'` (and its ADR-019 comment) with:
   ```tsx
     // a module field type is drawn by its renderer where the field sits: one widget per field
     it('draws one module widget per module field', () => {
       const { rerender } = renderWithProviders(<DynamicForm definition={definition} onSubmit={onSubmit} />, { modules: [sketchModule] })
       expect(screen.queryAllByTestId('sketch-field')).toHaveLength(0)

       const lote = field({ name: 'lote', label: 'Lote', type: 'SKETCH' })
       const acceso = field({ name: 'acceso', label: 'Acceso', type: 'SKETCH' })
       rerender(<DynamicForm definition={{ ...definition, fields: [...definition.fields, lote, acceso] }} onSubmit={onSubmit} />)
       expect(screen.queryAllByTestId('sketch-field')).toHaveLength(2)
     })

     it('hands a module field its current value and sends it back under its section', async () => {
       const user = userEvent.setup()
       const lote = field({ id: 'f9', name: 'lote', label: 'Lote', type: 'SKETCH' })
       renderWithProviders(
         <DynamicForm
           definition={{ ...definition, fields: [...definition.fields, lote] }}
           record={{ id: 'r1', createdAt: null, updatedAt: null, attributes: { codigo: 'P-1' }, sketches: { lote: 'old' } }}
           onSubmit={onSubmit}
         />,
         { modules: [sketchModule] }
       )
       expect(screen.getByTestId('sketch-field')).toHaveTextContent('old')

       await user.click(screen.getByRole('button', { name: 'dibujar lote' }))
       await user.click(screen.getByRole('button', { name: /Guardar|Save/ }))

       await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
       expect(onSubmit.mock.calls[0][0]).toEqual({ attributes: { codigo: 'P-1', area: null, uso: null }, sketches: { lote: 'drawn' } })
     })

     // sapgis always sent `geometries`, drawn or not. every registered section keeps that contract
     it('sends every registered section, even when the object has none of its fields', async () => {
       const user = userEvent.setup()
       renderWithProviders(<DynamicForm definition={definition} onSubmit={onSubmit} />, { modules: [sketchModule] })

       await user.type(screen.getByLabelText(/Código/), 'P-2')
       await user.click(screen.getByRole('button', { name: /Guardar|Save/ }))

       await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
       expect(onSubmit.mock.calls[0][0]).toEqual({ attributes: { codigo: 'P-2', area: null, uso: null }, sketches: {} })
     })
   ```
   Keep the fixtures' `geometry: null` / `geometries: {}` keys as they are. They are what the server sends when gis is installed, and they must still compile (index signatures).

- [ ] **Step 4: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/components`
Expected: FAIL, `Failed to resolve import "./DynamicForm"`. DataTable's ported tests already pass.

- [ ] **Step 5: Write `frontend/packages/core/src/components/dynamic-form/DynamicForm.tsx`**

The markup is sapgis's. The static `GeometryField` import is replaced by the registry lookup (R3).

```tsx
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import type { Control, FieldErrors, UseFormRegister } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { Button, Label } from '@chawpi/ui'
import { useRegistry } from '../../app/context'
import { buildRecordSchema, toAttributes, toFormValues } from '../../lib/metadata-to-zod'
import type { FieldRenderer } from '../../registry/contract'
import { recordSection, type FieldMeta, type Form, type ObjectDefinition, type RecordItem, type RecordPayload } from '../../types/metadata'
import { FieldInput } from './fields/FieldInput'

export interface DynamicFormProps {
  definition: ObjectDefinition
  // a named layout: sections in order instead of one flat grid
  form?: Form
  record?: RecordItem
  submitting?: boolean
  error?: string | null
  onSubmit: (payload: RecordPayload) => void
  onCancel?: () => void
  // a page can show the same object twice (a page section plus its own FORM); only one may save.
  // the rest are read-along field groups: fields to look at, no button to press.
  readOnly?: boolean
}

type Sections = Record<string, Record<string, unknown>>

// every registered section starts from what the record holds. all of them go out on submit, drawn
// or not: the contract sapgis had with its shape section.
function initialSections(renderers: Readonly<Record<string, FieldRenderer>>, record: RecordItem | undefined): Sections {
  const sections: Sections = {}
  for (const renderer of Object.values(renderers)) sections[renderer.section] = { ...recordSection(record, renderer.section) }
  return sections
}

// metadata in, working form out. adding a field to an object changes this form for free.
export function DynamicForm({ definition, form, record, submitting, error, onSubmit, onCancel, readOnly }: DynamicFormProps) {
  const { t } = useTranslation()
  const { fieldRenderers } = useRegistry()
  const sections = form ? resolveSections(form, definition.fields) : null
  const editable = definition.fields.filter((field) => field.visible)
  // a named form is the whole contract: validate and submit only what it shows
  const shown = sections ? sections.flatMap((section) => section.fields) : definition.fields
  // a module field never travels as an attribute: it has its own widget and its own payload slot
  const modelFields = shown.filter((field) => !fieldRenderers[field.type])
  const [extra, setExtra] = useState<Sections>(() => initialSections(fieldRenderers, record))

  const {
    register,
    control,
    handleSubmit,
    formState: { errors }
  } = useForm<Record<string, unknown>>({
    resolver: zodResolver(buildRecordSchema(modelFields)),
    defaultValues: toFormValues(modelFields, record?.attributes)
  })

  // a module field is a field, so it is drawn where the author put it instead of trailing the form
  const renderField = (field: FieldMeta) => {
    const renderer = fieldRenderers[field.type]
    if (!renderer) return <FieldRow key={field.id} field={field} control={control} register={register} errors={errors} />
    const Input = renderer.input
    return (
      <div key={field.id} className="sm:col-span-2">
        <Input
          field={field}
          value={extra[renderer.section]?.[field.name] ?? null}
          onChange={(value) => setExtra((current) => ({ ...current, [renderer.section]: { ...current[renderer.section], [field.name]: value } }))}
        />
      </div>
    )
  }

  return (
    <form className="space-y-5" noValidate onSubmit={handleSubmit((values) => onSubmit({ attributes: toAttributes(modelFields, values), ...extra }))}>
      {error ? <p className="rounded-md border border-danger/40 bg-danger/5 px-4 py-2.5 text-sm text-danger">{error}</p> : null}

      {sections ? (
        <div className="space-y-6">
          {sections.map((section, index) => (
            <section key={index} className="space-y-3">
              {section.title ? <h3 className="border-b border-border pb-1.5 text-sm font-semibold text-ink">{section.title}</h3> : null}
              <div className="grid gap-4 sm:grid-cols-2">{section.fields.map(renderField)}</div>
            </section>
          ))}
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">{editable.map(renderField)}</div>
      )}

      {readOnly ? null : (
        <div className="flex items-center gap-2">
          <Button type="submit" disabled={submitting}>
            {submitting ? t('common.loading') : t('common.save')}
          </Button>
          {onCancel ? (
            <Button type="button" variant="secondary" onClick={onCancel}>
              {t('common.cancel')}
            </Button>
          ) : null}
        </div>
      )}
    </form>
  )
}

function FieldRow({
  field,
  control,
  register,
  errors
}: {
  field: FieldMeta
  control: Control<Record<string, unknown>>
  register: UseFormRegister<Record<string, unknown>>
  errors: FieldErrors<Record<string, unknown>>
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={field.name}>
        {field.label}
        {field.required ? <span className="ml-1 text-danger">*</span> : null}
      </Label>
      <FieldInput field={field} control={control} register={register} invalid={Boolean(errors[field.name])} />
      {field.description ? <p className="text-xs text-ink-muted">{field.description}</p> : null}
      {errors[field.name] ? <p className="text-xs text-danger">{String(errors[field.name]?.message ?? '')}</p> : null}
    </div>
  )
}

// a section may name a field that was deleted meanwhile: skip it, keep the section
function resolveSections(form: Form, fields: FieldMeta[]): { title: string | null; fields: FieldMeta[] }[] {
  const byName = new Map(fields.map((field) => [field.name, field]))
  return form.definition.sections.map((section) => ({
    title: section.title,
    fields: section.fields.map((name) => byName.get(name)).filter((field): field is FieldMeta => field !== undefined)
  }))
}
```

- [ ] **Step 6: Route the related list's edit link through links**

In `frontend/packages/core/src/components/related/RelatedList.tsx`:
1. Add the import `import { useChawpiLinks } from '../../app/context'`.
2. In `RelatedList`, after `const { t } = useTranslation()`, add `const links = useChawpiLinks()`.
3. ``<Link to={`/data/objects/${side.objectName}/records/${record.id}`}>`` → `<Link to={links.record(side.objectName, record.id)}>`.

`frontend/packages/core/src/components/related/RelatedList.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import type { RelatedSide } from '../../types/metadata'
import { RelatedList } from './RelatedList'

vi.mock('../../queries', () => ({
  useObjectDefinition: () => ({
    data: {
      fields: [
        {
          id: 'f1',
          name: 'nombre',
          label: 'Nombre',
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
      ]
    }
  }),
  useRelatedRecords: () => ({ data: { content: [{ id: 'a/b', createdAt: null, updatedAt: null, attributes: { nombre: 'Ana' } }] } }),
  useRecords: () => ({ data: { content: [] } }),
  useLinkRelated: () => ({ link: { mutate: vi.fn(), isPending: false }, unlink: { mutate: vi.fn(), isPending: false } })
}))

const side: RelatedSide = { relationship: 'predio_titular', label: 'Titular', type: 'MANY_TO_ONE', objectName: 'titular', objectLabel: 'Titular', many: false }

describe('RelatedList', () => {
  it('links each related record to its own detail page', () => {
    renderWithProviders(<RelatedList objectName="predio" recordId="r1" side={side} />)
    expect(screen.getByText('Ana')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Editar' })).toHaveAttribute('href', '/data/objects/titular/records/a%2Fb')
  })
})
```

If the ported `RelatedList` calls another hook from `../../queries` in the many-to-many branch, add that name to the mock with the same `{ data: … }` shape.

- [ ] **Step 7: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { DataTable, type DataTableProps } from './components/data-table/DataTable'
export { DynamicForm, type DynamicFormProps } from './components/dynamic-form/DynamicForm'
export { FieldInput } from './components/dynamic-form/fields/FieldInput'
export { RelationField } from './components/dynamic-form/fields/RelationField'
export { RelatedList } from './components/related/RelatedList'
```

- [ ] **Step 8: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green. Nothing is committed.

---

### Task 10: History and audit (renderers via registry, breaks history↔documents)

**Files:**
- Create (ported + edited): `frontend/packages/core/src/features/history/{i18n.ts, api.ts, ChangeList.tsx, OperationBadge.tsx, changes.test.ts, RecordHistory.test.tsx}`
- Create: `frontend/packages/core/src/features/history/{changes.ts, useAuditExtensions.ts, RecordHistory.tsx}` (rewrites)
- Modify: `frontend/packages/core/src/i18n/coreBundles.ts`, `frontend/packages/core/src/i18n/createI18n.test.ts`, `frontend/packages/core/src/index.ts`
- Not ported (P5 documents): `features/history/IssuedDocumentLink.tsx`, `IssuedDocumentLink.test.tsx`, `useIssuedDocument`

**Interfaces:**
- Consumes:
  - `AuditEntry`, `AuditEntryPayload`, `AuditFilters`, `AuditOperation`, `ChangeDescription` (Task 4 `types/audit.ts`);
  - `AuditValueFormatter`, `HistoryEntryProps` (Task 4);
  - `useRegistry` (Task 5), `coreBundles` (Task 5);
  - `issueModule` (Task 9 fakes).
- Produces:
  - `historyMessages = { es, en }`.
  - `interface AuditExtensions { valueFormatters: readonly AuditValueFormatter[]; fieldLabels: Readonly<Record<string, string>> }` and `NO_AUDIT_EXTENSIONS`.
  - `formatAuditValue(value, extensions?)`, `describeChanges(entry, definition, extensions?)`, `relativeTime(iso, now?)`, `absoluteTime(iso)`.
  - `useAuditExtensions(): AuditExtensions`.
  - `useRecordHistory(objectName, recordId, limit?)`, `useAuditLog(filters)`.
  - `ChangeList({ changes })`, `OperationBadge({ operation })`, `RecordHistory({ objectName, recordId, definition })`.

- [ ] **Step 1: Port**

```bash
C=frontend/packages/core/src/features/history
for f in i18n.ts api.ts ChangeList.tsx OperationBadge.tsx changes.test.ts RecordHistory.test.tsx; do
  node frontend/tooling/port-from-sapgis.mjs features/history/$f $C/$f
done
```
Expected: exactly one `MANUAL:` line, `i18n.ts still imports @/lib/i18n`, which Step 2 handles. `ChangeList.tsx` and `OperationBadge.tsx` need no edits: the script dropped their i18n side-effect import and mapped `@/features/history/types` to `../../types/audit`.

- [ ] **Step 2: Turn the history strings into a plain bundle** — `features/history/i18n.ts`

1. Delete the line `import i18n from '@/lib/i18n'`.
2. Delete the keys `geometry` and `geometryUpdated` from both `es.history` and `en.history`. gis brings its own in P5.
3. Replace the last three statements (the two `i18n.addResourceBundle(...)` calls and `export default i18n`) with:
   ```ts
   // merged into core's `common` namespace by createChawpiI18n (coreBundles)
   export const historyMessages = { es, en }
   ```
4. Replace the comment at the top (`// the history screens own their strings. shared locale files stay untouched.`) with `// the history screens own their strings; coreBundles merges them into common.`

Then `frontend/packages/core/src/i18n/coreBundles.ts` becomes:

```ts
import { historyMessages } from '../features/history/i18n'

// strings a core feature keeps outside common.json, deep-merged into `common` when an app starts.
// a feature adds its bundle here instead of registering it by an import side effect.
export const coreBundles: Record<string, Record<string, unknown>>[] = [historyMessages]
```

Append to `frontend/packages/core/src/i18n/createI18n.test.ts`, inside the `describe`:

```ts
  it('merges the feature bundles into common', () => {
    const i18n = createChawpiI18n({ languages: ['es', 'en'], storageKey: KEY, modules: [] })
    expect(i18n.t('history.title')).toBe('Historial')
    expect(i18n.t('common.save')).toBe('Guardar')
  })
```

- [ ] **Step 3: Drop the document lookup from `features/history/api.ts`**

Delete `useIssuedDocument` with its comment block (it moves to `@chawpi/documents`), and delete the `import type { SapDocument } …` line. `useRecordHistory` and `useAuditLog` stay unchanged. The imports must read `from '../../api/client'` and `from '../../types/audit'`.

- [ ] **Step 4: Edit the ported tests**

`features/history/changes.test.ts`:
1. Add these imports and a `beforeAll`. The pure functions read the app's i18n instance, which a test has to create:
   ```ts
   import { beforeAll } from 'vitest'
   import { createChawpiI18n } from '../../i18n/createI18n'
   import { issueModule } from '../../test/fakeModules'
   import type { AuditExtensions } from './changes'

   const extensions: AuditExtensions = { valueFormatters: issueModule.auditValueFormatters ?? [], fieldLabels: issueModule.auditFieldLabels ?? {} }

   beforeAll(() => {
     createChawpiI18n({ languages: ['es', 'en'], storageKey: 'changes-test.lang', modules: [issueModule] })
   })
   ```
   Merge `beforeAll` into the existing `vitest` import line instead of adding a second one.
2. Replace the whole test `'marks a geometry change instead of dumping its coordinates'` with:
   ```ts
     it('lets a module name its own value and column instead of dumping them', () => {
       const described = describeChanges(entry([{ field: 'sketch', before: null, after: { strokes: [[0, 0, 1, 1]] } }]), predio, extensions)
       expect(described[0].label).toBe('Boceto')
       expect(described[0].after).toBe('boceto actualizado')
       expect(described[0].after).not.toContain('strokes')
     })

     it('without a module that knows it, a value is only an updated value and the column its name', () => {
       const described = describeChanges(entry([{ field: 'sketch', before: null, after: { strokes: [[0, 0, 1, 1]] } }]), predio)
       expect(described[0].label).toBe('sketch')
       expect(described[0].after).toBe('valor actualizado')
     })
   ```
3. Keep `'treats an unchanged geometry as no change'` as it is. It pins the deep compare, which any module value relies on.

`features/history/RecordHistory.test.tsx`:
1. In the `vi.mock('./api', …)` factory, delete the `useIssuedDocument` entry and its comment.
2. Add the imports `import type { ChawpiModule } from '../../registry/contract'` and `import { issueModule } from '../../test/fakeModules'`.
3. Replace `renderHistory` with:
   ```tsx
   function renderHistory(modules: ChawpiModule[] = []) {
     return renderWithProviders(<RecordHistory objectName="predio" recordId="record-1" definition={predio} />, { modules })
   }
   ```
4. In `'shows a link to the document for an issue, and an update still shows its changes next to it'`, change `renderHistory()` to `renderHistory([issueModule])`.
5. Add after that test:
   ```tsx
     it('shows only the badge for an operation no module draws', () => {
       state.entries = [issued]
       renderHistory()
       expect(screen.getByText('Emisión')).toBeInTheDocument()
       expect(screen.queryByRole('button', { name: /Ver documento/ })).not.toBeInTheDocument()
     })
   ```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/features/history src/i18n`
Expected: FAIL. `./changes` and `./RecordHistory` do not exist yet (Steps 6–7 write them).

- [ ] **Step 6: Write `features/history/changes.ts` and `features/history/useAuditExtensions.ts`**

`frontend/packages/core/src/features/history/changes.ts`:

```ts
import { getI18n } from 'react-i18next'
import type { AuditValueFormatter } from '../../registry/contract'
import type { AuditEntry, ChangeDescription } from '../../types/audit'
import type { ObjectDefinition } from '../../types/metadata'

// no server here: pure functions over an audit entry, the object metadata, and what modules told
// the registry about their own values.

export interface AuditExtensions {
  valueFormatters: readonly AuditValueFormatter[]
  fieldLabels: Readonly<Record<string, string>>
}

export const NO_AUDIT_EXTENSIONS: AuditExtensions = { valueFormatters: [], fieldLabels: {} }

// the instance the app initialised. these functions have no component to ask.
function t(key: string): string {
  return getI18n().t(key)
}

function language(): string {
  return getI18n()?.language || 'es'
}

// a value the user can read. never raw json: a module value (a shape) can be thousands of numbers.
export function formatAuditValue(value: unknown, extensions: AuditExtensions = NO_AUDIT_EXTENSIONS): string {
  if (value === null || value === undefined) return t('history.none')
  if (typeof value === 'boolean') return value ? t('history.yes') : t('history.no')
  if (typeof value === 'string') return value.trim() === '' ? t('history.none') : value
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : t('history.none')
  const formatter = extensions.valueFormatters.find((candidate) => candidate.matches(value))
  if (formatter) return t(formatter.labelKey)
  if (typeof value === 'object') return t('history.complexValue')
  return String(value)
}

// objects compare by shape, everything else by identity. NaN never equals itself, Object.is fixes it.
function sameValue(before: unknown, after: unknown): boolean {
  if (before === null || before === undefined) return after === null || after === undefined
  if (typeof before === 'object' || typeof after === 'object') {
    return JSON.stringify(before ?? null) === JSON.stringify(after ?? null)
  }
  return Object.is(before, after)
}

// a field may have been deleted since the change was recorded. a module column keeps the name its
// module gave it; anything else falls back to its technical name.
function resolveLabel(field: string, definition: ObjectDefinition | undefined, extensions: AuditExtensions): string {
  const meta = definition?.fields.find((candidate) => candidate.name === field)
  if (meta) return meta.label
  const key = extensions.fieldLabels[field]
  return key ? t(key) : field
}

export function describeChanges(
  entry: Pick<AuditEntry, 'changes'>,
  definition: ObjectDefinition | undefined,
  extensions: AuditExtensions = NO_AUDIT_EXTENSIONS
): ChangeDescription[] {
  return (entry.changes ?? [])
    .filter((change) => !sameValue(change.before, change.after))
    .map((change) => ({
      field: change.field,
      label: resolveLabel(change.field, definition, extensions),
      before: formatAuditValue(change.before, extensions),
      after: formatAuditValue(change.after, extensions)
    }))
}

const BUCKETS: { limit: number; unit: Intl.RelativeTimeFormatUnit; seconds: number }[] = [
  { limit: 60, unit: 'second', seconds: 1 },
  { limit: 3600, unit: 'minute', seconds: 60 },
  { limit: 86400, unit: 'hour', seconds: 3600 },
  { limit: 2592000, unit: 'day', seconds: 86400 },
  { limit: 31536000, unit: 'month', seconds: 2592000 }
]

// "hace 5 minutos" / "5 minutes ago", in whatever language i18n is on right now.
export function relativeTime(iso: string, now: Date | number = Date.now()): string {
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return iso
  const elapsed = (typeof now === 'number' ? now : now.getTime()) - then
  const seconds = Math.abs(elapsed) / 1000
  const bucket = BUCKETS.find((candidate) => seconds < candidate.limit)
  const unit = bucket?.unit ?? 'year'
  const size = bucket?.seconds ?? 31536000
  const amount = Math.round(-elapsed / 1000 / size)
  const format = new Intl.RelativeTimeFormat(language(), { numeric: 'always' })
  return format.format(amount, unit)
}

// the exact moment, for the title attribute the relative time hides.
export function absoluteTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(language())
}
```

`frontend/packages/core/src/features/history/useAuditExtensions.ts`:

```ts
import { useMemo } from 'react'
import { useRegistry } from '../../app/context'
import type { AuditExtensions } from './changes'

// what the registered modules know about audit values and columns, in the shape changes.ts takes
export function useAuditExtensions(): AuditExtensions {
  const { auditValueFormatters, auditFieldLabels } = useRegistry()
  return useMemo(() => ({ valueFormatters: auditValueFormatters, fieldLabels: auditFieldLabels }), [auditValueFormatters, auditFieldLabels])
}
```

- [ ] **Step 7: Write `features/history/RecordHistory.tsx`**

The markup is sapgis's. An entry that is not an UPDATE draws whatever module claimed its operation (R9).

```tsx
import { useTranslation } from 'react-i18next'
import { Card, CardBody, CardHeader, CardTitle } from '@chawpi/ui'
import { useRegistry } from '../../app/context'
import type { ObjectDefinition } from '../../types/metadata'
import { useRecordHistory } from './api'
import { absoluteTime, describeChanges, relativeTime } from './changes'
import { ChangeList } from './ChangeList'
import { OperationBadge } from './OperationBadge'
import { useAuditExtensions } from './useAuditExtensions'

interface RecordHistoryProps {
  objectName: string
  recordId: string
  definition: ObjectDefinition
}

// one record's audit trail as a timeline. history is informative: it never breaks the detail page.
export function RecordHistory({ objectName, recordId, definition }: RecordHistoryProps) {
  const { t } = useTranslation()
  const { historyRenderers } = useRegistry()
  const extensions = useAuditExtensions()
  const history = useRecordHistory(objectName, recordId)
  const entries = [...(history.data ?? [])].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('history.title')}</CardTitle>
      </CardHeader>
      <CardBody>
        {history.isLoading ? (
          <p className="text-sm text-ink-muted">{t('common.loading')}</p>
        ) : history.isError ? (
          // endpoint missing or down. say it quietly, no error styling.
          <p className="text-sm text-ink-muted">{t('history.unavailable')}</p>
        ) : entries.length === 0 ? (
          <p className="text-sm text-ink-muted">{t('history.empty')}</p>
        ) : (
          <ol data-testid="history-timeline" className="space-y-4 border-l border-border pl-4">
            {entries.map((entry) => {
              // core draws an update's changes; any other operation is drawn by the module that owns it, if any
              const ModuleBody = entry.operation === 'UPDATE' ? undefined : historyRenderers[entry.operation]
              return (
                <li key={entry.id} className="relative space-y-1.5">
                  <span className="absolute -left-[21px] top-2 h-2 w-2 rounded-full bg-border" />
                  <div className="flex flex-wrap items-center gap-2">
                    <OperationBadge operation={entry.operation} />
                    <span className="text-sm text-ink">{entry.userEmail ?? t('history.system')}</span>
                    <time className="text-xs text-ink-muted" dateTime={entry.occurredAt} title={absoluteTime(entry.occurredAt)}>
                      {relativeTime(entry.occurredAt)}
                    </time>
                  </div>
                  {entry.operation === 'UPDATE' ? (
                    <ChangeList changes={describeChanges(entry, definition, extensions)} />
                  ) : ModuleBody ? (
                    <ModuleBody entry={entry} />
                  ) : null}
                </li>
              )
            })}
          </ol>
        )}
      </CardBody>
    </Card>
  )
}
```

- [ ] **Step 8: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { absoluteTime, describeChanges, formatAuditValue, NO_AUDIT_EXTENSIONS, relativeTime, type AuditExtensions } from './features/history/changes'
export { useAuditExtensions } from './features/history/useAuditExtensions'
export { useAuditLog, useRecordHistory } from './features/history/api'
export { ChangeList } from './features/history/ChangeList'
export { OperationBadge } from './features/history/OperationBadge'
export { RecordHistory } from './features/history/RecordHistory'
```

- [ ] **Step 9: Verify**

```bash
yarn prettier --write frontend/packages/core/src
grep -rn "geometr" frontend/packages/core/src/features/history --include=*.ts --include=*.tsx | grep -v "\.test\." || echo "history is geometry-free"
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: `history is geometry-free`, and everything else green. Nothing is committed.

---

### Task 11: Page renderer (components and actions via registry, fallback page)

**Files:**
- Create (ported + edited): `frontend/packages/core/src/components/page-renderer/{layout.ts, layout.test.ts, ActionButton.test.tsx, PageRenderer.test.tsx}`
- Create: `frontend/packages/core/src/components/page-renderer/{PageRenderer.tsx, ActionButton.tsx, fallbackPage.ts, fallbackPage.test.ts}`
- Modify: `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes:
  - `DynamicForm` (Task 9), `RelatedList` (Task 9), `RecordHistory` (Task 10);
  - `useForm`, `useObjectRelationships` from `../../queries` (Task 7);
  - `useRegistry`, `useChawpiLinks` (Task 5);
  - `sketchModule`, `noteModule` (Task 9 fakes).
- Produces:
  - `PageRenderer({ page, definition, record, onSubmit: (payload: RecordPayload) => void, submitting?, error? })`.
  - `ActionButton({ component, objectName, recordId })`.
  - `ROW_CLASS`, `regionStyle(span): CSSProperties`, `regionKeys(template): string[]`. These were sapgis `features/pages/builder/templates.ts`; P5's page builder imports them from `@chawpi/core`.
  - `fallbackPage(objectName: string, sides: RelatedSide[]): Page`.

- [ ] **Step 1: Port**

```bash
C=frontend/packages/core/src/components/page-renderer
node frontend/tooling/port-from-sapgis.mjs features/pages/builder/templates.ts $C/layout.ts
node frontend/tooling/port-from-sapgis.mjs features/pages/builder/templates.test.ts $C/layout.test.ts
node frontend/tooling/port-from-sapgis.mjs components/page-renderer/ActionButton.test.tsx $C/ActionButton.test.tsx
node frontend/tooling/port-from-sapgis.mjs components/page-renderer/PageRenderer.test.tsx $C/PageRenderer.test.tsx
sed -i '' "s#from './templates'#from './layout'#" $C/layout.test.ts
```

In `layout.ts`, reword the one comment that says "so the geometry is written down exactly once" to "so the layout is written down exactly once" (the boundary test in Task 15 bans the word in core sources). Change nothing else.

- [ ] **Step 2: Edit the ported tests**

`ActionButton.test.tsx`:
1. Delete the `vi.hoisted(...)` transitions fixture and the `vi.mock('.../features/workflows/api', …)` block.
2. Delete the test `'says why a transition is closed instead of pretending it is open'`. It moves to `@chawpi/workflow` in P5.
3. Add `import { noteModule } from '../../test/fakeModules'` and these tests inside the `describe`:
   ```tsx
     it('lets the module that owns an action kind draw it', () => {
       renderWithProviders(<ActionButton component={action({ action: 'STAMP', title: 'Aprobar' })} objectName="predio" recordId="r1" />, { modules: [noteModule] })
       expect(screen.getByRole('button', { name: 'sellar Aprobar' })).toBeInTheDocument()
     })

     it('draws nothing for an action kind no module registered', () => {
       const { container } = renderWithProviders(<ActionButton component={action({ action: 'STAMP' })} objectName="predio" recordId="r1" />)
       expect(container).toBeEmptyDOMElement()
     })
   ```
4. Change the vitest import to `import { describe, expect, it } from 'vitest'`.

`PageRenderer.test.tsx`:
1. Delete the `vi.mock('.../components/map/MapView', …)` block and its comment.
2. Replace the line `import { renderWithProviders } from '@chawpi/testing'` with the following. Every page in this file may hold module fields and components.
   ```tsx
   import type { ReactElement } from 'react'
   import { renderWithProviders as renderBase } from '@chawpi/testing'
   import { noteModule, sketchModule } from '../../test/fakeModules'

   // every page here may hold module fields and components
   const renderWithProviders = (ui: ReactElement) => renderBase(ui, { modules: [sketchModule, noteModule] })
   ```
3. Replace the helper `geometryField` with the following, and in `definition.fields` replace `geometryField('lote', 'Lote')` with `sketchField('lote', 'Lote')`:
   ```tsx
   function sketchField(name: string, label: string): FieldMeta {
     return { ...field(name, label), type: 'SKETCH' }
   }
   ```
4. Rename the test `'draws the geometry a form names, where the author put it'` to `'draws the module field a form names, where the author put it'`. Its body stays: the fake widget draws `<label>Lote</label>`.
5. In `'renders only the fields a form names, in that order'`, change the comment `// a geometry the form does not name…` to `// a module field the form does not name is not drawn either: it is a field like the rest`.
6. Append this `describe` block at the end of the file:
   ```tsx
   describe('PageRenderer module components', () => {
     it('lets the module that registered a component type draw it', () => {
       const page = rootPage([node('NOTE', { title: 'aviso' })])
       renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)
       expect(screen.getByTestId('note')).toHaveTextContent('nota aviso r1')
     })

     it('draws nothing for a type no module registered, and keeps a tab of only that off the strip', () => {
       const page = rootPage([
         node('TABS', {
           children: [
             node('TAB', { title: 'Plano', children: [node('GHOST')] }),
             node('TAB', { title: 'Detalles', children: [node('TEXT', { content: 'hola' })] })
           ]
         })
       ])
       renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)
       expect(screen.queryByRole('tab', { name: 'Plano' })).not.toBeInTheDocument()
       expect(screen.getByRole('tab', { name: 'Detalles' })).toBeInTheDocument()
     })

     it('keeps module fields out of a form that does not save', () => {
       const page = rootPage([node('FORM', { fields: ['nombre'] }), node('FORM', { title: 'Lectura', fields: ['lote', 'codigo'] })])
       renderWithProviders(<PageRenderer page={page} definition={definition} record={record} onSubmit={vi.fn()} />)
       expect(screen.queryAllByTestId('sketch-field')).toHaveLength(0)
       expect(screen.getByText('Código')).toBeInTheDocument()
     })
   })
   ```

`frontend/packages/core/src/components/page-renderer/fallbackPage.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { fallbackPage } from './fallbackPage'

describe('fallbackPage', () => {
  it('lays out the whole form, one list per relationship and the history in one full-width region', () => {
    const page = fallbackPage('predio', [
      { relationship: 'predio_titular', label: 'Titular', type: 'MANY_TO_ONE', objectName: 'titular', objectLabel: 'Titular', many: false }
    ])

    expect(page.objectName).toBe('predio')
    expect(page.generated).toBe(true)
    expect(page.template.rows).toEqual([{ regions: [{ name: 'MAIN', span: 12 }] }])
    const region = page.definition.page.children[0]
    expect(region.region).toBe('MAIN')
    expect(region.children.map((child) => [child.type, child.relationship, child.fields])).toEqual([
      ['FORM', null, null],
      ['RELATED_LIST', 'predio_titular', null],
      ['HISTORY', null, null]
    ])
  })
})
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/components/page-renderer`
Expected: FAIL, `Failed to resolve import "./PageRenderer"` / `"./ActionButton"` / `"./fallbackPage"`. The ported `layout.test.ts` passes.

- [ ] **Step 4: Write `frontend/packages/core/src/components/page-renderer/fallbackPage.ts`**

```ts
import type { Page, PageComponent, PageComponentType, RelatedSide } from '../../types/metadata'

const MAIN = 'MAIN'

function node(type: PageComponentType, extra: Partial<PageComponent> = {}): PageComponent {
  return { type, column: 1, title: null, layout: 'single-column', children: [], relationship: null, fields: null, content: null, region: null, ...extra }
}

// the detail page when the backend has no pages module: the whole form, one list per
// relationship, then the history, in one full-width region.
export function fallbackPage(objectName: string, sides: RelatedSide[]): Page {
  const children = [node('FORM'), ...sides.map((side) => node('RELATED_LIST', { relationship: side.relationship })), node('HISTORY')]
  return {
    id: '',
    name: `${objectName}_record_detail`,
    label: objectName,
    objectName,
    kind: 'RECORD_DETAIL',
    template: { name: 'fallback', columns: 12, rows: [{ regions: [{ name: MAIN, span: 12 }] }] },
    generated: true,
    definition: { page: node('PAGE', { children: [node('REGION', { region: MAIN, children })] }) }
  }
}
```

- [ ] **Step 5: Write `frontend/packages/core/src/components/page-renderer/ActionButton.tsx`**

```tsx
import { Link } from 'react-router'
import { Button } from '@chawpi/ui'
import { useChawpiLinks, useRegistry } from '../../app/context'
import type { PageComponent } from '../../types/metadata'

interface ActionButtonProps {
  component: PageComponent
  objectName: string
  recordId: string
}

// one button an admin placed where they wanted it. NAVIGATE is core's; any other kind (workflow:
// TRANSITION) is drawn by the module that registered it, and nothing is drawn when none did.
export function ActionButton({ component, objectName, recordId }: ActionButtonProps) {
  const { pageActions } = useRegistry()
  if (component.action === 'NAVIGATE') return <NavigateAction component={component} />
  const ModuleAction = component.action ? pageActions[component.action] : undefined
  return ModuleAction ? <ModuleAction component={component} objectName={objectName} recordId={recordId} /> : null
}

// a Link when it stays inside the app, a plain anchor when it leaves it
function NavigateAction({ component }: { component: PageComponent }) {
  const links = useChawpiLinks()
  const label = component.title ?? component.target ?? component.url ?? ''

  if (component.url) {
    return (
      <a
        href={component.url}
        target="_blank"
        rel="noreferrer noopener"
        className="inline-flex h-9 items-center justify-center gap-2 rounded-md bg-brand px-4 text-sm font-medium text-white transition-colors hover:bg-brand-strong"
      >
        {label}
      </a>
    )
  }

  return (
    <Button asChild>
      {/* a target that went missing opens the object list instead of a url with a hole in it */}
      <Link to={component.target ? links.records(component.target) : links.objects()}>{label}</Link>
    </Button>
  )
}
```

- [ ] **Step 6: Write `frontend/packages/core/src/components/page-renderer/PageRenderer.tsx`**

This is sapgis's renderer. The MAP and WORKFLOW cases become a registry lookup in `default`. Geometry filters become "module field" filters. The layout helpers come from `./layout`.

```tsx
import { type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Card, CardBody, CardHeader, CardTitle, cn, Tabs } from '@chawpi/ui'
import { useRegistry } from '../../app/context'
import { RecordHistory } from '../../features/history/RecordHistory'
import { useForm as useStoredForm, useObjectRelationships } from '../../queries'
import type { FieldRenderer } from '../../registry/contract'
import { CORE_PAGE_COMPONENT_TYPES, type FieldMeta, type ObjectDefinition, type Page, type PageComponent, type PageLayout, type RecordItem, type RecordPayload } from '../../types/metadata'
import { DynamicForm } from '../dynamic-form/DynamicForm'
import { RelatedList } from '../related/RelatedList'
import { ActionButton } from './ActionButton'
import { ROW_CLASS, regionStyle } from './layout'

type SubmitHandler = (payload: RecordPayload) => void
type Renderers = Readonly<Record<string, FieldRenderer>>

const CORE_TYPES: readonly string[] = CORE_PAGE_COMPONENT_TYPES

export interface PageRendererProps {
  page: Page
  definition: ObjectDefinition
  record: RecordItem
  onSubmit: SubmitHandler
  submitting?: boolean
  error?: string | null
}

// the detail page an admin configured, drawn for one record. no component is hardcoded here:
// what shows up and where comes from the page definition tree, and module types from the registry.
export function PageRenderer({ page, definition, record, onSubmit, submitting, error }: PageRendererProps) {
  const { t } = useTranslation()
  const { fieldRenderers, pageComponents } = useRegistry()
  const relationships = useObjectRelationships(definition.name)
  const known = (relationships.data ?? []).map((side) => side.relationship)
  // one record, one save button: the first form in document order owns submission, the rest are
  // read-along field groups whose submit does nothing.
  const owner = firstForm(page.definition.page)
  // a leaf draws when core or some module knows its type
  const drawable = (type: string) => CORE_TYPES.includes(type) || type in pageComponents

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
        return (
          <div key={key} className="space-y-5">
            {body(component.children, component.layout, false)}
          </div>
        )

      case 'TABS': {
        const open = component.children.filter((child) => draws(child, known, drawable))
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

      case 'DYNAMIC_FORM': {
        const isOwner = component === owner
        return (
          <Card key={key}>
            {component.title ? (
              <CardHeader>
                <CardTitle>{component.title}</CardTitle>
              </CardHeader>
            ) : null}
            <CardBody>
              <DynamicForm
                definition={{ ...definition, fields: placedFields(component, definition) }}
                record={record}
                submitting={isOwner ? submitting : false}
                error={isOwner ? error : null}
                onSubmit={isOwner ? onSubmit : noop}
                readOnly={!isOwner}
              />
            </CardBody>
          </Card>
        )
      }

      case 'FORM': {
        const isOwner = component === owner
        return (
          <Card key={key}>
            {component.title ? (
              <CardHeader>
                <CardTitle>{component.title}</CardTitle>
              </CardHeader>
            ) : null}
            <CardBody>
              {component.form ? (
                // a named form owns its own layout; `fields` is ignored when one is named
                <StoredFormComponent
                  formName={component.form}
                  definition={definition}
                  record={record}
                  owner={isOwner}
                  submitting={submitting}
                  error={error}
                  onSubmit={onSubmit}
                />
              ) : (
                <DynamicForm
                  definition={narrowDefinition(definition, component.fields, isOwner, fieldRenderers)}
                  record={record}
                  submitting={isOwner ? submitting : false}
                  error={isOwner ? error : null}
                  onSubmit={isOwner ? onSubmit : noop}
                  readOnly={!isOwner}
                />
              )}
            </CardBody>
          </Card>
        )
      }

      case 'RELATED_LIST': {
        const side = (relationships.data ?? []).find((candidate) => candidate.relationship === component.relationship)
        // relationship gone or renamed: skip it rather than blow up the whole page
        if (!side) return null
        return <RelatedList key={key} objectName={definition.name} recordId={record.id} side={side} />
      }

      case 'HISTORY':
        return <RecordHistory key={key} objectName={definition.name} recordId={record.id} definition={definition} />

      case 'TEXT':
        return (
          <Card key={key}>
            {component.title ? (
              <CardHeader>
                <CardTitle>{component.title}</CardTitle>
              </CardHeader>
            ) : null}
            <CardBody>
              <p className="whitespace-pre-line text-sm text-ink-muted">{component.content ?? ''}</p>
            </CardBody>
          </Card>
        )

      default: {
        // MAP, WORKFLOW and the like: the module that registered the type draws it
        const ModuleComponent = pageComponents[component.type]
        return ModuleComponent ? <ModuleComponent key={key} component={component} definition={definition} record={record} /> : null
      }
    }
  }

  // the root is always a PAGE node now: one region per row slot, laid out by its template.
  return renderComponent(page.definition.page, 0)
}

function noop() {}

// its own component so the fetch only happens for a component that names a form
function StoredFormComponent({
  formName,
  definition,
  record,
  owner,
  submitting,
  error,
  onSubmit
}: {
  formName: string
  definition: ObjectDefinition
  record: RecordItem
  owner: boolean
  submitting?: boolean
  error?: string | null
  onSubmit: SubmitHandler
}) {
  const { t } = useTranslation()
  const { fieldRenderers } = useRegistry()
  const form = useStoredForm(definition.name, formName)

  if (form.isLoading) return <p className="text-sm text-ink-muted">{t('common.loading')}</p>

  return (
    <DynamicForm
      definition={owner ? definition : { ...definition, fields: definition.fields.filter((field) => !fieldRenderers[field.type]) }}
      form={form.data}
      record={record}
      submitting={owner ? submitting : false}
      error={owner ? error : null}
      onSubmit={owner ? onSubmit : noop}
      readOnly={!owner}
    />
  )
}

// a dynamic form's fields are its children, each carrying what this placement shows of it. an
// invisible one is dropped outright; editable can only take away what the object already granted.
function placedFields(component: PageComponent, definition: ObjectDefinition): FieldMeta[] {
  const byName = new Map(definition.fields.map((field) => [field.name, field]))
  return component.children
    .map((child) => {
      const meta = byName.get(child.field ?? '')
      if (!meta || child.visible === false) return null
      return child.editable === false ? { ...meta, editable: false } : meta
    })
    .filter((field): field is FieldMeta => field !== null)
}

// `fields` picks a subset in the author's order. a module field is picked the same way, except in
// the form that does not save, which must not offer a widget whose value it drops.
function narrowDefinition(definition: ObjectDefinition, fields: string[] | null, keepModuleFields: boolean, renderers: Renderers): ObjectDefinition {
  const byName = new Map(definition.fields.map((field) => [field.name, field]))
  const picked = fields ? fields.map((name) => byName.get(name)).filter((field): field is FieldMeta => field !== undefined) : definition.fields
  return {
    ...definition,
    fields: keepModuleFields ? picked : picked.filter((field) => !renderers[field.type])
  }
}

// pre-order: the first form you would read going down the page, starting from the root. both form
// types count -- a page whose only form is dynamic would otherwise have no owner, and every form on
// it would render read-only with no save button anywhere.
function firstForm(node: PageComponent): PageComponent | null {
  if (node.type === 'FORM' || node.type === 'DYNAMIC_FORM') return node
  for (const child of node.children) {
    const found = firstForm(child)
    if (found) return found
  }
  return null
}

// a tab whose every component draws nothing is a button that opens an empty panel
function draws(node: PageComponent, relationships: string[], drawable: (type: string) => boolean): boolean {
  if (node.type === 'RELATED_LIST') return relationships.includes(node.relationship ?? '')
  if (node.children.length > 0) return node.children.some((child) => draws(child, relationships, drawable))
  return !(node.type === 'TABS' || node.type === 'TAB' || node.type === 'SECTION' || node.type === 'DYNAMIC_FORM') && drawable(node.type)
}
```

- [ ] **Step 7: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { PageRenderer, type PageRendererProps } from './components/page-renderer/PageRenderer'
export { ActionButton } from './components/page-renderer/ActionButton'
export { ROW_CLASS, regionKeys, regionStyle } from './components/page-renderer/layout'
export { fallbackPage } from './components/page-renderer/fallbackPage'
```

- [ ] **Step 8: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green. The ported PageRenderer suite keeps all its sapgis cases (with SKETCH in place of GEOMETRY), plus 3 new module cases, 2 new ActionButton cases and 1 fallback case. Nothing is committed.

---

### Task 12: Records pages (list, form, detail with fallback page and module panels)

**Files:**
- Create (ported): `frontend/packages/core/src/features/records/{viewColumns.ts, viewColumns.test.ts}` (from sapgis `features/views/`)
- Create (ported + edited): `frontend/packages/core/src/features/records/{RecordListPage.tsx, RecordFormPage.tsx}`
- Create: `frontend/packages/core/src/features/records/RecordDetailPage.tsx` (rewrite)
- Test: `frontend/packages/core/src/features/records/RecordDetailPage.test.tsx`, `frontend/packages/core/src/features/records/RecordListPage.test.tsx`
- Modify: `frontend/packages/core/src/app/coreModule.ts`, `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes:
  - `PageRenderer`, `fallbackPage` (Task 11); `DynamicForm`, `DataTable` (Task 9);
  - query hooks (Task 7); `useChawpiLinks`, `useRegistry` (Task 5);
  - `RecordPanelProps`, `RecordListActionProps` (Task 4); `mockFetch`, `renderWithProviders` (Task 6).
- Produces:
  - `RecordListPage`, `RecordFormPage`, `RecordDetailPage`.
  - `viewColumns`, `viewQueryParams`, `effectiveSort`, `fallbackView`, `pickView`, `ListQuery` (sapgis `features/views/viewColumns.ts`, now in core; P5's view builder imports them from `@chawpi/core`).
  - `coreModule` routes `core:records`, `core:newRecord` and `core:record`.

- [ ] **Step 1: Port**

```bash
C=frontend/packages/core/src/features/records
node frontend/tooling/port-from-sapgis.mjs features/views/viewColumns.ts $C/viewColumns.ts
node frontend/tooling/port-from-sapgis.mjs features/views/viewColumns.test.ts $C/viewColumns.test.ts
node frontend/tooling/port-from-sapgis.mjs features/records/RecordListPage.tsx $C/RecordListPage.tsx
node frontend/tooling/port-from-sapgis.mjs features/records/RecordFormPage.tsx $C/RecordFormPage.tsx
```
Expected: no `MANUAL:` lines. The two view files need no edits.

- [ ] **Step 2: Write the failing tests**

`frontend/packages/core/src/features/records/RecordDetailPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock, type MockRoute } from '@chawpi/testing'
import type { ChawpiModule } from '../../registry/contract'
import type { ObjectDefinition, Page } from '../../types/metadata'
import { RecordDetailPage } from './RecordDetailPage'

const predio: ObjectDefinition = {
  id: 'o1',
  name: 'predio',
  label: 'Predio',
  pluralLabel: 'Predios',
  description: null,
  enabled: true,
  fields: [
    {
      id: 'f1',
      name: 'codigo',
      label: 'Código',
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
  ]
}

const configured: Page = {
  id: 'p1',
  name: 'predio_record_detail',
  label: 'Detalle',
  objectName: 'predio',
  kind: 'RECORD_DETAIL',
  template: { name: 'single-region', columns: 12, rows: [{ regions: [{ name: 'MAIN', span: 12 }] }] },
  generated: false,
  definition: {
    page: {
      type: 'PAGE',
      column: 1,
      title: null,
      layout: 'single-column',
      relationship: null,
      fields: null,
      content: null,
      children: [
        {
          type: 'REGION',
          region: 'MAIN',
          column: 1,
          title: null,
          layout: 'single-column',
          relationship: null,
          fields: null,
          content: null,
          children: [{ type: 'TEXT', column: 1, title: null, layout: 'single-column', relationship: null, fields: null, content: 'configurada', children: [] }]
        }
      ]
    }
  }
}

const base: MockRoute[] = [
  { path: '/metadata/objects/predio', body: predio },
  { path: '/objects/predio/records/r1', body: { id: 'r1', createdAt: null, updatedAt: null, attributes: { codigo: 'P-1' } } },
  { path: '/objects/predio/relationships', body: [] },
  { path: '/objects/predio/records/r1/history', body: [] },
  { method: 'PUT', path: '/objects/predio/records/r1', body: { id: 'r1', createdAt: null, updatedAt: null, attributes: { codigo: 'P-1' } } }
]

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

function mount(routes: MockRoute[], modules: ChawpiModule[] = []) {
  fetch = mockFetch([...routes, ...base])
  return renderWithProviders(<RecordDetailPage />, { route: '/data/objects/predio/records/r1', path: 'data/objects/:object/records/:id', modules })
}

describe('RecordDetailPage', () => {
  it('draws the page the server resolved', async () => {
    mount([{ path: '/objects/predio/pages/record-detail', body: configured }])
    expect(await screen.findByText('configurada')).toBeInTheDocument()
  })

  it('draws a page built from metadata when the server has no pages module', async () => {
    mount([])
    expect(await screen.findByRole('button', { name: 'Guardar' })).toBeInTheDocument()
    expect(screen.getByText('Historial')).toBeInTheDocument()
  })

  it('saves what the form holds and draws module panels under the page', async () => {
    const panels: ChawpiModule = { id: 'panels', recordPanels: [({ record }) => <p>{`panel ${record.id}`}</p>] }
    mount([], [panels])

    expect(await screen.findByText('panel r1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(fetch?.calls.some((call) => call.method === 'PUT')).toBe(true))
    expect(fetch?.calls.find((call) => call.method === 'PUT')?.body).toEqual({ attributes: { codigo: 'P-1' } })
  })
})
```

`frontend/packages/core/src/features/records/RecordListPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import type { ChawpiModule } from '../../registry/contract'
import { RecordListPage } from './RecordListPage'

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

const mapButton: ChawpiModule = { id: 'maps', recordListActions: [({ objectName, definition }) => <button>{`mapa ${objectName} ${definition.label}`}</button>] }

describe('RecordListPage', () => {
  it('lists on the fallback view, links the new record, and lets modules add header buttons', async () => {
    fetch = mockFetch([
      { path: '/metadata/objects/predio', body: { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] } },
      { path: '/objects/predio/views', status: 404, body: { title: 'Not Found' } },
      { path: '/objects/predio/records', body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } }
    ])
    renderWithProviders(<RecordListPage />, { route: '/data/objects/predio/records', path: 'data/objects/:object/records', modules: [mapButton] })

    expect(await screen.findByRole('button', { name: 'mapa predio Predio' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Nuevo registro/ })).toHaveAttribute('href', '/data/objects/predio/records/new')
  })
})
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/features/records`
Expected: FAIL. `./RecordDetailPage` does not exist, and the list test fails because the map button is not rendered (the ported list page has no module actions yet). The `viewColumns` tests pass.

- [ ] **Step 4: Edit `RecordListPage.tsx`**

1. `import { Map as MapIcon, Plus } from 'lucide-react'` → `import { Plus } from 'lucide-react'`.
2. Add the import `import { useChawpiLinks, useRegistry } from '../../app/context'`.
3. After `const navigate = useNavigate()`, add:
   ```tsx
     const links = useChawpiLinks()
     const { recordListActions } = useRegistry()
   ```
4. After `const definition = useObjectDefinition(object)`, add `const loaded = definition.data`.
5. Replace the whole `{definition.data?.geometry ? ( <Button …><Link to={`/gis/map?object=${object}`}>…</Link></Button> ) : null}` block with the following. That button returns as a gis `recordListActions` entry in P5.
   ```tsx
               {loaded && object
                 ? recordListActions.map((ListAction, index) => <ListAction key={index} objectName={object} definition={loaded} />)
                 : null}
   ```
6. ``<Link to={`/data/objects/${object}/records/new`}>`` → `<Link to={links.newRecord(object ?? '')}>`.
7. ``onOpen={(record) => void navigate(`/data/objects/${object}/records/${record.id}`)}`` → `onOpen={(record) => void navigate(links.record(object ?? '', record.id))}`.

- [ ] **Step 5: Edit `RecordFormPage.tsx`**

1. Add the import `import { useChawpiLinks } from '../../app/context'`.
2. After `const navigate = useNavigate()`, add `const links = useChawpiLinks()`.
3. ``void navigate(`/data/objects/${object}/records/${created.id}`)`` → `void navigate(links.record(object ?? '', created.id))`.

- [ ] **Step 6: Write `frontend/packages/core/src/features/records/RecordDetailPage.tsx`**

```tsx
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Button } from '@chawpi/ui'
import { ApiError } from '../../api/client'
import { useChawpiLinks, useRegistry } from '../../app/context'
import { fallbackPage } from '../../components/page-renderer/fallbackPage'
import { PageRenderer } from '../../components/page-renderer/PageRenderer'
import { useObjectDefinition, useObjectRelationships, useRecord, useResolvedPage, useSaveRecord } from '../../queries'
import { PageHeader } from '../../shell/PageHeader'

// the detail page is metadata too: the server resolves which components go where, this only
// supplies the record and the save wiring. modules add panels under it.
export function RecordDetailPage() {
  const { object, id } = useParams()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const links = useChawpiLinks()
  const { recordPanels } = useRegistry()
  const definition = useObjectDefinition(object)
  const record = useRecord(object, id)
  const page = useResolvedPage(object)
  const sides = useObjectRelationships(object)
  const save = useSaveRecord(object ?? '', id)
  const [error, setError] = useState<string | null>(null)

  // no pages module on the server: draw the page the metadata alone gives (R8)
  const resolved = page.data ?? (page.isError && object && !sides.isLoading ? fallbackPage(object, sides.data ?? []) : undefined)
  const current = definition.data
  const item = record.data

  if (!current || !item || !resolved) {
    return <p className="p-8 text-sm text-ink-muted">{t('common.loading')}</p>
  }

  return (
    <>
      <PageHeader
        title={`${current.label} · ${t('records.detail')}`}
        subtitle={item.id}
        actions={
          <Button variant="secondary" onClick={() => void navigate(links.records(current.name))}>
            {t('common.back')}
          </Button>
        }
      />

      <PageRenderer
        page={resolved}
        definition={current}
        record={item}
        submitting={save.isPending}
        error={error}
        onSubmit={async (payload) => {
          setError(null)
          try {
            await save.mutateAsync(payload)
          } catch (cause) {
            setError(cause instanceof ApiError ? [cause.message, ...cause.violations.map((v) => `${v.field}: ${v.message}`)].join(' — ') : String(cause))
          }
        }}
      />

      {recordPanels.map((Panel, index) => (
        <Panel key={index} objectName={current.name} definition={current} record={item} />
      ))}
    </>
  )
}
```

- [ ] **Step 7: Mount the record routes** — `frontend/packages/core/src/app/coreModule.ts`

Add the imports:

```ts
import { RecordDetailPage } from '../features/records/RecordDetailPage'
import { RecordFormPage } from '../features/records/RecordFormPage'
import { RecordListPage } from '../features/records/RecordListPage'
```

Append to `routes` (after `home`):

```ts
    { id: 'records', path: CORE_ROUTE_PATHS.records, component: RecordListPage },
    { id: 'newRecord', path: CORE_ROUTE_PATHS.newRecord, component: RecordFormPage },
    { id: 'record', path: CORE_ROUTE_PATHS.record, component: RecordDetailPage }
```

- [ ] **Step 8: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { effectiveSort, fallbackView, pickView, viewColumns, viewQueryParams, type ListQuery } from './features/records/viewColumns'
export { RecordListPage } from './features/records/RecordListPage'
export { RecordFormPage } from './features/records/RecordFormPage'
export { RecordDetailPage } from './features/records/RecordDetailPage'
```

- [ ] **Step 9: Verify**

```bash
yarn prettier --write frontend/packages/core/src
grep -rn "'/data\|\`/data\|/gis/" frontend/packages/core/src --include=*.tsx | grep -v "\.test\." || echo "no hardcoded urls"
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: `no hardcoded urls`, and everything green. Nothing is committed.

---

### Task 13: Objects and relationships (field types and flags via registry)

**Files:**
- Create: `frontend/packages/core/src/features/objects/{objectDraft.ts, ModuleFieldSettings.tsx}` (objectDraft rewrites sapgis's)
- Create (ported, no edits): `frontend/packages/core/src/features/objects/{relationshipSides.ts, relationshipSides.test.ts}`, `frontend/packages/core/src/features/relationships/RelationshipsPage.tsx`
- Create (ported + edited): `frontend/packages/core/src/features/objects/{objectDraft.test.ts, ObjectsPage.tsx, ObjectBuilderPage.tsx, ObjectEditorPage.tsx, ObjectEditorPage.test.tsx, ObjectRelationships.tsx}`
- Test: `frontend/packages/core/src/features/objects/ObjectsPage.test.tsx`
- Modify: `frontend/packages/core/src/app/coreModule.ts`, `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: `FieldRenderer` (Task 4); `useRegistry`, `useChawpiLinks`, `useObjectFlags` (Task 5); query hooks (Task 7); `sketchModule` (Task 9); `ApiError` (Task 3).
- Produces:
  - `FieldDraft = { name; label; type: FieldType; required; unique; enumOptions: string; relationTarget: string; settings: Record<string, string> }`. The sapgis `geometryType`/`srid` are replaced by `settings`.
  - `addableFieldTypes(renderers): FieldType[]`, `emptyFieldDraft(renderers?): FieldDraft`, `fieldPayload(draft, renderers): Record<string, unknown>`, `nameTaken`, `NameProblem`, `scopeOf`, `describeError` (unchanged).
  - `ModuleFieldSettings({ renderer?, settings, onChange })`.
  - `ObjectsPage`, `ObjectBuilderPage`, `ObjectEditorPage`, `ObjectRelationships`, `RelationshipsPage`, `sidesOf`, `relationshipOfField`, `RelationshipSide`.
  - `coreModule` routes `core:objects`, `core:newObject`, `core:editObject`, `core:relationships`, and nav entries `nav.objects` and `nav.relationships`.
- Wire change (allowed by R3): a new field's payload carries module keys only for the module's own type. sapgis sent `geometryType: null, srid: null` on every field. The backend ignores absent extension keys (P1 R5).

- [ ] **Step 1: Port**

```bash
C=frontend/packages/core/src/features
for f in relationshipSides.ts relationshipSides.test.ts objectDraft.test.ts ObjectsPage.tsx ObjectBuilderPage.tsx ObjectEditorPage.tsx ObjectEditorPage.test.tsx ObjectRelationships.tsx; do
  node frontend/tooling/port-from-sapgis.mjs features/objects/$f $C/objects/$f
done
node frontend/tooling/port-from-sapgis.mjs features/relationships/RelationshipsPage.tsx $C/relationships/RelationshipsPage.tsx
```
Expected: no `MANUAL:` lines. `@/features/workflows/api` is mapped to a path that does not exist, and Step 5 deletes that import.

- [ ] **Step 2: Write `objectDraft.ts` and `ModuleFieldSettings.tsx`**

`frontend/packages/core/src/features/objects/objectDraft.ts`:

```ts
import { ApiError } from '../../api/client'
import type { FieldRenderer } from '../../registry/contract'
import { CORE_FIELD_TYPES, type FieldType, type SystemField, type SystemFieldScope } from '../../types/metadata'

type Renderers = Readonly<Record<string, FieldRenderer>>

// the core types, then whatever the installed modules add, in registration order
export function addableFieldTypes(renderers: Renderers): FieldType[] {
  return [...CORE_FIELD_TYPES, ...Object.keys(renderers)]
}

export interface FieldDraft {
  name: string
  label: string
  type: FieldType
  required: boolean
  unique: boolean
  enumOptions: string
  relationTarget: string
  // module settings as typed, keyed by the module's names; the payload converts them
  settings: Record<string, string>
}

// every module's defaults are there from the start, so switching the type back and forth keeps
// whatever was typed
export function emptyFieldDraft(renderers: Renderers = {}): FieldDraft {
  const settings: Record<string, string> = {}
  for (const renderer of Object.values(renderers)) Object.assign(settings, renderer.settings?.defaults)
  return { name: '', label: '', type: 'TEXT', required: false, unique: false, enumOptions: '', relationTarget: '', settings }
}

// the field as the api takes it. a module's settings go out only with the module's own type.
export function fieldPayload(draft: FieldDraft, renderers: Renderers): Record<string, unknown> {
  const settings = renderers[draft.type]?.settings
  return {
    name: draft.name.trim().toLowerCase(),
    label: draft.label.trim() || draft.name.trim(),
    type: draft.type,
    required: draft.required,
    unique: draft.unique,
    enumOptions:
      draft.type === 'ENUM'
        ? draft.enumOptions
            .split(',')
            .map((option) => option.trim())
            .filter(Boolean)
        : null,
    relationTarget: draft.type === 'RELATION' ? draft.relationTarget : null,
    ...(settings ? settings.toPayload(draft.settings) : {})
  }
}

export interface NameProblem {
  code: 'SYSTEM' | 'DUPLICATE'
  value: string
}

// the server refuses both of these. answering here saves the round trip and, more to the point,
// says so while the name is still being typed. it normalises the way the server does.
// the sql keyword list is deliberately not mirrored: that 400 already reaches the screen.
export function nameTaken(name: string, fields: { name: string }[], system: SystemField[]): NameProblem | null {
  const wanted = name.trim().toLowerCase()
  if (!wanted) return null
  if (system.some((column) => column.name === wanted)) return { code: 'SYSTEM', value: wanted }
  if (fields.some((field) => field.name === wanted)) return { code: 'DUPLICATE', value: wanted }
  return null
}

// workflow_state is a conditional column, and on this object the condition may already be met —
// saying "only with a workflow" about a table that has the column would be a plain lie.
export function scopeOf(column: SystemField, hasWorkflow: boolean): SystemFieldScope {
  if (column.scope === 'WORKFLOW') return hasWorkflow ? 'ALWAYS' : 'WORKFLOW'
  return column.scope
}

// a refusal names the field or the rule that blocked it. flattening it keeps that name on screen.
export function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return [cause.message, ...cause.violations.map((violation) => `${violation.field}: ${violation.message}`)].join(' — ')
  }
  return String(cause)
}
```

`frontend/packages/core/src/features/objects/ModuleFieldSettings.tsx`:

```tsx
import type { FieldRenderer } from '../../registry/contract'

// the inputs a module type adds to the field form (gis: its shape kind and reference system).
// nothing for core types.
export function ModuleFieldSettings({
  renderer,
  settings,
  onChange
}: {
  renderer?: FieldRenderer
  settings: Record<string, string>
  onChange: (settings: Record<string, string>) => void
}) {
  const Editor = renderer?.settings?.editor
  return Editor ? <Editor settings={settings} onChange={(patch) => onChange({ ...settings, ...patch })} /> : null
}
```

- [ ] **Step 3: Edit the ported tests**

`objectDraft.test.ts`:
1. Replace the `./objectDraft` import with `import { addableFieldTypes, describeError, emptyFieldDraft, fieldPayload, nameTaken, scopeOf } from './objectDraft'`, and add `import { sketchModule } from '../../test/fakeModules'`.
2. Replace the tests `'offers GEOMETRY as a field type'` and `'starts a field on TEXT, with the geometry defaults ready for when it is picked'` (and the ADR-019 comment above the first) with:
   ```ts
     const renderers = sketchModule.fieldRenderers ?? {}

     it('offers the core types, then what installed modules add', () => {
       expect(addableFieldTypes({})).toEqual(['TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION'])
       expect(addableFieldTypes(renderers).at(-1)).toBe('SKETCH')
     })

     it('starts a field on TEXT, with every module default ready for when its type is picked', () => {
       expect(emptyFieldDraft(renderers)).toEqual({
         name: '',
         label: '',
         type: 'TEXT',
         required: false,
         unique: false,
         enumOptions: '',
         relationTarget: '',
         settings: { strokeWidth: '2' }
       })
       expect(emptyFieldDraft().settings).toEqual({})
     })

     it('builds the api payload, with module settings only for the module type', () => {
       const draft = { ...emptyFieldDraft(renderers), name: ' Croquis ', type: 'SKETCH', settings: { strokeWidth: '4' } }
       expect(fieldPayload(draft, renderers)).toEqual({
         name: 'croquis',
         label: 'Croquis',
         type: 'SKETCH',
         required: false,
         unique: false,
         enumOptions: null,
         relationTarget: null,
         strokeWidth: 4
       })
       expect(fieldPayload({ ...draft, type: 'ENUM', enumOptions: 'A, B,' }, renderers)).toEqual({
         name: 'croquis',
         label: 'Croquis',
         type: 'ENUM',
         required: false,
         unique: false,
         enumOptions: ['A', 'B'],
         relationTarget: null
       })
     })
   ```

`ObjectEditorPage.test.tsx`:
1. Delete the line `vi.mock('.../features/workflows/api', () => ({ useWorkflow: () => ({ data: undefined }) }))` and change its comment to `// predio has no workflow here: no module says otherwise, so the state column reads as reserved`.
2. Replace the imports of `QueryClient`, `QueryClientProvider`, `render`, `MemoryRouter`, `Route` and `Routes` (keep `screen`, `waitFor`, `userEvent`, the vitest names) with:
   ```tsx
   import { renderWithProviders } from '@chawpi/testing'
   import type { ChawpiModule } from '../../registry/contract'
   import { sketchModule } from '../../test/fakeModules'
   ```
3. Replace `renderPage` and `openSystemFields` with:
   ```tsx
   function renderPage(modules: ChawpiModule[] = []) {
     return renderWithProviders(<ObjectEditorPage />, { route: '/data/objects/predio/edit', path: 'data/objects/:object/edit', modules })
   }

   async function openSystemFields(modules: ChawpiModule[] = []) {
     renderPage(modules)
     await userEvent.click(screen.getByRole('button', { name: /Campos del sistema|System fields/ }))
   }
   ```
4. Add inside the `describe`:
   ```tsx
     it("reads a conditional column as the object's own once a module says the condition is met", async () => {
       await openSystemFields([{ id: 'wf', objectFlags: () => ({ workflow: true }) }])
       const state = screen.getByText('workflow_state').closest('tr')
       expect(state).toHaveTextContent('Sistema')
       expect(state).not.toHaveTextContent('solo con flujo')
     })

     it('offers no unique toggle for a module field type that cannot be unique', () => {
       definition.fields.push({ ...definition.fields[0], id: 'f-croquis', name: 'croquis', label: 'Croquis', type: 'SKETCH', relationTarget: null })
       try {
         renderPage([sketchModule])
         const row = screen.getByText('croquis').closest('tr')
         expect(row).not.toHaveTextContent('Único')
       } finally {
         definition.fields.pop()
       }
     })
   ```

`frontend/packages/core/src/features/objects/ObjectsPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@chawpi/testing'
import type { ChawpiModule } from '../../registry/contract'
import type { ObjectSummary } from '../../types/metadata'
import { ObjectsPage } from './ObjectsPage'

const objects: ObjectSummary[] = [{ id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true }]

vi.mock('../../queries', () => ({ useObjects: () => ({ data: objects, isLoading: false }) }))

const reference: ChawpiModule = {
  id: 'crs',
  objectColumns: [{ id: 'crs', headerKey: 'crs:column', cell: ({ object }) => <span>{`ref ${object.name}`}</span> }],
  i18n: { es: { column: 'Referencia' }, en: { column: 'Reference' } }
}

describe('ObjectsPage', () => {
  it('links to create, open and edit through the app links, with no module columns', () => {
    renderWithProviders(<ObjectsPage />)
    expect(screen.getByRole('link', { name: /Nuevo objeto/ })).toHaveAttribute('href', '/data/objects/new')
    expect(screen.getByRole('link', { name: 'Ver registros' })).toHaveAttribute('href', '/data/objects/predio/records')
    expect(screen.getByRole('link', { name: 'Editar predio' })).toHaveAttribute('href', '/data/objects/predio/edit')
    expect(screen.getAllByRole('columnheader')).toHaveLength(3)
  })

  it('adds the columns modules contribute', () => {
    renderWithProviders(<ObjectsPage />, { modules: [reference] })
    expect(screen.getByRole('columnheader', { name: 'Referencia' })).toBeInTheDocument()
    expect(screen.getByText('ref predio')).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `yarn workspace @chawpi/core test src/features/objects`
Expected: FAIL. The pages still import `ADDABLE_FIELD_TYPES`/`GEOMETRY_TYPES`, which no longer exist, and the ObjectsPage module column is missing.

- [ ] **Step 5: Edit `ObjectEditorPage.tsx`**

1. Delete `import { useWorkflow } from '…/features/workflows/api'`.
2. Replace the `./objectDraft` import with `import { addableFieldTypes, describeError, emptyFieldDraft, fieldPayload, nameTaken, scopeOf, type FieldDraft } from './objectDraft'`. Add these imports:
   ```tsx
   import { useChawpiLinks, useRegistry } from '../../app/context'
   import { useObjectFlags } from '../../registry/hooks'
   import { ModuleFieldSettings } from './ModuleFieldSettings'
   ```
3. Replace the two lines `// an object without a workflow answers 404, so no data means no state column` and `const { data: workflow } = useWorkflow(object)` with:
   ```tsx
     // modules answer facts about the object (workflow: whether one is attached, so its state column exists)
     const flags = useObjectFlags(object)
     const { fieldRenderers } = useRegistry()
     const links = useChawpiLinks()
   ```
4. `scopeOf(column, Boolean(workflow))` → `scopeOf(column, flags.workflow === true)`.
5. In `submitField`, replace the whole object literal passed to `addField.mutateAsync({ … })` (from `name:` to `srid: …`) so the call reads `await addField.mutateAsync(fieldPayload(draft, fieldRenderers))`.
6. `setDraft(draft ? null : emptyFieldDraft())` → `setDraft(draft ? null : emptyFieldDraft(fieldRenderers))`.
7. `{ADDABLE_FIELD_TYPES.map((type) => (` → `{addableFieldTypes(fieldRenderers).map((type) => (`.
8. In the draft form, `{draft.type === 'GEOMETRY' ? null : (` (the unique checkbox) → `{fieldRenderers[draft.type]?.uniqueAllowed === false ? null : (`.
9. Replace the whole `{draft.type === 'GEOMETRY' ? ( <div className="grid gap-3 sm:col-span-4 sm:grid-cols-2"> … </div> ) : null}` block with the following. The gis editor brings that grid wrapper itself in P5.
   ```tsx
                   <ModuleFieldSettings renderer={fieldRenderers[draft.type]} settings={draft.settings} onChange={(settings) => setDraft({ ...draft, settings })} />
   ```
10. In the fields table, replace
    ```tsx
                            // a unique geometry means nothing, and the server refuses it
                            ...(field.type === 'GEOMETRY' ? [] : [['unique', t('objects.fieldUnique')] as const]),
    ```
    with
    ```tsx
                            // some module types mean nothing as unique, and the server refuses them
                            ...(fieldRenderers[field.type]?.uniqueAllowed === false ? [] : [['unique', t('objects.fieldUnique')] as const]),
    ```
11. ``<Link to={`/data/objects/${object}/records`}>`` → `<Link to={links.records(object)}>`, and `void navigate('/data/objects')` → `void navigate(links.objects())`.

- [ ] **Step 6: Edit `ObjectBuilderPage.tsx`**

1. Replace the `./objectDraft` import with `import { addableFieldTypes, emptyFieldDraft, fieldPayload, nameTaken, type FieldDraft as SharedFieldDraft } from './objectDraft'`. Add these imports:
   ```tsx
   import { useChawpiLinks, useRegistry } from '../../app/context'
   import type { FieldRenderer } from '../../registry/contract'
   import { ModuleFieldSettings } from './ModuleFieldSettings'
   ```
2. Replace `emptyField` with:
   ```tsx
   function emptyField(renderers: Readonly<Record<string, FieldRenderer>>): FieldDraft {
     return { key: crypto.randomUUID(), ...emptyFieldDraft(renderers) }
   }
   ```
3. After `const navigate = useNavigate()`, add:
   ```tsx
     const links = useChawpiLinks()
     const { fieldRenderers } = useRegistry()
   ```
4. `useState<FieldDraft[]>([emptyField()])` → `useState<FieldDraft[]>(() => [emptyField(fieldRenderers)])`, and `[...current, emptyField()]` → `[...current, emptyField(fieldRenderers)]`.
5. In `submit`, replace `.map((field) => ({ name: …, srid: … }))` (the whole object literal) with `.map((field) => fieldPayload(field, fieldRenderers))`.
6. ``void navigate(`/data/objects/${created.name}/records`)`` → `void navigate(links.records(created.name))`.
7. `{ADDABLE_FIELD_TYPES.map((type) => (` → `{addableFieldTypes(fieldRenderers).map((type) => (`.
8. Replace the whole `{field.type === 'GEOMETRY' ? ( … ) : null}` block with:
   ```tsx
                   <ModuleFieldSettings renderer={fieldRenderers[field.type]} settings={field.settings} onChange={(settings) => patchField(field.key, { settings })} />
   ```
   The builder keeps its unique checkbox for every type, as sapgis did.

- [ ] **Step 7: Edit `ObjectsPage.tsx` and `ObjectRelationships.tsx`**

`ObjectsPage.tsx`:
1. Add the import `import { useChawpiLinks, useRegistry } from '../../app/context'`. In the component, after `const { t } = useTranslation()`, add:
   ```tsx
     const links = useChawpiLinks()
     const { objectColumns } = useRegistry()
   ```
2. `<Link to="/data/objects/new">` → `<Link to={links.newObject()}>`.
3. `<Th>{t('objects.geometry')}</Th>` → `{objectColumns.map((column) => (<Th key={column.id}>{t(column.headerKey)}</Th>))}`.
4. Replace the whole `<Td>{item.geometry ? ( <Badge>…</Badge> ) : ( <span …>{t('objects.noGeometry')}</span> )}</Td>` cell with:
   ```tsx
                       {objectColumns.map((column) => (
                         <Td key={column.id}>
                           <column.cell object={item} />
                         </Td>
                       ))}
   ```
5. ``<Link to={`/data/objects/${item.name}/records`}>`` → `<Link to={links.records(item.name)}>`, and ``<Link to={`/data/objects/${item.name}/edit`}>`` → `<Link to={links.editObject(item.name)}>`.

`ObjectRelationships.tsx`: add `import { useChawpiLinks } from '../../app/context'`. In `ObjectRelationships`, after `const { t } = useTranslation()`, add `const links = useChawpiLinks()`. Then ``to={`/data/objects/${otherObject}/edit`}`` → `to={links.editObject(otherObject)}`.

- [ ] **Step 8: Mount routes and nav** — `frontend/packages/core/src/app/coreModule.ts`

Change the lucide import to `import { Boxes, Database, FileStack } from 'lucide-react'` and add:

```ts
import { ObjectBuilderPage } from '../features/objects/ObjectBuilderPage'
import { ObjectEditorPage } from '../features/objects/ObjectEditorPage'
import { ObjectsPage } from '../features/objects/ObjectsPage'
import { RelationshipsPage } from '../features/relationships/RelationshipsPage'
```

Append to `routes`:

```ts
    { id: 'objects', path: CORE_ROUTE_PATHS.objects, component: ObjectsPage },
    { id: 'newObject', path: CORE_ROUTE_PATHS.newObject, component: ObjectBuilderPage },
    { id: 'editObject', path: CORE_ROUTE_PATHS.editObject, component: ObjectEditorPage },
    { id: 'relationships', path: CORE_ROUTE_PATHS.relationships, component: RelationshipsPage }
```

Replace `nav` with:

```ts
  nav: [
    { group: 'data', labelKey: 'nav.objects', order: 10, icon: Boxes, route: 'objects' },
    { group: 'data', labelKey: 'nav.records', order: 20, icon: Database, disabled: true },
    { group: 'data', labelKey: 'nav.relationships', order: 30, icon: FileStack, route: 'relationships' }
  ]
```

- [ ] **Step 9: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export {
  addableFieldTypes,
  describeError,
  emptyFieldDraft,
  fieldPayload,
  nameTaken,
  scopeOf,
  type FieldDraft,
  type NameProblem
} from './features/objects/objectDraft'
export * from './features/objects/relationshipSides'
export { ModuleFieldSettings } from './features/objects/ModuleFieldSettings'
export { ObjectsPage } from './features/objects/ObjectsPage'
export { ObjectBuilderPage } from './features/objects/ObjectBuilderPage'
export { ObjectEditorPage } from './features/objects/ObjectEditorPage'
export { ObjectRelationships } from './features/objects/ObjectRelationships'
export { RelationshipsPage } from './features/relationships/RelationshipsPage'
```

- [ ] **Step 10: Verify**

```bash
yarn prettier --write frontend/packages/core/src
grep -rn "GEOMETRY\|geometr\|srid\|EPSG\|'/data\|\`/data" frontend/packages/core/src/features/objects --include=*.tsx --include=*.ts | grep -v "\.test\." || echo "objects clean"
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: `objects clean`. All tests pass, including every ported ObjectEditorPage case and the 2 new ones. Nothing is committed.

---

### Task 14: Administration (users, roles, permissions, audit)

**Files:**
- Create (ported, no edits): `frontend/packages/core/src/features/admin/{types.ts, api.ts, permissionMatrix.ts, permissionMatrix.test.ts, RolesPage.tsx, PermissionsPage.tsx, UsersPage.tsx}`
- Create (ported + edited): `frontend/packages/core/src/features/admin/{i18n.ts, AuditPage.tsx, UsersPage.test.tsx}`
- Test: `frontend/packages/core/src/features/admin/AuditPage.test.tsx`
- Modify: `frontend/packages/core/src/i18n/coreBundles.ts`, `frontend/packages/core/src/app/coreModule.ts`, `frontend/packages/core/src/index.ts`

**Interfaces:**
- Consumes: `useAuth` (Task 5); `useAuditLog`, `describeChanges`, `ChangeList`, `OperationBadge`, `useAuditExtensions`, `relativeTime`, `absoluteTime` (Task 10); `useObjects`, `useObjectDefinition` (Task 7); `issueModule` (Task 9); `mockFetch`, `renderWithProviders` (Task 6).
- Produces:
  - `adminMessages = { es, en }`, merged into `common` through `coreBundles`.
  - `UsersPage`, `RolesPage`, `PermissionsPage`, `AuditPage`.
  - `coreModule` routes `core:users`, `core:roles`, `core:permissions` and `core:audit`, and the administration nav (Users, Roles, Permissions, Audit, in sapgis order).

- [ ] **Step 1: Port**

```bash
C=frontend/packages/core/src/features/admin
for f in types.ts api.ts i18n.ts permissionMatrix.ts permissionMatrix.test.ts UsersPage.tsx UsersPage.test.tsx RolesPage.tsx PermissionsPage.tsx AuditPage.tsx; do
  node frontend/tooling/port-from-sapgis.mjs features/admin/$f $C/$f
done
```
Expected: one `MANUAL:` line, `i18n.ts still imports @/lib/i18n`. The pages' `import './i18n'` side effects are already gone.

- [ ] **Step 2: Turn the admin strings into a plain bundle** — `features/admin/i18n.ts`

1. Delete `import i18n from '@/lib/i18n'`.
2. Replace the last three statements (two `i18n.addResourceBundle(...)` calls and `export default i18n`) with:
   ```ts
   // merged into core's `common` namespace by createChawpiI18n (coreBundles)
   export const adminMessages = { es, en }
   ```
3. Top comment → `// the admin screens own their strings; coreBundles merges them into common.`

`frontend/packages/core/src/i18n/coreBundles.ts`:

```ts
import { adminMessages } from '../features/admin/i18n'
import { historyMessages } from '../features/history/i18n'

// strings a core feature keeps outside common.json, deep-merged into `common` when an app starts.
// a feature adds its bundle here instead of registering it by an import side effect.
export const coreBundles: Record<string, Record<string, unknown>>[] = [historyMessages, adminMessages]
```

- [ ] **Step 3: Audit changes know what modules know** — `features/admin/AuditPage.tsx`

1. Add `import { useAuditExtensions } from '../history/useAuditExtensions'`.
2. Replace the body of `AuditRowChanges` with:
   ```tsx
     // labels need the object definition (cached by the rest of the app) and what modules say about their values
     const definition = useObjectDefinition(entry.objectName)
     const extensions = useAuditExtensions()
     return <ChangeList changes={describeChanges(entry, definition.data, extensions)} />
   ```

- [ ] **Step 4: Edit the ported users test** — `features/admin/UsersPage.test.tsx`

The providers now include the real `AuthProvider`, so the test hands in the signed-in user instead of mocking the module.
1. Delete the whole `vi.mock('../../auth/AuthProvider', …)` block and its comment.
2. Add `import type { AuthUser } from '../../types/auth'` and, after `SIGNED_IN_ID`:
   ```tsx
   // the page reads the signed-in user to protect them from themselves
   const signedIn: AuthUser = { id: SIGNED_IN_ID, email: 'ana@chawpi.test', displayName: 'Ana Admin', organizationId: 'org-1', roles: ['ADMIN'] }
   ```
3. Replace both `renderWithProviders(<UsersPage />)` calls with `renderWithProviders(<UsersPage />, { user: signedIn })`.

`frontend/packages/core/src/features/admin/AuditPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { mockFetch, renderWithProviders, type FetchMock } from '@chawpi/testing'
import { issueModule } from '../../test/fakeModules'
import { AuditPage } from './AuditPage'

let fetch: FetchMock | null = null
afterEach(() => fetch?.restore())

describe('AuditPage', () => {
  it('names a module value and column when a change row is opened', async () => {
    fetch = mockFetch([
      { path: '/objects', body: [] },
      { path: '/metadata/objects/predio', body: { id: 'o1', name: 'predio', label: 'Predio', pluralLabel: 'Predios', description: null, enabled: true, fields: [] } },
      {
        path: /^\/audit\?/,
        body: [
          {
            id: 'a1',
            userEmail: 'ana@chawpi.test',
            objectName: 'predio',
            recordId: 'r1',
            operation: 'UPDATE',
            occurredAt: '2026-09-17T11:00:00Z',
            changes: [{ field: 'sketch', before: null, after: { strokes: [[0, 0, 1, 1]] } }]
          }
        ]
      }
    ])
    renderWithProviders(<AuditPage />, { modules: [issueModule] })

    await userEvent.click(await screen.findByRole('button', { name: 'Ver cambios' }))

    expect(await screen.findByText('Boceto')).toBeInTheDocument()
    expect(screen.getByText('boceto actualizado')).toBeInTheDocument()
  })
})
```

- [ ] **Step 5: Mount routes and nav** — `frontend/packages/core/src/app/coreModule.ts`

Change the lucide import to `import { Boxes, Database, FileStack, KeyRound, Shield, Users } from 'lucide-react'` and add:

```ts
import { AuditPage } from '../features/admin/AuditPage'
import { PermissionsPage } from '../features/admin/PermissionsPage'
import { RolesPage } from '../features/admin/RolesPage'
import { UsersPage } from '../features/admin/UsersPage'
```

Append to `routes`:

```ts
    { id: 'users', path: CORE_ROUTE_PATHS.users, component: UsersPage },
    { id: 'roles', path: CORE_ROUTE_PATHS.roles, component: RolesPage },
    { id: 'permissions', path: CORE_ROUTE_PATHS.permissions, component: PermissionsPage },
    { id: 'audit', path: CORE_ROUTE_PATHS.audit, component: AuditPage }
```

Append to `nav`. As in sapgis, everyone sees them (R7), and the server refuses what a caller may not do.

```ts
    { group: 'administration', labelKey: 'nav.users', order: 10, icon: Users, route: 'users' },
    { group: 'administration', labelKey: 'nav.roles', order: 20, icon: Shield, route: 'roles' },
    { group: 'administration', labelKey: 'nav.permissions', order: 30, icon: KeyRound, route: 'permissions' },
    { group: 'administration', labelKey: 'nav.audit', order: 40, route: 'audit' }
```

- [ ] **Step 6: Export** — append to `frontend/packages/core/src/index.ts`

```ts
export { UsersPage } from './features/admin/UsersPage'
export { RolesPage } from './features/admin/RolesPage'
export { PermissionsPage } from './features/admin/PermissionsPage'
export { AuditPage } from './features/admin/AuditPage'
```

- [ ] **Step 7: Verify**

```bash
yarn prettier --write frontend/packages/core/src
yarn workspace @chawpi/core lint
yarn workspace @chawpi/core test
yarn workspace @chawpi/core build
git status --short
```
Expected: all green, including the ported `permissionMatrix` and `UsersPage` suites and the new audit test. Nothing is committed.

---

### Task 15: `ChawpiApp` smoke test, boundary test, core README, whole-workspace verification

**Files:**
- Test: `frontend/packages/core/src/app/ChawpiApp.smoke.test.tsx`, `frontend/packages/core/src/boundaries.test.ts`
- Create: `frontend/packages/core/README.md`
- Verify only: root `package.json` scripts, `.github/workflows/ci.yml` (frontend job already runs `yarn lint && yarn test && yarn build` once packages exist; no edit expected), `release-please-config.json`

**Interfaces:**
- Consumes: everything above. `ChawpiApp`, `coreModule`, `CORE_ROUTE_PATHS`, `joinPath`, `mockFetch`.
- Produces: the P4 exit gate. Root `yarn format:check`, `yarn test:tooling`, `yarn lint`, `yarn test` and `yarn build` are all green, and each package packs only `dist` + README.

- [ ] **Step 1: Write the smoke test** — `frontend/packages/core/src/app/ChawpiApp.smoke.test.tsx`

This mounts the real `ChawpiApp` (BrowserRouter, real providers) with a fake module and a plain fetch mock. It does not use MSW.

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockFetch, type FetchMock } from '@chawpi/testing'
import { CORE_ROUTE_PATHS } from '../links/links'
import { joinPath } from '../links/paths'
import type { ChawpiModule } from '../registry/contract'
import { ChawpiApp } from './ChawpiApp'
import { coreModule } from './coreModule'

const Sheets = () => <p>hojas del plano</p>
const plans: ChawpiModule = {
  id: 'plans',
  basePath: 'plans',
  routes: [{ id: 'sheets', path: 'sheets', lazy: async () => ({ default: Sheets }) }],
  navGroups: [{ id: 'plans', labelKey: 'plans:nav.group', order: 20 }],
  nav: [{ group: 'plans', labelKey: 'plans:nav.sheets', order: 10, route: 'sheets' }],
  i18n: { es: { nav: { group: 'Planos', sheets: 'Hojas' } }, en: { nav: { group: 'Plans', sheets: 'Sheets' } } }
}

const ana = { id: 'u1', email: 'ana@chawpi.test', displayName: 'Ana', organizationId: 'o1', roles: ['ADMIN'] }

let fetch: FetchMock | null = null
beforeEach(() => {
  localStorage.clear()
  window.history.pushState({}, '', '/')
})
afterEach(() => fetch?.restore())

describe('ChawpiApp', () => {
  it('sends a signed-out deep link to login, signs in, and reaches a module page from the sidebar', async () => {
    fetch = mockFetch(
      [
        { method: 'POST', path: '/auth/login', body: { token: 't', expiresAt: '2026-12-31T00:00:00Z', user: ana } },
        { path: '/auth/me/permissions', body: { admin: true, objects: {} } },
        { path: '/objects', body: [] }
      ],
      { baseUrl: '/backend/api' }
    )
    window.history.pushState({}, '', '/plans/sheets')
    render(<ChawpiApp config={{ apiBaseUrl: '/backend/api', storagePrefix: 'smoke', appName: 'Catastro' }} modules={[plans]} />)

    await userEvent.type(await screen.findByLabelText('Correo'), 'ana@chawpi.test')
    await userEvent.type(screen.getByLabelText('Contraseña'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))

    expect(await screen.findByRole('heading', { name: 'Inicio' })).toBeInTheDocument()
    expect(screen.getByText('Catastro')).toBeInTheDocument()
    expect(screen.getByText('Planos')).toBeInTheDocument()
    expect(localStorage.getItem('smoke.token')).toBe('t')
    expect(fetch.calls.map((call) => call.url)).toContain('/backend/api/auth/login')

    await userEvent.click(screen.getByRole('link', { name: 'Hojas' }))
    expect(await screen.findByText('hojas del plano')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/plans/sheets')
  })

  it('comes back signed in from its storage prefix and opens the deep link directly', async () => {
    localStorage.setItem('smoke.user', JSON.stringify(ana))
    localStorage.setItem('smoke.token', 't')
    fetch = mockFetch([{ path: '/auth/me/permissions', body: { admin: true, objects: {} } }])
    window.history.pushState({}, '', '/plans/sheets')
    render(<ChawpiApp config={{ storagePrefix: 'smoke' }} modules={[plans]} />)
    expect(await screen.findByText('hojas del plano')).toBeInTheDocument()
  })

  it('refuses to start when two modules claim the same field type', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const input = () => null
    const modules: ChawpiModule[] = [
      { id: 'a', fieldRenderers: { SKETCH: { section: 'a', input } } },
      { id: 'b', fieldRenderers: { SKETCH: { section: 'b', input } } }
    ]
    expect(() => render(<ChawpiApp modules={modules} />)).toThrow(/claimed by both 'a' and 'b'/)
  })

  it('mounts every core screen at the path CORE_ROUTE_PATHS names', () => {
    const mounted = Object.fromEntries((coreModule.routes ?? []).map((route) => [route.id, joinPath(route.path)]))
    const table = Object.fromEntries(Object.entries(CORE_ROUTE_PATHS).map(([id, path]) => [id, joinPath(path)]))
    expect(mounted).toEqual(table)
  })
})
```

- [ ] **Step 2: Write the boundary test** — `frontend/packages/core/src/boundaries.test.ts`

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// core must stay installable without gis, workflow, documents or the builders: no import of their
// heavy libraries or packages, no gis vocabulary, no hardcoded urls or storage keys.
const SRC = fileURLToPath(new URL('.', import.meta.url))

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sources(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : []
  })
}

function offenders(pattern: RegExp, except: (file: string) => boolean = () => false): string[] {
  return sources(SRC)
    .filter((file) => !except(file) && pattern.test(readFileSync(file, 'utf8')))
    .map((file) => relative(SRC, file))
}

describe('core boundaries', () => {
  it('scans the whole source tree', () => {
    expect(sources(SRC).length).toBeGreaterThan(50)
  })

  it('never imports a heavy library or another module package', () => {
    expect(offenders(/from '(maplibre-gl|terra-draw[^']*|@xyflow\/[^']*|@tiptap\/[^']*|@dnd-kit\/[^']*|@chawpi\/(?!ui')[^']*)'/)).toEqual([])
  })

  it('never names gis concepts outside tests', () => {
    expect(offenders(/geometr|srid|epsg|maplibre|geojson/i)).toEqual([])
  })

  it('never spells an in-app url: links build them', () => {
    expect(offenders(/['"`]\/(data|admin|gis|builder|automation|documents)\//)).toEqual([])
  })

  it('never names a storage key: storageKeys builds them', () => {
    expect(offenders(/localStorage\.(getItem|setItem|removeItem)\(['"`]/)).toEqual([])
  })
})
```

- [ ] **Step 3: Run the new tests**

Run: `yarn workspace @chawpi/core test src/app/ChawpiApp.smoke.test.tsx src/boundaries.test.ts`
Expected: PASS. If the boundary test lists a file, fix that file, not the test. The usual cause is a comment or leftover branch the earlier task's edits missed. Reword or remove it and re-run.

- [ ] **Step 4: Write `frontend/packages/core/README.md`**

````markdown
# @chawpi/core

The chawpi app in one component: shell, login, dashboard, objects, relationships, records, dynamic
forms, the record detail page renderer, history/audit and administration. Plus the module registry
that lets `@chawpi/gis`, `@chawpi/workflow`, `@chawpi/documents`, … plug in.

Peer dependencies: `react`, `react-dom`, `react-router`, `@tanstack/react-query`, `i18next`,
`react-i18next`. Styling: set up Tailwind as described in `@chawpi/ui`'s README.

## Quick start

```tsx
import { createRoot } from 'react-dom/client'
import { ChawpiApp } from '@chawpi/core'
import './index.css' // tailwind + @chawpi/ui/theme.css + @source, see @chawpi/ui

createRoot(document.getElementById('root')!).render(
  <ChawpiApp config={{ apiBaseUrl: '/api', appName: 'Catastro', storagePrefix: 'catastro' }} modules={[]} />
)
```

| config | default | meaning |
|---|---|---|
| `apiBaseUrl` | `/api` | REST base url (trailing slash ignored) |
| `storagePrefix` | `chawpi` | localStorage keys `<prefix>.token/.user/.lang`; give each app on an origin its own |
| `languages` | `['es', 'en']` | first is default and fallback; the shell toggle cycles through them |
| `appName`, `appTagline` | the `app.name`/`app.tagline` strings | shell and login header |
| `basename` | – | router basename when not served from `/` |
| `defaultLoginEmail` | `''` | login form prefill (demo apps) |

Config and modules are read once, at mount. One `ChawpiApp` per page: plain `api()` calls use the
client of the mounted app.

## Backend modules are optional

Core only needs the core backend. Without the pages module the record detail page is generated
from metadata (form, related lists, history); without the views module lists use every visible
field.

## Writing a module

A module is a plain object; every slot is optional.

```tsx
import type { ChawpiModule } from '@chawpi/core'

export function plansModule(): ChawpiModule {
  return {
    id: 'plans', // unique; also the i18n namespace and route-key prefix
    basePath: 'plans', // routes mount under /plans
    routes: [{ id: 'list', path: '', lazy: () => import('./PlansPage') }],
    navGroups: [{ id: 'plans', labelKey: 'plans:nav.group', order: 20 }],
    nav: [{ group: 'plans', labelKey: 'plans:nav.list', order: 10, route: 'list' }],
    i18n: { es: { nav: { group: 'Planos', list: 'Listado' } }, en: { nav: { group: 'Plans', list: 'List' } } }
  }
}
```

| slot | used for |
|---|---|
| `routes` | pages; `chrome`: `shell` (default), `bare` (signed in, no shell), `public` |
| `navGroups`, `nav` | sidebar; core declares `data` 10, `builder` 30, `automation` 40, `administration` 50 |
| `fieldRenderers` | new field types; values live in `record[section]`, the form sends them back there |
| `pageComponents`, `pageActions` | record-page component types and ACTION kinds |
| `recordPanels`, `recordListActions` | extra UI under a record page / in the record list header |
| `historyRenderers`, `auditValueFormatters`, `auditFieldLabels` | history entries and audit values |
| `dashboardCards`, `objectColumns`, `objectTileDetails` | dashboard and object list additions |
| `objectFlags`, `recordQueryKeys` | facts about an object; query keys to refresh after record writes |
| `providers`, `i18n` | app-wide wrappers; strings under the module's own namespace |

`createRegistry` validates the modules at startup and throws a `RegistryError` on a conflict:
- two modules claiming one type;
- a core type claimed;
- duplicate route paths;
- nav pointing at a missing route.

Links: `useChawpiLinks()` gives `records(object)`, `record(object, id)`, … for core screens and
`to('plans:list')` / `has('plans:list')` for module routes.

Testing: see `@chawpi/testing`.
````

- [ ] **Step 5: Whole-workspace verification**

```bash
cd /Users/jorge/IdeaProjects/chawpi
rm -rf frontend/packages/*/dist
yarn install --frozen-lockfile || yarn install
yarn format:check
yarn test:tooling
yarn lint
yarn test
yarn build
for p in ui core testing; do (cd frontend/packages/$p && npm pack --dry-run 2>&1 | grep -E "Tarball Contents|dist/index.js$|README.md|src/" ); done
node -e "const c=require('./release-please-config.json'); console.log(c.packages['.']['extra-files'].map(f => typeof f === 'string' ? f : f.path + ' ' + f.jsonpath).join('\n'))"
grep -n '"@chawpi/' frontend/packages/*/package.json
git status --short
```
Expected:
- the format check is clean;
- tooling, lint and test pass in every workspace;
- `yarn build` prints `@chawpi/ui`, then `@chawpi/core`, then `@chawpi/testing`, all successful;
- each `npm pack --dry-run` lists `dist/index.js` and `README.md` and no `src/` file;
- `extra-files` lists `gradle.properties`, root `package.json`, and `$.version` for each of `frontend/packages/{ui,core,testing}/package.json` (no dependency jsonpaths: internal ranges are `"*"`, pinned at publish by `set-version.mjs`);
- `grep -n '"@chawpi/' frontend/packages/*/package.json` shows only `"*"`;
- `git status` shows only uncommitted changes.

If `yarn install --frozen-lockfile` fails because the lockfile changed during P4, plain `yarn install` updates it, and `yarn.lock` shows in `git status` as modified.

- [ ] **Step 6: CI parity check**

Read `.github/workflows/ci.yml`. The frontend job must run `yarn install --frozen-lockfile`, `yarn format:check`, `yarn test:tooling` and `yarn lint && yarn test && yarn build` (the last gated on `frontend/packages/*/package.json`, which now exists). No edit is expected. If the file differs from that, report it instead of changing it (CI belongs to P7).

---

## Self-review

**1. Spec coverage** (spec "Frontend design", "Commits, versioning and releases", "Tests", and the P4 brief):

| Requirement | Task |
|---|---|
| `@chawpi/ui`: primitives, `cn`, `theme.css`, CSS exported, README with `@import` + `@source` | 2 |
| `@chawpi/core` `ChawpiApp` (apiBaseUrl, storagePrefix, appName, languages; modules[]) | 3 (config), 8 |
| Module registry + `ChawpiModule` contract (routes, nav, fieldRenderers, pageComponents, recordPanels/historyRenderers, i18n namespace, providers) | 4, 5 (providers/i18n) |
| api client, configurable base URL and storage prefix | 3 |
| Auth: AuthProvider/useAuth + permissions from `/api/auth/me/permissions` | 5 |
| i18n factory, own namespace per module | 5, 10, 14 (bundles) |
| Types; metadata-to-zod | 3 |
| Per-domain query hooks (split `lib/queries.ts`) | 7 |
| `useChawpiLinks()` replaces hardcoded URLs | 4, 5, then 8/9/11/12/13 edits; 15 boundary test |
| AppShell with nav from registry | 8 |
| Features: login, dashboard, objects, relationships, records, dynamic-form, data-table, related, page-renderer, admin, history | 8, 9, 10, 11, 12, 13, 14 |
| Cycles removed: auth in core, page-renderer via registry, DynamicForm renderers via registry, history↔documents | 5, 11, 9, 10 |
| Lazy routes | 4 (registry `lazy`), 8 (Suspense), 15 (smoke) |
| GIS/heavy deps out of core; geometry keys still carried | 3 (types), 9, 11, 13, 15 (boundary) |
| `@chawpi/testing`: renderWithProviders with modules, fetch mocks | 6 |
| Vite library mode + `tsc` declarations, ESM, peer deps, `exports`, `sideEffects`, scripts lint/test/build | 2, 3, 6 |
| release-please extra-files for every package | 2, 3, 6 |
| Root `yarn lint/test/build` pass; build order | 1, 15 |
| sapgis tests ported; new tests for registry, ChawpiApp routing + nav, api client config, links | 3–15 |
| Smoke test mounting `ChawpiApp` with a fake module and a MSW-less fetch mock | 15 |
| Unused `@dnd-kit/sortable`/`modifiers` dropped | not carried into any P4 package (P5 pages takes only `@dnd-kit/core`) |
| No commits; format per task | Global Constraints, every task's last step |

No verification app is built in P4. Examples are P6.

**2. Placeholder scan:** there are no "TBD", "similar to Task N" or undefined references. Ported files are named by exact sapgis path and target, and every hand edit shows the before and after text. New files are given in full. Two version-dependent option names (`rolldownOptions`, `initAsync`) carry an explicit fallback instruction.

**3. Type consistency:** the names used by later tasks all match their definitions:
- `RecordPayload` (Task 3), used by `useSaveRecord` (7), `DynamicForm` (9), `PageRenderer` (11);
- `FieldRenderer.section`/`input`/`uniqueAllowed`/`settings.{defaults,editor,toPayload}` (4), used in 9, 13 and the fakes;
- `ChawpiRegistry` fields (4) and the hooks `useRegistry`/`useChawpiLinks`/`useObjectFlags` (5);
- `renderWithProviders` options `modules/route/path/user/permissions/language/config` (6), used in 7–15;
- `AuditExtensions` (10), used by `AuditPage` (14);
- `coreModule` route ids equal `CORE_ROUTE_PATHS` keys (pinned by the smoke test).

**4. Review Focus:** each of the five lines has a pinned test in its owning task (listed in the section itself).
