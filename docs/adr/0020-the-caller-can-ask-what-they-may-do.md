# ADR-020: The caller can ask what they may do, and asking grants nothing

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

Permissions are checked inside the services that perform an action, which is where they belong. But
nothing told a client in advance what the caller could do. `GET /api/objects` answers "which objects
may I read", and a definition answers "which fields may I read and write", but not "may I create,
update or delete records here".

The first client to need that answer is GIS-XP, the end-user runtime that renders this platform's
metadata through its own backend for frontend. Without it the runtime either shows every button and
lets the request fail with `403`, or computes the answer itself — which means a second permission
engine outside this repository, the one thing that must not exist.

## Decision

`GET /api/auth/me/permissions` returns, for each object the caller may read, the record actions they
hold on it: `READ`, `CREATE`, `UPDATE`, `DELETE`. The administrator gets every action on every object
and `admin: true`.

```json
{ "admin": false, "objects": { "predio": ["READ", "CREATE", "UPDATE"] } }
```

It is answered by the same `CurrentUser.permittedObjects` the services use, one query per action. It
lives in `metadata`, because the answer is keyed by object, at a path under `/api/auth/me`, because
the question is about the caller.

Field access is not repeated here: the definition endpoints already drop unreadable fields and lock
unwritable ones for the caller.

## Consequences

- A client hides the actions it would be refused, from an answer the platform gives rather than one
  it guesses.
- Asking grants nothing. Every write is still checked where it happens, so a stale or tampered answer
  on the client opens no door.
- Metadata and organization rights (`MANAGE_METADATA`, `MANAGE_ORGANIZATION`) are left out: they
  belong to the console, not to the runtime. Adding them later is adding list entries, not a new
  endpoint.
- Record-level rules (`ownRecordsOnly`) are not reported. They filter reads silently and need no
  button to be hidden.
