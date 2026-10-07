# Record read scope SPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an app limit what a person or a service account reads of an object, by project, territory or region
([#48](https://github.com/wasichai/wasichai/issues/48)), on every route that reads a record, with no change for an app
that declares no scope.

**Architecture:** A core SPI, `RecordReadScope`, returns a `RecordCriterion` per caller and object. `RecordReadScopes`
(one core bean, every scope in `@Order`) asks them for a person or a service account who is not `ADMIN`, never for the
platform or an automation, and hands each criterion the object's full definition. The criteria join the reads where
`AccessPolicy.ownerFilter` already sits, parenthesised as ADR-025 requires: `RecordStore.findById` gains a `criteria`
argument, list reads add them to `RecordQuery.criteria`. Audit sits below `data` in core's DAG, so it asks through a
port of its own, `AuditRecordScope`, which `RecordReadScopes` implements. Decision: ADR-048.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, JUnit 5 + AssertJ + Mockito.

## Global Constraints

- No bean declared: same SQL, same queries, same answers. Every existing test passes unchanged.
- Values bound, never interpolated; every criterion parenthesised behind the tenant and owner filters.
- Out of scope reads as missing: `404` on a record route, the D29 `400` on a `RELATION` value.
- `.editorconfig` is law, max 160 columns. Conventional Commits. Comments in English, caveman style, say why.

## Task 1: The SPI and its composition

- [ ] `data/RecordReadScope.kt`: `RecordReadScope`, `RecordReadScopes` (criteria per caller and object, full
  definition, `ADMIN` and the platform never asked; `readable(...)` for audit).
- [ ] `audit/AuditRecordScope.kt`: the port audit asks.
- [ ] `RecordCriterion.term(...)`: one place that parenthesises and binds, used by the store and `RelationTargets`.
- [ ] `RecordStore.findById(..., criteria)`; `PhysicalTableRecordStore.byIdClause` (pure, tested).
- [ ] Unit tests: `RecordReadScopesTest`, `PhysicalTableRecordStoreTest` (no criteria: today's SQL; with one: in
  parens after the owner filter), `RelationTargetsTest` (the target query).

## Task 2: Every read path

- [ ] `RecordService`: list, rows, get, update and delete lookups.
- [ ] `RelatedRecordService`: the other side, the record the walk starts from, both ends of a link.
- [ ] `RelationTargets`: the D30 read folds the scope in.
- [ ] `AuditQueryService`: `history` `404`; `list` drops entries of out-of-scope or deleted records for a scoped caller.
- [ ] wasichai-workflow: `transitionsOf` and `apply` look the record up in scope.
- [ ] GIS features and the agent tools call `RecordService`, `RelatedRecordService`, `AuditQueryService` and
  `WorkflowService`: nothing of their own to change.
- [ ] Unit tests: `RecordServiceReadScopeTest`.

## Task 3: Integration tests

- [ ] `wasichai-core` `RecordReadScopeApiTest`: a project scope bean, two callers on the same object, every core route.
- [ ] `agentIt` `RecordReadScopeModulesTest`: GIS features, the agent's tools and workflow transitions.

## Task 4: Docs

- [ ] ADR-048 and its line in the index; no ADR-031 entry (no behaviour change without a bean).
- [ ] `docs/HISTORY.md`, `docs/architecture/overview.md`, `docs/modules/core.md`, `docs/guides/build-your-app.md`,
  `docs/security/authentication.md`, `docs/api/rest.md`.
