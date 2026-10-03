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
- **D22. Declared indexes, an optional count and keyset reads ([ADR-036](0036-declared-indexes-optional-count-and-keyset-reads.md)).**
  The metadata schema gains `custom_fields.indexed` and `custom_objects.indexes`. On a fresh database `indexed` sits
  before the `wasichai-gis` attribute columns, so those move one position, and `SchemaParityTest` lists the changed
  lines. Every `RELATION` column, indexed field and declared field set gets an index on the data table, the original
  built none of them, and a startup reconciliation adds them to existing tables. A field or relationship that a
  composite index names cannot be deleted (`409`). `count` and `after` are reserved record-list parameters: `?count=`
  with anything but `true`/`false` is a `400` where it used to filter a field named `count`, `?after=` is read as a
  cursor rather than a filter, and both are refused as new field names. A default page now carries `nextCursor`
  whenever another row follows. Tested by `DeclaredIndexApiTest`, `RecordKeysetApiTest`, `FieldApiTest` and
  `SchemaParityTest`.
- **D23. Composite unique constraints, and a repeat as a `409` ([ADR-037](0037-composite-unique-constraints-and-409-on-repeats.md)).**
  A record write that repeated a unique value was an untyped `500` with no `errors[]`. Now it is a `409`
  problem+json with one `errors[]` entry per field of the violated constraint, and any other unique or primary-key
  violation that reaches a response is a `409` too, without `errors[]`. Making a field `unique` over repeated values is
  a `409` naming `unique`. An object can declare `uniqueConstraints`, which the original could not: the metadata
  schema gains `custom_objects.unique_constraints`, which `SchemaParityTest` lists as a known deviation, and each entry
  is a `UNIQUE (a, b, …)` on the data table. A field or relationship that one names cannot be deleted
  (`409`). Tested by `CompositeUniqueApiTest`, `FieldApiTest` (core and parity) and `SchemaParityTest`.

**Because some records must never change**

- **D24. Append-only and api-only objects, and a pre-write guard.** The original had neither flag, and nothing could
  veto a record write before it landed. Now an object may be `appendOnly` (`UPDATE` and `DELETE` of its records answer
  `409` for everyone, ADMIN, the platform and automations included) or `apiOnly` (the generic record API answers `403`
  on writes, in-process callers still write), and a `RecordWriteGuard` bean can veto any record write
  ([ADR-040](0040-append-only-objects-and-a-pre-write-guard.md)). On an append-only end, a link that already exists or
  an unlink of one that does not answers `409`, where it is an idempotent `204` otherwise. Deleting a record that an
  append-only record points at, by a `RELATION` column or a join row, answers `409` too, where `ON DELETE SET NULL` /
  `CASCADE` would change the append-only record silently. Both flags are off by default, so every existing object
  behaves as before, but every object response now carries both keys. The `custom_objects.append_only` and
  `custom_objects.api_only` columns are a known schema-parity deviation, and the two keys a known wire-parity one
  (`GeometryWireParityTest`). Tested by `RecordWriteRulesTest`, `WriteRulesApiTest`,
  `WorkflowWriteRulesTest` and `AutomationWriteRulesTest`.

**Because a change must say why**

- **D25. Change reason on record writes.** The original's audit log had no reason, and nothing could require one.
  Now every record write route takes an optional `X-Change-Reason` header (and `RecordService`, `RelatedRecordService`
  and `WorkflowService` an optional `reason`), stored in the new `audit_log.reason` and returned as `reason` on every
  audit and history entry; an object may be `requiresReason`, and a write of its records without one answers `400` on
  `reason` ([ADR-041](0041-a-change-reason-on-record-writes.md)). Automation writes carry `automation '<name>'`. The
  flag is off by default, so every existing request behaves as before, but every object response now carries
  `requiresReason` and every audit entry `reason`. The `audit_log.reason` and `custom_objects.requires_reason` columns
  are a known schema-parity deviation, and the object key a known wire-parity one (`GeometryWireParityTest`). Tested by
  `ChangeReasonTest`, `RecordWriteRulesTest`, `ChangeReasonApiTest`, `WorkflowWriteRulesTest` and
  `AutomationWriteRulesTest`.

**Because an object has verbs of its own**

- **D26. Declared actions.** The original's permissions were a closed set, and a privilege that is not CRUD was an
  object of its own or a role-name check in app code. Now an object can declare actions (`object_actions` table,
  `/api/metadata/objects/{object}/actions`), granted through `PUT /api/roles/{name}/permissions` with the declaring
  `objectName`, and `permissions` gains a stored `declared_object_id` column. Observable changes: the `400 Unknown
  action` violation text now ends "or an action the object declares" (the message is the same); `GET
  /api/auth/me/permissions` may list declared actions after the four record actions; and wasichai-ui's roles page,
  typed by a closed `Action` union, drops declared rows, so saving a role there deletes its declared grants until the UI
  follows up. Tested by `DeclaredActionsApiTest` and `SchemaParityTest`
  ([ADR-042](0042-app-declared-actions.md)).

**Because systems call it, not only people**

- **D27. Service accounts.** The original's only caller was a person signing in with email and password. Now an
  organization has service accounts that trade a client id and secret at `POST /api/auth/token` for a short-lived token
  naming the account, managed at `/api/service-accounts` ([ADR-043](0043-service-accounts.md)). The
  `service_accounts` table is a known schema-parity deviation. Each account has a backing `users` row that
  `GET /api/users` leaves out and the user routes answer `404` to; audit entries it makes and its `GET /api/auth/me`
  carry `serviceAccount`, a key absent for a person. A person's login, token and answers are unchanged. Tested by `ServiceAccountApiTest`,
  `ServiceAccountAutomationTest` and `JwtServiceTest`.

**Because a reference can vanish under a write**

- **D28. A foreign-key violation is a `409`.** The original answered a write whose foreign key no longer held (the
  record it points at deleted meanwhile) with a `500`. Now it is a `409` problem+json, "A record this one points at
  does not exist any more"; other integrity violations stay `500`. It is how an append-only insert that loses the race
  against a delete answers ([ADR-044](0044-append-only-delete-check-under-a-row-lock.md)). Tested by
  `GlobalExceptionHandlerIntegrityTest` and `WriteRulesApiTest`.

**Kept on purpose, although they look like candidates.** Sections such as geometries stay out of audit diffs and
automation payloads ([ADR-019](0019-a-geometry-is-a-field.md)). `RecordService` still opens no transaction of its
own ([ADR-025](0025-extension-spis.md)). A `MULTI*` geometry field still cannot be drawn in the UI, because the draw
mode is single-part ([docs/modules/gis.md](../modules/gis.md), "Known limitations").

## Consequences

- "No functional change" is testable: the ported integration tests assert the original behaviour everywhere except
  these entries, and each entry has its own test.
- A future difference needs a new entry here, or it is a regression.
