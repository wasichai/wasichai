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
- **D29. A `RELATION` value naming no record is a `400`.** The original stored a `RELATION` value after checking only
  that it was a UUID, and the foreign key then failed with a `500`; 0.3.0 answered that `409` (D28). Now a write whose
  `RELATION` value names no record of the writer's organization, or a record of another one, is refused as
  "Invalid value for '<field>'" with `errors[].field` naming the field (`400` over REST), and nothing is stored. The
  check sits in `RecordWriteGuards.beforeWrite`, so every write path has it: the record API, the platform, automation
  actions. It runs after the built-in write rules (`appendOnly` `409`, `requiresReason` `400` on `reason`) and before
  the app's guards; type and format errors (a value that is no UUID) still come from the codec at the store write. Both
  cases answer alike, so the answer never tells whether the id exists elsewhere. A record deleted between the check and
  the write still fails the foreign key with D28's `409`. The link route is unchanged: an `otherId` that names no
  record stays a `404`. Tested by `RelationTargetApiTest`, `RelationTargetsTest`, `AutomationWriteRulesTest` and
  `WriteRulesApiTest`.
- **D30. A `RELATION` value must name a record the caller can read.** The original, and D29, checked a `RELATION`
  value against the organization only: an own-records-only caller could point a relation at another user's record, and
  a caller without `READ` on the target object at any of its records, and the `201` told them the id existed. Now, for
  a person or a service account who is not `ADMIN`, the target must also be in their read scope: `READ` on the target
  object (an org-wide grant counts) and, when every role of the caller is own-records-only, `created_by` the caller.
  The rules are the ones record reads apply (`RoleQueries`, `AccessPolicy`), folded into D29's one read per target
  object. A target outside the scope gets D29's answer for a missing one, `400` "Invalid value for '<field>'" with
  `errors[].field`, the same body, and nothing is stored. `ADMIN`, the platform (`asPlatform`) and automation actions
  keep D29's organization-only check. On an update, a value equal to the stored one is still not looked up, so an
  update that keeps a link the caller cannot see (made by someone who could) goes through. Tested by
  `RelationTargetApiTest`, `RelationTargetsTest` and `RecordServiceTest`.

**Because staff must be told what needs doing**

- **D31. Organizational units.** The original had no unit, area or group. Now core has a tree of units per
  organization and who belongs to which ([ADR-045](0045-organizational-units.md)): `/api/org-units`,
  `PUT /api/users/{id}/org-units` and `GET /api/auth/me/org-units`, under `MANAGE_ORGANIZATION` except the last.
  `AdminUserResponse` gains `orgUnits`, a list of unit codes, empty for a user in no unit. Units grant nothing and are
  not in the token. The `org_units` and `user_org_units` tables are a known schema-parity deviation. Tested by
  `OrgUnitApiTest` and `OrgUnitDirectoryTest`.
- **D32. Notifications.** The original could not tell anyone anything. With `wasichai-notifications` installed, people
  get notifications addressed to everyone, a user, a role or a unit, within a window, at
  `/api/auth/me/notifications` (list, summary, read, dismiss, snooze, read-all and a live `text/event-stream`);
  administrators publish at `/api/notifications`; date rules live at `/api/objects/{object}/notification-rules`
  ([ADR-046](0046-notifications-module.md), [ADR-047](0047-server-push-over-sse-and-listen-notify.md)). Without the
  module every one of those routes is a `404` (D1). The `notification*` tables are a known schema-parity deviation.
  Tested by the `notificationsIt` suite and `NotificationsOnlyApiTest`.
- **D33. A TAB has a key.** The original's tabs had only a title. Now a `TAB` may carry `key`, an upper-case token
  unique in its page, and generated pages key their tabs with their titles (`DETAILS`, `RELATED`, `HISTORY`, `MAP`), so
  a link can name a tab ([ADR-046](0046-notifications-module.md)). A component without a key is stored and sent as
  before (no `key` property). Tested by `PageServiceTest`, `PageGenerationTest` and `PageApiTest`.

**Because administration answers to an auditor too**

- **D34. Admin changes are in the audit log.** The original audited record writes only. Now every change to users,
  roles, permission sets, service accounts, organizational units, objects (their flags, indexes, uniques and declared
  actions), fields, relationships and the tenant writes one `audit_log` row in its own transaction, under a reserved
  `object_name` (`admin:user`, `admin:role`, `admin:permission`, `admin:service-account`, `admin:org-unit`,
  `admin:object`, `admin:field`, `admin:relationship`, `admin:organization`), with the acting user, the entity's id
  and its state before and after, never a password, hash or client secret
  ([ADR-049](0049-admin-changes-in-the-audit-log.md)). Observable changes: `GET /api/audit?objectName=admin:…`
  answers them to a `MANAGE_ORGANIZATION` holder and `[]` to anyone else (no `403`, no `READ` needed);
  `GET /api/audit` with no `admin:*` filter lists them too, for a `MANAGE_ORGANIZATION` holder only; their `CREATE`
  and `DELETE` entries list every key in `changes`. Record entries, their `changes` and the operation CHECK are
  unchanged. Tested by `AdminAuditApiTest`, `AuditQueryServiceAdminTest` and `AdminSnapshotsTest`.

**Because an incident is followed across rows and services**

- **D35. A correlation id and the source of every change.** The original had no correlation id and could not say what
  wrote an audit row. Now every response carries `X-Correlation-Id`, a `401` or `403` from the security chain included:
  the request's own when it sent exactly one value matching `^[A-Za-z0-9._-]{1,64}$`, a generated UUID otherwise (a
  malformed or oversized value is replaced, never echoed). Every new audit entry stores it and a `source` set by code
  only: `api` for a write made while serving a request, `platform` for `RecordService.asPlatform`, the app's own label
  for `asPlatform(…, source = "…")`, `automation:<rule>` for a rule's actions (which keep the triggering request's id
  through the queue), `app` otherwise ([ADR-050](0050-correlation-id-and-change-source-on-audit-rows.md)). Observable
  changes: the response header; `correlationId` and `source` on `/api/audit` and history entries, left out when null
  (every entry written before); `GET /api/audit?correlationId=&source=` filters, under the same read rules. Two
  nullable `audit_log` columns and `automation_runs.correlation_id` are known schema-parity deviations. Tested by
  `CorrelationIdWebFilterTest`, `ChangeOriginTest`, `RecordServicePlatformTest`, `AutomationDispatcherTest`,
  `AuditOriginApiTest` and `AutomationOnlyApiTest`.

**Because two people edit the same record**

- **D36. ETag, If-Match and PATCH on records.** The original's record `PUT` was a full replace with the last writer
  winning, and nothing answered a version. Now `GET`, `POST`, `PUT` and `PATCH` of a record and a workflow transition
  answer `ETag: "<updatedAt>"`; `PUT`, `PATCH`, `DELETE` and a transition take `If-Match` and write only while the
  record still carries one of its strong tags, compared in the write's own statement: `412` problem+json naming
  `If-Match` when the caller still reads the record, `404` when it is gone or out of reach, nothing stored or audited;
  a malformed `If-Match` is a `400` on it; none, or `*`, is the old write
  ([ADR-051](0051-optimistic-locking-and-partial-update-of-records.md)). The new
  `PATCH /api/objects/{object}/records/{id}` writes only the attribute keys sent (`null` clears), under every rule of
  `PUT`, and answers `400` on a key that is no attribute and `403` on an `editable: false` field, where `PUT` ignores
  both. `updated_at` is now set by the statement's `clock_timestamp()`, not the transaction's `now()`, so two writes in
  one transaction get different `updatedAt` values. CORS exposes `ETag`. Tested by `RecordETagTest`,
  `RecordServicePreconditionTest`, `PhysicalTableRecordStoreTest`, `RecordPreconditionApiTest` and
  `WorkflowOnlyApiTest`.

**Because an auditor asks about a period and a person**

- **D37. The audit list pages and narrows by period and user.** The original answered the newest rows of the tenant,
  500 at most, with no period and no user filter. Now `GET /api/audit` takes `from` and `to` (ISO-8601 instants,
  `occurred_at >= from AND occurred_at < to`), `userId` and `serviceAccount`, and `after=<cursor>`; the record history
  takes `from`, `to`, `userId` and `after` ([ADR-052](0052-audit-pages-by-cursor-period-and-user.md)). Observable
  changes: the body stays a JSON array, and `X-Next-Cursor` carries the next page's cursor when another row follows,
  exposed to browsers by core's CORS default; a malformed `from`, `to`, `userId` or `after`, or a cursor of another
  filter set, is a `400` naming the parameter; a read-scoped caller may get a short or empty page before the last. A
  request without the new parameters answers the same body. The index `audit_log_user_time_idx` is a known
  schema-parity deviation. Tested by `AuditPagingTest`, `WasichaiAutoConfigurationTest` and `AuditPagingApiTest`.

**Because a client builds its navigation from what the caller may do**

- **D38. Tenant-wide capabilities in the caller's permissions.** The original's `GET /api/auth/me/permissions`
  answered `admin` and `objects` only, so a role granted `MANAGE_METADATA` or `MANAGE_ORGANIZATION` with no object
  looked like one granted nothing. Now the answer also carries `capabilities`, always present: the object-less
  built-in actions the caller holds with no object, in a fixed order (`MANAGE_METADATA`, `MANAGE_ORGANIZATION`),
  answered by the check the services enforce, so `ADMIN` holds both and a service account never holds
  `MANAGE_ORGANIZATION` ([ADR-053](0053-the-caller-is-told-their-tenant-wide-capabilities.md)). `admin` and `objects`
  are unchanged. Tested by `CallerPermissionsServiceTest`, `PermissionEnforcementTest` and `ServiceAccountApiTest`.

**Because the audit trail is evidence**

- **D39. `audit_log` is append-only in the database.** The original's `audit_log` was an ordinary table its own
  credential could rewrite. Now two triggers refuse every `UPDATE`, `DELETE` and `TRUNCATE` on it, for every role,
  with `42501` and `audit_log is append-only: <operation> is not allowed`
  ([ADR-054](0054-audit-log-is-append-only-in-the-database.md)). Two exceptions keep behaviour: a foreign-key action
  that only nulls `document_id` (deleting a document or a tenant still works), and a purge (`DELETE`, `TRUNCATE`) in a
  transaction that set `wasichai.audit.purge = 'on'`, from a login as the role the new property
  `wasichai.audit.purge-role` names (none by default). At startup a `WARN` says when the role wasichai runs as could
  drop the triggers. No API answer changes. The functions `audit_log_guard()` and `audit_log_purge_role()` and the
  triggers `audit_log_append_only` and `audit_log_no_truncate` are known schema-parity deviations. Tested by
  `CoreMigrationSqlTest`, `WasichaiMigrationsTest`, `AuditLogOwnershipCheckTest`, `AuditLogAppendOnlyApiTest`,
  `AdminAuditApiTest` and `DocumentsOnlyApiTest`.

**Because a metadata author's default must mean something**

- **D40. A field's `defaultValue` is applied on create, and checked when it is set.** The original stored and answered
  `defaultValue` and never used it: a create that left the field out stored `NULL`, or failed on a `required` one,
  and a `required` field with a default that the caller may not write passed the create check and then failed on `NOT
  NULL`. Now every create (`POST`, `RecordService.create`, `asPlatform`, an automation's `CREATE_RECORD`) fills each
  attribute key it leaves out with the field's default, parsed by the field type's handler; a key sent, `null`
  included, wins. The default is written even where the caller's field permissions or `editable: false` forbid writing
  the field; a `RELATION` default is checked like a sent value, in the caller's read scope. A default the type cannot
  parse is a `400` naming `defaultValue` on field create, and a stored one that no longer parses is a `400` on the
  field at record create. `PUT …/fields/{field}` takes `defaultValue` (blank clears it), and new `enumOptions` that
  leave the default out are a `400` naming `enumOptions`; a blank default is none everywhere. Updates never apply a
  default, existing records are not touched, and the column gets no SQL `DEFAULT`. Tested by `FieldDefaultsTest`,
  `RecordServiceDefaultsTest`, `FieldDefaultValueApiTest` and `AutomationOnlyApiTest`.

**Because one deployment serves several customer organizations**

- **D41. Creating and deleting tenants takes `MANAGE_TENANTS`.** The original guarded `POST /api/organizations` and
  `DELETE /api/organizations/current` with `MANAGE_ORGANIZATION`, which `ADMIN` always passes, so a customer's
  administrator could create and delete tenants. Now both check `MANAGE_TENANTS`, a new object-less built-in action
  ([ADR-055](0055-tenant-provisioning-apart-from-tenant-administration.md)). With
  `wasichai.organizations.separate-provisioning=false`, the default, it means `MANAGE_ORGANIZATION` and both routes
  behave as before. With `true`, only a role's `MANAGE_TENANTS` grant counts: `ADMIN` and `MANAGE_ORGANIZATION` get
  `403`, and a service account is refused either way. Observable changes, also by default: the action is accepted by
  `PUT /api/roles/{name}/permissions`, where the original answered `400`, but only from a caller whose roles hold it
  (`403` otherwise) and only with no object (`400` naming `objectName` otherwise); an object may not declare an action
  of that name; `capabilities` in `GET /api/auth/me/permissions` lists it third, so the administrator's list is
  `MANAGE_METADATA`, `MANAGE_ORGANIZATION`, `MANAGE_TENANTS` by default. `PUT /api/organizations/current` and the
  `ADMIN` role created by provisioning are unchanged. The `V15` constraints are known schema-parity deviations. Tested
  by `CurrentUserTest`, `CallerPermissionsServiceTest`, `WasichaiAutoConfigurationTest`, `ObjectActionNameTest`,
  `SeparateProvisioningApiTest`, `OrganizationApiTest` and `PermissionEnforcementTest`.

**Because a relationship may join an object to itself**

- **D42. A self-relationship is read from either end.** The original accepted a relationship whose source and target
  are the same object, then decided the end of every read from the object alone, which is both: `GET
  …/records/{id}/related/{relationship}` always walked one end (so a parent's children, or the sources linked to a
  record, could not be read), and `GET /api/objects/{object}/relationships` listed one side. Now the related read takes
  `direction`: `forward` (the default) is exactly the walk the read always made, from the source end and, for
  `ONE_TO_MANY`, from the target end that holds the key (the record's parent); `inverse` walks the other end. Both keep
  the read's paging, `count=false` and `after=` unchanged and the same permissions, field permissions, own-records-only and
  read scope (D30). `inverse` on any other relationship, and any value other than the two, is a `400` naming
  `direction`; on this route `direction` is no longer read as a filter on a field of that name. The listing answers a
  self-relationship twice, forward then inverse, each with `direction` and the `label` and `many` of what that direction
  reads (`label` from the source end, `inverseLabel` or the plural label from the target end); any other entry is
  unchanged and has no `direction` key. No default read changes. The listing's one entry for a `ONE_TO_MANY`
  self-relationship used to say `label` and `many: true` while the read returned the parent; its forward entry now says
  `inverseLabel` and `many: false`, what the read returns. Link, unlink and a generated page's one related tab per relationship are
  unchanged; the agent's `list_relationships` reports `direction` and `related_records` takes it. Tested by
  `SelfRelationshipApiTest`, `RelationshipSideTest`, `RecordReadScopeApiTest`, `PageServiceTest` and
  `AgentToolCatalogTest`.

**Because an organization decides what reaches a model provider**

- **D43. The assistant reports its token usage, and an app can switch it off per caller.** The original's answer to
  `POST /api/agent/ask` was `answer`, `steps` and `truncated`. It gains `usage` (`model`, `inputTokens`,
  `outputTokens`), summed over the run's model calls, whenever the provider reported them, which a real one does; the
  key is left out when none were reported, so with the scripted test models the JSON is unchanged. With an app's
  `AgentAccessPolicy` that denies a caller, `GET /api/agent/status` answers `enabled: false` for them although a key is
  configured, and `ask` answers `403` with the policy's reason before the model is called (the original had no such
  answer). With an app's `AgentResultFilter` the tool results and `steps[].summary` are what the filter returned, and a
  filter that throws makes `ask` answer `500` "could not prepare the data for the model; nothing was sent" (or the
  filter's own error status). With no such bean, status and every error are as before
  ([ADR-056](0056-what-reaches-the-model-is-the-apps-to-shape.md)). Tested by `AgentExtensionsTest`,
  `WasichaiAgentAutoConfigurationTest`, `AgentAccessPolicyApiTest` and `AgentEmbabelTest`.

**Because a token must die with the access behind it**

- **D44. Revocation, sign-in limits and a password policy.** The original's tokens were stateless until `exp`, it
  had no logout, counted no sign-in attempts and knew one password rule, 8 characters. Now every token carries a
  `jti` claim, and `POST /api/auth/logout` answers `204` (authenticated). With `wasichai.security.jwt.revocation=true`
  a token issued before its user's marker (`users.tokens_valid_after`, core `V17`) is `401`: the marker moves when an
  administrator disables a user, sets their password or roles, or a service account is disabled, re-roled or rotated,
  and on logout; a deleted user's or account's token is `401` too; at once on the node of the change, within
  `wasichai.security.jwt.revocation-cache` elsewhere; a token issued after the change works at once. With
  `wasichai.security.login.enabled=true`, too many failed attempts on `POST /api/auth/login` (per email and client
  address, and per email) or `POST /api/auth/token` (per client id) are `429` with `Retry-After`, also for the right
  password, alike for known and unknown emails. A configured `PasswordPolicy` answers `400` with one `errors` entry per
  broken rule on `password` (`adminPassword` on provisioning), detail `Password does not meet the password policy`
  unless the one failure is the length, which keeps `Password too short`
  ([ADR-059](0059-token-revocation-login-limits-and-password-policy.md)). With none of the properties set, everything
  else answers as before. The column is a known schema-parity deviation. Tested by `TokenRevocationApiTest`,
  `LoginThrottleApiTest`, `PasswordPolicyApiTest`, `AuthDefaultsApiTest`, `LoginThrottleTest`, `PasswordPolicyTest`,
  `JwtServiceTest` and `WasichaiAutoConfigurationTest`.

**Because a client may send a create again**

- **D45. A record create takes an `Idempotency-Key`.** The original had no idempotency: a `POST
  /api/objects/{object}/records` sent twice, after a timeout or a `5xx`, created two records. Now the request may carry
  `Idempotency-Key` (1 to 128 printable ASCII characters, a `400` on the header otherwise), kept per organization and
  caller for `wasichai.idempotency.ttl` (24 hours) in the new core table `idempotency_keys`, written in the record's
  transaction ([ADR-058](0058-idempotency-key-on-record-creation.md)). The first request answers as before, from the
  stored bytes. The same key with the same method, path and body answers the stored `201` and body again with
  `Idempotent-Replayed: true` and the stored record's `ETag`, and writes, audits and announces nothing; with another
  body or object it is a `422` naming `Idempotency-Key`; while the first is still running it is a `409` with
  `Retry-After: 1`. A failed first request stores nothing. With the key, a listener that fails after the write rolls
  the record back too. Another caller's same key is independent. CORS exposes `Idempotent-Replayed` and `Retry-After`.
  Without the header nothing changes. The table is a known schema-parity deviation. Tested by `IdempotencyKeysTest`,
  `WasichaiAutoConfigurationTest` and `RecordIdempotencyApiTest`.

**Because people are told outside the app too**

- **D46. Notifications reach people by email, and an automation can notify.** The original had no notifications
  (D32). With `wasichai-notifications` installed, `GET` and `PUT /api/auth/me/notification-preferences` say which
  kinds reach the caller on each delivery channel of the app (a map; every kind by default; `{}` without channels; a
  key that is not a channel, or a value that is not a list of kinds, is `400`; a service account `403`). With
  `wasichai.notifications.email.enabled=true` and a mail sender, news (a notification created, reopened, or whose kind
  changed) is also queued per person and sent by email within `delivery-interval`, retried with backoff, then `FAILED`
  with its error, once per cluster, never failing the write that published it. Automations gain the action `NOTIFY`
  (`to`, `title`, `body`, `kind` `INFO` or `WARNING`), refused with a `400` when saved without the module. The source
  prefix `automation:` and the loop key `deliveries` are reserved like `manual`, `rule:`, `purge` and `rules`: an app's
  source of that name is refused. Without the module every route above is a `404` (D1); without channels nothing is
  queued or sent and every other answer is as before ([ADR-060](0060-delivery-channels-and-automation-notify.md)). The
  `notification_deliveries` and `notification_preferences` tables are a known schema-parity deviation. Tested by
  `DeliveriesTest`, `AutomationNotifyTest`, `DeliveryChannelsWiringTest`, `NotificationsOnlyApiTest`,
  `AutomationOnlyApiTest` and `AutomationApiTest`.

**Because evidence is a file**

- **D47. `FILE` and `IMAGE` fields, with upload and download routes.** The original had no file type and no
  multipart route. With wasichai-files installed, a `FILE` or `IMAGE` field reads, in the record JSON and the audit
  `before`/`after`, as `{id, name, contentType, size, sha256}`; its JSON carries `file: {maxBytes, contentTypes}`.
  `POST /api/objects/{object}/records/{id}/files/{field}` (multipart part `file`) replaces the file as a `PATCH` of
  the field: `200` with the record and its `ETag`, and every record write answer (`403` without `UPDATE` or on an
  `apiOnly` object, `404` for a record out of reach, `400` on a field the caller cannot write, `409` on an
  `appendOnly` object, `400` on `reason` without `X-Change-Reason` where it is required, `412` on a stale
  `If-Match`). `GET` on the same path streams the bytes for a caller who may read the record and the field (`403`,
  `404` otherwise), `IMAGE` inline and anything else as an attachment, always with `X-Content-Type-Options: nosniff`.
  `POST /api/objects/{object}/files/{field}` stages an upload (`201`, the descriptor) whose `id` a create may send as
  the field's value. Over `maxBytes`, empty or of a type the field refuses (sniffed from the bytes): `400` on the
  field, nothing stored. A record body may set a file field only to `null`, the file it holds, or the caller's own
  recent upload for it; anything else is a `400` on the field. Without the module the routes are `404` (D1) and the
  types unknown ([ADR-061](0061-file-and-image-fields-with-a-storage-spi.md)). Its table, columns, function and the
  longer type list are known schema-parity deviations. Tested by `FilesApiTest`, `S3FilesApiTest`,
  `FilesOnlyApiTest`, `FileFieldTypeTest`, `StoredFileGuardTest`, `ContentSnifferTest`, `LocalFileStoreTest` and
  `WasichaiFilesAutoConfigurationTest`.

**Kept on purpose, although they look like candidates.** Sections such as geometries stay out of audit diffs and
automation payloads ([ADR-019](0019-a-geometry-is-a-field.md)). `RecordService` still opens no transaction of its
own ([ADR-025](0025-extension-spis.md)). A `MULTI*` geometry field still cannot be drawn in the UI, because the draw
mode is single-part ([docs/modules/gis.md](../modules/gis.md), "Known limitations").

## Consequences

- "No functional change" is testable: the ported integration tests assert the original behaviour everywhere except
  these entries, and each entry has its own test.
- A future difference needs a new entry here, or it is a regression.
