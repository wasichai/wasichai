# ADR-035: Themes get extension tokens and data-slot hooks, and the library ships themes as optional sheets

**Status**: accepted · 2026-09-27 · amends [ADR-034](0034-user-preferences-and-themes.md)

## Context

[srtm-ui](https://github.com/wasichai/srtm-ui), a municipal revenue app built on the `@wasichai/*` packages, built a
theme `portal-tributario`: the prototype of an online municipal tax portal, in Arial at 14px with 3px corners, a
steel-blue brand, a blue brand bar, zebra tables, folder tabs and Bootstrap-3-style alerts. It did so on ADR-034
without forking `@wasichai/ui`, and the theme was accepted there
([wasichai/srtm-ui#44](https://github.com/wasichai/srtm-ui/issues/44), merged in
[wasichai/srtm-ui#57](https://github.com/wasichai/srtm-ui/pull/57)). Three things the library lacked had to be built
around it:

- **Tokens.** The 18 tokens of `theme.css` have no background for a success or an error alert, no fourth alert tone,
  no link or focus color apart from `brand`, no table head, stripe or thin-rule color and no color for a selection
  on a map. srtm-ui declared ten extension tokens of its own on `:root`, derived with `color-mix(in oklab, …)` from
  the active theme's base tokens.
- **Shape and type.** The bare `rounded` compiled to a fixed `0.25rem`, because Tailwind declares `--radius` inline,
  so a theme could not change it. `--font-sans` and `--radius-sm`..`xl` already were variables, but nothing said a
  theme may set them.
- **Hooks.** The primitives put no attribute on their markup that a stylesheet can hold on to. srtm-ui wrapped
  `Button`, `Input` and `Textarea` (`controles.tsx`) to add `data-ui`, `data-variant` and `data-size`, and wrote CSS
  partials against those attributes.

Tokens computed on `:root` have a flaw: a custom property resolves where it is declared and is inherited as a value,
so a nested theme gets the outer theme's tints. Themes do nest: `PrintableDocumentPage` pins `data-theme="light"` on
the document sheet, which inside a dark page would keep dark's tints. The theme also changes structure: under it,
srtm-ui's `useVarianteTema()` draws a brand bar, a tree menu, chevron steps and a title band that light and dark do
not have. Those are components, not styles.

## Decision

**Ten extension tokens join the 18.** `theme.css` sets them in the `light` block (which `:root` shares) and in the
`dark` block, and maps each in `@theme inline`, so `bg-danger-soft`, `text-link`, `border-line` and the others are
Tailwind utilities. ADR-034's "a theme sets every token" now means all 28.

| Token | Purpose | Value in light and dark |
|---|---|---|
| `success-soft` | background of a success alert | tint: `success` at 8% over `surface` |
| `danger-soft` | background of an error alert or message | tint: `danger` at 8% over `surface` |
| `notice` | text of a fourth, neutral alert tone | mix: `warning` at 70% with `ink` |
| `notice-soft` | background of that tone | tint: `warning-soft` at 50% over `surface` |
| `link` | text links, and the accent of radios and checkboxes | alias of `brand` |
| `focus` | focus ring and the border of a focused field | alias of `brand` |
| `table-head` | background of a table's header row | alias of `surface-muted` |
| `table-stripe` | background of the even rows of a zebra table | tint: `surface-muted` at 50% over `surface` |
| `line` | thin rules: row separators, bars, footers | alias of `border` |
| `map-selected` | a selected feature on a map | fixed `#e8590c` |

**Each theme block holds fixed values, not a formula.** A tint is a literal `oklch()` taken from srtm-ui's oklab
derivation (in oklab a tint keeps the hue of its source color; in oklch it drifts to the hue of the near-grey
surface), with the dark hue matched to its source. An alias is a `var()` written in each theme block, so it resolves
on the element that carries the theme. `map-selected` is the orange srtm-ui's lots map already uses for a selection.
Nested themes therefore stay self-contained, the theme tests measure contrast straight from the file, and light and
dark look as before, except where a class already asked for a token that did not exist (see Consequences).

**Shape and type follow a theme.** `--radius` moves into `@theme` (not inline), with the same `0.25rem`, so
`rounded` compiles to `var(--radius)`. Besides the 28 tokens, a theme may set `--font-sans`, `--radius`,
`--radius-sm`..`xl` and `--radius-card`.

**`data-slot` attributes are the styling contract of the primitives.** The names are shadcn's:

| Component | Hooks |
|---|---|
| `Button` | `data-slot="button"`, `data-variant` (default `primary`), `data-size` (default `md`); on the child with `asChild` |
| `Card` | `data-slot="card"` |
| `Input`, `Textarea` | `data-slot="input"`, `data-slot="textarea"` |
| `SelectTrigger` | `data-slot="select-trigger"` |
| `Table`, `Th`, `Td` | `data-slot="table"` on the `<table>`, `"table-head"`, `"table-cell"` |
| `Badge` | `data-slot="badge"` |
| `Tabs` | `data-slot="tabs"` (root), `"tabs-list"` (the tablist), `"tabs-trigger"` (each tab), `"tabs-content"` (each panel) |

They are set before the caller's props, so an app can override one (srtm-ui passes `data-variant="round"`). Classes
and markup do not change, and light and dark do not style the hooks.

**A theme the library ships is an optional sheet plus a `ThemeDefinition`, never a built-in.** The first is
`@wasichai/ui/themes/portal-tributario.css` with `PORTAL_TRIBUTARIO_THEME` from `@wasichai/core`
(`{ id: 'portal-tributario', label: 'theme.portalTributario', colorScheme: 'light' }`, labels in core's i18n). An app
that wants it imports the sheet after `theme.css` and lists the definition in `config.themes`; it is never in
`BUILT_IN_THEMES`, so an app that does not ask for it neither ships its CSS nor offers it. A library sheet sets every
token, the font and the radii, and styles only the library's own components, through `data-slot`. Its partials sit in
`@scope ([data-theme='<id>']) to ([data-theme]:not([data-theme='<id>']))`, so light and dark never change and neither
does a subtree pinned to another theme (the printed document sheet). Its body size and focus ring go in `@layer base`,
like the defaults they replace.

**A sheet sets as little as it can.** An unlayered rule beats any Tailwind utility, whatever its specificity, so it
can restyle a primitive; but the primitive's default classes and a caller's own classes are both utilities, and the
rule beats those too. With `data-slot` on every primitive, a sheet reaches every control of every screen, the admin's
included. So a rule sets only what the theme's tokens cannot: no rule repeats a fill or a radius the classes already
draw from the tokens, no rule recolors a variant a caller commonly restyles (`ghost` on the shell), no rule sets the
sides of a field (a search box keeps its room for its icon). What only fills a gap the classes leave, such as zebra
rows and a total row, goes in `@layer base`, so a row's own class (a selection, a hover) still wins. Moving the
primitives' defaults to a lower layer would let a sheet sit between them and a caller's classes; that is a change to
every primitive, left for when a second sheet needs it.

**A theme with its own layout stays in the app.** srtm-ui's brand bar, tree menu, chevron steps and title band,
drawn only under its theme through `useVarianteTema()`, stay in srtm-ui with the partials that paint them. The library
ships tokens and CSS, not shells or components per theme, until a component has a second user; the candidates
(`Alerta`, `PasosGalon`, `BarraInstruccion`, `ArbolNav`, `BandaTitulo`) are a separate issue.

**The boot script knows each theme's color scheme.** The inline script of ADR-034 lists every theme the app offers
as a map from id to color scheme (`light`, `dark` and each `config.themes` entry). A stored id outside the map (an
older build's, another app's) and `system` follow the operating system, as core's `resolveTheme` does, and
`color-scheme` is set for an app's theme too, not only for the built-ins. The snippet is in
[docs/modules/core.md](../modules/core.md#frontend-package).

## Consequences

- In light and dark the one visible change is `bg-danger-soft`. `@wasichai/documents` (`RecordDocuments`,
  `DocumentTypesPage`) already used it for its error messages, and it generated nothing; it now paints a soft red
  under `text-danger`, at 4.7:1 in light and above 5:1 in dark. `rounded` keeps its `0.25rem`. It is a difference
  from the original app, recorded as [ADR-031](0031-deliberate-deviations-from-sapgis.md) D19.
- Light `text-success` on `bg-success-soft` stays below AA, at about 3.6:1. Light `success` is already 3.9:1 on
  `surface` and 4.0:1 on white, so fixing it means darkening `success`, a visible change to every success text in
  light. It is an explicit, tested exception, left to a follow-up: the test asserts the pair still fails, so it
  speaks up when the fix lands. Fixed on 2026-09-28: light `success` is `oklch(52% 0.13 155)`
  ([ADR-031](0031-deliberate-deviations-from-sapgis.md) D20). Every other pair passes, in light and in dark: `success`, `danger`, `warning` and
  `notice` on their soft backgrounds at 4.5:1, `link` on `surface` at 4.5:1, `focus` on `surface` at 3:1.
- An app theme written before this must now set the ten extension tokens. Until it does, it inherits light's values
  from `:root`.
- A `data-slot` name is public API: renaming or dropping one breaks an app's stylesheet as surely as a prop would.
- Under `portal-tributario`, a caller's classes lose to the sheet where both set the same property: an `h-8 text-xs`
  select trigger is 38px and 14.5px like every field, a `font-mono text-xs` cell is 14.5px. That is the theme's look,
  and it breaks no control: the rules leave alone the properties where a caller's class carries meaning (the shell's
  colors, a selected row, the room for a search icon).
- A subtree pinned to another theme gets that theme's tokens and none of the partials, but the enclosing theme's font
  and radii still reach it: they are Tailwind theme variables, not tokens. The printed sheet under `portal-tributario`
  prints in Arial with 3px corners.
- The partials need `@scope` (Chromium and Safari since 2023–2024, Firefox since late 2025). A browser without it
  drops them and keeps the tokens: the theme's colors, font and radii, on the library's own shapes.
- srtm-ui adopts the release: it deletes its `extensions.css` and the partials that moved, renames its `data-ui`
  hooks to `data-slot` where one exists, retires its `controles.tsx` wrappers except what the library lacks (the
  round icon button and `NativeSelect`), and registers `PORTAL_TRIBUTARIO_THEME`. `navTree.ts`, `instrucciones.ts`,
  `tonoDeEstado`, the brand and the partials of its own components stay in srtm-ui.
