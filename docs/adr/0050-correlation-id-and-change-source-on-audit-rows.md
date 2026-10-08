# ADR-050: Every audit row carries a correlation id and the source of the change

**Status**: accepted · 2026-10-08 · builds on [ADR-016](0016-automations-queue-and-system-context.md),
[ADR-038](0038-record-service-joins-the-callers-transaction.md),
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md),
[ADR-041](0041-a-change-reason-on-record-writes.md) and [ADR-049](0049-admin-changes-in-the-audit-log.md)

## Context

`audit_log` could not tie a row to the request that produced it, nor say what produced it. Core had no correlation id
at all: no filter, no MDC key, no `X-Correlation-Id`. A person's request, an app's in-process write, the platform
(ADR-039, whose own Consequences said platform writes "cannot be told apart from each other in the audit log"), an
automation (only its free-text reason `automation '<name>'` hinted at it) and a scheduled job all looked alike.

A social-management app (SGSPE) must record, for each change, who, what, when, from where, and the request's
correlation id, so an incident can follow one user action across several rows and services. Its workaround (its own
filter, and the origin encoded in the change reason by convention) is fragile: the reason is user-visible text, capped
at 500 characters, and a `requiresReason` object makes the caller type one
([#50](https://github.com/wasichai/wasichai/issues/50)).

## Decision

### A correlation id on every request

Core declares a `WebFilter`, `CorrelationIdWebFilter` (bean `correlationIdWebFilter`, `@ConditionalOnMissingBean`), at
`Ordered.HIGHEST_PRECEDENCE`: ahead of Spring Security's `WebFilterChainProxy` (order `-100`), so a `401` or `403` the
security chain answers carries the id as well.

- It accepts `X-Correlation-Id` when the request carries exactly one value matching `^[A-Za-z0-9._-]{1,64}$`. Anything
  else (a malformed value, more than 64 characters, two headers) is **replaced** by a fresh UUID, never echoed and
  never stored, so a client cannot put arbitrary text into logs, responses or the audit table.
- It sets `X-Correlation-Id` on every response, right before commit (`beforeCommit`), so an error handler that resets
  the headers on its way to a `4xx` or `5xx` does not lose it.
- It puts the id in the Reactor context of the request, with the source `api`. Coroutine controllers, services and
  `TransactionalOperator` blocks see that context (ADR-038), so `AuditService` reads both from there and no signature
  changes.
- The context key is the MDC key, `correlationId`. Core registers a context-propagation `ThreadLocalAccessor` for it
  (`io.micrometer:context-propagation`, a new dependency of core, versioned by the Boot BOM) and defaults
  `spring.reactor.context-propagation=auto` in `WasichaiEnvironmentPostProcessor`, so every log line written while
  serving the request, on whatever thread, has `%X{correlationId}`. An app may set `limited` back: the header and the
  audit rows are unaffected, only the automatic MDC goes.

### Two nullable columns

`V10__audit_origin.sql` adds `audit_log.correlation_id text` and `audit_log.source text`, both nullable, with `CHECK`s
on the two character classes (`NOT VALID`: every existing row is null in both, so there is nothing to scan), and the
index `audit_log_correlation_idx (organization_id, correlation_id)`. Rows written before stay null; they read back
without either field.

### The source is set by code, never by a client

| `source` | Written by |
|---|---|
| `api` | any write made while serving an HTTP request: records, related records, admin routes, transitions, documents, an app's controller |
| `platform` | `RecordService.asPlatform(organizationId) { }` |
| an app's label | `RecordService.asPlatform(organizationId, source = "job:retention") { }` |
| `automation:<rule>` | a rule's actions: `UPDATE_FIELD`, `CREATE_RECORD`, and the `ISSUE` row of a document a `GENERATE_DOCUMENT` action issues |
| `app` | an in-process write nothing labelled (say, `AuditService.record` called by a job outside `asPlatform`) |

A source matches `^[A-Za-z0-9._:-]{1,64}$`: the correlation id's class plus `:` between a kind and a name. A rule's
name matches `^[a-z][a-z0-9_-]{0,48}$`, so `automation:<rule>` fits in 60; a name from before that rule that does not
fit is labelled plain `automation` rather than failing the run. An app's label that does not match makes `asPlatform`
throw `IllegalArgumentException` before the block runs.

No header, query parameter, body property or token claim reaches it. The label lives under a private key in the
Reactor context, written only by the request filter, `RecordService.asPlatform` and
`ChangeOrigin.within(source, correlationId) { }`, the in-process entry a module uses for work no request carries. The
innermost label wins; an outer correlation id stays when the inner block gives none.

The admin rows of ADR-049 are written inside the admin request, so they say `api`: an administrator acting through
the API is a person's request like any other. `asPlatform` keeps its two-argument form and gains an overload rather
than a default parameter, so code compiled against it keeps linking.

### An automation keeps the request's id

Matching runs inside the request; the actions run later, from the `automation_runs` queue (ADR-016). So the dispatcher
stores the correlation id with the queued run (`automation_runs.correlation_id`, automation's `V2`), and the runner
runs a run's actions inside `ChangeOrigin.within("automation:<rule>", run.correlationId)`. Its writes, a document it
issues and the runs its writes queue in turn all carry the first request's id. The audit `user_id` of an automation's
rows stays null and `created_by` stays the triggering user, as before (ADR-016, ADR-039). A run queued before the
migration, or by a write no request made, has no id and its rows have none.

### Read and filtered like every other entry

`AuditEntry` gains `correlationId` and `source`, left out when null, as `serviceAccount` is. `GET /api/audit` takes
`correlationId=` and `source=`, exact matches, blank meaning no filter. They only narrow: the organization filter,
`READ`, `MANAGE_ORGANIZATION` for `admin:*` rows (ADR-049), the read scope (ADR-048) and field permissions apply to
the result as before. The record history carries both fields too.

## Consequences

- One request can be followed across rows, objects, admin entries, automation runs and logs, and the platform's work
  can now be told apart: ADR-039's "one platform, no job name" no longer holds for a job that labels itself
  (`asPlatform(…, source = "job:retention")`). ADR-039 itself is unchanged; this is its follow-up.
- Every response gains a header and `/api/audit` two optional fields and two filters: ADR-031 D35. wasichai-ui may
  show and send them; it needs no change to keep working.
- Reactor's automatic context propagation is on by default in a Wasichai app. It costs a little on every operator; an
  app that measures it and does not need the MDC sets `spring.reactor.context-propagation=limited`.
- The SGSPE workaround (its own filter, origin in the reason) can go. The reason stays what a person said; an
  automation still writes `automation '<name>'` there, as ADR-041 promised.
- Not covered: the run log (`AutomationRunResponse`) does not show the id; module tables other than `audit_log` and
  `automation_runs` do not store it; a module's own background loop (notifications) writes through `asPlatform` and
  says `platform` until it labels itself.
- Tested by `CorrelationIdWebFilterTest`, `ChangeOriginTest`, `RecordServicePlatformTest`, `AutomationDispatcherTest`,
  `CoreMigrationSqlTest`, `AutomationMigrationSqlTest`, `WasichaiEnvironmentPostProcessorTest`,
  `WasichaiAutoConfigurationTest`, and the integration tests `AuditOriginApiTest` and `AutomationOnlyApiTest`.
