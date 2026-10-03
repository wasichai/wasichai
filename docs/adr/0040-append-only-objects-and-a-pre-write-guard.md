# ADR-040: Append-only objects, a pre-write guard SPI, and api-only objects

**Status**: accepted · 2026-10-02 · builds on [ADR-025](0025-extension-spis.md),
[ADR-038](0038-record-service-joins-the-callers-transaction.md) and
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)

## Context

Receipts, receipt lines, annulments, turno closings and a payment outbox are append-only by rule: no `UPDATE` and no
`DELETE`, for anyone. caja's and rentas' sources enforced it with `GRANT INSERT, SELECT`
([#15](https://github.com/wasichai/wasichai/issues/15)). Wasichai had nothing for it:

- no flag on an object; `editable: false` also blocks the create, `enabled: false` freezes everything;
- ADMIN, and since ADR-039 the platform, pass every permission check;
- `RecordChangeListener` runs after the write. Outside a caller's transaction it cannot veto anything;
- the generic record API is a second door. A clerk with `CREATE` on `recibo`, because the app's own cobranza writes
  as the caller, can also `POST /api/objects/recibo/records` and skip the app's rules, locks and numbering.

srtm-backend worked around it by decorating the `RecordStore` bean, which drops any other decorator and leaves object
deletion open.

## Decision

### `appendOnly: true` on an object

`UPDATE` and `DELETE` of its records answer **409** (`ConflictException`) for everyone: any role, ADMIN, the platform
(ADR-039) and automations. A create is still allowed. The refusal comes before the store write, so nothing is stored,
nothing is audited and no listener runs.

These count as an update, and are refused the same way:

- a **workflow transition** (it moves the record's state);
- a **link or unlink** where either end is append-only (the join row is history on both records, and the audit
  already writes an `UPDATE` on both). The refusal comes before the join table is read, so it is `409` even when the
  call would change nothing: linking a pair that is already linked, or unlinking one that is not, answers `409` here
  where it is an idempotent `204` elsewhere;
- an automation's `UPDATE_FIELD` (the run fails, the triggering write stays committed, as for any failed action);
- a **delete of another object's record that an append-only record points at**. A `RELATION` column is
  `ON DELETE SET NULL` and a join row `ON DELETE CASCADE`, so postgres would blank the append-only record's value or
  drop its link, with no guard and no history row. `RecordService.delete` checks first, after the `404` and before
  the guards and the store: one `EXISTS` per `RELATION` field of an append-only object aiming at this one, and per
  join table shared with an append-only object, tenant-filtered. A hit answers `409` naming the append-only object,
  for every caller, ADMIN and the platform included. Every record delete goes through
  `RecordService.delete`; `RecordStore.delete` has no other caller in wasichai.

Metadata deletes that would destroy stored values are refused with 409 too: deleting the object, one of its fields,
a relationship whose field or join table sits on it, or another object that shares a `MANY_TO_MANY` join table with
it (deleting that object drops the join table, links included). An admin with `MANAGE_METADATA` switches
`appendOnly` off first, which is a deliberate, visible step. Adding fields and editing labels stay allowed. Deleting
the whole organization (`DELETE /api/organizations/current`) still drops everything: it removes the tenant, not a
record.

`appendOnly` is not a database grant. Code that writes the physical table through `DatabaseClient` is not stopped;
that is internal API (ADR-024). An app that needs the database to enforce it adds its own grants.

### `RecordWriteGuard`, a pre-write SPI

```kotlin
interface RecordWriteGuard {
    suspend fun beforeWrite(change: RecordWrite)
}
```

Every bean is collected, in `@Order`, and called **before the store write**, for every caller and route. A throw
aborts the write: nothing is stored, nothing is audited, no listener runs. A `WasichaiException` gives its own status
(`ValidationException` 400, `ConflictException` 409, ...); anything else is a 500.

`RecordWrite` carries the organization, the user (`null` for the platform and automations), the object, the record id
(`null` on create), the kind, the stored attributes `before` and the `attributes` about to be written:

| Kind | `before` | `attributes` |
|---|---|---|
| `CREATED` | null | what the caller sends |
| `UPDATED` | the stored row | what the caller sends |
| `UPDATED` (link, unlink) | the stored row | `rel:<relationship>` to the other id, or to null |
| `DELETED` | the stored row | null |
| `TRANSITIONED` | the stored row | null, `transition` names it |

Section payloads (geometries) stay out, as they do for `RecordChange` (ADR-019).

The guard runs inside the caller's transaction when there is one (ADR-038), so a read sees what the caller wrote
before. It locks nothing for you.

**Not a `RecordStore` decorator.** A decorator replaces the store bean, so two of them do not compose, and the store
has no `before` row to judge. **Not a `RecordChangeListener` before the write.** Listeners judge what happened and
receive the stored row; the guard judges what is about to happen. Keeping them apart keeps both contracts simple.

**Where it runs.** `RecordWriteGuards` (core, one bean, not replaceable: the app adds a guard, it does not remove
`appendOnly`) is called by every write path that ships with wasichai:

| Route | Guard | `appendOnly` | `apiOnly` |
|---|---|---|---|
| `POST/PUT/DELETE /api/objects/{o}/records` | yes | yes | refused (403) |
| `RecordService.create/update/delete`, as a user or as the platform | yes | yes | allowed |
| `POST/DELETE .../records/{id}/related/{rel}` (both ends) | yes | yes | refused (403) if either end is api-only |
| `RelatedRecordService.link/unlink` in-process | yes | yes | allowed |
| Workflow transitions (`wasichai-workflow`) | yes | yes | allowed |
| Automation `UPDATE_FIELD` and `CREATE_RECORD` (`wasichai-automation`) | yes | yes | allowed |

`wasichai-documents` writes no records (issuing a document reads one and writes `documents`). A module or app that
writes through `RecordStore` itself calls `RecordWriteGuards.beforeWrite` before the write; `RecordStore` stays the
raw port.

### `apiOnly: true` on an object

The generic record API refuses writes on it with **403** (`ForbiddenException`), for ADMIN too: create, update,
delete, and link or unlink when either end is api-only. In-process `RecordService` and `RelatedRecordService` calls
still write, as the calling user or as the platform. Reads are unchanged.

**How the door is told apart.** `RecordController` and `RelatedRecordController` call `internal` overloads,
`create/update/delete(…, viaApi = true)` and `link/unlink(…, viaApi = true)`. The public methods keep their
signatures and pass `viaApi = false`. So an app that subclasses `RecordService` or `RelatedRecordService` and
overrides the public `create`, `update`, `delete`, `link` or `unlink` is no longer reached by the REST routes: they
go straight to the overloads. An app that needs to intercept every write uses a `RecordWriteGuard` instead.

**403, not 405.** The method exists on the path and works for other objects; 405 also obliges an `Allow` header that
would differ per object. 403 is "this door is closed to you", which is what it is, and the UI already handles it.

Workflow transitions are not refused: they run the workflow's own rules (roles per transition), not a free write, and
an object gets a workflow only when its admin attaches one. A guard can veto them.

### The metadata API

`appendOnly` and `apiOnly` are in `POST /api/objects` (default `false`), in every object response (`GET
/api/objects`, `GET /api/objects/{o}`, `GET /api/metadata/objects/{o}`), and in `PUT /api/objects/{o}`, where
**leaving them out keeps them as they are**. `enabled` defaults to `true` on that `PUT`, but a client that predates
the flags must not switch append-only off by saving a label.

A guard runs in the caller's coroutine: `beforeWrite` is `suspend` and no `withContext` or dispatcher switch sits
between a public write method and the call ([#15](https://github.com/wasichai/wasichai/issues/15)). An app can wrap its
own in-process `RecordService` and `RelatedRecordService` calls in a `CoroutineContext.Element` marker and have its guard
refuse a write whose `coroutineContext[Marker]` is null, which is every write through the generic REST API. The platform
(ADR-039) and automations call the guard in their own coroutine, so they carry no app marker. Pinned by
`WriteGuardCoroutineApiTest`.

## Consequences

- Immutability is one flag, enforced for everyone, and checkable in tests. srtm-backend's `CuotasInmutables`
  decorator can go: `appendOnly` covers update, delete and transition, and a `RecordWriteGuard` covers its insert
  invariants.
- The generic record API no longer has to be a second door: `apiOnly` closes it per object.
- A new write path in a module must call `RecordWriteGuards`. The table above is the checklist.
- `RecordService`, `RelatedRecordService`, `WorkflowService` and `AutomationRunner` take a `RecordWriteGuards`
  constructor argument. Code that builds them by hand (tests) passes `RecordWriteGuards(emptyList())`.
- The REST routes no longer call the public write methods of `RecordService` and `RelatedRecordService`: an
  override of those in an app's subclass is skipped by the REST API (see "How the door is told apart").
- A record an append-only one points at cannot be deleted while it does: the append-only record is history, and
  its reference to the deleted one would otherwise vanish. Unlinking first is refused too, so such a record stays
  until `appendOnly` is switched off. `RecordService` takes an `AppendOnlyReferences` constructor argument (a mock
  in hand-built tests).
- Two columns on `custom_objects`: a schema-parity deviation, [ADR-031](0031-deliberate-deviations-from-sapgis.md)
  D24.
