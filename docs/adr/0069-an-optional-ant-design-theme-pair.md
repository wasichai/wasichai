# ADR-069: An optional Ant Design theme pair

**Status**: accepted · 2026-10-20 · builds on [ADR-034](0034-user-preferences-and-themes.md) and extends
[ADR-035](0035-theme-extension-tokens-slots-and-optional-sheets.md)

## Context

ADR-035 made a library theme an optional sheet plus a `ThemeDefinition`, and shipped one, `portal-tributario`: the look
of one app, light only. wasichai-ui also wants a theme that is no one app's look: a familiar admin look, with a dark
half the user can pick or let follow the operating system. [Ant Design](https://ant.design/docs/spec/introduce) fits:
it is widely known, fully specified, and its default values are public as antd's seed and map tokens.

Its defaults do not all hold up under the theme tests. antd's primary `#1677ff` gives 4.1:1 with white, its error
`#ff4d4f` 3.3:1, its success `#52c41a` 2.3:1 and its warning `#faad14` 1.9:1: antd uses them as fills, but under
ADR-035's tokens `brand`, `danger`, `success` and `warning` are also text, and every text pair must reach WCAG AA. Its
focus ring, `#91caff`, is 1.7:1 on white, below the 3:1 a focus indicator needs (WCAG 1.4.11).

## Decision

**`ant-design` is an optional light theme, built like portal-tributario.** `ANT_DESIGN_THEME` in `@wasichai/core`
(`{ id: 'ant-design', label: 'theme.antDesign', colorScheme: 'light' }`) pairs with the sheet
`@wasichai/ui/themes/ant-design.css`; an app imports the sheet after `theme.css` and lists the definition in
`config.themes`. It is never in `BUILT_IN_THEMES`. Shipped in
[wasichai/wasichai-ui#64](https://github.com/wasichai/wasichai-ui/pull/64).

**Its values come from the Ant Design spec and antd 5.24's seed and map tokens.** The system font stack, 14px text on
22px, 6px corners (4px small, 8px large), white containers on the `#f5f5f5` layout, `#d9d9d9` borders, `#f0f0f0`
splits and the `#001529` sider as the shell. The sheet sets all 28 tokens, the font and the radii. Its partials paint
`Button`, the fields (`Input`, `Textarea`, `SelectTrigger`), `Card`, `Badge` (as antd's Tag), `Table`, `Tabs`, `Alert`
and `NavTree` (as antd's inline Menu), through ADR-035's `data-slot` hooks only, with ADR-035's rule of setting only
what the tokens cannot.

**An antd colour that misses AA as text moves to the nearest antd palette step that holds it.**

| Token | antd | Here | With white |
|---|---|---|---|
| `brand` | `#1677ff` (4.1:1) | `#1668dc`, antd's dark-algorithm primary | 5.2:1 |
| `danger` | `#ff4d4f` (3.3:1) | `#cf1322`, red-7 | 5.6:1 |
| `success` | `#52c41a` (2.3:1) | `#237804`, green-8 | 5.6:1 |
| `warning` | `#faad14` (1.9:1) | `#874d00`, gold-9 | 6.8:1 |

The focus ring and a focused field's border are the brand, not `#91caff`. Alerts keep antd's look: `ink` text on the
tone's soft background (antd's `colorXBg`), with antd's tone border, rather than the tone's colour as text.

**The dark half, `ant-design-dark`, lives in the same sheet.** `ANT_DESIGN_DARK_THEME` (`colorScheme: 'dark'`) takes
its values from antd's `darkAlgorithm`, under the same AA rule. The sheet gains a second token block; an app still
imports one file. The partials become theme-agnostic: each is scoped to both ids,
`@scope (:is([data-theme='ant-design'], [data-theme='ant-design-dark'])) to ([data-theme]:not(:is(…)))`, so a subtree
pinned to any other theme still stops them, and the literal colours they hold today (the alert borders, the menu's
hover grey, the hover blue, the shadows) move into sheet-local `--ant-*` custom properties that each token block sets.
An app pairs the two with `systemThemes: { light: 'ant-design', dark: 'ant-design-dark' }`, so `system` follows the
operating system within the pair, and lists both ids in the boot script's map (ADR-035). This half is decided here and
lands next in wasichai-ui.

**Not done.**
- Depending on the `antd` package. Its components and CSS-in-JS runtime are heavy, bypass the Tailwind tokens and the
  `data-slot` model of ADR-035, and would tie every module that renders a primitive to it, against module
  independence (rule 3 of wasichai-ui).
- A separate dark sheet. Two imports to keep in step, and every partial duplicated or imported twice.
- antd's exact colours. They fail AA as text, and the theme tests enforce AA on every pair.

## Consequences

- One more optional sheet to maintain, with its own tests (`antDesign.test.ts`: tokens, scope, hooks, and contrast for
  every text pair and the focus ring). Light, dark and portal-tributario do not change.
- antd's hover blue `#4096ff` (3.0:1 with white) appears only on borders, never as text.
- The brand, danger, success and warning are darker than antd's own (one to three palette steps), so the theme is close
  to antd, not identical to it.
- When the dark half lands, the light theme's look does not change: its literal colours become `--ant-*` properties
  with the same values.
- No REST change: ADR-032 is untouched. A theme id is only shape-checked by the server (ADR-034), so
  `ant-design-dark` needs nothing there.
- Opt-in, never a default: an app that does not import the sheet neither ships it nor offers it, so there is no
  ADR-031 entry.
