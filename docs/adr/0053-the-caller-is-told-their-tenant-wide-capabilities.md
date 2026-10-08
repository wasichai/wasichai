# ADR-053: The caller is told their tenant-wide capabilities too

**Status**: accepted · 2026-10-08 · amends [ADR-020](0020-the-caller-can-ask-what-they-may-do.md), builds on
[ADR-042](0042-app-declared-actions.md) and [ADR-043](0043-service-accounts.md)

## Context

ADR-020 made `GET /api/auth/me/permissions` answer, per readable object, the record actions the caller holds, and left
`MANAGE_METADATA` and `MANAGE_ORGANIZATION` out on purpose: "they belong to the console, not to the runtime. Adding
them later is adding list entries, not a new endpoint." ADR-042 added declared actions to each object's list.

Both rights can be granted to any role with no object (`PUT /api/roles/{name}/permissions`, `objectName: null`), and
only `admin: true` showed in the answer. A role that holds `MANAGE_METADATA` looked like one that holds nothing, so a
client either showed admin screens that answer `403` or hid them from people who may use them. A social-management app
(SGSPE) builds its navigation from this answer and guessed from role names, which cannot see a grant to a custom role
([#53](https://github.com/wasichai/wasichai/issues/53)).

## Decision

The answer gains one key, `capabilities`: the built-in actions that are not tied to an object which the caller holds
with no object, in a fixed order (`MANAGE_METADATA`, then `MANAGE_ORGANIZATION`). The key is always present, `[]` when
the caller holds none.

```json
{ "admin": false, "capabilities": ["MANAGE_METADATA"], "objects": { "predio": ["READ", "CREATE"] } }
```

- It is answered by `CurrentUser.hasPermission(user, action)`, the check the services enforce with
  `requirePermission`, so it cannot disagree with them: `ADMIN` holds both; a service account never holds
  `MANAGE_ORGANIZATION`, whatever its roles say (ADR-043); only an org-wide grant counts, because the metadata and
  organization endpoints check with no object.
- The list is `CallerPermissionsService.CAPABILITIES`. A new object-less built-in action is one more entry there.
- `admin` and `objects` are unchanged, byte for byte: an object's array still lists the record actions and the declared
  ones, never `MANAGE_METADATA`, even when it is granted on that one object. Whether to list it there is left open.
- The answer stays a report and grants nothing (ADR-020): every write is still checked by the service that performs it.
- A later read scope for the caller (ADR-048) gets a key of its own beside these. Nothing is reserved in this list
  for it, and this ADR does not define it.

## Consequences

- A client can show tenant administration to whoever may use it, from the platform's own answer. wasichai-ui may read
  `capabilities`; it needs no change to keep working, since the key is additive.
- A non-administrator's call costs two more one-row queries; the administrator's costs none.
- The Consequences of ADR-020 that left these rights out no longer hold; its decision is otherwise unchanged.
- Observable difference from the original app: ADR-031 D38.
- Tested by `CallerPermissionsServiceTest` and the integration tests `PermissionEnforcementTest` and
  `ServiceAccountApiTest`.
