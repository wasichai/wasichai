# Record preconditions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop concurrent record writes from overwriting each other
([#51](https://github.com/wasichai/wasichai/issues/51)): an `ETag` on every record answer, `If-Match` compared in the
write's own statement (`412` when stale), and a partial `PATCH`.

**Architecture:** The version is `updated_at`, now set by `clock_timestamp()` so two writes in one transaction differ.
`RecordETag` formats `"<updatedAt>"` and parses `If-Match`. `RecordStore` gains `updateIfUnchanged`,
`deleteIfUnchanged` and `transitionStateIfUnchanged` (default bodies refuse); `PhysicalTableRecordStore` adds the
compare to its `WHERE` in epoch micros. `RecordService` runs `PUT` and `PATCH` through one path; a zero-row compare is
re-read with the caller's filters: `412` if found, `404` if not. Decision: ADR-051.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, JUnit 5 + AssertJ + Mockito.

## Global Constraints

- No `If-Match`, or `*`: the statements and answers of before. No migration.
- Existing `RecordService` signatures stay; new ones are overloads. An app's own `RecordStore` keeps compiling.
- A record the caller cannot read is a `404`, never a `412` (ADR-048).

## Task 1: Core

- [ ] `PreconditionFailedException` (412) in `common`; `data/RecordETag.kt`.
- [ ] `RecordStore` compare-and-write methods; `PhysicalTableRecordStore` `clock_timestamp()`, `versionMatch`,
  `versions`.
- [ ] `RecordService`: `update`/`delete` overloads with `expectedUpdatedAt`, `patch`, shared `write`, `staleOrMissing`.
- [ ] `RecordController`: `ETag` on `GET`/`POST`/`PUT`/`PATCH`, `If-Match` on `PUT`/`PATCH`/`DELETE`, `@PatchMapping`.
  CORS exposes `ETag`.

## Task 2: Workflow

- [ ] `WorkflowService.apply(…, reason, expectedUpdatedAt)`; `412` vs `409` vs `404` after a missed transition; the
  controller reads `If-Match` and answers `ETag`.

## Task 3: Tests and docs

- [ ] Unit: `RecordETagTest`, `RecordServicePreconditionTest`, cases in `PhysicalTableRecordStoreTest`.
- [ ] Integration: `RecordPreconditionApiTest` (round trip, two clients, concurrent writers, PATCH rules, locked delete,
  one transaction), a case in `WorkflowOnlyApiTest`.
- [ ] ADR-051, ADR-031 D36, HISTORY, rest.md, core.md, workflow.md, build-your-app.md.
