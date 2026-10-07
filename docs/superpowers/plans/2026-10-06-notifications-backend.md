# Notifications and organizational units (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** wasichai tells people what needs doing: notifications (INFO, WARNING, ACTION) addressed to everyone, a user,
a role or an organizational unit, within a window, with a link to a record and tab, a route or a URL; produced by
people, app code, scheduled app sources and date rules; resolved when no longer true; delivered live over SSE.

**Architecture:** core gains organizational units (V9) and two directory ports. pages gains a TAB key. A new opt-in
module `wasichai-notifications` owns five tables, a public Kotlin API (`Notifications`, `NotificationSource`), REST for
admins and for "me", a reconcile loop, date rules, and an SSE stream fed by `LISTEN/NOTIFY`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux (coroutines, Reactor for the stream), R2DBC `DatabaseClient`,
r2dbc-postgresql (`LISTEN`), Flyway, JUnit 5, Testcontainers, `WebTestClient`, `StepVerifier`.

**Spec:** `docs/superpowers/specs/2026-10-06-notifications-design.md`. Every contract below (tables, routes, bodies,
validation, upsert table, visibility predicate) is the spec's; a task never invents a variant.

## Global Constraints

- Branch `claude/stoic-ritchie-86ufng`, based on `origin/dev`; the PR targets `dev`. Released as 0.4.0.
- `CLAUDE.md` rules: SQL through `DatabaseClient` with bound values; `${schemas.metadata}.<table>` for fixed tables;
  every query filters by `organization_id`; core never names a module (`CoreArchitectureTest`); beans in
  auto-configurations, `@ConditionalOnMissingBean` (not the migration bean); stereotypes stay on library classes that
  need proxies.
- `.editorconfig` is law (160 columns). `./gradlew ktlintFormat` before every commit. Conventional Commits with the
  module as scope (`feat(core)`, `feat(pages)`, `feat(notifications)`, `test(it)`, `docs`).
- Comments in English, caveman style: short, say why.
- Test first: write the failing test, see it fail, implement, see it pass.
- Integration tests are tagged `integration` (`WasichaiIntegrationTest`); they run with Testcontainers or
  `WASICHAI_TEST_DB_*`, never on port 5432. Each test names its own objects, units, roles and users (shared database).
- Bind arrays with `= ANY(:x)`, never `IN (:list)`. Use the injected `Clock` for "now" in the module.

## Review Focus

1. **Tenant isolation:** every notifications and org-unit query filters by organization; receipt actions use the
   visibility predicate, so another tenant's id, or one not addressed to the caller, is a `404`.
2. **Links:** a `RECORD` link is dropped for a reader without `READ` on its object, and when the object is gone.
3. **Stream:** the token is never read from the URL; the stream completes at `exp`; the user is resolved before the
   `Flux` is built.
4. **Upsert:** unchanged fingerprint writes nothing; receipts reset only on reopen or kind change; racing writers on a
   key end with one row.
5. **Once per cluster:** a source runs once per interval across replicas (`tryLock` plus the due re-check).
6. **Boundaries:** core names no module; without the module every notifications route is `404`.
7. **Schema parity:** every new table appears in the parity deviation list citing D31 or D32.

---

### Task 1 — `feat(core)`: organizational units, directories, `unpooled`

**Files:**
- Create `wasichai-core/src/main/resources/db/wasichai/core/V9__org_units.sql` (spec A, "Tables").
- Create `wasichai-core/src/main/kotlin/wasichai/core/identity/OrgUnits.kt` (`OrgUnitRef`, `OrgUnitDirectory`).
- Create `wasichai-core/src/main/kotlin/wasichai/core/identity/UserDirectory.kt`.
- Create `wasichai-core/src/main/kotlin/wasichai/core/platform/Connections.kt` (`object Connections { fun unpooled(...) }`).
- Modify `platform/ClusterLock.kt` (use `Connections.unpooled`), `organization/Organization.kt` (`ids()`),
  `autoconfigure/WasichaiSecurityAutoConfiguration.kt` (two beans), `test/.../architecture/CoreArchitectureTest.kt`
  (`notifications` in the module regex, `notification\w*` in the table regex).

**Tests:**
- `wasichai-core/src/test/kotlin/wasichai/core/platform/OrgUnitsMigrationSqlTest.kt` (unit, reads the SQL text):
  tables, checks, composite parent key, placeholders only.
- `wasichai-core/src/test/kotlin/wasichai/core/api/OrgUnitDirectoryTest.kt` (integration): closure holds direct units
  and ancestors, several units, another org isolated; `idsByEmail` is case-insensitive and leaves out disabled users
  and service accounts; empty inputs answer empty.
- `ClusterLockTest` and `CoreArchitectureTest` stay green.

- [ ] Write the tests; run `./gradlew :wasichai-core:test --tests '*OrgUnits*'` and see them fail.
- [ ] Implement; `./gradlew :wasichai-core:test :wasichai-core:integrationTest --tests '*OrgUnit*' --tests '*ClusterLock*'`.
- [ ] `./gradlew ktlintFormat`; commit `feat(core): organizational units and user directories`.

### Task 2 — `feat(pages)`: a TAB has a key

**Files:** modify `wasichai-pages/src/main/kotlin/wasichai/pages/Page.kt` (`key` with `@field:JsonInclude(NON_NULL)`),
`PageService.kt` (request field, validation, normalisation, `toRequest()` keeps keys), `PageGeneration.kt` (generated
tabs keyed by title; module tabs too).

**Tests:** `PageServiceTest` (normalised; bad format `400`; key on a non-TAB `400`; repeated key across nested strips
`400`; a `PUT` without definition keeps keys), `PageGenerationTest` (DETAILS, RELATED, HISTORY and a module tab carry
keys), `PageComponentJsonTest` (no `key` property when null), and in `wasichai-integration-tests` `pagesIt`
`PageApiTest` (generated page JSON has keys).

- [ ] Tests first; implement; `./gradlew :wasichai-pages:test`; ktlintFormat; commit `feat(pages): a tab has a key`.

### Task 3 — `feat(notifications)`: module skeleton

**Files:**
- Create `wasichai-notifications/build.gradle.kts` (plugins `wasichai.spring-module`, `wasichai.publishing`,
  `wasichai.integration-test`; `api(project(":wasichai-core"))`; `compileOnly(libs.r2dbc.postgresql)`;
  `testImplementation(project(":wasichai-test"))`; `testRuntimeOnly(libs.r2dbc.postgresql)`).
- Create `starters/wasichai-spring-boot-starter-notifications/build.gradle.kts` (as the automation starter).
- Create `wasichai-notifications/src/main/kotlin/wasichai/notifications/autoconfigure/NotificationsProperties.kt`
  (spec B, property table) and `WasichaiNotificationsAutoConfiguration.kt` (migration bean only for now).
- Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Create `src/main/resources/db/wasichai/notifications/V1__notifications.sql` (spec B, "Tables").

**Tests:** `NotificationsMigrationSqlTest` (tables, checks, unique key, placeholders), and
`WasichaiNotificationsAutoConfigurationTest` with `WasichaiContextRunner` (wires; `enabled=false` leaves no migration
bean; properties bind with their defaults).

- [ ] Tests first; implement; `./gradlew :wasichai-notifications:test`; commit `feat(notifications): module skeleton`.

### Task 4 — `feat(core)`: organizational units admin

**Files:** create `wasichai-core/src/main/kotlin/wasichai/core/admin/OrgUnitAdmin.kt` (DTOs, `OrgUnitService`
`@Service @Transactional`, `OrgUnitController`, `UserOrgUnitsController`); add `MyOrgUnitsController` to
`identity/OrgUnits.kt`; modify `admin/AdminService.kt` and `AdminDtos.kt` (`orgUnits` on `AdminUserResponse`),
`autoconfigure/WasichaiAdminAutoConfiguration.kt` and `WasichaiSecurityAutoConfiguration.kt` (beans).

**Tests:** `wasichai-core/src/test/kotlin/wasichai/core/api/OrgUnitApiTest.kt` (integration): CRUD; codes normalised;
repeated code `409`; unknown parent `400`; a non-admin and a service account `403`; another tenant's code `404`; move
under own subtree `400`; depth cap `400`; delete with children or members `409`; membership replace; unknown code
`400`; service account user `404`; `/api/auth/me/org-units` with paths; organization deletion with units. Extend
`AdminApiTest` for `orgUnits`.

- [ ] Tests first; implement; core tests green; commit `feat(core): organizational units admin`.

### Task 5 — `feat(notifications)`: model and validation

**Files:** create `Notification.kt` (the spec's public contract, verbatim, plus internal `StoredTarget`,
`PreparedNotification`, `InboxItem`, `NotificationSummary`, wire DTOs for link and audience with mapping functions),
`NotificationValidation.kt` (pure; collects `FieldViolation`s; strict and lenient recipient modes are the caller's),
`Fingerprint.kt`.

**Tests:** `NotificationValidationTest` (every row of the spec's validation table), `FingerprintTest` (stable for equal
content, independent of target order, a null `publishAt` stays null), `NotificationLinkJsonTest` (wire shapes).

- [ ] Tests first; implement; commit `feat(notifications): model and validation`.

### Task 6 — `feat(notifications)`: storage and audience

**Files:** create `NotificationRepository.kt` (insert, keyed upsert per the spec's table with `ON CONFLICT DO NOTHING`
then `FOR UPDATE`, replace targets, resolve, resolve all, open by source with keys, admin list and find, delete, purge,
`notify(org, user?)`), `InboxRepository.kt` (visibility predicate, page, summary, receipt upserts, read-all),
`AudienceResolver.kt` (strict and lenient; `UserDirectory`, `OrgUnitDirectory`, `RoleDirectory`),
`NotificationSignals.kt` (channel name; `pg_notify` helper; the hub is added in Task 11). Wire beans.

**Tests:** unit tests with fakes for `AudienceResolver`; the SQL is exercised by Tasks 8–10's integration tests.

- [ ] Tests first; implement; commit `feat(notifications): storage and audience`.

### Task 7 — `test(it)`: the module in the suites

**Files:** `wasichai-integration-tests/build.gradle.kts` (`"notifications"` in `wasichaiModules`, `notificationsIt` in
`fullAppSuites`, `"notificationsOnly" to listOf("notifications")` in `sliceSuites`); create
`src/notificationsOnly/kotlin/wasichai/it/slice/notifications/NotificationsOnlyApplication.kt` and a smoke
`NotificationsOnlyApiTest.kt`; create `src/notificationsIt/kotlin/wasichai/it/full/NotificationsTenant.kt` (a fixture
that provisions an organization, an admin token, users, roles and units for one test class); update
`ModuleBoundariesTest`, `FullAppBootTest` and `SchemaParityTest` (deviation lines for `org_units`, `user_org_units`
citing D31 and every `notification*` table citing D32, generated by running the suite).

- [ ] Wire; run `./gradlew :wasichai-integration-tests:notificationsOnly :wasichai-integration-tests:schemaParityIt`;
  commit `test(it): wire the notifications module into the suites`.

### Task 8 — `feat(notifications)`: publish from code, manage by hand

**Files:** create `Notifications.kt` (the bean; `TransactionalOperator` joins the caller's transaction; lenient
recipients), `NotificationAdmin.kt` (DTOs, service, `/api/notifications` controller). Wire beans.

**Tests (`notificationsIt`):** `NotificationsAdminApiTest` (CRUD; `403` without `MANAGE_ORGANIZATION`; strict `400`s
naming `audience[i]`; source-owned `409`; `status` filter; `readCount`), `NotificationsPublishTest` (key upsert is
idempotent; unchanged fingerprint writes nothing; kind change and reopen reset receipts; unknown email dropped;
nobody left answers `null`; a caller's rolled-back transaction leaves no notification).

- [ ] Tests first; implement; commit `feat(notifications): publish from code and manage by hand`.

### Task 9 — `feat(notifications)`: my notifications

**Files:** create `Inbox.kt` (service and `/api/auth/me/notifications` controller: list, summary, read, dismiss,
snooze, read-all; link filtering; service account `403`). Wire beans.

**Tests (`notificationsIt`):** `InboxApiTest`: USER, ROLE, unit subtree (ancestor unit reaches a member of a child),
ALL; window (scheduled and expired are hidden); another organization's id `404`; states `active`, `unread`,
`snoozed`; ACTION order by due date; `overdue`; link dropped without `READ` and after the object is deleted; dismiss of
a source ACTION `409`; snooze bounds `400`; read-all by kind; summary counts.

- [ ] Tests first; implement; commit `feat(notifications): my notifications`.

### Task 10 — `feat(notifications)`: scheduled sources that resolve themselves

**Files:** create `NotificationSource.kt` (SPI), `NotificationReconciler.kt` (pure `diff` and `apply`),
`NotificationLoop.kt` (`SmartLifecycle`; due check; `tryLock`; `asPlatform` per organization; purge; `runOnce()` for
tests). Wire beans (`ObjectProvider<NotificationSource>`, the `Clock` via `getIfUnique`, else the system clock).

**Tests:** `NotificationReconcilerDiffTest` (insert, update, reopen, skip, resolve missing; duplicate and missing keys
refused); `NotificationLoopTest` (due logic with a fixed clock; one failing organization does not stop the others);
`notificationsIt/NotificationSourceTest` (a `@TestConfiguration` source; create, update, resolve, reopen; reads
records through `RecordService` as the platform; a second run inside the interval does nothing).

- [ ] Tests first; implement; commit `feat(notifications): scheduled sources that resolve themselves`.

### Task 11 — `feat(notifications)`: live summary over SSE

**Files:** create `NotificationListener.kt` (`SmartLifecycle`; `LISTEN`; reconnect; health check; wildcard after
connect), `NotificationStream.kt` (the pipeline of spec D, testable with virtual time), `NotificationStreamController.kt`;
extend `NotificationSignals.kt` with the hub. Wire beans (`@ConditionalOnClass(name =
["io.r2dbc.postgresql.api.PostgresqlConnection"])` and `listen`).

**Tests:** `NotificationStreamTest` (`StepVerifier.withVirtualTime`: initial summary, debounce, distinct, heartbeat
comments, completion at expiry); `NotificationListenerTest` (payload parsing; a non-PostgreSQL connection turns it
off); `notificationsIt/NotificationStreamApiTest` (first summary; a `POST /api/notifications` brings a new one; a raw
`pg_notify` from another connection brings a recompute; `401` without a token).

- [ ] Tests first; implement; commit `feat(notifications): live summary over SSE`.

### Task 12a — `feat(notifications)`: date rule evaluation (pure)

**Files:** create `NotificationRule.kt` (definition, validation, window, stage, criteria builder, template rendering).

**Tests:** `NotificationRulesTest`: window bounds for DATE and DATETIME in a zone; stage choice; due instant;
templates (`{{field}}`, `{{days}}`, `{{date}}`, `{{object}}`, unknown placeholder rejected, title cut at 200);
conditions to filters and criteria; validation of stages, offsets, ops.

- [ ] Tests first; implement; commit `feat(notifications): date rule evaluation`.

### Task 12b — `feat(notifications)`: date rules

**Files:** create `NotificationRuleRepository.kt`, `NotificationRuleService.kt` and `NotificationRuleController.kt`
(`/api/objects/{object}/notification-rules`), `RuleNotifications.kt` (one rule, one organization, through
`RecordService.rows` as the platform), `NotificationRuleListener.kt`, `NotificationRuleFieldUsage.kt`; add the `rules`
work item to the loop. Wire beans.

**Tests (`notificationsIt`):** `NotificationRuleApiTest`: CRUD; `400`s (field not a date, unknown condition field,
bad op, EQ value refused by the codec, unknown placeholder, unknown role); `403` without `MANAGE_METADATA`; run
creates one notification per record with link and tab; stage escalation with a fixed clock; an update that leaves the
window resolves through the listener; a deleted record resolves; disabling or deleting the rule resolves; the cap; a
field in use reported by `FieldUsage`.

- [ ] Tests first; implement; commit `feat(notifications): date rules`.

### Task 13 — `test(it)`: notifications in the module matrix

**Files:** `wasichai-integration-tests/src/testFixtures/.../ModuleRoutes.kt` (every notifications route),
`src/test/.../AllModulesWiringTest.kt` and `ModuleRoutesTest.kt`; `NotificationsOnlyApiTest` positives (the inbox and
the admin work with core alone; a `RECORD` link with a tab is accepted without pages).

- [ ] Run the wiring and matrix suites; commit `test(it): notifications in the module matrix`.

### Task 14 — `docs`: module, API, security, guides, history

`docs/modules/notifications.md` (new) and its row in `docs/modules/README.md`; `docs/modules/core.md` (units, ports,
`Connections`), `docs/modules/pages.md` (TAB key); `docs/api/rest.md` (modules table; Organizational units;
Notifications; My notifications with the stream format; Notification rules; TAB `key`); `docs/security/authentication.md`
(units are not authorization; long-lived streams); `docs/architecture/overview.md` (module graph, ports);
`docs/guides/build-your-app.md` ("Tell people what needs doing"); `README.md`; `docs/HISTORY.md` (one entry, newest
first). Commit `docs: notifications, organizational units and tab keys`.

### Task 15 — `docs`: plans for wasichai-ui, srtm and caja

`docs/superpowers/plans/2026-10-06-notifications-wasichai-ui.md`, `…-srtm-adoption.md`, `…-caja-adoption.md`, in this
plan's format, from the spec's use cases. Commit `docs: notifications plans for wasichai-ui, srtm and caja`.

### Task 16 — verification

- [ ] `./gradlew ktlintFormat build` and `./gradlew -p build-logic test`.
- [ ] `./gradlew integrationTest` (all modules) and the suites `coreApiIt`, `pagesIt`, `notificationsIt`,
  `notificationsOnly`, `schemaParityIt`, `fullApp` in `wasichai-integration-tests`.
- [ ] Review against the Review Focus; push; open the draft PR to `dev`.

## Order and parallel groups

```
T0 (spec, ADRs, this plan)
├─ group 1: T1 · T2 · T3 · T15
├─ group 2: T4 (T1) · T5 (T3) · T7 (T1, T3)
├─ group 3: T6 (T1, T5) · T12a (T5)
├─ group 4: T8 · T9 · T10 · T11 (T6, T7)
├─ group 5: T12b (T10, T12a) · T14 draft
└─ group 6: T13 (T8–T12b) · T14 final · T16
```
