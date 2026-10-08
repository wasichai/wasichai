# Audit paging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Page `GET /api/audit` and the record history by cursor and narrow them by period and user
([#52](https://github.com/wasichai/wasichai/issues/52)): `from`, `to`, `userId`, `serviceAccount`, `after`, the next
cursor in `X-Next-Cursor`, the body still a JSON array.

**Architecture:** `AuditQueryService.select` builds the SQL and its values (testable by `EXPLAIN`); `read` fetches
`limit + 1` rows and takes the cursor from the page's last row before the read scope (ADR-048) drops any.
`AuditCursor` is base64url of a version, a hash of the filters as applied, `occurred_at` and `id`. The controller
parses `from`, `to` and `userId` itself so a bad one is a `400` naming it. Decision: ADR-052.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, JUnit 5 + AssertJ + Mockito.

## Global Constraints

- A request without the new parameters answers the same body; the header is the only addition.
- Values always bound. Tenant filter unchanged. Admin rows still excluded in SQL for non-managers.
- Migration `V12` (`V11` was reserved for #51, which needed none), additive: one index.

## Task 1: Core

- [ ] `AuditCursor`, `AuditPage`, `AuditQuery`; `AuditFilter.from/to/userId/serviceAccount`.
- [ ] `AuditQueryService.page`, `historyPage`, `select`, `read`; `list`/`history` delegate.
- [ ] `AuditController`: new parameters, `ResponseEntity` with `X-Next-Cursor`.
- [ ] CORS default exposes `X-Next-Cursor`. `V12__audit_user_index.sql`.

## Task 2: Tests

- [ ] Unit: `AuditPagingTest`, `CoreMigrationSqlTest`, `WasichaiAutoConfigurationTest`.
- [ ] Integration: `AuditPagingApiTest` (1,200 rows, filters, `400`s, default, history, scope, `EXPLAIN`);
  `SchemaParityTest` deviation.

## Task 3: Docs

- [ ] ADR-052 and its index line, ADR-031 D37, HISTORY, rest.md, core.md.
