# ADR-034: A stored resource for user preferences, starting with theme and locale

**Status**: accepted · 2026-09-26 · amended by [ADR-035](0035-theme-extension-tokens-slots-and-optional-sheets.md)

## Context

The original app had one light palette; there was never a reason to store a UI preference. The language a user picks
lives only in the browser (`<prefix>.lang` in `localStorage`) and never reaches the server, so it does not follow the
user to another browser or device. Users now want a dark mode and the choice to have it follow the operating system,
and wasichai-ui wants a place to add more per-user choices later without a new endpoint each time.

## Decision

**`GET/PUT /api/auth/me/preferences`** returns and updates `{ theme, locale }` for the calling user, backed by a new
`user_preferences` table (`user_id` primary key and foreign key to `users`, `theme` not null defaulting to
`'system'`, `locale` nullable, `updated_at`). Authenticated callers only, and each caller reads and writes their own
row; the caller's identity never comes from the request body. A field left out of the `PUT` keeps its stored value; a
`PUT` with no existing row inserts one. No row reads as the defaults (`{ "theme": "system", "locale": null }`)
without ever being persisted.

**The server validates the shape of a theme id, not its value.** `theme` must match `^[a-z0-9-]{1,40}$` or the
request is `400`; it is never checked against a list of known themes, because themes belong to each app
(`WasichaiConfig.themes` in wasichai-ui) and the server has no way to know them. `locale` is `null` or a BCP 47 tag,
otherwise `400`.

**The UI switches themes with `data-theme` on `<html>` over CSS-variable tokens.** `light` and `dark` are the two
built-in themes; an app adds its own with a CSS block that sets every token and a config entry, without touching any
package. `system` is not a theme itself: it is the user's preference to follow `prefers-color-scheme`, resolved to
`light` or `dark` at render time.

**The local copy (`<prefix>.theme`) avoids a flash.** wasichai-ui writes the preference (a theme id, or `system`) to
`localStorage` on every change; an inline script in the app's `index.html` reads it before the bundle loads, resolves
`system` through `prefers-color-scheme` and sets `data-theme` immediately, so a user who chose dark never sees a light
flash on load.

## Consequences

- A new wasichai-ui against an older backend without this endpoint gets `404` on the `GET` (or on a `PUT` sent before
  the `GET` settled) and falls back to a browser-only preference: the theme selector and the language button keep
  working, they just do not follow the user across browsers.
- A theme that does not set every token leaves some elements in whichever theme's value happens to be inherited;
  adding a theme means setting the whole table, not a difference from `light`.
- Printed documents stay light regardless of the chosen theme: `PrintableDocumentPage` pins `data-theme="light"` on
  the document sheet, because a printed page is paper, not screen chrome.
- `user_preferences` is a known schema-parity deviation ([ADR-031](0031-deliberate-deviations-from-sapgis.md) D18):
  the original schema has no such table.
