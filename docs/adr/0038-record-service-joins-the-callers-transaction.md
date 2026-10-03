# ADR-038: RecordService joins the caller's transaction, and that is supported API

**Status**: accepted · 2026-10-02 · refines [ADR-025](0025-extension-spis.md)

## Context

[ADR-025](0025-extension-spis.md) says `RecordService` opens no transaction of its own and leaves atomicity to a
listener that needs it. It does not say what happens when the caller opens one. An app needs that answer:
[caja-backend](https://github.com/wasichai/caja-backend) charges payment orders, and one charge writes a turno, a
receipt, its lines, the orders and an outbox event. Those writes commit together or not at all; compensating afterwards
is not acceptable for money.

It works today, but only by accident of the implementation. Every write of `RecordService` goes through R2DBC
`DatabaseClient`, whose connection is the one bound to the current reactive transaction
(`ConnectionFactoryUtils.getConnection`), and so are the audit row and the listeners' own writes. ADR-024 says only SPIs
and exposed beans are supported API, so an app depending on this had nothing to lean on
([#13](https://github.com/wasichai/wasichai/issues/13)).

## Decision

**`RecordService` joins the caller's reactive transaction, and this is supported.** `create`, `update` and `delete`,
the audit row each one writes and every `RecordChangeListener` that writes through R2DBC take part in whatever
transaction is active when `RecordService` is called. Nothing commits until the caller's transaction does; if it
fails, all of it rolls back together.

An app uses Spring's `TransactionalOperator`, which is a bean of every app that has the R2DBC starter:

```kotlin
@Service
class ChargeService(
    private val records: RecordService,
    private val transactions: TransactionalOperator
) {
    suspend fun charge(request: Charge) =
        transactions.executeAndAwait {
            val receipt = records.create("receipt", RecordRequest(request.receipt()))
            request.orders.forEach { records.update("order", it.id, RecordRequest(it.paid())) }
            records.create("outbox_event", RecordRequest(request.event(receipt.id)))
            receipt
        }
}
```

- An exception out of the block, from `RecordService` or a listener or the app's own code, rolls back every record
  write and every audit row in it.
- `CurrentUser.require()` and the permission checks work inside the block: the security context lives in the Reactor
  context, which `executeAndAwait` keeps. Tenant filtering and field access behave exactly as outside.
- The block sees its own writes: a `get` after a `create` finds the record.
- `@Transactional` on a Spring bean's suspend function does the same; `TransactionalOperator` is the form this ADR pins.
- Outside a transaction nothing changes: each call commits by itself, and a listener that throws after the write leaves
  that write committed (and its audit row), as ADR-025 describes.

**No `RecordService.inTransaction { }` helper.** It would only wrap `TransactionalOperator.executeAndAwait`, and an
abstraction needs a concrete second user (rule 10). Spring's operator is the API.

**What the platform promises.** `RecordService` never opens, suspends or commits a transaction itself, and never moves
a write to another connection. A change that would break that (a `REQUIRES_NEW` audit write, a listener that hops to a
different scheduler for its write) needs a new ADR. `RecordServiceTransactionTest` in `wasichai-core` pins the
behaviour: several writes with a failing listener or an explicit throw at the end leave no record and no audit row, a
completed block commits all of it, and the security context works inside.

## Consequences

- A listener that writes on another thread or connection (a detached coroutine, an outbox drained later) is outside the
  caller's transaction. Writing through `DatabaseClient` in the listener's own call joins it.
- Keep the calls inside the block sequential. They share one connection.
- ADR-025's sentence "a listener that needs atomicity opens one itself" stays true for a listener that has to be atomic
  with its own writes; an app that needs the whole business operation atomic opens the transaction around it.
