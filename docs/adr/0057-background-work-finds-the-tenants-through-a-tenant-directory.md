# ADR-057: Background work finds the tenants through a tenant directory

**Status**: accepted · 2026-10-08 · follows up [ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md),
builds on [ADR-024](0024-libraries-and-starters.md) and [ADR-050](0050-correlation-id-and-change-source-on-audit-rows.md)

## Context

ADR-039 gave background code `RecordService.asPlatform(organizationId) { }` and `ClusterLock`, but a job that runs for
every tenant had no supported way to find the tenants. `OrganizationController` has no list on purpose (a tenant must
not enumerate the others). Core iterated tenants twice, internally: `DeclaredIndexReconciler` read
`CustomObjectRepository.findAllOrganizations()` (every object of every organization), and the notifications loop read
`OrganizationRepository.ids()`. Repositories are internal API (ADR-024), yet an app's scheduled jobs (SGSPE: deadlines,
escalations, retention, recalculation) called `findAllOrganizations()` to find the tenants that have its model
([#62](https://github.com/wasichai/wasichai/issues/62)).

## Decision

A supported bean in core, `wasichai.core.platform.TenantDirectory`, with the safety rules of `asPlatform`:

- `organizations()` lists every organization; `organizationsWithObject(name)` only those that define an object with that
  name, enabled or not (how an app finds "its" tenants). Both answer `TenantRef(id, slug)`, nothing more (no names, no
  counts), ordered by id: a snapshot, not a live view.
- **Never reachable from a request.** Both throw `IllegalStateException` when the Reactor context carries Spring
  Security's `SecurityContext` key, which every request through the security chain has, with a token or anonymous.
  The check is the one `asPlatform` uses, moved to one internal place (`platform.Background`) that both call.
- **No controller takes it.** `TenantDirectoryBoundaryTest` scans every `@Controller` of core and the modules and fails
  when one has it as a constructor parameter or field, generic ones included.
- The default, `DatabaseTenantDirectory`, is two `DatabaseClient` reads over `organizations` and `custom_objects`,
  declared `@ConditionalOnMissingBean` in `WasichaiMetadataAutoConfiguration`. An app that replaces it owns the tripwire.
- **`RecordService.forEachOrganization(objectName = null, source = "platform") { organizationId -> }`** walks the
  directory in order and runs the block inside `asPlatform(organizationId, source)` (ADR-050 label). One tenant's
  exception is logged and the loop goes on; cancellation of the caller stops it. It checks the tripwire itself before
  asking the directory, so a replaced directory without one cannot turn the loop's refusal into a logged failure per
  tenant, and checks `source` before anything runs. It lives on `RecordService` next to `asPlatform`, the call it wraps.
- **One internal iteration.** `DeclaredIndexReconciler` and the notifications loop walk the directory.
  `OrganizationRepository.ids()`, whose only user was the notifications loop, is gone.
  `CustomObjectRepository.findAllOrganizations()` has no caller left in core and is deprecated, kept one release for
  the apps that called it.

## Consequences

- An app's job finds its tenants without touching a repository, and cannot leak the tenant list through a request.
- No REST route, header or answer changes; there is no new ADR-031 entry and no migration.
- The index reconciliation at startup reads each organization's objects instead of all of them in one query: one more
  short query per tenant, once per start.
- `RecordService` takes the `TenantDirectory` as a twelfth constructor argument. An app that builds `RecordService`
  itself passes one.
- Tested by `TenantDirectoryTest` and the `forEachOrganization` cases of `RecordServicePlatformTest`,
  `TenantDirectoryBoundaryTest`, and the integration test `TenantDirectoryApiTest` (every organization, only the
  defining ones, a scheduled job and a startup hook, a request with and without a token refused, the per-tenant loop
  writing as the platform and going on after a failure); `DeclaredIndexApiTest` covers the reconciler on the directory.
