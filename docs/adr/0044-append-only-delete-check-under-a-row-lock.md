# ADR-044: The append-only delete check runs under a row lock, in one transaction with the delete

**Status**: accepted · 2026-10-03 · refines [ADR-040](0040-append-only-objects-and-a-pre-write-guard.md) and
[ADR-038](0038-record-service-joins-the-callers-transaction.md)

## Context

[ADR-040](0040-append-only-objects-and-a-pre-write-guard.md) has `RecordService.delete` refuse with `409` a record that
an append-only record points at, through a `RELATION` column or a join row. It checks with one `EXISTS` per possible
reference, then calls the store. Check and delete were separate statements, each committing by itself: an append-only
insert pointing at the record could commit in between, and the delete's `ON DELETE SET NULL` (or the join table's
`CASCADE`) then changed that new append-only row, with no guard and no history. The check is read-only; nothing it
read stopped the insert.

PostgreSQL's foreign-key check on the insert takes `FOR KEY SHARE` on the referenced row. A `SELECT … FOR UPDATE` on
that row conflicts with it, both ways round. A single `DELETE … WHERE NOT EXISTS (…)` does not close the gap: its
subquery reads the statement's snapshot, so an insert that commits while the delete waits for the row stays invisible to
it.

## Decision

**When an append-only object can point at the record, lock, check and delete run in one transaction.**
`AppendOnlyReferences.deleting(organizationId, definition, recordId, guard = { … }) { … }` first collects, from
metadata, the `RELATION` columns of append-only objects aiming at the object and the join tables it shares with one.
With none, the guards and the delete run as before: no transaction, no lock, no extra statement. With any:

1. the `EXISTS` checks, unlocked: a record already referenced is refused before the guards, in ADR-040's order;
2. the `RecordWriteGuard`s, outside the lock, so a guard that takes a lock of its own (an advisory lock, say, that an
   endpoint also takes before inserting a referencing record) cannot deadlock against the row lock;
3. `SELECT id FROM <table> WHERE id = :id AND organization_id = :org FOR UPDATE` on the record;
4. the `EXISTS` checks again, each a statement of its own;
5. `RecordStore.delete`.

Steps 3 to 5 run in the caller's transaction when one is active (`TransactionSynchronizationManager`
`isActualTransactionActive`), inline, and in a `TransactionalOperator` of their own otherwise. Inline, not through a
participating operator: a refusal is the caller's to catch (ADR-038), and a participant that fails marks the
transaction rollback-only. Spring 7.0's reactive `commit` does not act on that mark today, but nothing promises it
never will.

An insert already holding its key-share lock makes step 3 wait until it commits, and step 4 then sees it: `409`. An
insert that comes after step 3 waits for the delete's commit and then fails its foreign-key check, which the API
answers `409` too (below). Either way nothing append-only changes behind its back. The audit row and the listeners
stay where they were, after the delete.

**Read committed is assumed**, PostgreSQL's default. Step 4 sees an insert that committed while step 3 waited only
because each statement takes a fresh snapshot. A caller running `REPEATABLE READ` keeps its snapshot and can miss it;
under `SERIALIZABLE` the commit fails instead.

**A foreign-key violation answers `409`.** `GlobalExceptionHandler` maps a `DataIntegrityViolationException` whose
driver `sqlState` is `23503` to `409` problem+json ("A record this one points at does not exist any more"); any other
integrity violation stays a `500`. That is the losing insert's answer, and every other foreign-key violation's: it was
a `500` everywhere, so it is ADR-031 D28.

**This narrows ADR-038's promise.** `RecordService` still never suspends or commits a caller's transaction and never
moves a write to another connection. When there is no caller transaction, `delete` of a record that append-only
objects can point at now opens a short one of its own around lock, check and delete. Inside a caller's transaction it
runs in that one, so the lock lasts until the caller commits.

`AppendOnlyReferences.rejectDelete` is gone; `deleting` replaces it, and the constructor takes a `transactions`
supplier. The bean is still not `@ConditionalOnMissingBean`. The transaction manager is looked up on first use, as
`ClusterLock` does, so an app whose objects have no append-only referrer never needs one.

## Consequences

- An insert of an append-only record pointing at a record whose delete already holds the lock fails with `409`
  instead of committing a reference that is nulled at once.
- Deleting such a record holds its row lock for the delete only, or until the caller's transaction ends. Writers of
  append-only records pointing at it wait that long.
- Such a delete runs the reference checks twice, once before the guards and once under the lock.
- The referrer list is read from metadata before the lock. An object switched to `appendOnly`, or a `RELATION` field
  added, while a delete runs is not covered; metadata changes are an admin's deliberate step, not a data race.
- `WriteRulesApiTest` pins both orders, each interleaved through a held transaction and `pg_blocking_pids`: an insert
  in flight makes the delete wait and answer `409` with the receipt's column intact; a delete in flight makes the
  insert wait and answer `409`, with no receipt stored. It also pins that a refused delete caught inside a caller's
  transaction leaves the rest of it to commit.
