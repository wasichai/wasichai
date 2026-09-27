# portal-tributario theme in the library Implementation Plan

> **For agentic workers:** Task 0 touches shared files and goes first; Tasks 1 to 5 touch disjoint files and run in
> parallel. Each is test first (red, then the implementation, then green) and leaves the commit to the integrator.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** wasichai/wasichai-ui#12, phases A and B: the ten extension tokens and `--radius` in `theme.css`, `data-slot`
hooks on the primitives, and srtm-ui's `portal-tributario` theme as an optional sheet with its `ThemeDefinition`.

**Architecture:** `@wasichai/ui` gains the tokens (fixed values in the light and dark blocks), the hooks and a sheet
under `src/themes/portal-tributario/`, exported as `@wasichai/ui/themes/portal-tributario.css`. `@wasichai/core`
exports `PORTAL_TRIBUTARIO_THEME`, never built in. Phase C (components) is a separate issue.

**Tech Stack:** React 19, Tailwind 4 (`tailwindcss` 4.3.3, srtm-ui's version, for the compile test), vitest +
testing-library, WCAG contrast from `oklch()` and hex.

**Spec:** `docs/superpowers/specs/2026-09-27-portal-tributario-theme-design.md` (wasichai repository)

## Global Constraints

- Repositories: `wasichai-ui` (packages, branch `claude/elegant-ride-6jw8b4`), `wasichai` (docs, same branch),
  `srtm-ui` (read only: the source of the theme and the adoption check; nothing is committed there).
- Extension tokens: `success-soft`, `danger-soft`, `notice`, `notice-soft`, `link`, `focus`, `table-head`,
  `table-stripe`, `line`, `map-selected`, in both blocks and in `@theme inline`. `link`/`focus` alias `brand`,
  `table-head` aliases `surface-muted`, `line` aliases `border`, `map-selected` is `#e8590c`; the rest are literal
  `oklch()`.
- `data-slot` names are shadcn's and are set before `{...props}`. Classes and visible markup do not change.
- The sheet styles only the library's own components through `data-slot`, in the theme's `@scope`, setting only what
  the tokens cannot, with no `data-ui`. `PORTAL_TRIBUTARIO_THEME` is never in `BUILT_IN_THEMES`.
- Light and dark do not change, except `bg-danger-soft`. Light `success` on `success-soft` is a tested exception.
- `.editorconfig` is law, max 160 columns. Conventional Commits. Comments in English, caveman style, say why.

## Review Focus

1. **Nested themes**: the document sheet pinned to `data-theme="light"` inside a dark page must get light's tints,
   so every extension token is set in each theme block, never only on `:root` (Task 1 test "aliases in both blocks").
2. **A caller's hook wins**: srtm-ui passes `data-variant="round"`; the slot attributes go before `{...props}`
   (Task 2 test "a caller overrides data-variant").
3. **Light and dark are untouched**: the compiled CSS of main and the branch differs only by what the spec allows
   (Verification 2).
4. **The exception stays honest**: the test asserts light `success` on `success-soft` still fails AA, so fixing
   `success` later fails it and the exception is removed with the fix.

---

## Task 0: Shared setup

**Files:**
- Modify: `packages/ui/package.json`
- Create: `packages/ui/src/test/css.ts`, `packages/ui/src/test/css.test.ts`

- [ ] **Step 1: Package.** `yarn workspace @wasichai/ui add -D -E tailwindcss@4.3.3`; add
  `exports["./themes/portal-tributario.css"] = "./dist/themes/portal-tributario/index.css"`; the build ends with
  `cp src/theme.css dist/theme.css && mkdir -p dist/themes && cp -r src/themes/portal-tributario dist/themes/`.
- [ ] **Step 2: Helpers.** Port `rule`, `rules` and `contrast` from srtm-ui's `src/themes/css.ts`; `channels()` also
  reads `oklch(L% C H)` (OKLab to linear sRGB, clipped to the gamut); add `resolveVar(tokens, name)` for `var(--x)`
  aliases. `css.test.ts` covers srtm-ui's WCAG cases and a known `oklch` pair.
- [ ] **Step 3: Commit** `test(ui): css rule and contrast helpers for the theme tests`, and `chore(ui): …` for the
  export and the dev dependency.

## Task 1: Extension tokens and `--radius` (phase A)

**Files:**
- Modify: `packages/ui/src/theme.css`, `packages/ui/src/theme.test.ts`
- Create: `packages/ui/src/theme.tailwind.test.ts`

- [ ] **Step 1: Failing tests.** `theme.test.ts`: the exact extension list in light and dark; the aliases in both
  blocks; `map-selected` equals `#e8590c`; `success`, `danger`, `warning` and `notice` at 4.5:1 on their `-soft` in
  light and dark, except light `success` on `success-soft`, asserted to still fail; `link` on `surface` at 4.5:1 and
  `focus` on `surface` at 3:1. `theme.tailwind.test.ts`, with `compile` from `tailwindcss`: `rounded` gives
  `var(--radius)` with `--radius: 0.25rem`, `bg-danger-soft` gives `var(--danger-soft)`, `font-sans` gives
  `var(--font-sans)`.
- [ ] **Step 2: Run** `yarn workspace @wasichai/ui test theme` → FAIL.
- [ ] **Step 3: Implement.** The ten tokens in `:root, [data-theme='light']` and `[data-theme='dark']`, each in
  `@theme inline`; `@theme { --radius: 0.25rem; }` beside `--radius-card`. Starting values (the tests decide; adjust
  only to keep AA): light `success-soft` `oklch(96% 0.01 165)`, `danger-soft` `oklch(96% 0.015 20)`, `notice`
  `oklch(42% 0.08 63)`, `notice-soft` `oklch(97% 0.025 85)`, `table-stripe` `oklch(98% 0.003 260)`; dark, hue matched
  to the source color, `success-soft` `oklch(26% 0.02 160)`, `danger-soft` `oklch(26% 0.025 25)`, `notice`
  `oklch(84% 0.09 80)`, `notice-soft` `oklch(28.5% 0.03 72)`, `table-stripe` `oklch(20% 0.014 265)`.
- [ ] **Step 4: Run** `yarn workspace @wasichai/ui test` → PASS.
- [ ] **Step 5: Commit** `feat(ui): extension tokens and --radius as a theme variable`.

## Task 2: `data-slot` hooks (phase A)

**Files:**
- Modify: `packages/ui/src/button.tsx`, `input.tsx`, `select.tsx`, `table.tsx`, `tabs.tsx`, `button.test.tsx`
- Create: `packages/ui/src/input.test.tsx`, `select.test.tsx`, `table.test.tsx`, `tabs.test.tsx`

- [ ] **Step 1: Failing tests.** Each slot of the spec's table (`button`, `input`, `textarea`, `select-trigger`,
  `table`, `table-head`, `table-cell`, `badge`, `tabs`, `tabs-list`, `tabs-trigger`, `tabs-content`); `data-variant`
  and `data-size` by default (`primary`, `md`) and explicit; on the child with `asChild`; a caller overrides
  `data-variant`; classes unchanged.
- [ ] **Step 2: Run** `yarn workspace @wasichai/ui test` → FAIL.
- [ ] **Step 3: Implement** the attributes before `{...props}`, with no change to classes or visible markup.
- [ ] **Step 4: Run** → PASS. **Commit** `feat(ui): data-slot hooks on the primitives`.

## Task 3: `PORTAL_TRIBUTARIO_THEME` (phase B)

**Files:**
- Modify: `packages/core/src/theme/themes.ts`, `packages/core/src/index.ts`,
  `packages/core/src/i18n/locales/{es,en}/common.json`, `packages/core/README.md`
- Create or extend: `packages/core/src/theme/themes.test.ts`

- [ ] **Step 1: Failing tests.** Not in `BUILT_IN_THEMES`; `resolveConfig({ themes: [PORTAL_TRIBUTARIO_THEME] })`
  and `resolveTheme` resolve it; `theme.portalTributario` exists in es and en; the sheet's `tokens.css` uses
  `[data-theme='${PORTAL_TRIBUTARIO_THEME.id}']`.
- [ ] **Step 2: Implement**
  `PORTAL_TRIBUTARIO_THEME = { id: 'portal-tributario', label: 'theme.portalTributario', colorScheme: 'light' }`,
  export it, add "Portal tributario" / "Tax portal". The README shows how to register the theme and the boot script
  with the id → color-scheme map (`Object.hasOwn`, srtm-ui's version).
- [ ] **Step 3: Run** `yarn workspace @wasichai/core test` → PASS. **Commit** `feat(core): PORTAL_TRIBUTARIO_THEME`.

## Task 4: The `portal-tributario` sheet (phase B)

**Files:**
- Create: `packages/ui/src/themes/portal-tributario/{index,tokens,controls,tables,tabs}.css`,
  `packages/ui/src/themes/portalTributario.test.ts`
- Modify: `packages/ui/README.md`

- [ ] **Step 1: Failing tests**, ported from srtm-ui's `tokens.test.tsx` and `parciales.test.tsx`: every light token
  of `../theme.css`, base and extension, is set; Arial, 3px and 14px; srtm-ui's AA pairs and the focus ring at 3:1;
  each partial imported after `tokens.css`, under `[data-theme='portal-tributario'] `, outside any `@layer`; the
  exact list of partials; key values (invalid field, disabled control, active tab joined to its panel, zebra
  skipping `aria-selected` rows); no `data-ui` anywhere in the sheet.
- [ ] **Step 2: Implement.** `tokens.css` copied from srtm-ui (tokens, font, radii; the 14px body and the focus ring
  in `@layer base`). `controls.css`, `tables.css` and `tabs.css` keep only the rules for the library's components,
  moved from `data-ui` to `data-slot`: `button` → `button`; `input`/`textarea`/`select` →
  `input`/`textarea`/`select-trigger`; `table` → `table`; `ficha-tabs`/`ficha-tab`/`ficha-panel` → `tabs`,
  `tabs-list`, `tabs-trigger`, `tabs-content`; radios and checkboxes as they are. `index.css` imports `tokens.css`,
  then the partials. Left out, in srtm-ui: `alerts`, `nav`, `pasos`, `banda`, `shell`; `ficha-kv`, `ficha-seccion`,
  `paginador`; `record-*`, `workspace-*`; `round`, `NativeSelect`; `data-numeric`.
- [ ] **Step 3: README**: the extension tokens, the `data-slot` table, font and radii per theme, how an app registers
  the theme (`@import` after `theme.css`, `config.themes`, the color scheme in the `index.html` script), what the
  sheet paints.
- [ ] **Step 4: Run** `yarn workspace @wasichai/ui test` → PASS. **Commit**
  `feat(ui): optional portal-tributario theme sheet`.

## Task 4b: Review follow-ups (after the code review)

The review ran the sheet against the admin: with `data-slot` on every primitive, the unlayered partials reached every
control and beat callers' classes. Fixed in `fix(ui): keep the portal-tributario sheet off callers' classes and pinned
subtrees`:

- [x] No `ghost` rule (the shell's sign-out turned link blue on the blue shell, 1.14:1; the template editor's pressed
  marks lost their background); no fill or radius rule the classes already draw; `sm` sets only its top and bottom.
- [x] Fields set no padding (the record list's search box keeps `pl-8` around its icon).
- [x] Zebra and total rows in `@layer base` (a selected row's `bg-brand-soft` and the row hover win).
- [x] Every partial in `@scope (…) to ([data-theme]:not(…))`: the printed sheet pinned to light keeps its look.
- [x] Only a `Card` (new `data-slot="card"`) that holds tabs steps aside, not any box (the page builder's palette).
- [x] Radios, checkboxes and `td a` back to srtm-ui; `Button` names no variant or size for `null`, and reads cva's
  defaults from one constant; the theme tests share their token lists and one CSS reader.

## Task 5: Docs in wasichai

**Files:**
- Create: `docs/adr/0035-theme-extension-tokens-slots-and-optional-sheets.md`, this plan, its spec
- Modify: `docs/adr/0034-user-preferences-and-themes.md` (status line only), `docs/adr/README.md`,
  `docs/HISTORY.md`, `docs/modules/core.md`, `docs/guides/build-your-app.md`

- [ ] **Step 1:** ADR-035 amends ADR-034: 28 tokens, `--radius`/`--font-sans` per theme, `data-slot` as the styling
  contract, library themes as optional sheets for their own components only, layout themes stay in the app;
  consequences: `bg-danger-soft`, the `success` exception, srtm-ui's adoption. ADR-034 gains "amended by ADR-035".
- [ ] **Step 2:** the index entry, the `HISTORY.md` entry, and in `core.md` and `build-your-app.md` the 28 tokens, the
  hooks, the optional sheet and the boot script with its `schemes` map.
- [ ] **Step 3:** `awk 'length > 160'` prints nothing and every relative link resolves. **Commit**
  `docs(adr): theme extension tokens, data-slot hooks and optional sheets`.

## Integration

- [ ] Review each diff, commit one phase at a time, `yarn format`, then a `code-review` pass; fix what it finds.

## Verification

1. **wasichai-ui:** `yarn format:check`, `yarn test:tooling`, `yarn lint`, `yarn test`, `yarn build` and
   `node tooling/check-release.mjs --pack 0.0.0-local`, whose `@wasichai/ui` tarball carries
   `dist/themes/portal-tributario/*.css`.
2. **Light and dark unchanged.** In a scratch directory, compile with Tailwind 4.3.3 the CSS of `packages/*/src` on
   main and on the branch and diff them. Allowed: the new variables, `--radius`, `rounded` → `var(--radius)` and new
   utilities (`bg-danger-soft`).
3. **Against srtm-ui:** `yarn pack` each package into a scratch directory; in srtm-ui, a temporary `package.json`
   with `file:` dependencies and `resolutions`, `yarn install --pure-lockfile`, then restore it; `yarn lint`,
   `yarn typecheck`, `yarn test` and `yarn build` pass with srtm-ui unchanged. Then, throwaway: import
   `@wasichai/ui/themes/portal-tributario.css` in `src/index.css`, `yarn build`, and check the emitted CSS has the
   `[data-slot=…]` rules under the theme. `git checkout .` leaves `git status` clean; srtm-ui's `package.json` and
   `yarn.lock` are never committed.

## Afterwards

- Push both branches; draft pull requests: wasichai-ui `feat(ui): …` with `Closes #12` and a summary per phase,
  wasichai `docs(adr): …`. On #12, drop the "blocked" line and link both.
- srtm-ui: an adoption issue under epic #44 (delete `extensions.css` and the moved partials, `data-ui` →
  `data-slot`, retire `controles.tsx` except `round` and `NativeSelect`, import the sheet and register
  `PORTAL_TRIBUTARIO_THEME`; `navTree.ts`, `instrucciones.ts`, `tonoDeEstado`, the brand and its own partials stay).
- Follow-up issues: phase C components; light `success` contrast.
- release-please proposes the release once the pull request merges; nobody tags it by hand.
