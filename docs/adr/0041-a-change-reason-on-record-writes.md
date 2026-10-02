# ADR-041: A change reason on record writes, required per object

**Status**: accepted · 2026-10-02 · builds on [ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)
and [ADR-040](0040-append-only-objects-and-a-pre-write-guard.md)

## Context

caja's rule 10: every data change carries an observation from the user, and without it nothing is saved
([#19](https://github.com/wasichai/wasichai/issues/19)). `audit_log` stores who, when, the object, the operation, and
before and after. It had no reason. caja-backend put a required `observacion` field on every object it writes, which
is a field per object for something that belongs to the change, not to the record, and the generic record API and
the admin could not ask for one.

## Decision

### Where the reason travels

**REST: the `X-Change-Reason` header**, optional, on every record write route:

- `POST`, `PUT`, `DELETE` under `/api/objects/{object}/records`;
- link and unlink under `/api/objects/{object}/records/{id}/related/{relationship}`;
- `POST /api/objects/{object}/records/{id}/transitions/{name}` (`wasichai-workflow`).

Not a body field: the record body is the sections map (`attributes`, `geometries`, ...), where an unknown object key
is a section and anything else is ignored (ADR-019), and a `DELETE` has no body. A header is the same on every verb
and does not compete with a field name.

A header value is ISO-8859-1 at best, and a browser's `fetch` refuses anything else. So a value is taken as is
(Spanish, Portuguese and French travel unchanged), or in the RFC 8187 form `UTF-8''<percent-encoded>` for any other
text. A plain value is never percent-decoded: `10% de descuento` means what it says. A malformed `UTF-8''` value is a
`400` on `reason`.

**In-process: an optional `reason` argument**, as overloads beside the existing signatures:

- `RecordService.create(object, request, reason)`, `update(object, id, request, reason)`, `delete(object, id, reason)`;
- `RelatedRecordService.link(…, reason)`, `unlink(…, reason)`;
- `WorkflowService.apply(object, id, transition, reason)`.

The methods without it keep their signatures and pass `null`, so callers and mocks compile unchanged.

### What a reason is

Trimmed; blank is no reason; at most **500 characters** (code points), longer is a `400` on `reason`
(`ChangeReason.MAX_LENGTH`). Control characters are a `400` on `reason` too, except tab, `\n` and `\r`: PostgreSQL
`text` cannot hold NUL, and `RecordService` writes the record before the audit row, so a NUL found there would leave a
stored change with no audit; the rest have no business in an observation, while a line break does. One observation,
not a document. It is stored on the audit row of the write that carried it, in the new nullable `audit_log.reason`,
and `GET /api/audit` and `GET …/records/{id}/history` return it as `reason` on every entry (`null` when none was
given). It is shown to whoever may read the entry: it is free text about the change, not a field value, so field
permissions do not filter it.

`RecordWrite` carries it too (`reason`), so a `RecordWriteGuard` can judge it (a minimum length, a vocabulary).
`RecordChange` does not: no listener needs it yet, and the audit row is where history lives.

### `requiresReason: true` on an object

A write of its records with no reason answers **400** problem+json with `errors[0].field = "reason"`, before anything
is stored: no row, no audit, no listener, no guard. The check sits in `RecordWriteGuards.beforeWrite`, the choke point
every write path already calls (ADR-040), after `appendOnly` (a write that can never happen is a `409`, reason or not)
and before the app's guards.

Per path:

| Path | Reason from | `requiresReason` |
|---|---|---|
| Record API `POST/PUT/DELETE` | the header | required |
| `RecordService` as a user | the argument | required |
| `RecordService.asPlatform` | the argument | **required**: the code can always say why ("nightly closing") |
| Link, unlink (API and in-process) | the header or argument, stored on both records' rows | required when **either** end requires it |
| Workflow transition (API and in-process) | the header or argument, stored on the transition's row | required |
| Automation `UPDATE_FIELD`, `CREATE_RECORD` | `automation '<name>'`, always | satisfied by it |

**Automations give their own reason.** No user is behind an automation's write, so nobody can be asked. Refusing it
would make an automation useless on a `requiresReason` object, and a silent exemption would leave its rows without
one. Its name says why the record changed, so every write it makes carries `automation '<name>'`, on any object. The
triggering write keeps its own reason on its own row.

**Issuing a document is not asked.** `wasichai-documents` writes no record (ADR-040) and its `ISSUE` row has no
reason. Metadata changes are not record writes either.

**A link that changes nothing still asks.** The check runs before the join table is read, as `appendOnly` does
(ADR-040), so linking a pair already linked on a `requiresReason` end without a reason is a `400`.

### The metadata API

`requiresReason` is in `POST /api/objects` (default `false`), in every object response (`GET /api/objects`,
`GET /api/objects/{o}`, `GET /api/metadata/objects/{o}`), and in `PUT /api/objects/{o}`, where leaving it out keeps it
as it is, like `appendOnly` and `apiOnly` (ADR-040). The UI reads it to ask for a reason before it writes.

## Consequences

- caja's rule 10 is one flag; its per-object `observacion` field can go, and the reason lands in the audit trail
  where the admin's history screen shows it.
- `AuditService.record` takes a `reason` (default `null`). An app subclass that overrides `record` must add the
  parameter; callers compile unchanged.
- An app or module that writes through `RecordStore` itself passes its reason in `RecordWrite` when it calls
  `RecordWriteGuards.beforeWrite`, or a `requiresReason` object refuses it.
- Automation audit rows now carry `automation '<name>'` in `reason`.
- Two columns: `audit_log.reason` and `custom_objects.requires_reason` (migration `V6__change_reason`), a
  schema-parity deviation, and `requiresReason` on the object json and `reason` on the audit json, wire-parity ones:
  [ADR-031](0031-deliberate-deviations-from-sapgis.md) D25.
