# ADR-080: `RecordService.upsertAll`, a batch write by natural key

**Status**: accepted · 2026-11-14 · builds on [ADR-062](0062-batch-record-creation.md),
[ADR-037](0037-composite-unique-constraints-and-409-on-repeats.md) and
[ADR-051](0051-optimistic-locking-and-partial-update-of-records.md)

## Context

An app that keeps an object as a copy of what it computes needs to sync it on every change: `hneyra/curricula`
publishes its catalog (plans, cycles, courses, syllabi) as Custom Objects rebuilt on every import and merge
([#163](https://github.com/wasichai/wasichai/issues/163)). `createAll` (ADR-062) only creates, `update` and `patch` want
the id, the idempotency key (ADR-058) covers creates alone, and the record store has no `ON CONFLICT`. Syncing meant
reading everything, comparing in the app and sending hundreds of single updates.

## Decision

`wasichai-core` adds to `RecordService`:

```kotlin
suspend fun upsertAll(
    objectName: String,
    key: List<String>,
    requests: List<RecordRequest>,
    reason: String? = null,
    deleteMissing: Boolean = false
): UpsertResult // created, updated, unchanged, deleted, records
```

**The key is a natural key the model already has**: one field with `unique: true`, or one of the object's composite
`uniqueConstraints` (ADR-037), named in any order and case. Anything else is a `400` on `key`. Every request carries
every part of it, not `null`, and no two the same: a `400` naming the field and the records, before anything is
written. Postgres lets many `NULL`s through a unique, so a `NULL` part names no record.

**Per request**: the records that carry the keys are read in the caller's reach (owner filter and read scope, as
`update` reads), up to 200 keys per statement, `(a = :c0 AND b = :c1) OR …` bound by each field's handler. A request
whose key no record carries is created as `createAll` creates it. One whose record reads otherwise is patched as
`patch` patches (ADR-051): only what it sends is written, `null` clears, what it leaves out stays. One whose record
already reads as it says is neither written, audited nor told. Values compare as a read answers them, through the
field's handler (`"5"` and `5` are one `INTEGER`, `1.50` and `1.5` one `DECIMAL`); a section value is compared the
same way, so a module type whose read form differs from what it takes counts as changed.

**Merge, not replace.** A sync usually sends every field, and then merge and replace are the same; when it does not, a
field it does not own must not be cleared. `deleteMissing = true` makes it a full sync: every record in the caller's
reach whose key no request carries is deleted, as `delete` deletes, page by page.

**Order**: deletes, then updates, then creates, each in request order, so a sync can free a unique value before it is
taken again. `records` answers one record per request, in request order: what the create or the update answered, or
the record as the caller reads it.

**The same rules.** A key that is no attribute of the object is a `400`, and a person's read-only one a `403`, as in a
`PATCH`; the platform writes it (ADR-082). The caller needs `CREATE` when something is created, `UPDATE` when a record
carries one of the keys and `DELETE` with `deleteMissing`, checked before the first write. Guards, validation,
defaults, value providers, the audit row (as the object says, ADR-079) and listeners run per written record exactly as
in `create`, `patch` and `delete`, which it calls. `reason` (ADR-041) goes on every one.

**One transaction**: the caller's when there is one (ADR-038), else its own, as `createAll`. Any failure stores
nothing. It works as the platform (ADR-039). In process only, as `createAll`: there is no REST route, so an `apiOnly`
object takes it (ADR-040).

**Why not `INSERT … ON CONFLICT`.** It would skip the guards, the value providers, the audit and the listeners, or
re-implement each of them for one statement, and it cannot tell "the same" from "changed" without reading the row
anyway. The calls `upsertAll` makes are the ones the app would make; what it saves is the reads and the writes of what
did not change.

## Consequences

- A sync costs one read per 200 keys plus one write path per record that changed.
- The comparison is the API's: a value whose read form differs from what was sent (a `DATETIME` with more precision
  than microseconds) counts as changed every time.
- No REST change and no ADR-031 entry: what it stores is what the same `create`, `patch` and `delete` calls store.
- Tested by `RecordUpsertApiTest`.
