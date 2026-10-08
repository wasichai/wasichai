# Audit origin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store a correlation id and the origin of the change on every audit row
([#50](https://github.com/wasichai/wasichai/issues/50)): `X-Correlation-Id` kept or generated, echoed on every
response, in the Reactor context and the MDC; `source` set by code only; filters on `GET /api/audit`.

**Architecture:** `wasichai.core.platform.ChangeOrigin` holds both in the Reactor context (the id under the MDC key
`correlationId`, the source under a private key). `CorrelationIdWebFilter`, first of all filters, labels every request
`api` with its id. `PlatformCaller` labels `asPlatform` blocks `platform` or the app's label. `AuditService.record`
reads both, `app` when nothing labelled the work. Automation stores the id on the queued run and runs its actions inside
`ChangeOrigin.within("automation:<rule>", id)`. Decision: ADR-050.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, micrometer context-propagation, JUnit 5 +
AssertJ + Mockito.

## Global Constraints

- Additive migrations only: nullable columns, `NOT VALID` checks, one index. Old rows read back without the fields.
- No header, parameter, body property or claim can set the source. A bad client id is replaced, never echoed.
- `asPlatform(organizationId) { }` keeps its JVM signature; the label is an overload.

## Task 1: Core

- [ ] `platform/ChangeOrigin.kt`, `platform/CorrelationIdWebFilter.kt`; bean `correlationIdWebFilter`; default
  `spring.reactor.context-propagation=auto`; dependency `io.micrometer:context-propagation`.
- [ ] `PlatformCaller.run(organizationId, source, block)`; `RecordService.asPlatform(organizationId, source, block)`.
- [ ] `AuditService.record` writes `correlation_id` and `source`; `V10__audit_origin.sql`.
- [ ] `AuditEntry.correlationId`, `AuditEntry.source` (non-null only); `AuditFilter`; `GET /api/audit?correlationId=&source=`.

## Task 2: Automation

- [ ] `automation_runs.correlation_id` (`V2__run_correlation_id.sql`); `AutomationRun.correlationId`; the dispatcher
  stores `ChangeOrigin.correlationId()`; the runner wraps the actions in `ChangeOrigin.within`.

## Task 3: Tests and docs

- [ ] Unit: `CorrelationIdWebFilterTest`, `ChangeOriginTest`, `AutomationDispatcherTest`, cases in
  `RecordServicePlatformTest`, the migration, environment and auto-configuration tests.
- [ ] Integration: `AuditOriginApiTest`, two cases in `AutomationOnlyApiTest`, D35 lines in `SchemaParityTest`.
- [ ] ADR-050, ADR-031 D35, HISTORY, rest.md, core.md, automation.md, build-your-app.md, authentication.md.
