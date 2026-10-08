# ADR-051: Records answer an ETag, honour If-Match in the write itself, and take a partial PATCH

**Status**: accepted · 2026-10-08 · builds on [ADR-004](0004-physical-table-per-object.md),
[ADR-038](0038-record-service-joins-the-callers-transaction.md),
[ADR-040](0040-append-only-objects-and-a-pre-write-guard.md), [ADR-041](0041-a-change-reason-on-record-writes.md),
[ADR-044](0044-append-only-delete-check-under-a-row-lock.md) and
[ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md)

## Context

`PUT /api/objects/{object}/records/{id}` is a full replace and the last writer wins. A field the client leaves out is
written as `NULL`, and nothing lets a writer say "only if the record is still what I read". Two people editing the same
case, or a person and an automation, silently lose one edit; the audit log shows both writes, the record only the
second. `version` has been a reserved field name "for optimistic locking that does not exist yet".

A social-management app (SGSPE) works around it: its portal sends the `updatedAt` it read as `If-Match` and the app
compares it before writing. The check and the `UPDATE` are two statements, so a window stays open, and `/admin`, which
sends nothing, still overwrites ([#51](https://github.com/wasichai/wasichai/issues/51)).

## Decision

### The version is `updated_at`, moved by the statement's clock

The record's version is its `updated_at`. Every write that changes a record row (`RecordStore.update`, a workflow
transition) now sets `updated_at = clock_timestamp()` instead of `now()`.

`now()` is the transaction's start time, so two writes inside one transaction (ADR-038: an app's command often makes
several) would share a value and a stale version would still match. `clock_timestamp()` is read per statement, at
microsecond precision, so every write gets its own. We weighed the alternative the reserved name suggested, a
`version bigint` system column:

- it needs a migration that adds a column to every existing record table, a data schema core's Flyway migrations do
  not walk today, plus `ObjectSchemaManager`, the system-field catalogue and `RecordResponse` changes;
- the API already carries `updatedAt` on every record, a list item included, so a client needs no new field;
- the counter's one advantage, immunity to the clock, does not matter for an equality check: a stale version would
  have to land on the same microsecond as a later write of the same row.

So the counter is the bigger change for the same guarantee, and `version` stays a reserved name with no column, free
for a later decision. Inserts keep the column default (`now()`): a write that follows in the same transaction reads
the clock later and differs.

### The ETag is the quoted `updatedAt`

`GET`, `POST`, `PUT` and `PATCH` of a record, and a workflow transition, answer `ETag: "<updatedAt>"`, the instant
exactly as the record JSON writes it (ISO-8601, UTC, e.g. `"2026-10-07T10:15:30.123456Z"`). A strong tag (RFC 9110
8.8.3). A list item has no header, but `"` + its `updatedAt` + `"` is its ETag, so a grid can edit without a read
first. The issue proposed epoch microseconds; the ISO form is the same value and spares a JavaScript client, whose
`Date` keeps milliseconds only, the conversion. Clients should still treat it as opaque. CORS exposes `ETag`, so a
cross-origin client can read it.

### `If-Match` is compared in the write's own statement

`PUT`, `PATCH`, `DELETE` and `POST …/transitions/{name}` read `If-Match`:

- absent, or `*`: no precondition, today's write;
- a list of entity tags: the write lands only while `updated_at` is one of the strong ones. A weak tag (`W/"…"`) never
  matches under strong comparison (RFC 9110 13.1.1), nor does a quoted value that is no instant; if nothing usable is
  left, the write is stale;
- malformed (unquoted, blank, `*` mixed with tags): `400` naming `If-Match`, before anything else is read.

The compare is a condition of the write itself, `… WHERE id = :id AND organization_id = :org AND
CAST(EXTRACT(EPOCH FROM updated_at) * 1000000 AS bigint) = ANY(:expected)`, in the `UPDATE`, the `DELETE` and the
transition's `UPDATE`. Epoch microseconds are `updated_at`'s own precision, so nothing is rounded between what a read
said and what is compared. Of N writers holding the same version exactly one matches; PostgreSQL re-checks the
condition on the row version it waited for. With no precondition the statements are the ones they were.

Zero rows matched: the record is re-read with the caller's own filters (owner filter, read scope). Found, the write
is **stale**: `412 Precondition Failed`, problem+json with `errors: [{ field: "If-Match", … }]`. Not found, it is gone
or out of the caller's reach: `404`, as a missing one (ADR-048), never a `412` that would confirm it exists. A stale
write stores, audits and tells no listener anything.

412, not wasichai's usual `409`: the RFC names it, HTTP libraries know it, and a `PUT` here already answers `409` for
other reasons (`appendOnly`, a repeated unique value); a client must tell "someone else changed it, re-read" from
"this can never succeed". `PreconditionFailedException` joins the domain exceptions in `common`.

A precondition is evaluated where the write happens, after the checks that would refuse the request anyway: an unknown
record (`404`), permissions, field rules, `appendOnly` (`409`), `requiresReason` (`400`), the RELATION target check and
every `RecordWriteGuard` answer as before (RFC 9110 13.2.1: a request that fails without its precondition keeps that
answer). On the delete that checks append-only references under a row lock (ADR-044), the compare is in that same
`DELETE`, inside the lock's transaction, so a `412` rolls it back and nothing changes.

Link and unlink do not take `If-Match`: they write a join-table row and leave both records' rows, so their
`updated_at`, untouched. A precondition there could only be checked, not compared in the write, and a link is set
membership (linking twice is a no-op), not a replace that can lose an edit.

### In-process, the same compare

`RecordService` gains overloads, not default parameters, so code compiled against the existing ones keeps linking (as
ADR-050 did for `asPlatform`):

- `update(objectName, id, request, reason, expectedUpdatedAt: Instant?)`,
- `delete(objectName, id, reason, expectedUpdatedAt: Instant?)`,
- `patch(objectName, id, request, reason = null, expectedUpdatedAt = null)`, new;

and `WorkflowService.apply(objectName, id, transitionName, reason, expectedUpdatedAt: List<Instant>?)`. Inside the
caller's transaction the compare is the same statement, so an app's own command gets the atomic check too (ADR-038).

The `RecordStore` port gains `updateIfUnchanged`, `deleteIfUnchanged` and `transitionStateIfUnchanged`, each taking the
accepted instants and answering null or false when no row matched. They have default bodies that throw
`UnsupportedOperationException`: an app's own store keeps compiling, and refuses a precondition rather than checking
and writing apart.

### `PATCH` merges attributes

`PATCH /api/objects/{object}/records/{id}` takes the same body as `PUT` with JSON merge semantics on `attributes`
(RFC 7396): only the keys sent are written, `null` clears one, every other field keeps its stored value, also against
a concurrent write of another field, since the statement assigns only the sent columns. Sections (geometries) merge as
they already do on `PUT`. `PUT` is unchanged.

`RecordService` runs `PUT` and `PATCH` through one path, so the rules cannot drift apart: `UPDATE` permission,
`apiOnly` on the API route (`403`), field permissions, `appendOnly` (`409`), `requiresReason` (`400` on `reason`), the
RELATION target check (ADR-031 D29, D30, only for values sent and changed), every `RecordWriteGuard`, the audit row
(full before and after, so `changes` names only what moved) and every listener. A guard sees a `PATCH` as the `PUT` of
the same change: `RecordWrite.attributes` is the stored row with the sent keys over it. Two rules are `PATCH`'s own,
because a partial write names only what it means to change and a silent drop would let the caller believe it landed:

- a key that is no attribute of the object is a `400` on that key (`PUT` ignores it, as it always has);
- a field whose metadata says `editable: false` is a `403` "Field '…' is read-only" (`PUT` keeps the stored value).

A field the caller's roles may not write is a `400` on that field, as on `PUT`: the same check answers both.

## Consequences

- Concurrent edits stop overwriting each other for every client that sends `If-Match`, in one statement, inside or
  outside a transaction; `/admin` and other clients that send nothing behave as before. A partial client stops
  blanking fields it does not know.
- Observable changes, all opt-in except one: `ETag` on record answers and transitions, `If-Match` and its `412`,
  `PATCH`, and `updated_at` taken from the statement's clock instead of the transaction's start, so two writes in one
  transaction now differ in `updatedAt` (ADR-031 D36).
- wasichai-ui may adopt `If-Match` (send the `ETag` it read, re-read on `412`) and `PATCH`; it needs no change to keep
  working (ADR-032).
- An app that replaces `RecordStore` must implement the three new methods before its clients send `If-Match`;
  until then such a write is a `500`.
- Not covered: `If-None-Match` and `304` on reads; a per-object flag that makes `If-Match` mandatory (`428`), the
  issue's optional follow-up; link and unlink, for the reason above; automations and the agent's tools write without
  a precondition, as before.
- Tested by `RecordETagTest`, `RecordServicePreconditionTest`, `PhysicalTableRecordStoreTest`, and the integration
  tests `RecordPreconditionApiTest` (ETag round trip, two clients, eight concurrent writers, `PATCH` rules, the locked
  delete, one transaction) and `WorkflowOnlyApiTest`.
