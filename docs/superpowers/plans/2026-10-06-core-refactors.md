# Core refactors after the architecture review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the duplication the architecture review found in the record write path, in nullable SQL binds and in
role assignment, with no change in behaviour: same REST answers, same SQL effects, same audit rows, same listener calls.

**Architecture:** Three small, independent refactors in `wasichai-core`, and two module call sites that stop carrying
their own copy of a core helper. `RecordService` opens every write through one gate and describes it once, so guards,
the audit row and listeners are always told the same write. A public `bindNullable` in `platform` replaces the copies
in `metadata`, `admin`, wasichai-pages and wasichai-automation. One internal `RoleAssignments` serves user and
service-account administration. Behaviour changes found by the review are not part of this plan: each one needs its
own change and, where it changes what a caller sees, its own ADR-031 entry.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, JUnit 5 + AssertJ + Mockito.

## Global Constraints

- Branch `claude/inspiring-volta-rfj0ig` from `origin/dev`; one draft PR into `dev`.
- No public constructor or bean changes: an app that declares its own `RecordService`, `AdminService` or
  `ServiceAccountService` bean keeps compiling. New public API: `wasichai.core.platform.bindNullable` only.
- Each task keeps the build green: `./gradlew ktlintFormat build`; integration tests of the touched modules pass.
- Characterization tests come first and pass on the old code before the code moves.
- `.editorconfig` is law, max 160 columns. Conventional Commits. Comments in English, caveman style, say why.

## Task 1: Pin what a record write tells guards, audit and listeners

- [ ] Add `wasichai-core/src/test/kotlin/wasichai/core/data/RecordWriteTrailTest.kt`: create, update and delete as a
  user and as the platform; the exact `RecordWrite`, audit call and `RecordChange` of each; the order of the checks in
  front of a write (reason, caller, api-only, permission, disabled).
- [ ] Run `./gradlew :wasichai-core:test --tests '*RecordWriteTrailTest'` on the unchanged `RecordService`: green.

## Task 2: RecordService opens and describes each write in one place

- [ ] Add a private `open(objectName, action, reason, viaApi)` that runs the shared gate in today's order and returns a
  private `Write(caller, definition, reason)`.
- [ ] Add `Write.guard(...)` (builds the `RecordWrite`) and `Write.recorded(...)` (the audit row, then the listeners).
- [ ] Use them in `create`, `update` and `delete`. Public and internal signatures stay as they are.
- [ ] Run `RecordWriteTrailTest`, `RecordWriteRulesTest`, `RecordAuditSnapshotTest`, `RecordServiceTest`,
  `RecordServicePlatformTest`, then `:wasichai-core:integrationTest`.

## Task 3: One nullable bind for every repository

- [ ] Add `wasichai-core/src/main/kotlin/wasichai/core/platform/Binds.kt`: `bindNullable(name, value)` (reified,
  boxed type) and `bindNullable(name, value, type)`.
- [ ] Unit test `BindsTest`: a null binds as null of the boxed type (`Boolean` too), a value binds as itself.
- [ ] Remove the internal copy in `MetadataRepository.kt` and the private ones in `AdminService`, `PageRepository` and
  `AutomationRunRepository`; replace the inline `if (x == null) bindNull(...) else bind(...)` in core, documents and
  automation.
- [ ] Run unit tests, then the integration tests of core, pages, automation and documents.

## Task 4: One role assignment for users and service accounts

- [ ] Add internal `wasichai-core/src/main/kotlin/wasichai/core/admin/RoleAssignments.kt`: `findRole`, `resolve`
  (names to ids of the tenant, with a per-name check the service accounts use to refuse `ADMIN`), `assign`, `replace`.
- [ ] `AdminService` and `ServiceAccountService` build it from the `db` and `schemas` they already take.
- [ ] Run `AdminApiTest`, `ServiceAccountApiTest`, `PermissionEnforcementTest`.

## Task 5: A role's permissions look each object up once

- [ ] `AdminService.setPermissions` loads each named object once per request instead of twice per entry. Same order of
  checks: the action is judged before the object.
- [ ] Run `AdminApiTest`, `DeclaredActionsApiTest`.

## Task 6: History and review

- [ ] `docs/HISTORY.md`: one entry, newest first.
- [ ] Final review of the whole diff against this plan; draft PR into `dev`.
