# ADR-060: RecordService.createAll, a batch create that looks things up once

**Status**: accepted · 2026-10-08 · refines [ADR-038](0038-record-service-joins-the-callers-transaction.md); keeps
ADR-031 D29 and D30 ([ADR-031](0031-deliberate-deviations-from-sapgis.md))

## Context

`RecordService.create` costs a fixed number of round trips per record: the definition (`metadata.loadDefinition`, two
statements), the permission and field access (role queries for a caller who is not `ADMIN`), the workflow state
(`workflows.stateOf`), and, since D29 and D30 ([#33](https://github.com/wasichai/wasichai/issues/33),
[#39](https://github.com/wasichai/wasichai/issues/39)), for every `RELATION` value an `objects.findById` of the target
object plus a `SELECT id … WHERE id = ANY(:ids)`, with a role-scoped `EXISTS` folded in for a reader who is not
`ADMIN`. A caller that writes many records of one object in one go pays all of it again for every record, although
the definition, the caller and most targets are the same each time ([#77](https://github.com/wasichai/wasichai/issues/77)).

The measured case is srtm-backend's annual arbitrios determination
([wasichai/srtm-backend#84](https://github.com/wasichai/srtm-backend/issues/84),
[#101](https://github.com/wasichai/srtm-backend/issues/101)): about 48 `cuota_arbitrio` records per predio, each with
four `RELATION` fields (predio, contribuyente, servicio, parámetro). Servicio and parámetro repeat across thousands of
cuotas, predio and contribuyente across the 48 of a predio. `pg_stat_statements` (track = all, PostgreSQL 18 + PostGIS,
`ADMIN` caller, 300 predios):

| | wasichai 0.2.0 | 0.3.2 |
|---|---|---|
| SQL statements per created record | 11.7 | 17.7 (19.7 before srtm cached `definitionOf`) |
| write time per record | 1.00 ms | 1.53 ms |

Eight statements per record of the increase are the relation checks, two per `RELATION` field. For a caller who is not
`ADMIN` the gap is larger. The issue proposed a batch create, a per-transaction cache of definitions and verified
targets, or both, and asked for about 10 statements per record or fewer.

## Decision

**A batch create, in process only.** `wasichai-core` adds to `RecordService`:

```kotlin
suspend fun createAll(
    objectName: String,
    requests: List<RecordRequest>,
    reason: String? = null
): List<RecordResponse>
```

It creates every request as a record of `objectName`, as the current caller (a person, a service account, or the
platform inside `asPlatform`), and answers in request order, each answer exactly what `create` would answer for that
request. An empty list answers an empty list, without a query. There is no REST route, so an `apiOnly` object takes it as it takes an
in-process `create` (ADR-040), and there is no `Idempotency-Key` variant (ADR-058).

**Once per batch**: the reason is normalised, the caller resolved, the definition loaded, the `CREATE` permission and
the object's disabled flag checked, the field access read, the workflow state asked, and the `RELATION` targets looked
up. **Per record, as `create` does**: the unwritable and required checks, field defaults (D40) and the sections filter,
the write guards (`appendOnly`, `requiresReason`, the `RELATION` check, the app's `RecordWriteGuard`s), the insert,
the stored row, the audit row, the listeners and the projection of the answer to what the caller may read. `create` and
`createAll` share these steps as private helpers, so a rule cannot apply to one and not the other.

**Relation targets once per target object.** Before the first insert, `RelationTargets.check` collects the `RELATION`
values of every record (after defaults, as the guard sees them), groups the distinct ids by target object and runs
`existing` once per target object, in the scope `rejectMissing` uses (organization only for `ADMIN`, the platform and
automations; the caller's read scope, D30, otherwise). The result, `RelationTargets.Checked`, belongs to that reader:
`rejectMissing` refuses one made for another (`require`). Each record's guard then answers from it the ids it found, and
looks up as before any id it did not find or never saw, so the check is the same rule, one query per target object per
batch instead of per record. A miss is read again because an earlier record's listener may have written that target
meanwhile, which a `create` loop would see; that read happens only on the way to a refusal or such a write. Grouping by
target object, not by field as the issue sketched, is D29's "one read per target object": two fields to the same object
share one lookup. Nothing to look up runs no query. The public `RecordWriteGuards.beforeWrite(definition, change,
reader)` that other modules call is unchanged; an internal overload takes the `Checked`.

**Errors are the ones `create` gives, record by record, in request order.** The batch throws exactly the exception
that calling `create` for each request in turn would throw first. The checks that need no database run for every
request up front, and a failure is kept and thrown when the loop reaches that record, so a record that fails late in
the list never hides an earlier one's error. A missing or unreadable target fails the record that names it with D29's
`ValidationException`, "Invalid value for '<field>'" on the field, byte for byte the same.

**One transaction, all or nothing.** The batch joins the caller's transaction when there is one (ADR-038) and runs
inline in it; with none it opens its own, the same "join or open" as `IdempotencyKeys.once` (ADR-058). The first
failing record, a throwing guard or a throwing listener fails the batch, and nothing of it is stored, audited or kept.
This narrows ADR-038's "never opens a transaction itself" a third time, after the append-only delete (ADR-044) and the
keyed create (ADR-058): `RecordService` still never suspends or commits a caller's transaction and never moves a write
to another connection. `RecordService` takes a `transactions: () -> TransactionalOperator` supplier as its last
constructor argument, resolved on first use as `IdempotencyKeys` does.

**Why not the per-transaction cache (option 2).** A cache that outlives one call has to be told when it is wrong: a
definition changed in the same transaction, a target deleted after it was found, a role or a read scope that changed,
an app's `RecordReadScope` that depends on data the transaction wrote. Every write path (update, links, transitions,
automations, related records) would have to keep it right, and the D29/D30 check would answer from state no statement
read. The batch's lookups live for one call, for one reader, before its first insert, so none of that arises. The batch
is the concrete user; the cache has no user the batch does not serve (rule 10). It can come with a new ADR when one
shows up, for example repeated `create` calls from code that cannot gather its records first.

**No ADR-031 entry.** Nothing changes over REST or for `create`: a batch stores the same records, audit rows and listener
calls as the same `create` calls inside one transaction, as ADR-038's in-process transaction and ADR-039's platform
writes added none.

## Consequences

- The measured case drops to at most 10 statements per record, pinned by `RecordBatchCreateApiTest`: on core alone,
  48 records with four `RELATION` fields cost 2.21 statements per record for `ADMIN` (12.00 with a `create` loop) and
  2.25 for a scoped reader (14.00), with one `id = ANY` lookup per target object for the batch instead of 192. What is left per
  record is the insert, the stored row, the audit row, the listeners' own writes and any `RELATION` id the batch could
  not answer.
- A batch is atomic even outside a caller's transaction: a listener that throws on record 40 undoes records 1 to 39,
  where 40 plain `create` calls would keep them (ADR-025). It holds one connection, and the foreign-key locks its
  inserts take, until it commits. The app picks the batch size, srtm one predio's cuotas.
- Permission, field access and relation targets are read once, at the start of the batch. A target deleted after the
  lookup still fails its foreign key with D28's `409`, as for `create`.
- A failed batch stores nothing, so it can be sent again whole. A batch that committed but whose answer was lost is the
  app's to detect, by a unique constraint (ADR-037) for example; there is no key for it.
- `update` and `delete` are unchanged.
- Tested by `RecordServiceBatchTest` (lookups once, one `existing` per target object over the distinct ids, the first
  failing record in order, request order, the empty list, the reader mismatch) and the integration test
  `RecordBatchCreateApiTest` (statements counted through a wrapped `ConnectionFactory` against the `create` loop, a
  missing and an unreadable target store nothing, a caller's transaction rolls the batch back).
