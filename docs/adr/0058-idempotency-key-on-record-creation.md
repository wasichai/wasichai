# ADR-058: A record create takes an Idempotency-Key, held by an advisory lock and stored with the record

**Status**: accepted · 2026-10-08 · builds on [ADR-038](0038-record-service-joins-the-callers-transaction.md),
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md),
[ADR-043](0043-service-accounts.md) and [ADR-051](0051-optimistic-locking-and-partial-update-of-records.md)

## Context

`POST /api/objects/{object}/records` is not idempotent. A client that times out, loses the connection or retries after a
`5xx` cannot tell whether the record was created, and a second send creates a second record. Unique constraints
(ADR-037) help only when the object has a natural key. Service accounts (ADR-043) retry by design. An app that takes
registrations, reports or payments from unreliable networks then holds duplicated cases that it cannot undo, because the
audit trail is append-only ([#63](https://github.com/wasichai/wasichai/issues/63)).

## Decision

**An optional `Idempotency-Key` header on `POST …/records`.** 1 to 128 printable ASCII characters, else a `400` on the
header. Without it, nothing changes: the same handler method as before serves the request (the keyed one is mapped
with `headers = "Idempotency-Key"`). `POST /api/organizations` and link are a follow-up.

**A core table, `idempotency_keys`** (`V16`): `organization_id`, `user_id`, `key`, `request_hash`, `response_status`,
`response_body`, `created_at`, unique on `(organization_id, user_id, key)` `NULLS NOT DISTINCT`. The key is the
caller's: a person or a service account (a `users` row, ADR-043); `user_id` null is the platform (`asPlatform`), one
caller like any other. Another caller's same key is another key, so no caller can replay or detect another's.

**One transaction holds the lock, the record and the key.** `IdempotencyKeys.once` joins the caller's transaction
(ADR-038) or opens one, then:

1. `pg_try_advisory_xact_lock` on a hash of (organization, caller, key) (`ClusterLock.lockId`), **without waiting**.
   Not acquired: another request with the key is running, so `409` with `Retry-After: 1` and problem+json naming the
   header (`RetryLaterException`).
2. A row of that key older than `wasichai.idempotency.ttl` is deleted: the ttl is exact whatever the purge's pace.
3. A row left: same `request_hash` replays it; another hash is a `422` naming the header
   (`UnprocessableContentException`). The hash is SHA-256 over method, path and the body as parsed with its keys sorted
   at every level (so key order and spacing do not count, a value's JSON does). `X-Change-Reason` is not in it.
4. No row: the create runs as without a key (permissions, rules, guards, audit, listeners), its answer is serialised
   with the app's `JsonMapper` and inserted with `201`. Commit makes record, audit row and key visible together.

A failed create throws out of the transaction, so nothing is stored and the key is free again: the client corrects the
body and retries with it. The same holds for a listener that throws after the write: a keyed create is atomic, where
an unkeyed one keeps the record (ADR-025).

**Why an advisory lock, and no `status` column.** The issue proposed a row with `status` = in progress, visible to the
second request. Such a row must commit before the record does, so it is a separate transaction, and then a crash leaves
a key in progress for ever, or a key without its record. Inserting the row inside the record's transaction instead makes
a concurrent second insert wait on the unique index until the first ends: one record, the other gets the replay, but it
holds a pooled connection for as long as the first runs, and many retries can drain the pool. The transaction advisory
lock gives the in-flight answer without either problem: taken and released with the transaction, never waited for, gone
with a crashed connection. A row then only ever means "committed", so `status` would always say the same and is left
out. The advisory lock key is 64 bits of a hash; a collision with another key or another lock gives a spurious `409`
that a retry clears.

**The answer is the stored bytes, also the first time.** The keyed handler writes `response_body` as it is, so the first
answer and every replay are byte for byte the same. A replay adds `Idempotent-Replayed: true` and the `ETag` of the
record as the stored answer holds it (its `updatedAt` then, ADR-051), which a later write may have moved on. The
correlation id (ADR-050) is the replay request's own. A replay writes, audits and announces nothing, and checks no
permission: it hands the caller back their own answer.

**Retention.** `wasichai.idempotency.ttl` (default `24h`, must be positive). `IdempotencyKeyPurge` deletes expired
rows of every organization in one statement every `wasichai.idempotency.purge-interval` (default `1h`, `0` keeps it
off) under `ClusterLock.tryLock` (ADR-039), so one replica purges; it touches no record and runs as nobody.

**In process.** `RecordService.create(objectName, request, reason, idempotencyKey)` is an overload (code compiled
against the three-argument call keeps working; null is the plain create). A replay returns the record as its stored JSON
holds it: a date is its ISO string, a decimal a `BigDecimal`. A key used in process and over REST by the same caller is
the same key, as long as the request hashes the same.

## Consequences

- A retrying client gets one record per key. The `409` asks it to retry; the retry gets the replay once the first one
  committed, or creates when the first one failed.
- Clients see new answers only when they send the header: `422`, a `409` with `Retry-After`, `Idempotent-Replayed`
  (ADR-031 D45). CORS exposes `Idempotent-Replayed` and `Retry-After`.
- One more table and up to four statements per keyed create (lock, expired delete, read, insert); none without a key.
- The response body is stored as text for the ttl. It holds what the caller could read of the record, no more.
- `RecordService` takes `IdempotencyKeys` as its thirteenth constructor argument.
- Needs PostgreSQL 15 or later for `NULLS NOT DISTINCT`; the platform targets 18.
- Tested by `IdempotencyKeysTest`, `WasichaiAutoConfigurationTest` and the integration test `RecordIdempotencyApiTest`.
