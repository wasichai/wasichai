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
  already writes an `UPDATE` on both);
- an automation's `UPDATE_FIELD` (the run fails, the triggering write stays committed, as for any failed action).

Metadata deletes that would destroy stored values are refused with 409 too: deleting the object, one of its fields,
or a relationship whose field or join table sits on it. An admin with `MANAGE_METADATA` switches `appendOnly` off
first, which is a deliberate, visible step. Adding fields and editing labels stay allowed.

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

**403, not 405.** The method exists on the path and works for other objects; 405 also obliges an `Allow` header that
would differ per object. 403 is "this door is closed to you", which is what it is, and the UI already handles it.

Workflow transitions are not refused: they run the workflow's own rules (roles per transition), not a free write, and
an object gets a workflow only when its admin attaches one. A guard can veto them.

### The metadata API

`appendOnly` and `apiOnly` are in `POST /api/objects` (default `false`), in every object response (`GET
/api/objects`, `GET /api/objects/{o}`, `GET /api/metadata/objects/{o}`), and in `PUT /api/objects/{o}`, where
**leaving them out keeps them as they are**. `enabled` defaults to `true` on that `PUT`, but a client that predates
the flags must not switch append-only off by saving a label.

## Consequences

- Immutability is one flag, enforced for everyone, and checkable in tests. srtm-backend's `CuotasInmutables`
  decorator can go: `appendOnly` covers update, delete and transition, and a `RecordWriteGuard` covers its insert
  invariants.
- The generic record API no longer has to be a second door: `apiOnly` closes it per object.
- A new write path in a module must call `RecordWriteGuards`. The table above is the checklist.
- `RecordService`, `RelatedRecordService`, `WorkflowService` and `AutomationRunner` take a `RecordWriteGuards`
  constructor argument. Code that builds them by hand (tests) passes `RecordWriteGuards(emptyList())`.
- Two columns on `custom_objects`: a schema-parity deviation, [ADR-031](0031-deliberate-deviations-from-sapgis.md)
  D24.
