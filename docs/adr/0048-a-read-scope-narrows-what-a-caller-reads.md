# ADR-048: A read scope SPI narrows what a caller reads of an object

**Status**: accepted · 2026-10-07 · builds on [ADR-025](0025-extension-spis.md),
[ADR-040](0040-append-only-objects-and-a-pre-write-guard.md) and
[ADR-031](0031-deliberate-deviations-from-sapgis.md) D30

## Context

The only read restriction below the object was `own_records_only`: a role whose every grant sets it reads just the rows
its caller created. A social-management app (SGSPE) runs several projects for one tenant and must authorize by project,
territory and region: a person assigned to project A must not read project B's records, nor see them in a map, a
count, a history or an assistant's answer ([#48](https://github.com/wasichai/wasichai/issues/48)). Row ownership is the
wrong axis: the people who create the rows are not the people who read them.

The existing SPIs do not fill the gap. `RecordQueryContributor` turns query-string parameters into a `RecordCriterion`:
it never sees the caller, a client that leaves the parameter out gets no criterion, and by-id reads never ask it.
`RecordWriteGuard` (ADR-040) judges writes only, so an app could already keep a person's writes to their projects, but
any caller with `READ` on an object read every row of it.

## Decision

### `RecordReadScope`, a core SPI

```kotlin
interface RecordReadScope {
    suspend fun criterion(caller: AuthenticatedUser, definition: ObjectDefinition): RecordCriterion?
}
```

- `null`: no restriction for this caller on this object.
- A criterion: ANDed, in parentheses, into every read of the object. A record outside it reads as **missing**.
- Nothing in scope: a criterion that matches nothing, `RecordCriterion { _, _ -> "false" }`. Lists are empty and every
  total is `0`.

It is collected like the other SPIs of ADR-025: every bean, in `@Order`, none by default. With several, every
criterion applies (AND). `RecordReadScopes` is the one bean that asks them; like `RecordWriteGuards` it has no
`@ConditionalOnMissingBean`, because an app adds a scope and never removes another one.

`criterion` is `suspend`, so the app can look up the caller's assignments; it is asked once per object a read
touches, so the app caches them per request itself. The `RecordCriterion` it returns is the existing, non-suspending
shape: plain SQL whose every value goes through `bind`. It is always handed the object's **full** definition, whatever
projection the read selects with, so it may filter on a field the caller cannot read.

### Who is asked

A person or a service account. Never `ADMIN`, the platform (`asPlatform`, ADR-039) or an automation: they read the
whole organization, as they do under `own_records_only`, field access and D30's `RELATION` check. A service account
holding the `ADMIN` role is not `ADMIN` (ADR-043), so it is asked.

The issue's proposal also let an app return a criterion for `ADMIN`. This decision does not: `ADMIN` passes every
record rule in core, and an app that wants a narrower administrator gives that person a role of its own. Asking for
`ADMIN` would make every scope remember to answer `null` for it, or hide the tenant's data from the one who repairs it.
Asking for it later is one line in `RecordReadScopes.appliesTo` and a new decision.

### Where it applies

Next to `AccessPolicy.ownerFilter`, once per path:

| Read | Out of scope |
|---|---|
| `GET /api/objects/{o}/records` and its `totalElements`; `RecordService.list` and `rows` | left out of the rows and the count |
| `GET /api/objects/{o}/records/{id}`; `RecordService.get` | `404` |
| `PUT` and `DELETE` of a record; `RecordService.update` and `delete` | `404`, nothing written |
| `GET .../records/{id}/related/{rel}`, both directions, join tables too | the record the walk starts from: `404`; the other side: left out |
| `POST .../related/{rel}` and `DELETE .../related/{rel}/{otherId}` | `404` on either end |
| A `RELATION` value on any write by a person or a service account (D29, D30) | `400` on the field, the answer for a missing record |
| `GET .../records/{id}/history` | `404` |
| `GET /api/audit` | entries left out |
| Workflow: `GET .../records/{id}/transitions`, `POST .../transitions/{name}` | `404` |
| GIS: `GET /api/gis/objects/{o}/features` and `features/{id}` (through `RecordService.rows` and `get`) | left out, `404` |
| The agent: `query_records`, `count_records`, `get_record`, `related_records`, `record_history`, `available_transitions` | as above, as refusals |

`/api/audit` keeps an entry only when its record exists and is in scope. An entry whose record (or object) is gone
cannot be shown to be in anyone's scope, so it is shown only to a caller with no scope on that object. The filter
runs after `limit`, so a scoped caller may get fewer entries than asked for. Entries that name no record stay.

### How it joins a read

`RecordStore.findById` takes `criteria: List<RecordCriterion> = emptyList()` beside `createdBy`; list reads append the
scope to `RecordQuery.criteria`, after the module criteria. Both go through one helper: every criterion in its own
parentheses, behind `organization_id` and `created_by`, its values bound as `c<n>`. An app's `OR` cannot widen the
tenant filter or the owner filter. `RelationTargets` folds the criterion into D30's single read per target object.

Audit sits below `data` in core's DAG (ADR-025), so it asks through a port of its own, `AuditRecordScope`, which
`RecordReadScopes` implements: one id-only read per object the list touches, none when no scope applies.

### What it does not reach

- **GeoServer layers.** GeoServer reads the published table itself, with its own credentials; no wasichai rule reaches
  it, `own_records_only` included. Do not publish a scoped object as a layer, or secure the layer in GeoServer.
- **wasichai-documents.** Issuing a document reads the record without the caller's rules (ADR-023), and listing a
  record's documents checks `READ` on the object only. Neither applies `own_records_only` today, nor the scope.
- **Notifications.** A `RECORD` link is not checked against record-level rules; the record route answers `404`.
- **`GET /api/auth/me/permissions`** does not report the scope. That is a separate, additive change.
- **In-process code without a reader**: `RelatedRecordService.relatedRows` without `reader`, and anything that reads
  through `RecordStore` itself, reads as the platform does.

## Consequences

- With no `RecordReadScope` bean nothing changes: the same SQL, the same queries, the same answers. So no ADR-031
  entry: the REST behaviour of an app without a scope is the original's. With one, out of scope answers as missing does,
  which the original already did for `own_records_only`.
- An app's own `RecordStore` (a decorator) adds `criteria` to its `findById` and passes it on, as ADR-039 did with
  `userId`. Dropping it would let a by-id read skip the scope.
- `RecordService`, `RelatedRecordService`, `RelationTargets`, `AuditQueryService` and `WorkflowService` take a
  `RecordReadScopes`; `RelationTargets` also a `CustomFieldRepository`. Code that builds them by hand passes
  `RecordReadScopes(emptyList(), store)`. `RelatedRecordService.relatedRows` takes an optional `reader`.
- Cost, only with a scope: one `criterion` call per object per read, one id-only read per object on `/api/audit` and
  `/history`, one fields read per target object in the `RELATION` check.
- Tested by `RecordReadScopesTest`, `RecordServiceReadScopeTest`, `RelationTargetsReadScopeTest`,
  `AuditQueryServiceScopeTest`, `PhysicalTableRecordStoreTest`, `WasichaiAutoConfigurationTest`, the integration tests
  `RecordReadScopeApiTest` (core) and `RecordReadScopeModulesTest` (GIS, the agent, workflow).
