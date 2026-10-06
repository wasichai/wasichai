# Theme components with a second user: `Alert` and `NavTree` go up to the library

**Status:** draft 2026-10-06. **Scope:** wasichai-ui, srtm-ui, caja-ui, wasichai (docs). **Issue:**
[wasichai/wasichai-ui#14](https://github.com/wasichai/wasichai-ui/issues/14), phase C of #12.

## Context

ADR-035 moved into the library what srtm-ui built for its `portal-tributario` theme and had a second user or an explicit
decision: the extension tokens, the `data-slot` hooks and the optional sheet `@wasichai/ui/themes/portal-tributario.css`.
The components the theme draws stayed in srtm-ui until one has a second user (wasichai-ui rule 6). caja-ui, rewritten on
wasichai-ui the way srtm-ui is, copied them from `srtm-ui@a1df33a` with a header "copiado de srtm-ui…: sube a wasichai-ui
en la fase 2 (wasichai-ui#14)". The copies match srtm-ui's but for comments.

| Candidate                                         | srtm-ui (`dev`)                    | caja-ui (`main`)  | Second user?               |
| ------------------------------------------------- | ---------------------------------- | ----------------- | -------------------------- |
| `Alerta`                                          | 21 files, 37 uses                  | 22 files, 25 uses | yes                        |
| `ArbolNav` (with `esGrupo`, `hojaActiva`, types)  | `LateralPortal`                    | `LateralPortal`   | yes                        |
| `useVarianteTema`                                 | shell, fichas, wizards             | `AppShell`        | yes, but ADR-035 keeps it  |
| `BandaTitulo`, `CabeceraBanda`                    | `FichaHeader`, `CabeceraAsistente` | copied, unused    | no                         |
| `PasosGalon`, `BarraInstruccion`                  | the wizards                        | absent            | no                         |

caja-ui never copied srtm-ui's `alerts.css`: under `portal-tributario` its alerts are text in their tone's colour, without
the prototype's box.

Decisions taken with the user:

- **Scope:** the library and the adoption in both apps (their local copies and partials are deleted).
- **Components:** `Alert` and `NavTree` only. `BandaTitulo`, `PasosGalon`, `BarraInstruccion`, `useVarianteTema` and the
  shells per theme stay in the apps; caja-ui deletes its unused copy of `BandaTitulo`. #14 stays open with what is left.
- **Placement:** `Alert` in `@wasichai/ui`, with the primitives. `NavTree` in `@wasichai/core`, which already depends on
  `react-router` (`Link`, `useLocation`, `matchPath`); `@wasichai/ui` stays router-free.
- **Names:** the library speaks English (wasichai-ui's `CLAUDE.md`), so the components, props and hooks are renamed;
  the words move to core's i18n, as the primitives' do.

## `Alert` (`@wasichai/ui`, `src/alert.tsx`)

srtm-ui's `Alerta`, renamed and without words of its own:

```ts
export type AlertTone = 'success' | 'warning' | 'danger' | 'notice'

export interface AlertProps {
  tone: AlertTone
  // bold, at the start: "Atención.", "La sesión caducó."
  title?: ReactNode
  children: ReactNode
  // shows a dismiss button (a check)
  onDismiss?: () => void
  // what the place adds to the classic look: a box, a margin, the alignment
  className?: string
}
```

- `role="alert"` for `danger` (it interrupts); `role="status"` for the rest (announced politely).
- **Light and dark do not change.** The classic look is the text in its tone's colour (`text-sm` plus `text-success`,
  `text-warning`, `text-danger` or `text-notice`), merged with `className` by `cn`. Each place keeps the box or margins
  it passes today.
- **Markup**, as `Alerta`'s: a `<div>` with the role, `data-slot="alert"` and `data-tone`; a `<span data-slot="alert-text">`
  with the bold title, a space and the children; and, with `onDismiss`, a
  `<button type="button" data-slot="alert-dismiss">` with lucide's `Check`.
- **Words:** the button's `aria-label` is `t('common.dismissAlert')`: "Entendido, cerrar el aviso" / "Got it, dismiss
  this notice".

The apps rename as they adopt it:

| `Alerta`   | `Alert`     |
| ---------- | ----------- |
| `exito`    | `success`   |
| `atencion` | `warning`   |
| `error`    | `danger`    |
| `aviso`    | `notice`    |
| `titulo`   | `title`     |
| `onCerrar` | `onDismiss` |

## `NavTree` (`@wasichai/core`, `src/shell/NavTree.tsx` and `src/shell/navTree.ts`)

srtm-ui's `ArbolNav`, with the same markup and behaviour: a light panel headed by the way home and a button that folds
it, a title, groups (buttons with a caret that fold their list) and leaves in the link colour, the current one marked.

```ts
export interface NavTreeLeaf {
  label: string
  to: string
  // route patterns (react-router's) that draw this leaf's page too
  alsoAt?: string[]
  // another app: a plain <a>, loaded in full, never current
  external?: boolean
  // drawn only by a leaf at the root, where a group has its caret
  icon?: LucideIcon
}

export interface NavTreeGroup<L extends NavTreeLeaf = NavTreeLeaf> {
  label: string
  children: NavTreeNode<L>[]
}

export type NavTreeNode<L extends NavTreeLeaf = NavTreeLeaf> = NavTreeGroup<L> | L

export const isNavTreeGroup: <L extends NavTreeLeaf>(node: NavTreeNode<L>) => node is NavTreeGroup<L>
export function navTreeLeaves<L extends NavTreeLeaf>(nodes: NavTreeNode<L>[]): L[]
export function currentNavTreeLeaf<L extends NavTreeLeaf>(nodes: NavTreeNode<L>[], pathname: string): L | undefined

export interface NavTreeProps<L extends NavTreeLeaf = NavTreeLeaf> {
  id?: string
  // the <nav>'s aria-label
  label: string
  // the bold title over the groups: "Mis trámites", "Ventanilla"
  title: string
  nodes: NavTreeNode<L>[]
  // where "Ir al inicio" goes: the library hardcodes no route (wasichai-ui rule 2)
  homeTo: string
  // folded, the panel is hidden; whoever folded it shows a way back
  open: boolean
  // which groups are open, by key (a group's labels from the root, joined by "/"); a group not in it is open
  groups: Record<string, boolean>
  onToggleGroup: (key: string) => void
  onNavigate: () => void
  onFold: () => void
}
```

- **The app's leaves.** An app extends `NavTreeLeaf` with its own fields (caja-ui's `clave`, `seOfreceCon`,
  `conSujeto`; both apps' `soloAdmin`). The generics keep that type, so `currentNavTreeLeaf` returns the app's leaf and
  identity comparisons (caja-ui's breadcrumb trail) still hold.
- **The current leaf** is srtm-ui's latest rule (`dev`, after its infracciones work): the leaf whose route is the path;
  else one whose `alsoAt` matches it; else the one whose route is the longest start of the path. An `external` leaf is
  never current. The exact route goes first because `/infracciones/:id` matches `/infracciones/cuis` too.
- **Markup and hooks**, as `ArbolNav`'s with `data-ui` turned into `data-slot`: `nav-tree` on the `<nav>`,
  `nav-tree-group` on a group's button and on a leaf at the root (drawn like a group), `nav-tree-leaf` on a leaf and
  `nav-tree-caret` on a caret. Leaves are `Link`s with a computed `aria-current="page"`, not `NavLink`s; an external one
  is an `<a>`. The panel's "Ir al inicio" is a `NavLink` to `homeTo` with `end`.
- **Words:** "Ir al inicio" is `t('common.goHome')` ("Go to the home page") and the fold button's `aria-label` is
  `t('common.hideMenu')` ("Ocultar el menú" / "Hide the menu").
- **What stays in the apps:** the tree's content, its filtering (`arbolPara`, roles and permissions), the panel's and the
  groups' state and their `sessionStorage`, `LateralPortal`, the breadcrumb trail (`rastro`), the hamburger that unfolds
  the panel and `AppShell`.

## The theme sheet

Two partials join `packages/ui/src/themes/portal-tributario/`, imported by its `index.css` after `tabs.css`, each inside
the sheet's `@scope ([data-theme='portal-tributario']) to ([data-theme]:not([data-theme='portal-tributario']))`. Light
and dark never change, nor a subtree pinned to another theme. The sheet paints a component of core (`NavTree`) the same
way it paints ui's: through its `data-slot`.

**`alerts.css`**, srtm-ui's values on `[data-slot='alert']`:

| Rule                          | Value                                                                                          |
| ----------------------------- | ---------------------------------------------------------------------------------------------- |
| the box                       | flex, `align-items: flex-start`, `gap: 12px`, `padding: 14px 18px`, 1px border, radius 3px, 14.5px, `line-height: 1.6` |
| `[data-tone='success']`       | border `#d6e9c6`, `var(--success-soft)`, `var(--success)`                                       |
| `[data-tone='warning']`       | border `#faebcc`, `var(--warning-soft)`, `var(--warning)`                                       |
| `[data-tone='danger']`        | border `#ebccd1`, `var(--danger-soft)`, `var(--danger)`                                         |
| `[data-tone='notice']`        | border `#e8e0c4`, `var(--notice-soft)`, `var(--notice)`                                         |
| `[data-slot='alert-text']`    | `flex: 1; min-width: 0`                                                                        |
| `[data-slot='alert-dismiss']` | `margin-left: 0; padding: 3px` (top right)                                                     |

The borders are Bootstrap 3's and have no token. The text on its soft background passes AA in every theme
(`theme.test.ts` already measures the four pairs).

**`nav.css`**, srtm-ui's values on `[data-slot='nav-tree']`, the current leaf after the hover so it keeps its background
under the pointer:

| Rule                                            | Value                            | Contrast                    |
| ----------------------------------------------- | -------------------------------- | --------------------------- |
| `[data-slot='nav-tree-caret']`                  | `#555555`                        | 6.66:1 on `table-head`      |
| `[data-slot='nav-tree-group']:hover`            | text `#0d4d80`                   | 7.85:1 on `table-head`      |
| `[data-slot='nav-tree-leaf']:hover`             | background `#e9e9e9`             | `link` on it: 4.70:1        |
| `[data-slot='nav-tree-leaf'][aria-current='page']` | text `#0d4d80`, background `#e6e6e6` | 7.04:1                  |

## Strings

Three keys join core's `common` bundle, in `es` and `en`: `common.dismissAlert`, `common.goHome` and `common.hideMenu`.
`Alert` reads its key from core's bundle like the other primitives (`@wasichai/ui` imports nothing from core); `NavTree`
lives in core. An app that does not load core's bundle supplies the keys itself, as the ui README says.

## Tests

- **ui** `alert.test.tsx`: the role per tone, the tone's class, the hooks, `className` merged, no button without
  `onDismiss`, the button with its label calling it.
- **ui** `portalTributario.test.ts`: both partials imported by `index.css`, every rule inside the `@scope`, no `data-ui`
  left in the sheet, and the contrast of `nav.css`'s own colours (the four ratios above).
- **core** `navTree.test.ts`: `isNavTreeGroup`, `navTreeLeaves`, and `currentNavTreeLeaf` (exact route over `alsoAt`,
  `alsoAt` over a prefix, the longest prefix, an external leaf never current, a page with no leaf).
- **core** `NavTree.test.tsx`, from srtm-ui's `arbolNav.test.tsx` where it tests the component: groups fold with
  `aria-expanded` and `aria-controls`, the current leaf has `aria-current` and its chevron, an external leaf is an `<a>`
  and a root leaf shows its icon, `open={false}` hides the panel, the fold button calls `onFold`, a pick calls
  `onNavigate` (an external one does not), "Ir al inicio" goes to `homeTo`, and the hooks.
- **core** bundles: the three keys exist in `es` and `en`.

## Docs

- `packages/ui/README.md`: `Alert` among the primitives, its hooks in the `data-slot` table, and what the sheet paints
  for alerts and the tree. `packages/core/README.md`: `NavTree` and its helpers, with the `nav-tree*` hooks.
- wasichai `docs/adr/0035-…`: a dated note under "A theme with its own layout stays in the app" (like the 2026-09-28 one
  under Consequences): with caja-ui as second user, `Alert` went up to `@wasichai/ui` and `NavTree` to `@wasichai/core`,
  their partials to the sheet on `data-slot`; the rest stays in the apps. The hooks table gains `Alert` and `NavTree`. No
  new ADR: this applies the rule ADR-035 set.
- wasichai `docs/HISTORY.md`: an entry in the format of 2026-09-29's, linking this spec and the plans.
- wasichai `docs/modules/core.md`: `Alert` among ui's primitives and `NavTree` among core's exports.

## Release and adoption

- **wasichai-ui:** a branch off `dev`, PR to `dev`. Merged, a `v0.5.0-dev.0` tag on `dev` publishes the pre-release
  (pushing the tag publishes, so it is asked first). `dev` into `main` and the 0.5.0 release are out of scope.
- **Before the pre-release** each app is checked against wasichai-ui's `dist` copied into its `node_modules`, never
  committed.
- **srtm-ui:** a branch off `dev`, PR to `dev`. It moves every `@wasichai/*` to `0.5.0-dev.0`, then:
  - deletes `components/Alerta.tsx` and its test, `shell/ArbolNav.tsx`, `themes/portal-tributario/alerts.css` and
    `nav.css` with their `@import`s;
  - renames every `Alerta` to `Alert` (table above), `KitDelPortal`'s `renderAlert` included;
  - `navTree.ts`: `HojaNav extends NavTreeLeaf` with `soloAdmin`, and a group is a `NavTreeGroup<HojaNav>` (`soloAdmin`
    stays on leaves: no group uses it in either app, only the Administración leaf); `NAV_TREE` renames
    `hijos`, `tambienEn`, `externa` and `icono` to `children`, `alsoAt`, `external` and `icon`; `esGrupo`, `hojas` and
    `hojaActiva` give way to core's; `LateralPortal` draws `NavTree` with `homeTo="/"`;
  - tests: `arbolNav.test.tsx` keeps what is srtm's (the content of `NAV_TREE`, the panel's memory, a narrow screen, the
    classic shell untouched); `parciales.test.tsx` drops the alerts and tree blocks; tests reading `data-ui="alerta"` or
    `data-tono` on an alert read `data-slot="alert"` and `data-tone`;
  - `src/themes/portal-tributario/README.md` and `src/kit/README.md`: the files table, the Alertas and Menú en árbol
    sections, the hooks table and the wasichai-ui section point to the library.
- **caja-ui:** it has no `dev` branch; a branch off `main`, PR to `main`. It moves every `@wasichai/*` to `0.5.0-dev.0`,
  then:
  - deletes `components/Alerta.tsx` and its test, `components/BandaTitulo.tsx` and its test, `shell/ArbolNav.tsx`, and
    `themes/portal-tributario/nav.css` with its `@import`;
  - renames every `Alerta` to `Alert`;
  - `navTree.ts`: its leaf (`clave`, `seOfreceCon`, `conSujeto`, `soloAdmin`) extends `NavTreeLeaf`, a group is a
    `NavTreeGroup<HojaNav>` (as in srtm-ui, `soloAdmin` stays on leaves); `esGrupo`, `hojasDe`
    and `hojaActiva` give way to core's, and `rastro` uses `currentNavTreeLeaf`; `AppShell`, `Breadcrumbs`, `PortalApp`,
    `InicioPage`, `GuardaDeHoja` and `LateralPortal` follow;
  - tests: `parciales.test.tsx` drops the tree block; `kit.test.tsx` and `ThemeMenu.test.tsx` read the `data-slot`s;
  - `README.md`: the list of pieces copied from srtm-ui loses `Alerta`, `ArbolNav` and `BandaTitulo`, and says that under
    `portal-tributario` the alerts now have the prototype's box.
- **The issue:** every PR says `Refs wasichai/wasichai-ui#14`, not `Closes`. Once they merge, a comment on #14 says what
  went up and what is left.

## Behaviour

- **Light and dark:** no change. Same classes, same markup; only the attributes' names change (`data-ui` to `data-slot`,
  `data-tono` to `data-tone`), and nothing outside the two apps reads them.
- **`portal-tributario`, srtm-ui:** no change; the values are the same.
- **`portal-tributario`, caja-ui:** its alerts gain the prototype's box (soft background, border, room). Its tree does
  not change.
- **The current leaf in caja-ui:** it takes srtm-ui's latest rule; caja-ui has no `alsoAt`, so no leaf changes.
- **Public API:** the `data-slot` names `alert`, `alert-text`, `alert-dismiss`, `nav-tree`, `nav-tree-group`,
  `nav-tree-leaf` and `nav-tree-caret` are public from 0.5.0 (ADR-035: renaming one breaks an app's sheet).

## Out of scope

`BandaTitulo`, `CabeceraBanda`, `PasosGalon`, `BarraInstruccion`, `useVarianteTema` and the shells per theme (they wait
for a second user, or a decision, on #14); caja-ui's other copies from srtm-ui (`KitDelPortal`, `TabBar`, the shell,
the login); `dev` into `main` and the 0.5.0 release in wasichai-ui.
