# ADR-052: The audit list pages by cursor and narrows by period and user

**Status**: accepted · 2026-10-08 · builds on [ADR-036](0036-declared-indexes-optional-count-and-keyset-reads.md),
[ADR-043](0043-service-accounts.md), [ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md),
[ADR-049](0049-admin-changes-in-the-audit-log.md) and [ADR-050](0050-correlation-id-and-change-source-on-audit-rows.md)

## Context

`GET /api/audit` took `objectName`, `recordId`, `operation`, `correlationId`, `source` and `limit`, capped at 500
(default 100), with no offset, no cursor, no period and no user. Everything past the newest 500 rows of the tenant was
out of reach of the API, and "what did user X change between two dates?" had no answer.
`/api/objects/{object}/records/{id}/history` had the same cap. A social-management app (SGSPE) must let an auditor
answer who changed what in a period; its operators queried the database by hand
([#52](https://github.com/wasichai/wasichai/issues/52)).

Record lists already page with a keyset cursor (ADR-036): `after=<nextCursor>`, opaque, `400` on a cursor of another
sort. The audit list answers a bare JSON array, and wasichai-ui and apps read it as one.

## Decision

### Four filters

`GET /api/audit` takes, besides what it took:

- `from`, `to`: ISO-8601 instants, `Z` or an offset; `occurred_at >= from AND occurred_at < to`, so consecutive
  periods never share a row. An inverted range is empty, not an error.
- `userId`: a UUID, the acting user or service account (`audit_log.user_id`).
- `serviceAccount`: a service account's name (ADR-043), matched through `service_accounts`. A deleted account's rows
  have no name left to match, as they already had no `serviceAccount` on the entry.

There is no `userEmail` filter: an email is not a stable key. Blank values are no filter, as for `correlationId` and
`source`. A malformed `from`, `to` or `userId` is a `400` whose `errors[0].field` names the parameter; `userId` must be
the canonical 8-4-4-4-12 form (`UUID.fromString` alone accepts `1-2-3-4-5`). The controller takes them as strings and
parses them itself for that reason. A `+` left unencoded in an offset arrives as a space and is read back as `+`.

The record history takes `from`, `to` and `userId` the same way.

### Keyset pages, the cursor in a header

Both routes take `after=<cursor>`. The list is ordered by `(occurred_at DESC, id DESC)` as before, and a page starts
strictly after the cursor's row: `occurred_at <= :at AND (occurred_at < :at OR id < :id)`, whose first half bounds the
index scan. Every page reads one row more than `limit`; when that row exists, the response carries
`X-Next-Cursor: <cursor>` taken from the page's last row. The last page has no header.

The body stays a JSON array, so a client that knows nothing of paging reads exactly what it read before; that is why
the cursor is a header and not a `nextCursor` property as on record pages. `limit` keeps its default of 100 and its cap
of 500 per page.

The cursor is built like `RecordCursor` (ADR-036): base64url of a version, a filter hash, the row's `occurred_at`
(full microseconds) and its `id`. The filter hash is a truncated SHA-256 of everything that shapes the list, as
applied (the route, `objectName`, `recordId`, the upper-cased `operation`, `correlationId`, `source`, `from`, `to`,
`userId`, `serviceAccount`), not the `limit`, which may change between pages. A cursor that does not decode, or whose
hash is not the request's, is a `400` naming `after`: a cursor never silently continues another list. The tenant is not
in the cursor: a cursor sent to another tenant only resumes that tenant's own rows at that point.

### The read scope after the page

ADR-048 drops the entries of records outside the caller's read scope after the rows are read. The cursor is taken from
the page's last row **as read**, before that drop. So an unscoped caller gets every row exactly once; a scoped caller
gets every in-scope entry exactly once too, but a page may come short of `limit`, even empty, with `X-Next-Cursor`
still set. A client follows the header until a page comes without it, not until a page comes short. Filtering in SQL
instead would mean evaluating each object's read scope inside the audit query, which ADR-048 kept out of audit.

The admin rows of ADR-049 stay filtered in SQL for a caller without `MANAGE_ORGANIZATION`, before the limit, so they
never shorten a page.

### One index

`V12__audit_user_index.sql` adds `audit_log_user_time_idx (organization_id, user_id, occurred_at DESC)`: equality on a
tenant and a user, the period and the order on the third column. V1's `(organization_id, occurred_at DESC)` already
serves `from`/`to` alone. Core has no `V11`: the number was reserved for
[#51](https://github.com/wasichai/wasichai/issues/51), which shipped without a migration. Flyway accepts the gap, and
no later migration may take `V11`, since a database past `V12` would refuse it.

### The service

`AuditQueryService.page(…, filter, after)` and `historyPage(…, filter, after)` return an `AuditPage(entries,
nextCursor)`. `list` and `history` keep their signatures and return the first page's entries, so the agent's history
tool and apps calling them are unchanged. `AuditFilter` gains `from`, `to`, `userId` and `serviceAccount`.

Core's default CORS configuration exposes `X-Next-Cursor` (`Access-Control-Expose-Headers`), so a UI served from
another origin can read it. An app that declares its own `CorsConfigurationSource` adds it there.

## Consequences

- The whole log is reachable through the API, one page of up to 500 at a time, and "who changed what between two
  dates" is one request per page.
- New query parameters and a new response header: ADR-031 D37. A request without the new parameters answers the same
  body as before; the header is the only addition.
- A scoped caller may see short or empty pages before the end. The UI must loop on the header, not on the page size.
- Core's migrations skip `V11`; the next core migration is `V13`.
- One more index on `audit_log`, maintained on every write.
- Tested by `AuditPagingTest`, `CoreMigrationSqlTest`, `WasichaiAutoConfigurationTest` and the integration test
  `AuditPagingApiTest` (1,200 rows at `limit=500`, filters and combinations, `400`s, unchanged default, history, a
  scoped caller, the query plan); `SchemaParityTest` lists the index as a D37 deviation.
