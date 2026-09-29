# Metadata UI, phase 1: the `src/kit` incubator in srtm-ui

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Tasks run in order, each on the
> previous one's commit. Each is test first where it adds behaviour, and behaviour-preserving where it moves code: the
> whole suite stays green after every task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** srtm-ui's reusable form engine, editable list and flow utilities live in `src/kit/`, free of SRTM vocabulary
behind a boundaries test, ready to move to wasichai-ui in phase 2. The portal adopts `@wasichai/*@0.4.0-dev.0`'s shared
primitives and dedupes its field blocks.

**Architecture:** `src/kit` imports only React, react-hook-form, react-query, react-router, lucide and `@wasichai/*`. What
the engine knew of the SRTM becomes injected:

- enum labels and extra field kinds come through `KitProvider`;
- the Spanish strings through `KitTexts` defaults;
- the AUTO placeholders through a placeholder function;
- the suggestion dependencies through `dependsOn`;
- the oldest year through `yearFrom`.

The portal keeps the specs, the widgets and the orchestration.

**Tech Stack:** React 19.3, react-hook-form 7.89, @tanstack/react-query 5, react-router 8 (data router), vitest 5 + jsdom,
@wasichai/* 0.4.0-dev.0.

**Spec:** `docs/superpowers/specs/2026-09-29-metadata-ui-design.md` (wasichai repository)

## Global Constraints

- **Branch:** `refactor/kit-incubadora` off `dev`, in the worktree `../srtm-ui-refactor-kit`. The PR is based on `dev`,
  and nothing is merged into `main`.
- **Green after every task:** `yarn lint && yarn typecheck && yarn test && yarn build`. The baseline on `dev` is 46
  files and 459 tests.
- **Test file names:** vitest includes only `src/**/*.test.tsx`, so every new test is `.test.tsx`.
- **Language:**
  - Kit code, identifiers and comments are in English, caveman style.
  - Portal code keeps its style.
  - Commits are in Spanish Conventional Commits (`refactor(kit): …`), ending with the attribution trailers.
- **Visible text:** what the clerk sees does not change (the integration suites assert it), except where a task says so.
- **Task 5 dependency:** Task 5 needs `@wasichai/*@0.4.0-dev.0` published. Tasks 1–4 do not.

## Review Focus

1. **Stale suggestions:** changing a field listed in `dependsOn` (the `tipo_via`) refetches the list. A field not
   listed does not (Task 3).
2. **Years:** `kind: 'year'` with `yearFrom: 1900` offers this year down to 1900. Without it, it offers 100 years
   (Task 3).
3. **Backend-assigned fields:**
   - A new record shows `(AUTOGENERADO)`.
   - A stored one without the code shows `SIN CÓDIGO (padrón)`, or `SIN FECHA (padrón)` for a date, in the form and in
     the ficha.
   - The predio's fields follow `codigo_predio` (Task 3; `sinCodigo.test.tsx`).
4. **Numbers above 999 in the paginators:** they keep es-PE grouping (`1 a 10 de 12,345 registros`) after the swap to
   the library (Task 5).
5. **The boundary really bites:** a kit file importing `../portal/api` or saying `contribuyente` fails the boundaries
   test (Task 1).

---

### Task 1: the boundary

**Files:** Create `src/kit/boundaries.test.tsx`.

- [ ] **Step 1: write the test.** Model it on wasichai-ui's `packages/gis/src/boundaries.test.ts`.

```ts
const SRC = import.meta.dirname
// what a kit file may import besides another kit file: what wasichai-ui's packages already depend on
const ALLOWED = /^(react|react-dom|react-hook-form|react-router|@tanstack\/react-query|@wasichai\/core|@wasichai\/ui|lucide-react)$/
// the incubated code must not know the app it grows in
const DOMAIN = /contribuyente|predio|declaraci|srtm|rentas|ubigeo|reniec|padr[oó]n|catastro|peren[eé]|\bdj\b/i
```

  The test has two cases:
  - Every import of a non-test source under `src/kit` is either relative and resolves inside `SRC`, or a package matching
    `ALLOWED`.
  - No non-test source matches `DOMAIN`.

  Offenders are listed as `file -> specifier`.
- [ ] **Step 2: prove it bites.**
  - Add `src/kit/probe.ts` with `import '../portal/api'` and `// contribuyente`, run
    `yarn vitest run src/kit/boundaries`, and expect FAIL listing both.
  - Delete the probe; the test must PASS on an empty kit.
- [ ] **Step 3: commit** `test(kit): frontera de la incubadora`.

### Task 2: texts and `KitProvider`

**Files:** Create `src/kit/texts.ts`, `src/kit/KitProvider.tsx`, `src/kit/KitProvider.test.tsx`.

**Interfaces — Produces:**

```ts
// src/kit/texts.ts
export interface KitTexts {
  required: string // 'Este dato es obligatorio'
  integer: string // 'Debe ser un número entero'
  number: string // 'Debe ser un número'
  saving: string // 'Guardando…'
  saveFailed: string // 'No se pudo guardar'
  cancel: string // 'Cancelar'
  select: string // 'SELECCIONAR'
  datePlaceholder: string // 'DD/MM/AAAA'
  yes: string // 'SÍ'
  no: string // 'NO'
  months: readonly string[] // ENERO … DICIEMBRE (SETIEMBRE, as the portal writes it)
}
export const DEFAULT_TEXTS: KitTexts

// src/kit/KitProvider.tsx
export interface KitConfig {
  texts: KitTexts
  // how an enum value reads (accents, SOLTERO(A)); default: as stored
  enumLabel: (field: string, value: string) => string
  // controls for kinds the app adds (Task 3's KindRenderer)
  kinds: Record<string, KindRenderer>
}
export function KitProvider(props: { texts?: Partial<KitTexts>; enumLabel?: KitConfig['enumLabel']; kinds?: KitConfig['kinds']; children: ReactNode })
export const useKit: () => KitConfig // outside a provider: the defaults
```

- [ ] **Step 1: failing test.**
  - `useKit()` without a provider returns `DEFAULT_TEXTS` and an identity `enumLabel`.
  - With `<KitProvider texts={{ select: 'ELEGIR' }} enumLabel={(f, v) => v + '!'}>`, `select` is `ELEGIR`, the other
    texts keep their defaults, and `enumLabel('x', 'A')` is `A!`.
- [ ] **Step 2:** FAIL. **Step 3:** implement with `createContext` + `use`, merging `texts` over the defaults and
  memoising the merge.
- [ ] **Step 4:** PASS. **Step 5: commit** `feat(kit): textos y KitProvider`.

### Task 3: the engine moves to `src/kit/forms`

**Files:**
- Move (`git mv`), then edit:

| From `src/portal/forms/` | To `src/kit/forms/` |
|---|---|
| `RecordForm.tsx` | `RecordForm.tsx` |
| `FieldGrid.tsx` | `FieldGrid.tsx` |
| `grupo.ts` | `group.ts` |
| `campoId.ts` | `fieldId.ts` |
| `bloqueo.ts` | `locked.ts` |
| `styles.ts` | `styles.ts` |
| `SuggestInput.tsx` | `SuggestInput.tsx` |
| `RecordForm.test.tsx` | `RecordForm.test.tsx` |

- Create:
  - `src/kit/forms/spec.ts` with the types and the helpers `dataFields` and `emptyOf`, taken from `specs.tsx`.
  - `src/kit/forms/kinds.tsx` with the control registry.
  - `src/kit/format.ts` with `formatText`, `formatNumber`, `formatMoney`, `formatDate` and `currentYear`, moved from
    `portal/components/format.ts`, which re-exports them and keeps `recentYears`, `today` and `MESES`.
  - `src/portal/forms/auto.ts`.
- Modify: every importer. There are 39 files, the list comes from `grep -rlE "forms/(RecordForm|FieldGrid|specs|grupo|SuggestInput|campoId|bloqueo|styles)'"`,
  and `yarn typecheck` finds any that are missed.

**Interfaces — Produces** (`src/kit/forms/spec.ts`):

```ts
export type FieldKind =
  | 'text' | 'longtext' | 'enum' | 'integer' | 'decimal' | 'money' | 'date' | 'month' | 'year' | 'boolean' | 'multi'
  | 'suggest' | 'hidden' | 'geometry' | 'custom' | (string & {})
export type FormValues = Record<string, string>
export interface PlaceholderContext { values: FormValues; saved: boolean }
export interface SuggestSource {
  fetch: (q: string, values: FormValues) => Promise<string[]>
  // the fields the list depends on: a change refetches
  dependsOn?: readonly string[]
}
export interface FieldSpec {
  // unchanged: name, label, kind, required, when, enabledWhen, greyedValue, lockedWhen, validate, onChange, onBlur,
  // choices, readOnly, span, render, shownInFicha
  // a string is the input's hint. a function is what an empty value means (shown in the form and in the ficha)
  placeholder?: string | ((ctx: PlaceholderContext) => string | undefined)
  suggest?: SuggestSource
  // kind year: the oldest year offered; default 100 years back
  yearFrom?: number
}
export interface SectionSpec {
  // stable: React's key now, tenant adjustments later
  id: string
  title: string
  number?: number
  action?: (form: UseFormReturn<FormValues>) => ReactNode
  fields: FieldSpec[]
}
export function dataFields(sections: SectionSpec[]): FieldSpec[]
export function emptyOf<T>(sections: SectionSpec[], extra?: Partial<T>): T
```

**Other exports:**
- `kinds.tsx`: `KindProps` (`field`, `form`, `values`, `options`, `aria`, `rules`), `KindRenderer =
  (props: KindProps) => ReactNode`, and `CORE_KINDS: Record<string, KindRenderer>` covering `multi`, `enum`, `boolean`,
  `month`, `year`, `longtext`, `suggest` and the default text input.
- `group.ts`: `useFormGroup` (was `useGrupoFormularios`), `useSharedFields` (was `useComun`), `FormLink` (was `Enlace`)
  and `SharedFields` (was `Comun`). `FormHandle` renames `cambios`, `valores` and `errores` to `changes`, `values` and
  `errors`. The hook's return renames `enlaces`, `pendientes`, `cambios`, `valores` and `errores` to `links`, `pending`,
  `changes`, `values` and `errors`.
- `locked.ts`: `LOCKED`, `lockedOf`, `lock`, `unlock` and `lockedIn`.
- `fieldId.ts`: `FieldIdContext` and `useFieldId`.
- `RecordForm` props rename `enlace`, `comun`, `bloqueados` and `nota` to `link`, `shared`, `locked` and `note`.
- The portal: `src/portal/forms/auto.ts` exports `AUTO`, `SIN_CODIGO`, `SIN_FECHA` and
  `auto(opts?: { exists?: (v: FormValues) => boolean; date?: boolean }): (ctx: PlaceholderContext) => string`. It
  returns `AUTO` while the record, or `exists`, is missing, and `SIN_FECHA`/`SIN_CODIGO` after.

- [ ] **Step 1: failing kit tests** in `src/kit/forms/RecordForm.test.tsx`, added to the moved file:
  - A `suggest` field with `dependsOn: ['tipo']` fetches again when `tipo` changes, and not when another field changes.
  - `kind: 'year', yearFrom: 2020` offers the current year down to 2020.
  - A kind registered through `<KitProvider kinds={{ color: … }}>` renders its control.
  - A function placeholder shows its text on an empty read-only field, and `FieldGrid` shows it for an empty value.
  - Sections render with `key` = `id`: two sections with the same title both render.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: move and implement.**
  - `git mv` the files and apply the renames.
  - Replace the `if/else` chain in `Field` with `{ ...CORE_KINDS, ...useKit().kinds }[field.kind ?? 'text'] ??
    CORE_KINDS.text`, keeping `hidden`, `geometry`, `custom`, the greyed branch and the read-only branch where they are.
  - `SuggestInput`'s `queryKey` is `[field.name, ...(field.suggest.dependsOn ?? []).map((n) => values[n])]`.
  - `etiqueta` is replaced by `useKit().enumLabel`, and the Spanish literals by `useKit().texts`.
  - `vacioDe` goes. An empty field's placeholder is `typeof p === 'function' ? p({ values, saved }) : p`.
- [ ] **Step 4: portal edits.**
  - Every spec section gets an `id`: its title in kebab case, e.g. `datos-de-la-declaracion`.
  - `placeholder: AUTO` becomes `placeholder: auto()`, or `auto({ date: true })` for dates. `DEL_PREDIO` becomes
    `auto({ exists: (v) => Boolean(v.codigo_predio) })`.
  - Each `suggest: async (q, v) => …` becomes `suggest: { fetch: async (q, v) => …, dependsOn: [...] }`. The
    dependencies come from the rentas call's arguments: `['tipo_via', 'ubigeo']`, `['tipo_unidad_urbana', 'ubigeo']`,
    `['tipo_zona', 'ubigeo']` or `['tipo_via']`.
  - Both `kind: 'year'` fields get `yearFrom: 1900`.
  - `PortalApp` wraps the router in `<KitProvider enumLabel={etiqueta}>`.
- [ ] **Step 5:** `yarn typecheck && yarn test` → PASS, 459 plus the new tests. Run `yarn lint` and `yarn build`.
- [ ] **Step 6: commit** `refactor(kit): el motor de formularios pasa a src/kit, sin rastros de dominio`.

### Task 4: field blocks in the portal

**Files:** Create `src/portal/forms/bloques.tsx`. Modify `specs.tsx`, `declaracionSpecs.tsx`, `LoteCatastroPage.tsx`,
`BuscarPrediosDialog.tsx`, `ContribuyenteListas.tsx`, `DeclaracionListas.tsx`, `Nuevos.tsx`,
`NuevaDeclaracionPage.tsx` and `Condominos.tsx`.

**Interfaces — Produces:**

```ts
// the cascade and the four fields it writes, as every address of the srtm asks them
export function ubigeoCampos(): FieldSpec[] // ubigeo_cascada (custom, span 6) + ubigeo, departamento, provincia, distrito (hidden)
// the municipality's own district: what a new address starts on
export const PERENE_UBIGEO = { ubigeo: '120302', departamento: 'JUNIN', provincia: 'CHANCHAMAYO', distrito: 'PERENE' } as const
export const PERENE_PREDIO = { ...PERENE_UBIGEO, region: 'SELVA' } as const
export const nombres: (items: { nombre: string | null }[]) => string[]
export const describirContribuyente: (c: Contribuyente) => Picked // "{documento ?? 's/d'} · {nombre_completo}"
```

- [ ] **Step 1: failing test** `src/portal/forms/bloques.test.tsx`:
  - `ubigeoCampos()` has the 5 names and kinds, with `departamento`, `provincia` and `distrito` required.
  - `describirContribuyente({ numero_documento: null, nombre_completo: 'ANA' })` returns the label `s/d · ANA`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: implement and replace** the 4 ubigeo blocks, the 5 `PERENE` literals, the 4 `nombres` copies and the 2
  `describir*` copies.
  - `Nuevos.tsx` and `NuevaDeclaracionPage` use `PERENE_PREDIO`. The other literals, which lack a `region`, use
    `PERENE_UBIGEO`.
  - `geo.ts`'s `PERENE` is map coordinates and stays.
- [ ] **Step 4:** the suite passes. **Step 5: commit** `refactor(portal): bloques de campos compartidos por las specs`.

**Deliberately out:** a `personaCampos()` fragment. The three document and person copies differ in spans, in which names
are required, and in the contribuyente's `enabledWhen`/`greyedValue`. A fragment with four flags would read worse than
the repetition. This refines the approved plan.

### Task 5: adopt `@wasichai/*@0.4.0-dev.0`

**Files:**
- Modify `package.json` (the 8 dependencies and `@wasichai/testing`, all `-E 0.4.0-dev.0`) and `yarn.lock`.
- Primitive swaps: `HijosPanel.tsx`, `EliminarFicha.tsx`, `EmisionesPage.tsx`, `Listas.tsx`, `BuscarPrediosDialog.tsx`,
  `VerPdf.tsx`, and the 17 importers of `components/QueryState`.
- Notices: `ContribuyenteListas.tsx` and `TitularesDelPredio.tsx`.
- Theme: `src/themes/portal-tributario/tables.css`, `src/themes/tablas.test.tsx` and `src/portal/tablasTema.test.tsx`.
- Create `src/kit/ui/errorMessage.ts`, `src/kit/ui/UnsavedChanges.tsx` (from `components/CambiosPendientes.tsx`),
  `src/portal/i18n.ts` and `src/kit/ui/errorMessage.test.tsx`.
- Delete `components/Paginador.tsx`, `components/Pagination.tsx`, `components/QueryState.tsx` and
  `components/CambiosPendientes.tsx`. `components/PdfDialog.tsx` shrinks to an adapter.

**Interfaces — Produces:**

```ts
// src/kit/ui/errorMessage.ts: what a failure says, or the fallback when it says nothing usable
export function errorMessage(error: unknown, fallback: string): string
// src/kit/ui/UnsavedChanges.tsx (useSalidaConCambios / ConfirmarDescarte, renamed; strings from KitTexts:
// leaveTitle '¿Salir sin guardar?', leaveConsequence 'Si sales, se pierden.', leaveConfirm 'Salir sin guardar',
// keepEditing 'Seguir editando', pendingIn (list) => `Hay cambios sin guardar en ${list}.`)
export function useUnsavedChanges(pending: string[]): { dialog: ReactNode; allow: () => void }
export function ConfirmDiscard(props: {
  title: string
  pending: string[]
  consequence: string
  confirmLabel: string
  onConfirm: () => void
  onKeep: () => void
})
// src/portal/i18n.ts: the srtm's wording and number format over the library's
export function ajustarI18n(i18n: I18n): void
```

- [ ] **Step 1: failing tests.**
  - `errorMessage(new Error('x'), 'y')` returns `x`; `errorMessage('boom', 'y')` returns `y`;
    `errorMessage(new Error(''), 'y')` returns `y`.
  - In `tablasTema.test.tsx`, the paginators are found by `[data-slot="pagination"]`.
  - A new case in `PortalApp.test.tsx`: a list of 12,345 contribuyentes reads `12,345 registros`.
  - `listasSrtm`'s `1 a 1 de 1 registros` still holds.
- [ ] **Step 2:** bump the packages, run `yarn install`, and watch FAIL.
- [ ] **Step 3: swap.**
  - `Paginador` → `PageSizePagination`; `Pagination` → the library's.
  - `QueryState`, `EmptyState`, `LoadingState` and `ErrorState` come from `@wasichai/core`.
  - `ConfirmDialog` replaces the hand-written dialogs in `HijosPanel` (delete), `EliminarFicha`, `EliminarEmision` and
    `ConfirmDiscard`. The texts stay the same.
  - `components/PdfDialog.tsx` renders the library's `PdfDialog` with `load={(p) => rentas.blob(p)}`, a `renderError`
    that lists `RentasError.faltan`, and an `onError` that narrows to `RentasError`.
  - `errorMessage` replaces the 7 copies.
  - `ajustarI18n` sets `common.range_one` to `… registros`, as the srtm writes it, and swaps the `number` formatter for
    es-PE through `i18n.services.formatter?.add('number', …)`. `PortalApp` calls it right after `createWasichaiI18n`.
  - `tables.css` selects `[data-slot='pagination']`.
  - The two red notices written by hand become `<Alerta tono="error" className="rounded-md border border-danger/40
    bg-danger/10 px-4 py-3">`. `AnularDeclaracion`'s notice keeps `role="note"` and is left alone.
- [ ] **Step 4:** the suite passes; `yarn build`.
- [ ] **Step 5: commit** `refactor(portal): primitivas compartidas de @wasichai 0.4.0-dev.0`.

### Task 6: the editable list

**Files:** Create `src/kit/crud/EditableList.tsx` and `src/kit/crud/EditableList.test.tsx`. Modify
`src/portal/pages/HijosPanel.tsx`, which becomes the srtm's wrapper so its 8 uses do not change.

**Interfaces — Produces:**

```ts
export interface Column<T> { label: string; render: (row: T) => ReactNode; className?: string; numeric?: boolean }
export interface EditableListProps<T extends { id?: string }> {
  queryKey: readonly unknown[]
  load: () => Promise<T[]>
  save: (editing: T | null, values: T) => Promise<unknown>
  remove: (row: T) => Promise<unknown>
  // after a save or a removal: what else must read again
  onChanged?: () => Promise<unknown> | void
  plural: string
  singular: string
  sections: SectionSpec[] | ((rows: T[], editing: T | null) => SectionSpec[])
  options?: Record<string, string[]>
  columns: Column<T>[]
  // a last column (the estado's badge); none = no column
  status?: { label: string; render: (row: T) => ReactNode }
  newRow: (rows: T[]) => T
  notice?: (rows: T[]) => ReactNode
  fixed?: (row: T, rows: T[]) => string | null
  footer?: (values: FormValues, form: UseFormReturn<FormValues>) => ReactNode
  wide?: boolean
  readOnly?: boolean
}
```

KitTexts gains `listOf(plural)`, `add(singular)`, `edit(singular)`, `remove(singular)`, `newOne(singular)`,
`editOne(singular)`, `noResults`, `removeTitle(singular)`, `removeBody`, `removeFailed`, `save` (`Grabar`) and `status`
(`Estado`). The Spanish is exactly `HijosPanel`'s current text.

- [ ] **Step 1: failing tests** (no portal imports):
  - The rows render with the arrow keys moving the selection and Enter editing.
  - `+` opens a new form seeded by `newRow`; saving calls `save(null, values)` and then `onChanged`.
  - `fixed` disables the bin with its reason as the title.
  - A failed `remove` shows `errorMessage` in the confirmation.
  - `status` absent means no Estado column.
  - `readOnly` hides the toolbar.
- [ ] **Step 2:** FAIL. **Step 3:** move `HijosPanel`'s body.
  - `HijosPanel` maps `api` to `load`/`save`/`remove`, `nuevo` to `newRow`, `catalog` to `options` (via `useCatalogos`), and `useRefresh` to
    `onChanged`.
  - `sinEstado` means no `status`; otherwise `status` renders `EstadoBadge`.
- [ ] **Step 4:** the suite passes, including `listasSrtm` and `condominos`. **Step 5: commit**
  `refactor(kit): lista editable genérica; HijosPanel queda como envoltorio`.

### Task 7: the incubator's README

- [ ] **Step 1:** Write `src/kit/README.md` in the style of `src/themes/portal-tributario/README.md`. It covers:
  - What the kit is.
  - Its rules: the boundary, English, no domain, what enters and what goes up.
  - The per-piece table from the spec, trimmed to srtm-ui's pieces.
  - What goes up in phase 2 and where.
  - Links to the spec and the plans in wasichai.
- [ ] **Step 2:** run `yarn lint`, then commit `docs(kit): reglas de la incubadora y qué sube a wasichai-ui`.

## Integration

1. **Branch hygiene:** after Task 7, `git log dev..HEAD` shows only this work.
2. **Code review:** run `superpowers:requesting-code-review`.
3. **PR:** open it against `dev` with `gh pr create --base dev`. Title: `refactor(kit): incubadora de piezas
   reutilizables y primitivas de @wasichai 0.4.0-dev.0`.
4. **Merge:** wait for green CI; nothing is merged without asking.

## Verification

- `yarn lint && yarn typecheck && yarn test && yarn build` are green, with 459 tests plus the new ones.
- The Task 1 probe fails when present and passes once removed.
- `grep -rE "CatalogKey|useCatalogos|rentas|etiqueta\(" src/kit` finds nothing.
- If srtm-backend is up, a smoke run with `yarn dev` and Playwright covers:
  - the contribuyente's ficha: edit its data, add a domicilio, edit it and delete it;
  - the DJ wizard, from start to Terminar;
  - an emisión, deleted;
  - a PU shown and printed.
