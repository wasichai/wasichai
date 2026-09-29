# Pages, forms and components as metadata: evaluation and design

**Status:** approved 2026-09-29 (phase 1 in progress). **Scope:** srtm-ui, wasichai-ui, wasichai; caja-ui as the second
user.

## Question

Can srtm-ui's pages, forms and components be defined as metadata, the way wasichai-ui is conceived, so sister UIs that
use wasichai-ui reuse them, without losing the ability to customise, and staying maintainable and readable? Which pieces
go up to wasichai-ui, which stay in srtm-ui, which are better left alone?

Decisions taken with the user:

- **Who edits:** both. Defaults live in code (the app's spec); a tenant may adjust them on the server.
- **Second concrete user (wasichai-ui rule 6):** caja-ui, rewritten on wasichai-ui the way srtm-ui is.
- **Branches:** every change lives on branches off a `dev` branch (a clone of `main`) in each repository; PRs target `dev`;
  nothing is merged into any `main`. Releases, when needed, are `next-minor-dev.N` pre-releases from `dev` under the npm
  dist-tag `dev` (wasichai-ui `0.4.0-dev.N`, wasichai `0.3.0-dev.N`).

## What there is today

- **Two form engines in one bundle.** srtm-ui's `/admin` is `WasichaiApp`: `DynamicForm` renders server metadata at
  runtime (ADR-003, ADR-011, ADR-012) with no rules. The portal has its own `RecordForm` over `FieldSpec`/`SectionSpec`
  (`src/portal/forms/specs.tsx`, `declaracionSpecs.tsx`): about 240 fields in TypeScript with about 48 functions
  (`when`, `enabledWhen`, `validate`, `suggest`, `render`, the `...RENIEC` mixins).
- **The portal bypasses wasichai's renderers.** `createRegistry([])`; no `PageRenderer`, `DataTable` or views; its data
  is flat DTOs from `/api/srtm/**`, not `RecordItem.attributes`.
- **wasichai's metadata cannot express** column spans, conditional rules, a widget chosen by name, suggestion sources,
  wizards, several forms saved by one button, or pages other than `RECORD_DETAIL`. Its registry
  (`packages/core/src/registry/contract.ts`) picks a renderer by field *type* only and core types cannot be claimed.
- **Much of the portal is already local metadata:** the specs, `Columna<T>` in `HijosPanel` (8 uses), `NAV_TREE`, the
  `*_TABS` constants.
- **Prior art in the family:** kamayuk-lib's `Pantalla` + `DefinicionDePantalla` interpreter (caja-web) works with pure
  data because those screens only read (caja ADR-0040). Screens that write, with rules, need more than pure data.

## Verdict

Yes, but neither "everything as metadata" nor "everything from the server". Metadata where it is data (layout, labels,
columns, spans, simple rules); code where it is behaviour (RENIEC, cascades, maps, the DJ's orchestration); and a
**named registry** joining both, so server metadata can *name* registered behaviour without *containing* it.

```
L0 Model (wasichai FieldMeta, server)         type, required, options, base label
      ▼ defaults
L1 App spec (TypeScript in srtm-ui / caja-ui) sections, spans, rules, widgets, behaviour
      ▼ merged by section id and field name (allow-list)
L2 Tenant adjustments (JSON, server)          label, span, order, hide, require
      ▼
Engine (src/kit → @wasichai)  ── named registry: widgets · validators · sources · behaviours
      ▼
React composition (fichas, orchestration, maps) — the full escape hatch
```

**Rules**

1. **Four rungs of flexibility**, each case on the lowest that suffices: (1) a declarative property (`span`,
   `required`, a simple condition); (2) a named reference to something registered (`widget: 'ubigeo'`); (3) a function
   in the spec (code only); (4) its own React component (`render`, or the page written by hand).
2. **The server only uses rungs 1 and 2.** No remote code runs, and a tenant's adjustment cannot bypass a legal rule.
3. **L2 allow-list:** label, span, order, placeholder, hide (only fields not required by L0/L1), require (tighten only).
   An adjustment naming a missing field is skipped and reported, as ADR-012 does with columns.
4. **Stable ids:** sections carry an `id` (today React keys on the title).
5. **A tested boundary:** what is meant to go up lives in srtm-ui's `src/kit/`, guarded by a boundaries test like
   wasichai-ui's `boundaries.test.ts`.
6. **Three reuse tiers:** wasichai-ui (generic), a Peru/public-sector pack (DNI/RUC, INEI ubigeo), the app. Rule 6
   applies at each tier.

**Pros:** one engine for admin, portal and caja-ui; bounded customisation without deploys; specs read as data;
behaviour tested in isolation. **Cons / risks:** layer merging adds indirection ("where does this field come from?";
mitigated by a pure, tested `resolveSpec`); inner-platform effect (mitigated: only what has two users goes up); the
server contract must land in wasichai first (rule 4) and the builders must learn to edit it, the expensive part.

## Per piece

⬆ up to wasichai-ui · ◐ incubate in srtm-ui's `src/kit` (phase 1), up later · ◼ stays in srtm-ui · ✋ leave alone

| Piece | Verdict | Pros | Cons / risks |
|---|---|---|---|
| Form engine: `RecordForm`, `FieldGrid`, spec types, `grupo.ts`, `SuggestInput`, `campoId`, `bloqueo` | ◐ now, ⬆ phase 2 converging with `DynamicForm` | 362 tests; has what wasichai lacks (rules, spans, backend errors under their field, group save); caja-ui needs it | Domain leaks (suggest's queryKey names `tipo_via`/`ubigeo`, years from 1900, Spanish strings, `etiqueta()`, AUTO/padrón); two engines in wasichai unless converged; flat string values vs `attributes` + sections needs an adapter |
| Domain specs (`CONTRIBUYENTE_SECTIONS`, `DJ_*`, `LOTE_SECTIONS`…) | ◼ | Typed, refactorable, tested; regulatory screens | Labels and required flags duplicate `model.json`; server-edited without an allow-list, an admin could break a legal DJ |
| Field blocks: document/person, address + streets, ubigeo | ◼ fragments now; ⬆ to the Peru pack when caja-ui uses them | Removes the ubigeo block ×4, `PERENE` ×5, `nombres` ×4 | Copies differ (RUC → razón social, spans, required); a fragment with many flags is worse than repetition. `direccion.ts` must stay in step with `Reglas.kt` |
| `HijosPanel` + `Columna<T>` | ◐ decoupled; ⬆ phase 2 as an editable `RELATED_LIST` variant | 8 uses; a declarative view; caja-ui (order lines, payment means) | Select-row-then-toolbar interaction is SRTM's; 15 props already, prop explosion risk (headless core + default skin) |
| Fichas as a `PageRenderer` tree | ✋ | Would get a visual builder | `PageRenderer` is per object and "the first FORM saves"; the DJ saves 3 forms of 2 records; step gating; nearly every node custom = indirection without value. Instead: `FichaTabs` improvements (lazy mount, `?tab=`) into `@wasichai/ui` `Tabs` in phase 2 |
| Wizards | ✋ orchestration; ⬆ `PasosGalon`/`BarraInstruccion` via wasichai-ui#14 | — | A wizard DSL needs guards, effects, several records: a client-side workflow language |
| DJ orchestration (`guardar`, `sobre`, `useComun`) | ✋ | — | Read-merge-write ×4 risks lost updates; fixed at the source with PATCH in wasichai's API (phase 2, backend first) |
| Top-level lists (`Listas.tsx`, `BuscarPage`) | ◼ | Cheap dedupe | 2 uses (rule of three); `/api/srtm` search, not wasichai views |
| GIS (`LotesMapImpl`, `CatastroMapa`, `UbicarDireccion`, `BuscarPrediosDialog`) | ✋ | Registered by name once the registry exists | The widget stays code |
| Valuation (`CategoriasFields`, `UsoFields`, `ObraCategoriaField`) | ✋ | Registered by name | Regulatory (R.M. 309-2022) |
| RENIEC (`reniec.ts`) | ✋ (integration postponed) | — | If it ever goes up: a behaviour with the lookup injected as a port |
| Emisiones | ✋; only its confirmation goes | — | Domain polling and retention |
| Domain-free primitives: `ConfirmDialog` (4 hand-written), `Paginador`/`Pagination`, `QueryState`/`EmptyState`, `PdfDialog` | ⬆ **phase 1**: confirm, pagination, PDF to `@wasichai/ui`; `QueryState` to `@wasichai/core` (react-query + `ApiError`; ui cannot import core) | Low risk; pagination already has a second user (core's `DataTable`), `PdfDialog` caja-ui's receipts, `ConfirmDialog` its annulments | Keep the `data-slot` hooks themes rely on; strings through i18n (en/es) |
| Flow utilities: `CambiosPendientes`, error message (×9) | ◐ | Less code | `CambiosPendientes` needs the data router (`useBlocker`); no clear second user yet |
| Portal layout: `Alerta`, `PasosGalon`, `BarraInstruccion`, `ArbolNav`, `BandaTitulo` | ⬆ phase 2 (wasichai-ui#14) | caja-ui is the missing second user | `navTree.ts`, `instrucciones.ts`, `tonoDeEstado`, the brand bar stay (ADR-035) |
| Shell: `WorkspaceTabs`, `TabBar`, `Breadcrumbs`, `AppShell`, routes | `WorkspaceTabs` ⬆ with caja-ui (drop SRTM's `kind` union); ◼ the rest | The portal as a `WasichaiModule` would let caja-ui compose the same way | `NAV_TREE` (`tambienEn`, `soloAdmin`) does not fit `navGroups`/`nav`: evaluate in phase 2 |
| Enum labels (`etiquetas.ts`) | ⬆ to the contract: `enumOptions` with labels (backend first) | caja-ui would not hand-write its own map | Touches wasichai's backend |
| Admin (`WasichaiApp`) | ✋ | Already 100% metadata | — |

## Phases

- **Phase 1 (now):** wasichai-ui gains `-dev` pre-releases from `dev` and the domain-free primitives, released as
  `0.4.0-dev.0`; srtm-ui incubates the engine, the editable list and flow utilities in `src/kit` behind a boundaries
  test, adopts `0.4.0-dev.0` and dedupes its field blocks. Plans: `2026-09-29-metadata-ui-f1-wasichai-ui.md`,
  `2026-09-29-metadata-ui-f1-srtm-ui.md`.
- **Phase 2:** an ADR for the form spec contract (section ids, placements with span/label/required/visible/widget, the
  L2 allow-list, `enumOptions` with labels, PATCH); `kit/forms` and `kit/crud` to wasichai-ui with `DynamicForm` as an
  adapter of the same engine; #14's layout and `WorkspaceTabs`; caja-ui starts on them.
- **Phase 3:** L2 tenant adjustments stored per tenant and edited in `@wasichai/forms`' builder; serialisable
  `ConditionData` (`eq`, `in`, `filled`, `not`/`all`/`any`, named predicates); the Peru pack when caja-ui needs it.
