# The UI has themes, and the user's choice follows them

**Status**: design under review · 2026-09-26 · leads to ADR-034 and ADR-031 D18

## Context

`@wasichai/ui/theme.css` holds the only palette: one `@theme` block of `oklch` tokens (`surface`, `ink`, `brand`,
`shell`, `danger`, …) that every package uses through Tailwind classes (`bg-surface`, `text-ink`). There is no dark
mode and no way to add another palette. About twenty classes bypass the tokens (`text-white`, `bg-black/50`,
`bg-amber-500/15`, `bg-white`), mostly in `AppShell.tsx`, `button.tsx`, `OperationBadge.tsx` and
`PrintableDocumentPage.tsx`.

The user's language lives only in the browser (`<prefix>.lang` in `localStorage`); nothing about the user's choices
reaches the server.

## Goal

- The end user picks **System**, **Light** or **Dark** (plus any theme the app adds). System follows
  `prefers-color-scheme` and is the default.
- The choice is stored **per user in the API** and follows them to other browsers. The same resource stores the
  language, which is the second concrete user of it.
- An app can **add themes** (high contrast, a customer palette) with CSS and one config entry, without touching any
  package.
- Existing apps keep working with no change and gain dark mode. A new UI against a backend without the endpoint keeps
  working with a browser-only preference.

Not in scope: per-organization or admin-defined themes, theming the MapLibre basemap, dark printed documents.

This is a deliberate addition over the original app, recorded as ADR-031 D18.

## Design

### 1. API (wasichai-core, lands first)

```
GET /api/auth/me/preferences   → { "theme": "system", "locale": null }
PUT /api/auth/me/preferences   { "theme"?: string, "locale"?: string | null } → the stored preferences
```

- A field left out of the `PUT` keeps its value. Authenticated callers only; each caller reads and writes their own.
- `theme`: `system` by default; any id matching `^[a-z0-9-]{1,40}$`, otherwise `400`. The server does **not** check
  it against a list: themes belong to each app.
- `locale`: `null` (the browser's) or a BCP 47 tag of at most 35 characters, otherwise `400`.
- Storage: migration `V2__user_preferences.sql` in `db/wasichai/core`:
  `user_preferences (user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE, theme text NOT NULL DEFAULT
  'system', locale text, updated_at timestamptz NOT NULL DEFAULT now())`. No row reads as the defaults; the first
  `PUT` inserts it.
- Documented in `docs/api/rest.md` next to `/api/auth/me`. ADR-034 records the resource and why the server does not
  validate theme ids.

### 2. Tokens (`@wasichai/ui`)

A theme is a name. `<html>` carries `data-theme="<id>"` and `color-scheme: light|dark`. `theme.css` becomes:

```css
:root, [data-theme='light'] { --surface: oklch(99% 0.002 260); --ink: …; /* every token */ }
[data-theme='dark']         { --surface: oklch(…); --ink: …; /* every token */ }
@theme inline { --color-surface: var(--surface); --color-ink: var(--ink); /* … */ }
```

- Class names do not change, so no module rewrites its components. Themes nest: an element with
  `data-theme="light"` is light inside a dark page.
- Dark palette: same hues (260–265, brand 262), lightness inverted for `surface*`, `ink*` and `border`; `brand`
  raised to about 68% L for AA contrast on the dark surface; `shell` one step darker than `surface`.
- New tokens, only what the fixed colors need:

  | Token | Replaces | In |
  |---|---|---|
  | `on-brand`, `on-danger` | `text-white` on a fill | `button.tsx`, `ActionButton.tsx`, `ComponentMock.tsx`, `PrintableDocumentPage.tsx` |
  | `shell-ink` | `text-white`, `bg-white/12`, `border-white/10` | `AppShell.tsx` |
  | `warning`, `warning-soft` | `bg-amber-500/15 text-amber-700` | `OperationBadge.tsx` |
  | `overlay` | `bg-black/50` | `dialog.tsx` |

- The document sheet in `PrintableDocumentPage` is paper: it carries `data-theme="light"` and stays light in every
  theme. Printing is unchanged.
- An app adds a theme with a CSS block `[data-theme='high-contrast'] { --surface: …; … }` that sets every token, and
  a config entry (section 3).

### 3. Core (`@wasichai/core`)

**Config.** `WasichaiConfig.themes?: ThemeDefinition[]`, `ThemeDefinition = { id, label, colorScheme: 'light' |
'dark' }`, where `label` is an i18n key. `light` and `dark` are always there; `config.themes` adds to them (an id
that repeats a built-in one throws in `resolveConfig`). `storageKeys` gains `theme` (`<prefix>.theme`).

**Preferences** (`src/preferences/`). `usePreferences()` is a react-query read of `GET /api/auth/me/preferences`,
enabled only with a session. `useUpdatePreferences()` does the `PUT` with an optimistic update and writes the local
copies (`<prefix>.theme`, `<prefix>.lang`). A failed `PUT` rolls back and shows its error like other mutations. A
`404` on the `GET` means an older backend: the preference stays browser-only and the selector keeps working.

**ThemeProvider**, inside `AuthProvider` in `WasichaiProviders`:

1. Preference = API value → local copy → `system`. An id not in the configured themes reads as `system`.
2. `system` resolves through `matchMedia('(prefers-color-scheme: dark)')` and follows its changes.
3. Sets `data-theme` and `style.colorScheme` on `document.documentElement`.
4. `useTheme()` → `{ preference, theme, colorScheme, themes, setPreference }`, exported from the package.

**Language.** When preferences load with a `locale` that is in `config.languages` and differs from the current one,
core calls `changeLanguage`. The shell's language button goes through `useUpdatePreferences({ locale })`. Without a
session (the login screen) it stays browser-only, as today.

**No flash.** The build guide and `docs/modules/core.md` document a six-line inline `<script>` for the app's
`index.html` that reads `<prefix>.theme` and sets `data-theme` before the bundle loads. The four samples add it. An
app without it only flashes the light theme on load.

**Shell.** The user footer of `AppShell` gets a theme selector: System, the configured themes in order. Labels
`theme.system`, `theme.light`, `theme.dark` in the core i18n namespace (es, en).

### 4. GIS

MapLibre controls and the GIS panels use tokens; `useTheme().colorScheme` is there for a module that needs it. The
basemap is unchanged.

## Testing

- **wasichai:** controller and service tests for `GET`/`PUT` (defaults with no row, partial `PUT`, invalid `theme`
  and `locale` → `400`, no token → `401`, a caller never sees another's row); the migration runs in the integration
  suite.
- **`@wasichai/ui`:** a test parses `theme.css` and fails if a token defined for `light` is missing in `dark`; a test
  scans `packages/*/src` and fails on fixed palette classes (`bg-white`, `text-gray-500`, `amber-…`) outside an
  explicit allowlist (`print:bg-white`).
- **`@wasichai/core`:** resolution order (API, local, system; unknown id); `matchMedia` changes under `system`;
  optimistic update and rollback; `404` fallback; locale applied from preferences; the selector in `AppShell`.
- **Samples:** full-sample's Playwright e2e switches to Dark, reloads, and checks `data-theme` on `<html>`.

## Delivery

1. **wasichai:** the endpoint, migration, ADR-034, ADR-031 D18 and `docs/api/rest.md`. Released as a minor version.
2. **wasichai-ui:** tokens and the migration of fixed colors (useful on its own, no API needed), then preferences,
   `ThemeProvider`, shell selector and docs. Released as a minor version.
3. **Samples:** the inline script, and the e2e in full-sample.
4. `docs/HISTORY.md` records it once both releases are out.
