# srtm-ui's portal theme moves into the library, as tokens, hooks and an optional sheet

**Status**: design accepted · 2026-09-27 · leads to ADR-035

## Context

[srtm-ui](https://github.com/wasichai/srtm-ui), a municipal revenue app on the `@wasichai/*` packages, built a
theme `portal-tributario` on ADR-034: the prototype of an online municipal tax portal, in Arial at 14px with 3px
corners, a steel-blue brand, a blue brand bar, zebra tables, folder tabs and Bootstrap-3-style alerts. It was
accepted there ([wasichai/srtm-ui#44](https://github.com/wasichai/srtm-ui/issues/44), merged in
[wasichai/srtm-ui#57](https://github.com/wasichai/srtm-ui/pull/57)) without forking `@wasichai/ui`, in three layers:

1. **Extension tokens** (`src/themes/extensions.css`): ten colors the 18 tokens of `theme.css` lack, declared on
   `:root` with `color-mix(in oklab, …)` from the active theme's base tokens, mapped in `@theme inline`, plus
   `@theme { --radius: 0.25rem }` so the bare `rounded` follows a theme.
2. **Component partials** (`src/themes/portal-tributario/*.css`): the prototype's exact shapes, hooked on `data-ui`
   attributes that srtm-ui's wrappers (`controles.tsx`) and its own components set. Unlayered, and scoped under
   `[data-theme='portal-tributario']`.
3. **Portal structure**: a brand bar, a tree menu, chevron steps and a title band, drawn only when
   `useVarianteTema()` reads `'portal'`.

Two limits show. The `:root` tints resolve once there and are inherited, so the document sheet that
`PrintableDocumentPage` pins to `data-theme="light"` would keep a dark page's tints. And the wrappers exist only
because the primitives set no attribute a stylesheet can hold on to.
[wasichai/wasichai-ui#12](https://github.com/wasichai/wasichai-ui/issues/12) moves into the library what has a second
user, or what its owner decided to publish.

## Goal

- Every theme, light and dark included, has the ten extension tokens, with values that nest.
- A theme can change the plain radius and the font.
- The primitives carry stable `data-slot` hooks, so a theme's CSS needs no wrapper.
- An app offers `portal-tributario` with one CSS import and one config entry; an app that does not ask for it gets
  nothing new.
- Light and dark look as before, except `bg-danger-soft`, which `@wasichai/documents` already used and which
  generated nothing.

## Design

### 1. Extension tokens (`@wasichai/ui/theme.css`)

The ten tokens go in `:root, [data-theme='light']` and in `[data-theme='dark']`, and each in `@theme inline`. The rule
of ADR-034 ("a theme sets every token") now covers all 28.

| Token | Purpose | Light | Dark |
|---|---|---|---|
| `success-soft` | success alert background | `oklch(96% 0.01 165)` | `oklch(26% 0.02 160)` |
| `danger-soft` | error alert or message background | `oklch(96% 0.015 20)` | `oklch(26% 0.025 25)` |
| `notice` | text of a fourth, neutral alert tone | `oklch(42% 0.08 63)` | `oklch(84% 0.09 80)` |
| `notice-soft` | background of that tone | `oklch(97% 0.025 85)` | `oklch(28.5% 0.03 72)` |
| `link` | text links, radio and checkbox accent | `var(--brand)` | `var(--brand)` |
| `focus` | focus ring, focused field border | `var(--brand)` | `var(--brand)` |
| `table-head` | table header row | `var(--surface-muted)` | `var(--surface-muted)` |
| `table-stripe` | even rows of a zebra table | `oklch(98% 0.003 260)` | `oklch(20% 0.014 265)` |
| `line` | thin rules | `var(--border)` | `var(--border)` |
| `map-selected` | a selected feature on a map | `#e8590c` | `#e8590c` |

- **Fixed values, not `color-mix()`.** The tints are literal `oklch()` taken from srtm-ui's oklab derivation
  (`success`/`danger` at 8% over `surface`, `warning` at 70% with `ink`, `warning-soft` and `surface-muted` at 50%
  over `surface`), the dark hues matched to their source color. The table holds the starting values; the contrast
  tests have the last word. Written in each theme block, a value nests with its theme, and the tests read it from
  the file.
- **Aliases keep light and dark unchanged.** `link` and `focus` are `brand`, `table-head` is `surface-muted`, `line`
  is `border`; `map-selected` is the orange srtm-ui's lots map already uses for a selection.

### 2. Radius and font

`@theme { --radius: 0.25rem; }` joins `--radius-card` (a theme variable, not inline): the same value, but `rounded`
now compiles to `var(--radius)`. Besides the tokens, a theme may set `--font-sans`, `--radius`, `--radius-sm`..`xl`
and `--radius-card`, and the docs say so. `font-sans` and the document's font already read `--font-sans`.

### 3. `data-slot` hooks (`@wasichai/ui`)

Fixed names, as shadcn has them, set before `{...props}` so a caller can override one (srtm-ui passes
`data-variant="round"`). Classes and visible markup do not change, and light and dark do not style the hooks.

| Component | Hooks |
|---|---|
| `Button` | `data-slot="button"`, `data-variant` (default `primary`), `data-size` (default `md`); on the child with `asChild` |
| `Card` | `data-slot="card"`: the box whose tabs step aside under the theme |
| `Input`, `Textarea` | `data-slot="input"`, `data-slot="textarea"` |
| `SelectTrigger` | `data-slot="select-trigger"`: only the trigger, the one part the theme paints |
| `Table`, `Th`, `Td` | `data-slot="table"` on the `<table>`, `"table-head"`, `"table-cell"` |
| `Badge` | `data-slot="badge"` |
| `Tabs` | `data-slot="tabs"` (root), `"tabs-list"` (the tablist), `"tabs-trigger"` (each tab), `"tabs-content"` (each panel) |

### 4. The optional sheet, and what stays in srtm-ui

`packages/ui/src/themes/portal-tributario/` holds `index.css`, which imports `tokens.css` and then the partials
`controls.css`, `tables.css` and `tabs.css`. The build copies the folder to `dist/themes/`, and `package.json` exports
it as `@wasichai/ui/themes/portal-tributario.css`.

- `tokens.css` is srtm-ui's: the `[data-theme='portal-tributario']` block with all 28 tokens, `--font-sans` (Arial,
  Helvetica), the radii at 3px, and, in `@layer base` like the defaults they replace, the 14px body and the focus
  ring in `focus`.
- The partials keep only the rules for the library's own components, moved from `data-ui` to `data-slot`: `button`
  to `button`, `input`/`textarea`/`select` to `input`/`textarea`/`select-trigger`, `table` to `table`, and
  `ficha-tabs`/`ficha-tab`/`ficha-panel` to `tabs`/`tabs-list`/`tabs-trigger`/`tabs-content`, and the box that holds
  the tabs to a `Card` (`data-slot="card"`). Each partial sits in
  `@scope ([data-theme='portal-tributario']) to ([data-theme]:not([data-theme='portal-tributario']))`, so light, dark
  and a subtree pinned to another theme never change. The sheet has no `data-ui`.
- The partials set as little as they can, because an unlayered rule beats a caller's classes as surely as the
  primitive's own and, with `data-slot` on every primitive, reaches every screen of the admin: no rule repeats a fill
  or a radius the tokens already give the classes, `ghost` keeps the classes' colors (the shell's sign-out on the blue
  shell), fields keep their sides (a search box's room for its icon), and the zebra and total rows go in `@layer base`
  so a selected or hovered row keeps its class. Radios, checkboxes and links in cells stay in srtm-ui: they are no
  library hook.
- Stay in srtm-ui, because they paint components that exist only there: `alerts.css`, `nav.css`, `pasos.css`,
  `banda.css` and `shell.css`; `ficha-kv`, `ficha-seccion` and `paginador` from `tables.css`; `record-*` and
  `workspace-*` from `tabs.css`; the round icon button and `NativeSelect` from `controls.css`; `data-numeric`. So do
  `useVarianteTema()` and the portal structure, `navTree.ts`, `instrucciones.ts`, `tonoDeEstado` and the brand.

### 5. Core: the `ThemeDefinition`

`packages/core/src/theme/themes.ts` exports
`PORTAL_TRIBUTARIO_THEME = { id: 'portal-tributario', label: 'theme.portalTributario', colorScheme: 'light' }`, and
`index.ts` re-exports it. The label is in core's `common.json` ("Portal tributario" in es, "Tax portal" in en). It is
never in `BUILT_IN_THEMES`: an app lists it in `config.themes` next to the sheet's import, and `resolveConfig` and
`resolveTheme` handle it like any app theme.

### 6. Docs

- wasichai: ADR-035 (amends ADR-034, which gains "amended by" in its status line), the ADR index, a `HISTORY.md`
  entry, and the Themes paragraph of `docs/modules/core.md` and `docs/guides/build-your-app.md`: 28 tokens, the
  `data-slot` hooks, the optional sheet, and the boot script with its id → color-scheme map.
- wasichai-ui: `packages/ui/README.md` (extension tokens, the `data-slot` table, font and radii per theme, how an app
  registers the sheet, what it paints) and `packages/core/README.md` (registering the theme, the boot script).

The boot script lists every theme the app offers in `schemes` (id → color scheme). `Object.hasOwn(schemes, stored)`
accepts only those; `system` and an unknown id follow the operating system, as `resolveTheme` does, and
`colorScheme` is set for an app's theme too.

## Testing

- **Helpers** (`packages/ui/src/test/css.ts`, ported from srtm-ui's `src/themes/css.ts`): read a CSS rule, resolve a
  `var()` alias, and measure a WCAG contrast from hex or `oklch(L% C H)` (OKLab to linear sRGB, clipped to the
  gamut). `css.test.ts` covers srtm-ui's WCAG cases and a known `oklch` pair.
- **Tokens** (`theme.test.ts`): the exact extension list in light and in dark; the aliases in both blocks;
  `map-selected` is `#e8590c`; `success`, `danger`, `warning` and `notice` pass 4.5:1 on their soft background in
  light and in dark; `link` passes 4.5:1 and `focus` 3:1 on `surface`.
- **The `success` exception.** Light `success` on `success-soft` is about 3.6:1. Light `success` is already 3.9:1 on
  `surface` and 4.0:1 on white, so raising it means darkening `success` and every success text in light with it. The
  test asserts the pair still fails, so it flags the day a follow-up fixes `success` and the exception can go.
- **Tailwind** (`theme.tailwind.test.ts`, with `tailwindcss`'s `compile`): `rounded` gives `var(--radius)` with
  `--radius: 0.25rem`, `bg-danger-soft` gives `var(--danger-soft)`, `font-sans` gives `var(--font-sans)`.
- **Hooks** (`button`, `input`, `select`, `table` and `tabs` tests): each slot; `data-variant` and `data-size`
  default and explicit; `asChild`; a caller's `data-variant` wins; classes unchanged.
- **Core** (`themes.test.ts`): not in `BUILT_IN_THEMES`; `resolveConfig` and `resolveTheme` accept it; the label
  exists in es and en; the sheet's `tokens.css` uses `[data-theme='portal-tributario']`.
- **Sheet** (`portalTributario.test.ts`, ported from srtm-ui's token and partial tests): every light token, base and
  extension, is set; Arial, 3px and 14px; srtm-ui's AA pairs and the focus ring at 3:1; each partial imported after
  `tokens.css`, unlayered and under the theme; the exact list of partials; key values (invalid field, disabled
  control, active tab joined to its panel, zebra skipping `aria-selected` rows); no `data-ui`.
- **By hand**: the CSS compiled from `packages/*/src` on main and on the branch differs only by the new variables,
  `--radius`, `rounded` → `var(--radius)` and new utilities such as `bg-danger-soft`; srtm-ui builds and tests green
  against packed tarballs, unchanged, and imports the sheet into a build whose CSS has the `[data-slot=…]` rules
  under the theme.

## Not in scope

- **Components (phase C).** `Alerta`, `PasosGalon`, `BarraInstruccion`, `ArbolNav` and `BandaTitulo`, stripped of
  anything SRTM, are candidates for the library once a second app needs them; a separate issue.
- **Shells per theme.** A theme that changes layout (`useVarianteTema`) stays in the app. The library ships tokens
  and CSS, not a shell or a component per theme.
- Darkening light `success` (a follow-up issue), and a dark variant of `portal-tributario` (the prototype is light).

## Delivery

1. **wasichai-ui:** one pull request closing #12, a commit per part; released by release-please.
2. **wasichai:** these docs, on their own pull request.
3. **srtm-ui:** an adoption issue under its epic #44: delete `extensions.css` and the partials that moved, rename
   `data-ui` to `data-slot` where a slot exists, retire `controles.tsx` except the round icon button and
   `NativeSelect`, import the sheet and register `PORTAL_TRIBUTARIO_THEME`.
