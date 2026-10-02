# ADR-031: Where wasichai deliberately behaves differently from the original

**Status**: accepted · 2026-09-25

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

The library split promised no functional change: same REST API, same behaviour, same UI. Two things forced small
exceptions. First, modules became optional, so situations the original could never meet now happen: a field whose
type's module is gone, a page component nobody draws, an automation action whose module is missing. Second,
porting every line under review surfaced a few bugs that were cheaper to fix than to preserve. This ADR is the one
list of both, so "same behaviour" has a precise meaning.

## Decision

These are the only intended differences. Anything else that behaves differently is a bug.

**Because modules are optional**

- **D1. A route of a module that is not installed.** Could not happen in the original. Answers `404` to an
  authenticated caller and `401` without a token, never `403`, so the frontend reads it as "not installed".
- **D2. Any edit, even label-only, of a field whose type's module is not installed.** Could not happen. Answers
  `409`: the field is revalidated against its handler on every edit ([ADR-025](0025-extension-spis.md)).
- **D3. An automation with `GENERATE_DOCUMENT` when wasichai-documents is absent.** Could not happen. Refused when
  saved. If documents is removed later, the run fails with a message instead of issuing.
- **D4. A page component or action kind that no registered module draws.** Could not happen. The component draws
  a muted placeholder, and an action with a null or unknown kind draws nothing.
- **D5. A freshly dropped page action.** Was always `TRANSITION`. Now the first registered kind: `TRANSITION` with
  workflow installed, `NAVIGATE` without it.
- **D6. The development seed user.** Was always created by a migration. Now created only with
  `wasichai.seed.dev=true`, as `admin@wasichai.local` ([ADR-030](0030-rebrand-sapgis-to-chawpi.md)).
- **D7. The AI provider.** Was Anthropic, built in. `wasichai-agent` is now provider-neutral, and
  `wasichai-spring-boot-starter-agent` adds Anthropic as the default. An app can exclude it and add another Embabel
  provider.
- **D8. A switch for core.** Modules have `wasichai.<module>.enabled`, core has none ([ADR-024](0024-libraries-and-starters.md)).

**Fixed on the way**

- **D9. Link and unlink of related records.** Were unchecked on the other record's organization and owner, and
  answered `204` or `500`. Both records are now held to the record API's rules, with `404` otherwise and one
  `UPDATE` history row on each ([ADR-025](0025-extension-spis.md)).
- **D10. The audit "after" snapshot.** Was the caller's writable projection, so a locked field looked cleared. Now
  every stored field ([ADR-025](0025-extension-spis.md)).
- **D11. A `401` in the middle of a session.** Cleared only the token, leaving a signed-in-looking user whose every
  call failed. Now signs the user out and returns to the login page.
- **D12. The record detail page when the custom page fails to load.** Any error fell back to the default page. Now
  only a `404` does. Other errors show the error and a retry, so an outage is not hidden behind a working-looking
  page.
- **D13. A `NAVIGATE` action with no target.** Linked to `/undefined`. Now links to the objects list.
- **D14. The login form's email.** Was prefilled with the seed user. Now empty by default, configurable with
  `WasichaiApp` `config.defaultLoginEmail`.
- **D15. Issuing a document.** The record history did not refresh. Now it does, so the `ISSUE` entry shows at once.
- **D16. Field type names in requests.** Matched exactly after upper-casing. Now surrounding whitespace is trimmed
  first (`" text "` is `TEXT`).

**Because the libraries assume no infrastructure**

- **D17. GeoServer's datastore host.** Was `postgres`, the name of the original's docker compose service. Now
  `localhost`, like `wasichai.database.host`: nothing in the libraries names a container or a compose service
  ([ADR-033](0033-samples-and-infrastructure-in-their-own-repositories.md)). An app whose GeoServer runs on a
  container network sets `wasichai.gis.geoserver.datastore.host` (`WASICHAI_GIS_GEOSERVER_DATASTORE_HOST`) to the
  database's host there, e.g. `postgres` with wasichai-infrastructure.

**Because users choose how it looks**

- **D18. Themes and stored preferences.** The original had one light palette and kept the language in the browser.
  Now the user picks System, Light, Dark or an app's theme, and `GET/PUT /api/auth/me/preferences` stores it with the
  language ([ADR-034](0034-user-preferences-and-themes.md)). The `user_preferences` table is a known schema-parity
  deviation. Tested by `UserPreferencesApiTest`, `UserPreferencesIsolationTest` and wasichai-ui's `ThemeProvider` tests.
- **D19. The error message of the documents panel and of document types.** Its `bg-danger-soft` background named a
  token no theme defined, so it drew nothing and the error was plain red text. Now the token exists and the message
  sits on a soft red, `text-danger` at 4.7:1 in light
  ([ADR-035](0035-theme-extension-tokens-slots-and-optional-sheets.md)). Tested by wasichai-ui's `theme.test.ts` and
  `theme.tailwind.test.ts`.
- **D20. Success text in light.** Was `oklch(58% 0.13 155)`, 3.9:1 on the page and below WCAG AA. Now
  `oklch(52% 0.13 155)`: every success text, badge and icon in light is a little darker, 5.0:1 on `surface` and 4.6:1
  on `success-soft`. Dark is unchanged. Tested by wasichai-ui's `theme.test.ts`.

**Because paging must be stable**

- **D21. Tied rows have a stable order.** The original ordered a record list by the sort key alone, so rows sharing it
  (everything one transaction writes shares `created_at`) came back in whatever order the plan produced, and a client
  paging through them could see a row twice and miss a sibling. Now every record list ends its `ORDER BY` with `id`, in
  the direction of the primary sort, and the audit list does the same. Tested by `PhysicalTableRecordStoreTest` and
  `RecordApiTest`.

**Kept on purpose, although they look like candidates.** Sections such as geometries stay out of audit diffs and
automation payloads ([ADR-019](0019-a-geometry-is-a-field.md)). `RecordService` still opens no transaction of its
own ([ADR-025](0025-extension-spis.md)). A `MULTI*` geometry field still cannot be drawn in the UI, because the draw
mode is single-part ([docs/modules/gis.md](../modules/gis.md), "Known limitations").

## Consequences

- "No functional change" is testable: the ported integration tests assert the original behaviour everywhere except
  these entries, and each entry has its own test.
- A future difference needs a new entry here, or it is a regression.
