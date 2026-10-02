# ADR-042: An object declares its own actions, and they are granted, checked and listed like CRUD

**Status**: accepted · 2026-10-02

## Context

Permissions are `(role, object, action)` over a closed set: `READ`, `CREATE`, `UPDATE`, `DELETE`, `MANAGE_METADATA`,
`MANAGE_ORGANIZATION`, enforced by a `CHECK` on `permissions.action` and by `AdminService`, which answers
`400 Unknown action` to anything else. An app with privileges that are not CRUD has two ways round that today, and
both are wrong.

caja has five: cobrar, anular, reimprimir, reversar un cierre, and anular el recibo de otro cajero. caja-backend splits
each into an object of its own (`anulacion_recibo`, `reimpresion_recibo`, `reversion_cierre`) so the privilege becomes a
`CREATE` there. The last one has no object to hide behind, so it is checked by **role name** in app code: a second
permission engine outside the platform, the thing ADR-020 exists to prevent. `GET /api/auth/me/permissions` cannot
report it, so the UI cannot hide or explain the button from the platform's answer.

## Decision

**An object declares its actions as metadata.** A new table `object_actions (object_id, name, label)`, keyed by
`(object_id, name)`, managed through a sub-resource of the object, the way fields are:

```http
GET    /api/metadata/objects/{object}/actions             READ on the object
POST   /api/metadata/objects/{object}/actions             { "name": "ANULAR_AJENO", "label": "Anular recibo ajeno" }
DELETE /api/metadata/objects/{object}/actions/{action}    MANAGE_METADATA
```

The name is upper snake, `^[A-Z][A-Z0-9_]{1,48}$` like a role name (sent in any case, stored upper), and never one of
the six built-in actions: a declared action adds a verb, it cannot shadow one. Both rules are also `CHECK`s on the
table. A repeated name is `409`. There is no update: the name is what grants and app code refer to, and `label` alone
did not earn an endpoint yet.

A sub-resource rather than an `actions` list on `POST/PUT /api/objects`: object update has no replace-a-list semantics
today, and a sub-resource keeps "remove one action" a single explicit request instead of a diff.

**Granted through the same endpoint, on its object only.** `PUT /api/roles/{name}/permissions` accepts a declared
action with the `objectName` that declares it. Without an object, or on an object that does not declare it, it is the
same `400 Unknown action '…'` as any undeclared name; the action is judged before the object, as it always was. The
message is unchanged; the violation's text now reads "must be one of READ, …, MANAGE_ORGANIZATION or an action the
object declares". A
declared action means nothing tenant-wide, because it is the object's verb.

The database holds that rule, not only the service. `permissions` gains a stored generated column,
`declared_object_id`: the row's `object_id` when the action is not built in, `NULL` when it is. A composite foreign key
`(declared_object_id, action) → object_actions (object_id, name) ON DELETE CASCADE` binds only declared grants (a
`NULL` skips the check), and the old `CHECK` becomes "built in, or names an object". So a grant of an undeclared
action cannot be stored, and **removing a declared action removes its grants** by cascade, in the same statement.
Deleting the object cascades both. `STORED` is spelled out because PostgreSQL 18 defaults generated columns to
virtual, which a foreign key cannot use.

**Checked by the call that already exists.** `CurrentUser.requirePermission(user, "ANULAR_AJENO", objectId)` needs no
change: it looks for an allowed row with that action on that object, which is exactly a declared grant. The
administrator short-circuits it, so `ADMIN` holds every declared action, as it holds every built-in one. That
short-circuit does not ask whether the action is declared: a typo in app code passes for the administrator and fails
for everyone else, the same as a typo of a built-in action today.

**Listed in the ADR-020 answer.** `GET /api/auth/me/permissions` keeps its shape and its rule (only objects the caller
may `READ`). Each object's list is the record actions held, then the declared actions held, by name:

```json
{ "admin": false, "objects": { "recibo": ["READ", "CREATE", "ANULAR_AJENO"] } }
```

The administrator gets the four record actions plus every action each object declares. One extra query either way,
not one per object.

## Consequences

- caja can drop `anulacion_recibo`, `reimpresion_recibo` and `reversion_cierre` as permission carriers and declare
  `ANULAR`, `REIMPRIMIR`, `REVERSAR` and `ANULAR_AJENO` on the objects they act on. The role-name check goes.
- The REST change is additive on the wire: a new sub-resource, and new strings in lists that already held strings. It
  is **not** harmless to every client. wasichai-ui's roles page reads a role's permissions into a matrix typed by a
  closed `Action` union and drops the rows it does not know; saving then sends the replace-all
  `PUT /api/roles/{name}/permissions` without them, which deletes the role's declared-action grants. Until wasichai-ui
  follows up (widen `Action` to `string`, keep and show declared rows, listing them per object from
  `GET /api/metadata/objects/{object}/actions`), an administrator who saves a role there loses its declared grants.
  A client that only reads `GET /api/auth/me/permissions` and looks for the four record actions is unaffected.
- An app's own endpoints still call `requirePermission` themselves. The platform knows the verb exists and who holds
  it; what the verb does stays in the app, as every non-CRUD behaviour does.
- `SchemaParityTest` lists the new table and the `permissions` changes as known deviations under this ADR. They add a
  feature, but the observable changes above (the violation text, the permissions list, the roles-page hazard) are
  recorded as [ADR-031](0031-deliberate-deviations-from-sapgis.md) D26.
- Not done, for want of a user: renaming or relabelling a declared action, tenant-wide declared actions, declared
  actions in the definition response. Each is additive when someone needs it.
