# ADR-047: Server push over SSE, with PostgreSQL LISTEN/NOTIFY between replicas

**Status**: accepted · 2026-10-06 · builds on [ADR-046](0046-notifications-module.md)

## Context

Notifications (ADR-046) must reach a person while they work, not when they reload. wasichai had no long-lived
endpoint: every controller is a `suspend fun` answering once. The platform runs as several replicas behind a balancer,
and a write on one must reach a stream held by another. The only infrastructure every app has is PostgreSQL.

## Decision

**Server-Sent Events for the browser, `LISTEN/NOTIFY` between replicas.**

- `GET /api/auth/me/notifications/stream` answers `text/event-stream`. It sends the person's summary (counts per kind
  and the latest notification), never the notifications themselves: the UI fetches what it shows through the usual
  routes, with the usual checks. One event type, `summary`, sent when it changes.
- **Writers notify inside their transaction** (`pg_notify` on `<metadataSchema>_notifications`): delivered on commit,
  dropped on rollback. The payload is ids only (organization, and the user for receipts).
- **One `LISTEN` connection per replica**, outside the pool, reconnecting with backoff, checked every minute, and
  followed by a "recompute everything" signal after each reconnect.
- **A refresh floor:** every stream recomputes every `stream-refresh` (60 s) whatever it heard, because a window that
  opens writes nothing. With `LISTEN` down the stream degrades to polling, never to silence.
- **Proxies:** a comment line every 25 s, `X-Accel-Buffering: no`.
- **Security:** the token travels in the `Authorization` header only, never in the URL (so the UI uses a `fetch`-based
  SSE client, not `EventSource`), and the stream completes at the token's expiry.

Not chosen: WebSockets (two-way is not needed, and they need more from proxies), a message broker (infrastructure no
app has), per-replica polling of the tables (load grows with open streams).

## Consequences

- One extra database connection per replica. PgBouncer in transaction mode breaks `LISTEN`: run it in session mode or
  give the replica a direct connection; otherwise the stream works at the refresh floor.
- A browser keeps one stream per tab; over HTTP/1.1 the UI shares one between tabs.
- The precedent is reusable: another module that needs push can listen on its own channel the same way.
