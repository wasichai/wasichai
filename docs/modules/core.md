# Core module

An app that installs core gets identity and login, organizations (the tenant), Custom Objects and Fields, dynamic
records and related records, relationships, caller permissions, audit and history, and the admin screens for users,
roles and permissions. Every other module builds on it; core itself depends on nothing else in wasichai.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter")
```

The starter is core plus what an app runs on: the R2DBC and JDBC PostgreSQL drivers, Flyway, and actuator
(`/actuator/health` is already public in core's security chain).

```bash
yarn add @wasichai/core @wasichai/ui
```

```tsx
<WasichaiApp config={{ apiBaseUrl: '/api', appName: 'My app', storagePrefix: 'myapp' }} modules={[]} />
```

See [../guides/build-your-app.md](../guides/build-your-app.md).

Core has no `wasichai.core.enabled` switch: every module has one, core is the base they stand on
([ADR-024](../adr/0024-libraries-and-starters.md), [ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D8).

### Your app

Never put the app's main class in package `wasichai` or below it: `@WasichaiApplication`'s (and plain
`@ConfigurationPropertiesScan`'s) component scan starts at the app's own package, so a scan that reaches into
`wasichai.*` would register the library's controllers a second time ([ADR-024](../adr/0024-libraries-and-starters.md)).
An app overrides any core bean by declaring its own bean of the same type — see "Extension points" below.

## What it adds

- Identity: login and the signed-in user, under `/api/auth` (`POST /api/auth/login`, `GET /api/auth/me`,
  `GET /api/auth/me/permissions` for caller permissions).
- Organizations: the tenant itself, under `/api/organizations`.
- Custom Objects and Fields: object and field metadata, under `/api/objects` and `/api/metadata/objects` (system
  fields under `/api/metadata/system-fields`), the 12 scalar field types and the `FieldTypeRegistry`.
- Relationships: `/api/relationships`, plus the related-record routes nested under `/api/objects/{object}`.
- Dynamic records and related records: `/api/objects/{object}/records`.
- Audit and history: `/api/audit` and `/api/objects/{object}/records/{id}/history`.
- Admin: users and roles, under `/api/users` and `/api/roles`.
- Background work: `RecordService.asPlatform(organizationId) { }` for writes with no user, and the `ClusterLock` bean
  (`tryLock`, `withXactLock`) over PostgreSQL advisory locks
  ([ADR-039](../adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)).

See [../api/rest.md](../api/rest.md) for the full method-by-method table.

Screens (`packages/core/src/app/coreModule.ts` in
[wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/core)): login (public), the dashboard, the
objects list and builder, relationships, the record list/form/detail pages, users, roles, permissions and audit. Nav
groups: `data` (order 10), `builder` (30, filled by other modules), `automation` (40, filled by other modules) and
`administration` (50) — modules that add screens of their own place them in these same groups.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.database.host` | `localhost` | PostgreSQL host |
| `wasichai.database.port` | `5432` | PostgreSQL port |
| `wasichai.database.name` | `wasichai` | database name |
| `wasichai.database.username` | `wasichai` | database user |
| `wasichai.database.password` | `wasichai` | database password |
| `wasichai.database.metadata-schema` | `wasichai` | schema Flyway owns: identity, organizations, metadata |
| `wasichai.database.data-schema` | `app_data` | schema holding one physical table per custom object (ADR-004) |
| `wasichai.database.migrate` | `true` | `false` when the app runs migrations another way |
| `wasichai.metadata.reconcile-indexes` | `true` | at startup, build the declared and relation indexes data tables lack (ADR-036) |
| `wasichai.security.jwt.secret` | *(none)* | HS256 signing key, at least 32 bytes; required, a library must not ship one that works |
| `wasichai.web.problem-base-uri` | `https://wasichai.dev/problems` | RFC 7807 `type` base; the full type is this plus `/<status>` |
| `wasichai.web.cors-allowed-origin-patterns` | `["http://localhost:*"]` | browser origins the API answers |
| `wasichai.seed.dev` | `false` | `true` adds the dev seed migration (see "Database") |

The index reconciliation runs in the `ApplicationReadyEvent` listener, so it holds readiness while it builds. On the
first start after an upgrade that adds relation indexes to existing tables, a large table can take a while: give a
Kubernetes startup probe room for it, or switch the reconciliation off and build the indexes another way. Later
starts find nothing missing and cost one catalog read.

`wasichai.security.jwt.issuer` (default `wasichai`) and `wasichai.security.jwt.ttl` (default 8 hours) are also read from
`JwtProperties` but rarely need changing. `metadata-schema` and `data-schema` must differ and are validated as
plain identifiers at boot.

Without any YAML, `WasichaiEnvironmentPostProcessor` adds lowest-precedence defaults: `wasichai.database.*` from
`WASICHAI_DB_HOST`, `WASICHAI_DB_PORT`, `WASICHAI_DB_NAME`, `WASICHAI_DB_USERNAME`, `WASICHAI_DB_PASSWORD` (env names, not a
mechanical `WASICHAI_DATABASE_*` transform of the property path); `spring.r2dbc.url`/`username`/`password` built from
those; an R2DBC pool of 5 to 20 connections; and `spring.webflux.problemdetails.enabled=true`. It never sets
`wasichai.security.jwt.secret` — only `WASICHAI_JWT_SECRET` does, and an app that sets neither fails at boot instead of
starting with a usable default.

## Security

Core declares one `SecurityWebFilterChain` at `@Order(0)`, `@ConditionalOnMissingBean`: Spring Boot's reactive
resource-server auto-configuration always contributes a catch-all chain of its own, so core's must win on order,
not by being the only one. Public paths are `/api/auth/login`, `/api/health`, `/actuator/health/**` and every
`OPTIONS` request; everything else needs a valid token. An app that declares its own `SecurityWebFilterChain` bean
replaces core's chain entirely, public paths and CORS included. A module that needs a chain next to core's declares
it in an auto-configuration that runs after core's, with its own `securityMatcher` and an `@Order` below `0`.

The signing key is a `WasichaiJwtKey` bean, wrapping the raw `SecretKey` in its own type so an app's unrelated
`SecretKey` bean can never become the JWT key by accident, and injection never turns ambiguous. An app that wants a
different key declares its own `WasichaiJwtKey` bean instead of setting the property.

`PasswordEncoder` is a `BCryptPasswordEncoder` bean, `@ConditionalOnMissingBean` like everything else here.

See [../security/authentication.md](../security/authentication.md) for the full authentication, tenancy and
authorization model.

## Extension points

**Defines** (core depends on no module, so it implements none of its own SPIs; see
[../architecture/overview.md#extension-spis](../architecture/overview.md#extension-spis) and
[ADR-025](../adr/0025-extension-spis.md)):

- `FieldTypeHandler` + `FieldTypeRegistry` — a field type's validation, storage and read shape; the registry checks
  for collisions at boot and holds core's 12 scalar types first, then every module's handler in `@Order`.
- `RecordQueryContributor` + `RecordCriterion` — a `WHERE` fragment a module adds to the record list query, always
  parenthesised before it joins the rest.
- `SystemColumnContributor` → `SystemColumns` — a reserved column name a module owns on every record table, so no
  custom field can take it.
- `RecordChangeListener` — runs synchronously, in `@Order`, inside the caller's own call right after a record write.
- `RecordWriteGuard` — runs in `@Order` right **before** every record write, on every route (record API, related
  records, `RecordService` as a user or as the platform, workflow transitions, automations); a throw aborts the write
  with nothing stored or audited. `RecordWriteGuards` calls them after refusing changes to an `appendOnly` object
  ([ADR-040](../adr/0040-append-only-objects-and-a-pre-write-guard.md)) and writes without a reason to a
  `requiresReason` one ([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)); `RecordWrite.reason` is the
  write's change reason.
- `ObjectRemovalListener`, `FieldUsage` — a module's veto or note when an object or field is about to be removed.
- `WorkflowStates` — the state a record is in, if any; core's default is `NoWorkflowStates`, a null object.
- `ModuleMigration` — one Flyway location and history table per module ([ADR-026](../adr/0026-per-module-migrations.md));
  core registers its own (`core`) and its opt-in dev seed (`core_seed`).

**Overridable beans:** every bean core declares is `@ConditionalOnMissingBean`, so an app replaces any of them by
declaring its own bean of the same type, grouped by the auto-configuration that owns them. The one exception is
`RecordWriteGuards`: `appendOnly` holds for everyone, so an app adds a `RecordWriteGuard` instead
([ADR-040](../adr/0040-append-only-objects-and-a-pre-write-guard.md)).

- Platform (`WasichaiPlatformAutoConfiguration`): `wasichaiSchemas`, `systemColumns`, `wasichaiMigrations`,
  `globalExceptionHandler`, `healthController`.
- Security (`WasichaiSecurityAutoConfiguration`): `wasichaiJwtKey`, `jwtDecoder`, `passwordEncoder`,
  `securityFilterChain`, `corsConfigurationSource`, `roleQueries`, `roleDirectory`, `currentUser`, `accessPolicy`,
  `userRepository`, `jwtService`, `authService`, `authController`.
- Metadata (`WasichaiMetadataAutoConfiguration`): `fieldTypeRegistry`, `customObjectRepository`,
  `customFieldRepository`, `relationshipRepository`, `objectSchemaManager`, `metadataService`, `relationshipService`,
  `metadataMapper`, `relationshipMapper`, `callerPermissionsService`, `objectController`, `objectMetadataController`,
  `systemFieldController`, `relationshipController`, `callerPermissionsController`.
- Data (`WasichaiDataAutoConfiguration`): `auditService`, `auditQueryService`, `auditController`, `workflowStates`,
  `recordStore`, `clusterLock`, `recordQueryParser`, `recordService`, `relatedRecordService`, `recordController`,
  `relatedRecordController`.
- Admin (`WasichaiAdminAutoConfiguration`): `adminService`, `userAdminController`, `roleAdminController`,
  `organizationRepository`, `organizationService`, `organizationController`.

`wasichaiCoreMigration` and `wasichaiCoreSeedMigration` are the two exceptions: they register `ModuleMigration` values
into an ordered list, not a single replaceable bean, so they carry no `@ConditionalOnMissingBean`.

## Database

Migration location `classpath:db/wasichai/core`, history table `flyway_history_core`, order `0`
([ADR-026](../adr/0026-per-module-migrations.md)). Creates the `pgcrypto` extension `WITH SCHEMA public` (shared by
every app in the database, so it outlives any one app) and, when the server ships it, `pgvector` the same way.
Tables: `organizations`, `users`, `roles`, `user_roles`, `custom_objects`, `custom_fields`, `relationships`,
`permissions`, `field_permissions`, `audit_log`. `V3__declared_indexes.sql` adds `custom_fields.indexed` and
`custom_objects.indexes` ([ADR-036](../adr/0036-declared-indexes-optional-count-and-keyset-reads.md)). The indexes
themselves sit on each data table, built by `ObjectSchemaManager`. A declared index is named
`<physical table>_ix_<hash of its columns>`. `V4__unique_constraints.sql` adds `custom_objects.unique_constraints`
([ADR-037](../adr/0037-composite-unique-constraints-and-409-on-repeats.md)), whose constraints are named
`<physical table>_uq_<hash of its columns>`.

The opt-in dev seed, `classpath:db/wasichai/core-seed` (history table `flyway_history_core_seed`, order `10`), runs
only with `wasichai.seed.dev=true` and inserts a demo organization, an `ADMIN` role with every permission, and the
user `admin@wasichai.local` / `admin`
([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D6, [ADR-030](../adr/0030-rebrand-sapgis-to-chawpi.md)).
The password hash is written out as a literal `$2a$` BCrypt hash instead of calling pgcrypto's `crypt()`, because
with several schemas in one database the extension resolves from whichever schema created it first.

## Frontend package

`@wasichai/core`: `WasichaiApp`, the config type and defaults from `config.ts` (`apiBaseUrl` `/api`, `appName` unset,
`storagePrefix` `wasichai`, `defaultLoginEmail` `''`, plus `languages`, `appTagline` and `basename`), the registry
types (`WasichaiModule` and its contribution types), `createRegistry`, `useWasichaiLinks` (route building, `to()`/`has()`
for module routes, and typed helpers for every core screen) and `useAuth`. i18n namespace `core`; core's own strings
are always reachable through `fallbackNS`. `QueryState` draws a react-query result as loading, an error (with a retry
that refetches) or its data; `LoadingState`, `EmptyState` and `ErrorState` (a 404 or 403 shows no retry) are its
parts, usable on their own.

`@wasichai/ui`: the shared primitives (`Button`, `Card*`, `Dialog*`, `Input`, `Textarea`, `Label`, `Select*`,
`Table`/`Th`/`Td`/`Badge`, `Tabs`, `ConfirmDialog`, `Pagination`/`PageSizePagination`, `PdfDialog`), the `cn()` class
merger, and the Tailwind 4 theme (`theme.css`). An app's Tailwind entry point consumes it as:

```css
@import 'tailwindcss';
@import '@wasichai/ui/theme.css';
@source '../node_modules/@wasichai';
```

`@source` must see every `@wasichai/*` package's class names, not only `@wasichai/ui`'s own.

**Themes.** `<html>` carries `data-theme="<id>"`; `theme.css` defines the tokens for the two built-in themes,
`light` and `dark`, and every package's Tailwind classes (`bg-surface`, `text-ink`, …) resolve against whichever
theme is active. `WasichaiConfig.themes?: ThemeDefinition[]` (`{ id, label, colorScheme: 'light' | 'dark' }`, `label`
an i18n key) adds an app's own themes to the two built-ins; an id that repeats a built-in one throws in
`resolveConfig`. An app adds a theme with one CSS block that sets every token (all 28, the ten extension tokens
`success-soft` to `map-selected` included; `--font-sans` and the radii may be set too), e.g.
`[data-theme='high-contrast'] { --surface: …; /* every token in theme.css */ }`, and one entry in `config.themes` —
no package needs a change. The primitives carry `data-slot` hooks (`button` with `data-variant` and `data-size`,
`input`, `select-trigger`, `table-cell`, `tabs-trigger`, …) that a theme's CSS can style and light and dark leave
alone ([ADR-035](../adr/0035-theme-extension-tokens-slots-and-optional-sheets.md)). The library ships one optional
theme, never built in: an app that wants it imports `@wasichai/ui/themes/portal-tributario.css` right after
`@wasichai/ui/theme.css` and adds `PORTAL_TRIBUTARIO_THEME` from `@wasichai/core` to `config.themes`. `useTheme()`
returns `{ preference, theme, colorScheme, themes, setPreference }`: `preference` is what the user picked, including
`system`, which resolves through `prefers-color-scheme`; `theme` is what is actually applied. To avoid a flash of the
wrong theme before the bundle loads, an app's `index.html` reads the stored theme before its module script runs
(`wasichai` below is `storagePrefix`); `schemes` lists every theme id the app offers, with its color scheme:

```html
<script>
  // before the bundle: apply the stored theme so a dark user never sees a light flash. `wasichai` = storagePrefix.
  // every theme the app offers, id -> color scheme: light, dark and each config.themes entry.
  // system and an unknown id (an old build, another app's theme) follow the os, as core's resolveTheme does
  try {
    const schemes = { light: 'light', dark: 'dark', 'portal-tributario': 'light' }
    const stored = localStorage.getItem('wasichai.theme')
    const dark = matchMedia('(prefers-color-scheme: dark)').matches
    const theme = stored && Object.hasOwn(schemes, stored) ? stored : dark ? 'dark' : 'light'
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = schemes[theme]
  } catch {}
</script>
```

See [`packages/core` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/core) and
[`packages/ui` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/ui) for the full API.

## Without this module

Core is always installed.

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md):

- D1: a route of a module that is not installed answers `404` to an authenticated caller and `401` without a token,
  never `403`.
- D2: any edit of a field whose type's module is not installed answers `409` — the field is revalidated against its
  handler on every edit.
- D6: the dev seed user is created only with `wasichai.seed.dev=true`, as `admin@wasichai.local`.
- D8: core has no `wasichai.<module>.enabled` switch; every other module has one.
- D9: link and unlink of related records now hold both records to the record API's rules, with `404` instead of an
  unchecked write, and one `UPDATE` history row on each.
- D10: the audit "after" snapshot stores every field, not just the caller's writable projection.
- D11: a `401` in the middle of a session signs the user out and returns to the login page, instead of only dropping
  the token.
- D12: the record detail page falls back to the default page only on a `404` from the custom page, not on any error.
- D13: a `NAVIGATE` page action with no target links to the objects list, instead of `/undefined`.
- D14: the login form's email is empty by default, configurable with `WasichaiApp` `config.defaultLoginEmail`.
- D16: field type names are trimmed before matching, so `" text "` is `TEXT`.
- D21: every record list and the audit list end their `ORDER BY` with `id`, so rows tied on the sort key keep a stable order.
- D22: declared and relation indexes, two new metadata columns, `count`/`after` reserved, `nextCursor` on pages
  (ADR-036).
- D23: composite unique constraints, a new metadata column, and a repeated unique value as a `409` naming its fields
  instead of a `500` (ADR-037).
- D24: append-only and api-only objects, two new metadata columns, and a `RecordWriteGuard` SPI that can veto any record
  write (ADR-040).

## Known limitations

None.
