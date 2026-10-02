# ADR-039: Background work runs RecordService as the platform, and takes a cluster lock

**Status**: accepted · 2026-10-02 · builds on [ADR-016](0016-automations-queue-and-system-context.md) and
[ADR-038](0038-record-service-joins-the-callers-transaction.md)

## Context

[caja-backend](https://github.com/wasichai/caja-backend) publishes its outbox from inside the app. That needs a loop, a
lock so that one replica publishes and not all of them, and record writes made by nobody in particular: no user sits
behind a publisher ([#18](https://github.com/wasichai/wasichai/issues/18)).

Wasichai had neither. `CurrentUser.require()` needs a `Jwt` principal, so background code could not call
`RecordService` at all; srtm-backend and caja-backend wrote to the physical tables through `DatabaseClient`, resolved
through `custom_objects`, and called `AuditService` with a null user. That is internal API under ADR-024. Advisory locks
were raw SQL inside modules (`DocumentRepository`), and `AutomationDrain` keeps its own private loop.

Automations already act as the platform (ADR-016): no permission check, the organization that owns the record, a null
user in the audit row. An app needs the same thing for its own background work.

## Decision

### `RecordService.asPlatform(organizationId) { }`

```kotlin
records.asPlatform(organizationId) {
    val event = records.list("outbox_event", pending).content.first()
    records.update("outbox_event", UUID.fromString(event.id), RecordRequest(mapOf("status" to "SENT")))
}
```

Every `RecordService` call inside the block (`list`, `get`, `rows`, `create`, `update`, `delete`) acts as the platform:

- **Tenant**: every query is scoped to the organization given, exactly as a token's organization scopes a request.
- **Permissions**: none apply, as for `ADMIN`: no role permission, no field rule, no `own_records_only` filter. Field
  validation, required fields and a disabled object still refuse a write, as they do for anyone.
- **Who did it**: nobody. `created_by`, `updated_by`, the audit row's `user_id` and `RecordChange.userId` are null,
  which reads as "the platform did this", the way an automation's audit rows already do (ADR-016).

Only `RecordService` honours it. Related records, workflow transitions, metadata and the admin services still ask
`CurrentUser`, and throw 401 inside the block: each one is extended when it has a background caller, not before
(rule 10).

**A block, not a platform principal.** The alternative was an `Authentication` for the platform in Spring Security's
context. Everything that reads that context (`CurrentUser`, method security, an app's own filters) would then have to
learn that an authenticated caller may have no user id, and a mistake anywhere would widen access. The block is one
entry point, read in one place.

**The platform lives in the Reactor context**, under a key only core can name (a private instance, not a class).
`TransactionalOperator` keeps the Reactor context, so the block composes with ADR-038 either way round: a transaction
inside the block, or the block inside a transaction, commits or rolls back the platform's writes and audit rows with
everything else.

**A request never becomes the platform.** No filter, header or claim reaches the key. On top of that, `asPlatform`
throws `IllegalStateException` when the Reactor context carries Spring Security's `SecurityContext` key, which its
`ReactorContextWebFilter` puts on every request that goes through a security chain: with a token, and anonymous too
(an `OPTIONS` request, a public path). So an app cannot call it while serving a request, not even by mistake in a
service a controller reaches; it hands the work to a background job instead. Should a user's security context appear
inside the block anyway, the user wins: never the wider caller. Relaxing this is a new ADR.
`PlatformRecordServiceTest` pins both requests (with a token and without one) and the writes.

**`RecordStore.insert` and `update` take `userId: UUID?`.** Null is the platform. An app with its own `RecordStore`
changes `UUID` to `UUID?` in two signatures; core's `PhysicalTableRecordStore` binds a typed null.

**Automations triggered by a platform write run with no user.** Their run carries the triggering user, which is now
null; `AutomationRunner` writes `created_by`/`updated_by` null instead of failing the run with "no acting user".

### `ClusterLock`

A bean of core (`@ConditionalOnMissingBean`, in `WasichaiDataAutoConfiguration`) over PostgreSQL advisory locks. One
database is one cluster: every replica of an app that shares it agrees.

```kotlin
// leader work: whoever gets it publishes, the others skip this tick
clusterLock.tryLock("caja.outbox")?.use { publisher.publishPending() }

// a critical section, held until the transaction ends
clusterLock.withXactLock("caja.turno.$cajaId") { turnos.open(cajaId) }
```

- **`tryLock(key): Lease?`** takes a session lock without waiting (`pg_try_advisory_lock`), null when another session
  holds it. The lease holds a connection of its own, taken under the pool (an r2dbc `Wrapped` factory is unwrapped), so
  releasing it closes the connection and the lock goes with the session: a lock can never return to the pool still
  held. `Lease.release()` is idempotent; `Lease.use { }` releases when the block ends, throws or is cancelled. A lease
  never released keeps its connection and its lock until the app stops.
- **`withXactLock(key) { }`** takes a transaction lock, waiting for it (`pg_advisory_xact_lock`), and runs the block in
  a `TransactionalOperator` with the default propagation: it joins the caller's transaction (ADR-038), so the lock
  lasts until that one commits or rolls back, or opens one of its own. The transaction manager is looked up on first
  use, so an app without one can still `tryLock`.
- **Keys are strings**, turned into the bigint PostgreSQL wants by the first eight bytes of their SHA-256
  (`ClusterLock.lockId`). Not `hashtext`: it is internal to PostgreSQL and has changed between versions, and two
  replicas that disagree on the number hold "the same" lock at once. `ClusterLockKeyTest` pins two values; changing
  the hash is a breaking change. Name keys like the app's packages (`caja.outbox`) so modules do not collide.

**Not added**: a blocking session lock, a `tryXactLock`, and a scheduler. The issue's loop is Spring's `@Scheduled` or
a coroutine the app owns; none of these has a user yet (rule 10). `DocumentRepository` keeps its `hashtext` key and
`AutomationDrain` its loop: moving them would change their lock ids under a running cluster for no gain.

## Consequences

- Background code calls the supported API (`RecordService`, `ClusterLock`) instead of the physical tables and
  `AuditService`, which stay internal (ADR-024).
- A platform write is audited and announced to listeners like any other, with a null user, so automations and history
  see it.
- Platform writes cannot be told apart from each other in the audit log: one platform, no job name. An app that needs
  that writes it into the record itself.
- `asPlatform` cannot be used inside a request. That is the price of "a request never becomes the platform", and the
  reason it is a hard error instead of a convention.
- Each held lease is one PostgreSQL connection outside the pool's limit.
