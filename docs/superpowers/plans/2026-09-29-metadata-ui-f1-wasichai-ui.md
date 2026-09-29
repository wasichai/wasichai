# Metadata UI, phase 1: dev pre-releases and shared primitives in wasichai-ui

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Part A and Part B touch
> disjoint files and run in parallel. Each task is test first (red, the implementation, green) and ends in a commit.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** wasichai-ui can publish `0.4.0-dev.N` pre-releases from `dev` under the npm dist-tag `dev`, and ships the
domain-free primitives srtm-ui and caja-ui share: `ConfirmDialog`, `Pagination`, `PageSizePagination`, `PdfDialog`
(`@wasichai/ui`) and `QueryState` (`@wasichai/core`).

**Architecture:** Part A adds `tooling/dev-release.mjs` (next dev version, a tag's version, dist-tag of a version),
`tooling/publish-packages.mjs` (the upload, once per version) and a `release-dev.yml` started by pushing a `vX.Y.0-dev.N`
tag, which calls `publish.yml` as a reusable workflow, then creates the GitHub prerelease (amendments 1 and 2). Part B
generalises srtm-ui's components: markup and behaviour kept, strings moved to core's `common` bundle (en, es), `data-slot`
hooks for themes.

**Tech Stack:** React 19.3, react-i18next, Radix dialog, vitest + testing-library, node:test for tooling, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-29-metadata-ui-design.md` (wasichai repository)

## Amendments (2026-09-29)

Made while the plan ran, on the controller's ruling and the reviews. The tasks below carry the amended text and mark it
(*amended*, with the number); what each replaced is said here.

1. **A tag starts the release, not a dispatch (Task A2, Integration).** The plan had *Release dev* as a
   `workflow_dispatch` run on `dev`. GitHub offers a manual dispatch only for a workflow file on the default branch, and
   nothing of `dev` may reach `main`, so the trigger would never appear. The workflow is now `on: push: tags: ['v*-dev.*']`:
   a tag push runs the workflow file of the tagged commit, which lives on `dev`. It checks the tag is `vX.Y.Z-dev.N`
   (`dev-release.mjs version-of <tag>`, a third CLI command beside `next` and `dist-tag`) and that the commit is on
   `origin/dev` (anyone can tag any commit), publishes through the reusable `publish.yml`, and only then creates the
   GitHub prerelease for that tag (`gh release create <tag> --verify-tag --prerelease`). The tag exists before the upload,
   so a failed upload leaves a tag and no release: fix the cause (token, package access, registry) and re-run the failed
   jobs of the tag's run, which skips the packages already up. A re-run reuses the tagged commit, so a cause that needs a
   code change gets a new commit on `dev` and the next `N`; the incomplete `dev.N` is never used. The human runs
   `git fetch --tags && node tooling/dev-release.mjs next`, which prints a version (e.g. `0.4.0-dev.0`); the tag is `v` plus
   that. The fetch lets the counter see the tags already pushed.
2. **`tooling/publish-packages.mjs` (Task A2).** The publish loop left `publish.yml`'s shell for a script with tests. It
   skips a version already on the registry, so re-running the tag's workflow completes a partial upload instead of failing
   with E409 on the packages already up. Under any dist-tag but `latest` it reads each package's `latest` before and after
   `npm publish`. If the registry moved it and the package had an earlier `latest`, it puts that one back with
   `npm dist-tag add` and fails the run so it is noticed. On a package's first publish there is no earlier `latest` to
   restore (the registry sets it anyway): the run fails saying so, and `latest` stays until the next release. It also
   refuses to start if a package's version differs from the one asked for.
3. **Spanish plurals need a `_many` twin (Task B1).** `Intl.PluralRules('es')` answers `many` for multiples of 1,000,000,
   and i18next then looks for `records_many` and `range_many`; without them it prints the raw key. The `es` bundle carries
   both, with the text of `_other`, and `sharedPrimitives.test.tsx` renders a million records.
4. **`bg-surface` behind the `PdfDialog` iframe (Task B3).** srtm-ui's iframe had `bg-white`, but the ui theme test forbids
   fixed palette classes (they ignore the theme), so the iframe backdrop is the theme's own `bg-surface`, over the
   `bg-surface-muted` box.
5. **`QueryState` carries `data-state` (Task B4).** `data-slot="query-state"` alone cannot tell the loading, empty and error
   markup apart for a theme sheet, so each part also sets `data-state` (`loading`, `empty`, `error`).
6. **Pagination hooks (final review): `data-mode` names what the footer shows.** `Pagination` renders
   `data-mode="pages"` (shows "Página x de y" / record count); `PageSizePagination` renders
   `data-mode="range"` (a range footer "a–b de n" with a rows picker, for client slices or a server page).
   Task B1's `server`/`client` values are superseded.

## Global Constraints

- Branches: Part A `ci/release-dev` (worktree `../wasichai-ui-ci-release-dev`), Part B `feat/ui-primitivas-compartidas`
  (worktree `../wasichai-ui-feat-primitivas`), both off `dev`, PRs based on `dev`. Nothing is merged into `main`.
- Pre-release version: next minor of `.release-please-manifest.json` plus `-dev.N` (`0.3.1` → `0.4.0-dev.0`), npm
  dist-tag `dev`. `latest` stays where release-please put it.
- Rule 3: `@wasichai/ui` imports nothing from `@wasichai/core`; core may import ui (each `boundaries.test.ts`).
- `data-slot` set before `{...props}`, so a caller overrides it. No `data-ui` in the library (ADR-035).
- Every visible string is a `common.*` key present in both `core/src/i18n/locales/{en,es}/common.json`; numbers in
  strings use i18next's `number` format (`{{total, number}}`) so an app can swap the formatter.
- `DataTable` does not change (rule 5).
- `.editorconfig` is law (160 columns); `yarn format`; Conventional Commits in English; comments in English, caveman.

## Review Focus

1. **A dev release never moves `latest`:** `distTagFor('0.4.0-dev.0')` is `dev`, `distTagFor('0.4.0')` is `latest`,
   anything else throws (Task A1).
2. **No version is published twice:** the next dev number comes from the existing `v*-dev.*` tags, and a gap
   (`dev.0`, `dev.2`) continues after the highest (Task A1); a version already on the registry is skipped (amendment 2).
3. **Empty and single-page lists:** `PageSizePagination` with `total = 0` shows `0 a 0 de 0 registros` and disables both
   arrows; `Pagination` with one page hides the arrows (Task B1).
4. **A confirmation while busy:** confirm disabled while `busy`; Escape and the overlay call `onCancel`; the error has
   `role="alert"` (Task B2).
5. **A PDF that changes or closes mid-load:** the blob url is revoked on close and on unmount, and a load that resolves
   after `source` changed is dropped (Task B3).

---

## Part A — `ci/release-dev`

### Task A1: `tooling/dev-release.mjs`

**Files:** Create `tooling/dev-release.mjs`, `tooling/dev-release.test.mjs`.

**Interfaces — Produces:**

```js
export function nextDevVersion(baseVersion, tags) // ('0.3.1', ['v0.3.1', 'v0.4.0-dev.0']) -> '0.4.0-dev.1'
export function distTagFor(version) // '0.4.0-dev.3' -> 'dev', '0.4.0' -> 'latest', else throws
// CLI: `node tooling/dev-release.mjs next` prints the next version (manifest + `git tag --list 'v*-dev.*'`)
//      `node tooling/dev-release.mjs dist-tag <version>` prints its dist-tag
//      `node tooling/dev-release.mjs version-of <tag>` prints the version of a `vX.Y.Z-dev.N` tag, else fails (amendment 1)
```

- [ ] **Step 1: failing tests** (`node:test`, `assert/strict`, same style as `set-version.test.mjs`):
  - `nextDevVersion('0.3.1', [])` → `0.4.0-dev.0`; `('0.3.1', ['v0.4.0-dev.0', 'v0.4.0-dev.1'])` → `0.4.0-dev.2`;
    `('0.3.1', ['v0.4.0-dev.0', 'v0.4.0-dev.2'])` → `0.4.0-dev.3`; tags of another line (`v0.5.0-dev.4`) are ignored;
    `('1.2.3', [])` → `1.3.0-dev.0`; `('0.4.0-dev.1', [])` throws `/plain version/`.
  - `distTagFor('0.4.0-dev.3')` → `dev`; `distTagFor('0.4.0')` → `latest`; `distTagFor('0.4.0-rc.1')` throws.
- [ ] **Step 2:** `yarn test:tooling` → FAIL (`Cannot find module './dev-release.mjs'`).
- [ ] **Step 3: implement.**

```js
const PLAIN = /^(\d+)\.(\d+)\.(\d+)$/
const DEV = /^\d+\.\d+\.\d+-dev\.(\d+)$/

// dev builds ride the next minor: bump-minor-pre-major is on, so main's next release is that minor too
export function nextDevVersion(baseVersion, tags) {
  const match = PLAIN.exec(baseVersion)
  if (!match) throw new Error(`dev-release: needs a plain version, got '${baseVersion}'`)
  const line = `${match[1]}.${Number(match[2]) + 1}.0`
  const taken = tags.map((tag) => tag.replace(/^v/, '')).filter((v) => v.startsWith(`${line}-dev.`)).map((v) => Number(DEV.exec(v)?.[1] ?? -1))
  return `${line}-dev.${Math.max(-1, ...taken) + 1}`
}

// a dev build never becomes latest
export function distTagFor(version) {
  if (PLAIN.test(version)) return 'latest'
  if (DEV.test(version)) return 'dev'
  throw new Error(`dev-release: no dist-tag for '${version}'`)
}
```

  CLI block guarded by `process.argv[1] === fileURLToPath(import.meta.url)`, reading `.release-please-manifest.json`
  (`["."]`) and `git tag --list "v*-dev.*"` through `execFileSync`.
- [ ] **Step 4:** `yarn test:tooling` → PASS (21 + the new ones).
- [ ] **Step 5: commit** `ci(release): compute the next dev version and a version's dist-tag`.

### Task A2: workflows and docs

**Files:** Modify `.github/workflows/publish.yml`; create `.github/workflows/release-dev.yml`, `tooling/publish-packages.mjs`,
`tooling/publish-packages.test.mjs`; modify `README.md`.

> **Amended (1, 2).** The steps below are the amended ones. The plan had `release-dev.yml` on `workflow_dispatch` (guarded by
> `if: github.ref == 'refs/heads/dev'`), the next version computed in its first job, the GitHub release created with
> `--target "$GITHUB_SHA"`, and the publish loop inline in `publish.yml`.

- [ ] **Step 1: `publish.yml`.**
  - Add `workflow_call: { inputs: { version: { type: string, required: true } } }` next to `release`.
  - Both jobs: `if: github.event_name != 'release' || !github.event.release.prerelease`. A dev prerelease was already
    published by the call.
  - Version: an input of the call, `INPUT_VERSION`; a `release` event takes it from its tag (`${GITHUB_REF_NAME#v}`). A call
    without a version fails: a caller's ref name is no release tag.
  - Publish: `tag="$(node tooling/dev-release.mjs dist-tag "$VERSION")"`, `node tooling/set-version.mjs "$VERSION"`, then
    `node tooling/publish-packages.mjs "$VERSION" "$tag"` (amendment 2).
- [ ] **Step 2: `tooling/publish-packages.mjs`** and its test (`node:test`, `npm` faked): it uploads every public package at
  one version under one dist-tag, skips a package whose version is already on the registry, and for a dist-tag other than
  `latest` fails the run when the publish moved `latest`, restoring the earlier one when there was one (amendment 2).
- [ ] **Step 3: `release-dev.yml`** (amendment 1), in outline:

```yaml
name: Release dev
on:
  push:
    tags: ['v*-dev.*']
concurrency: # one dev release at a time
  group: release-dev
  cancel-in-progress: false
jobs:
  version: # contents: read
    steps:
      - checkout with fetch-depth: 0
      - version="$(node tooling/dev-release.mjs version-of "$GITHUB_REF_NAME")" # refuses anything but vX.Y.Z-dev.N
      - git fetch origin +refs/heads/dev:refs/remotes/origin/dev
      - git merge-base --is-ancestor "$GITHUB_SHA" origin/dev # else fail: only commits on dev ship
      - echo "version=$version" >> "$GITHUB_OUTPUT"
  publish: # contents: read, packages: write
    needs: version
    uses: ./.github/workflows/publish.yml
    with:
      version: ${{ needs.version.outputs.version }}
  release: # contents: write
    # only after every package is up: the prerelease says this dev build is complete
    needs: [version, publish]
    steps:
      - gh release create "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" --verify-tag --prerelease --generate-notes
```

- [ ] **Step 4: README.** A "Dev pre-releases" section: `dev` is a clone of `main` that never merges back; the human runs
  `git fetch --tags && node tooling/dev-release.mjs next`, tags `v` plus the version it prints on a `dev` commit and pushes
  the tag; a failed upload is finished by re-running the failed jobs of that tag's run (skipping the packages already up),
  and a cause that needs a code change gets a new commit and the next `N`; consumers pin the exact `X.Y.0-dev.N`
  (`yarn add -E @wasichai/core@0.4.0-dev.0`); `npm view @wasichai/ui dist-tags`.
- [ ] **Step 5:** `yarn format:check && yarn test:tooling && node tooling/check-release.mjs --pack 0.4.0-dev.0` → green.
- [ ] **Step 6: commit** `ci(release): publish dev pre-releases from the dev branch under the dev dist-tag`.

---

## Part B — `feat/ui-primitivas-compartidas`

Every task adds its strings to both `core/src/i18n/locales/{en,es}/common.json` under `common`, and its Spanish
rendering to `packages/core/src/i18n/sharedPrimitives.test.tsx`. That test is created in B1 and renders the ui primitive
with `renderWithProviders` from `@wasichai/testing`, which is Spanish by default. The ui tests assert behaviour and hooks.
The core test asserts the words.

### Task B1: `Pagination` and `PageSizePagination`

**Files:** Create `packages/ui/src/pagination.tsx`, `packages/ui/src/pagination.test.tsx`,
`packages/core/src/i18n/sharedPrimitives.test.tsx`. Modify `packages/ui/src/index.ts`, both `common.json`.

**Interfaces — Produces:**

```ts
// server paging: the backend's page. arrows only when there is more than one page
export function Pagination(props: { page: number; totalPages: number; totalElements: number; onPage: (page: number) => void })
// client paging with a rows picker: "Filas [10]" · "1 a 10 de 47 registros < >"
export function PageSizePagination(props: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
  onSize: (size: number) => void
  sizes?: readonly number[] // default [5, 10, 25]
})
```

Strings: `common.records_one` / `records_other` (`{{count, number}} registro(s)` / `record(s)`), `common.page` (exists),
`common.rows` (`Filas` / `Rows`), `common.range_one` / `range_other` (`{{from, number}} a {{to, number}} de {{count,
number}} registro(s)` / `{{from, number}}–{{to, number}} of {{count, number}} record(s)`; `count` = total),
`common.previousPage` (`Página anterior` / `Previous page`), `common.nextPage` (`Página siguiente` / `Next page`).
*Amended (3):* the `es` bundle also has `records_many` and `range_many`, worded like `_other`.

- [ ] **Step 1: failing tests.**
  - ui: the root has `data-slot="pagination"` and `data-mode` (`server` / `client`).
  - ui: the arrows call `onPage(page ± 1)` and are disabled at the ends.
  - ui: `Pagination` with `totalPages = 1` has no arrows.
  - ui: `PageSizePagination` with `total = 0` disables both arrows.
  - ui: changing the picker calls `onSize(25)`.
  - core: `1 a 10 de 47 registros`, `1 registro`, `Página 2 de 5`, `Filas`, and the aria labels `Página anterior` /
    `Página siguiente`.
  - core *(amended, 3)*: a million records renders as the plural, not as the raw key `common.records_many`.
- [ ] **Step 2:** `yarn workspace @wasichai/ui test` and `yarn workspace @wasichai/core test` → FAIL.
- [ ] **Step 3: implement.**
  - Markup is srtm-ui's `src/portal/components/Pagination.tsx` and `Paginador.tsx`, verbatim, including the `secondary`
    arrows (server) and the `ghost` arrows (client).
  - The page-size `<select>` carries `data-slot="native-select"` and Input's classes
    (`h-8 w-20 rounded-md border border-border bg-surface px-3 text-sm text-ink`).
  - Numbers come only through `t()`. Export both from `index.ts`.
- [ ] **Step 4:** both workspaces → PASS; `yarn lint`.
- [ ] **Step 5: commit** `feat(ui): Pagination and PageSizePagination`.

### Task B2: `ConfirmDialog`

**Files:** Create `packages/ui/src/confirm-dialog.tsx`, `packages/ui/src/confirm-dialog.test.tsx`. Modify `index.ts`,
`sharedPrimitives.test.tsx`.

**Interfaces — Produces:**

```ts
// a question before something that cannot be undone. mounted open: the caller renders it only while asking
export interface ConfirmDialogProps {
  title: ReactNode
  description: ReactNode
  confirmLabel?: ReactNode // default t('common.delete')
  cancelLabel?: ReactNode // default t('common.cancel')
  variant?: 'danger' | 'primary' // default 'danger'
  busy?: boolean // confirm disabled while the action runs
  error?: ReactNode // under the description, role=alert
  onConfirm: () => void
  onCancel: () => void
}
export function ConfirmDialog(props: ConfirmDialogProps)
```

- [ ] **Step 1: failing tests.**
  - ui: the title, the description and the two buttons.
  - ui: confirm calls `onConfirm` and is disabled while `busy`.
  - ui: cancel, Escape and the dialog's close button each call `onCancel`.
  - ui: `error` renders with `role="alert"`.
  - ui: the content has `data-slot="confirm-dialog"`.
  - core: the default labels are `Eliminar` and `Cancelar`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3: implement.**
  - srtm-ui's `EliminarFicha` markup: `DialogContent className="max-w-md"`, title `text-lg font-semibold`, description
    `mt-2 text-sm text-ink-muted`.
  - The error is `<p role="alert" className="mt-3 text-sm text-danger">`, and the buttons sit in
    `mt-5 flex justify-end gap-2`.
  - `Dialog open onOpenChange={(open) => !open && onCancel()}`.
- [ ] **Step 4:** → PASS. **Step 5: commit** `feat(ui): ConfirmDialog`.

### Task B3: `PdfDialog`

**Files:** Create `packages/ui/src/pdf-dialog.tsx`, `packages/ui/src/pdf-dialog.test.tsx`. Modify `index.ts`, both
`common.json`, `sharedPrimitives.test.tsx`.

**Interfaces — Produces:**

```ts
export interface PdfFile {
  blob: Blob
  filename: string
}
// a generated PDF shown to print or download. its blob url lives while the dialog is open
export interface PdfDialogProps {
  title: string
  // what to load; a new one loads again
  source: string
  load: (source: string) => Promise<PdfFile>
  onClose: () => void
  // who opened it may act on a failure (pick another titular); it is shown all the same
  onError?: (error: unknown) => void
  // a failure's body; default: its message
  renderError?: (error: unknown) => ReactNode
}
export function PdfDialog(props: PdfDialogProps)
```

Strings: `common.print`, `common.download`, `common.generating` (`Generando…`), `common.pdfPreview`,
`common.pdfFailed` (`No se pudo generar el documento`), and the existing `common.close`.

- [ ] **Step 1: failing tests.** Stub `URL.createObjectURL`/`revokeObjectURL` with `vi.fn`.
  - ui: `role="status"` while it loads.
  - ui: then the iframe with `title` and the blob url, Imprimir enabled, and Descargar as a link with `download=filename`.
  - ui: closing revokes the url and calls `onClose`; unmounting also revokes it.
  - ui: a `source` change drops the first, late resolution (the iframe shows the second url only).
  - ui: a rejection shows `renderError(error)` (or the message) in `role="alert"` and calls `onError` once.
  - core: `Generando…`, `Imprimir`, `Descargar`, `Cerrar`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3: implement.** srtm-ui's `src/portal/components/PdfDialog.tsx` with its states kept: loading, ready, error.
  - The `rentas.blob` call becomes `load(source)`, and `RentasError` becomes `unknown`.
  - `data-slot="pdf-dialog"` on the content.
  - *Amended (4):* the iframe has `bg-surface`, not a fixed palette class, over the box's `bg-surface-muted`.
- [ ] **Step 4:** → PASS. **Step 5: commit** `feat(ui): PdfDialog`.

### Task B4: `QueryState` in core

**Files:** Create `packages/core/src/components/query-state/QueryState.tsx`, `QueryState.test.tsx`. Modify
`packages/core/src/index.ts`, both `common.json`.

**Interfaces — Produces:**

```ts
export function LoadingState(props: { label?: string }) // default t('common.loading')
export function EmptyState(props: { title: string; icon?: LucideIcon; children?: ReactNode }) // icon default Inbox
export function ErrorState(props: { error: unknown; onRetry?: () => void })
// loading, error or the data, in that order
export function QueryState<T>(props: { query: UseQueryResult<T>; children: (data: T) => ReactNode })
```

Strings: `common.notFound` (`No se encontró el registro`), `common.forbidden` (`No tienes permiso para ver esto`),
`common.loadFailed` (`No se pudo cargar`), and the existing `common.loading` and `common.retry`.

- [ ] **Step 1: failing tests** (`renderWithProviders`, a fake `UseQueryResult`).
  - Pending shows `role="status"` with `Cargando…`.
  - A 404 `ApiError` shows `No se encontró el registro` without Reintentar; a 403 behaves the same.
  - A plain `Error` shows `No se pudo cargar`, its message and Reintentar, which calls `refetch`.
  - Success renders `children(data)`.
  - The markup carries `data-slot="query-state"` and, *amended (5)*, `data-state` (`loading`, `empty`, `error`).
- [ ] **Step 2:** → FAIL. **Step 3: implement** srtm-ui's `src/portal/components/QueryState.tsx` with strings via `t()`.
- [ ] **Step 4:** `yarn test` (all workspaces) and `yarn lint` → PASS. **Step 5: commit** `feat(core): QueryState`.

### Task B5: docs

- [ ] **Step 1:** Add the new exports, with one line each, to `packages/ui/README.md` (the primitives list) and to
  wasichai's `docs/modules/core.md` (`QueryState`).
- [ ] **Step 2:** In wasichai's `docs/HISTORY.md`, add an entry dated 2026-09-29: the primitives that went up, why (the
  second user is caja-ui), the `dev` line and its first version.
- [ ] **Step 3: commits.** In wasichai-ui: `docs(ui): the shared primitives`. In wasichai, on branch
  `docs/metadata-ui-f1`: `docs: metadata UI evaluation, phase 1 plans and history`.

## Integration

1. Open PRs A and B against `dev`, and the wasichai docs PR against wasichai's `dev`. CI must be green.
2. **Ask the user** before merging A and B into `dev`.
3. **Ask the user** before pushing the tag `v0.4.0-dev.0` on the `dev` commit that holds A and B (*amended, 1*: it was
   dispatching *Release dev*). The version comes from `git fetch --tags && node tooling/dev-release.mjs next`; the tag is `v`
   plus that.

## Verification

- `yarn install && yarn lint && yarn test && yarn build && yarn test:tooling` green on each branch.
- `node tooling/check-release.mjs --pack 0.4.0-dev.0` prints no problem.
- After the release, `npm view @wasichai/ui dist-tags --registry https://npm.pkg.github.com` shows `dev: 0.4.0-dev.0`
  and `latest: 0.3.1`. The GitHub release `v0.4.0-dev.0` is marked prerelease.
- A tag on a commit that is not on `origin/dev`, or one that is not `vX.Y.Z-dev.N`, fails the *version* job before anything
  builds (amendment 1).
- `git log origin/main..origin/dev` lists only this work, and `origin/main` has not moved.
