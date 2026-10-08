# REST API

Base path `/api`. Everything except `/api/auth/login`, `/api/auth/token` and `/api/health` needs
`Authorization: Bearer <token>`.

## Correlation id

Every response carries `X-Correlation-Id`, a `401` or `403` included. A request may send its own: exactly one value
matching `^[A-Za-z0-9._-]{1,64}$` is kept and echoed; anything else (a malformed value, more than 64 characters, two
headers) is replaced by a generated UUID, never echoed. With no header the server generates one. The id is stored on
every audit entry the request writes, and on the automation runs it queues, so `GET /api/audit?correlationId=…` lists
everything one request changed ([ADR-050](../adr/0050-correlation-id-and-change-source-on-audit-rows.md)). It is in
the server's log lines as the MDC key `correlationId`.

## Modules and routes

Core serves auth, organizations, objects, fields, declared actions, relationships, records, related records, caller
permissions, audit and history, and admin. Every other route belongs to one module and exists only when that module is
installed (its starter is on the classpath and `wasichai.<module>.enabled` is not `false`):

| Module | Routes | Doc |
|---|---|---|
| views | `/api/metadata/objects/{object}/views`, `/api/objects/{object}/views/**` | [views.md](../modules/views.md) |
| forms | `/api/metadata/objects/{object}/forms`, `/api/objects/{object}/forms/**` | [forms.md](../modules/forms.md) |
| pages | `/api/metadata/{objects/{object}/pages,page-templates}`, `/api/{objects/{object}/pages,pages}/**` | [pages.md](../modules/pages.md) |
| workflow | `/api/objects/{object}/workflow`, `/api/objects/{object}/records/{id}/transitions/**` | [workflow.md](../modules/workflow.md) |
| automation | `/api/automation-runs`, `/api/objects/{object}/automations/**` | [automation.md](../modules/automation.md) |
| documents | `/api/documents/{id}`, `/api/objects/{object}/{document-types,records/{id}/documents}/**` | [documents.md](../modules/documents.md) |
| notifications | `/api/{,auth/me/}notifications/**`, `/api/{,objects/{object}/}notification-rules/**` | [notifications.md](../modules/notifications.md) |
| gis | `/api/gis/layers/**`, `/api/gis/services`, `/api/gis/objects/{object}/features/**` | [gis.md](../modules/gis.md) |
| agent | `/api/agent/status`, `/api/agent/ask` | [agent.md](../modules/agent.md) |
| files | `/api/objects/{object}/files/{field}`, `/api/objects/{object}/records/{id}/files/{field}` | [files.md](../modules/files.md) |

A route of a module that is not installed answers `404` to an authenticated caller and `401` without a token, never
`403`. The frontend relies on that `404` to tell "not installed" from "not allowed" (ADR-031 D1).

## Auth

```http
POST /api/auth/login        { "email": "...", "password": "..." }  →  { token, expiresAt, user }
POST /api/auth/token        { "clientId": "...", "clientSecret": "..." }  →  { token, expiresAt, serviceAccount }
POST /api/auth/logout       → 204, every token the caller holds stops working (with revocation on)
GET  /api/auth/me
GET  /api/auth/me/permissions   what the caller may do with each object they can read
GET  /api/auth/me/org-units     the caller's own organizational units (see below)
GET  /api/auth/me/notifications  the caller's notifications, with wasichai-notifications (see below)
GET  /api/auth/me/notification-preferences  which kinds reach the caller by email, with wasichai-notifications
GET  /api/auth/me/preferences   → { "theme": "system", "locale": null }
PUT  /api/auth/me/preferences   { "theme"?: "dark", "locale"?: "en" | null }  →  the stored preferences
```

Signing in and out ([ADR-059](../adr/0059-token-revocation-login-limits-and-password-policy.md)):

- **Logout.** `POST /api/auth/logout` takes the caller's token and answers `204`. With
  `wasichai.security.jwt.revocation=true` every token the caller was issued until then is refused from that moment
  (`401`); a new sign-in works at once. With revocation off (the default) it answers the same and the token keeps
  working until `exp`, so a client can call it unconditionally on sign-out.
- **Revoked tokens.** With revocation on, a token issued before an administrator disabled its user, changed their
  password or roles, or deleted them, or before a service account was disabled, given new roles, rotated or deleted,
  is `401`, like an expired one: at once on the node that made the change, within `revocation-cache` (5 s) on others.
- **Too many attempts.** With `wasichai.security.login.enabled=true`, the `max-attempts`-th failed sign-in (5) for an
  email from one client address, or the `account-max-attempts`-th (20) for an email from anywhere, is
  `429 Too Many Requests` with `Retry-After` (seconds) and `detail` `Too many sign-in attempts, try again later`; so is
  every attempt until the `window` (15 minutes) that the first one opened closes, the right password included. A
  known and an unknown email get the same answers. `POST /api/auth/token` is limited the same way per client id. A
  success forgets the count.
- **Password policy.** `POST /api/users`, a `password` in `PUT /api/users/{id}` and `POST /api/organizations` check
  the new password against the `PasswordPolicy`: `400` with one `errors` entry per broken rule on `password`
  (`adminPassword` for provisioning). By default the one rule is 8 characters, answered as always:
  `detail` `Password too short`, `must be at least 8 characters`. Several broken rules, or any other one, say
  `Password does not meet the password policy`.

Tokens carry a `jti` claim, a random id per token.

```json
{ "admin": false, "capabilities": ["MANAGE_METADATA"], "objects": { "predio": ["READ", "CREATE", "UPDATE"] } }
```

Only objects the caller may `READ` are listed, as in `GET /api/objects`, each with the record actions
the caller holds on it: `READ`, `CREATE`, `UPDATE`, `DELETE`, followed by the [declared actions](#declared-actions)
they hold, by name (`["READ", "CREATE", "ANULAR_AJENO"]`). The administrator gets all four on every
object, plus every action the object declares. Field access is not repeated here: the definition endpoints already leave out the fields the
caller cannot read and mark the ones they cannot write `editable: false`.

`capabilities` lists the built-in actions that are not tied to an object which the caller holds tenant-wide, that is
granted with no object (`objectName: null`): `MANAGE_METADATA` (objects, fields, relationships, declared actions),
`MANAGE_ORGANIZATION` (users, roles, service accounts, units, the organization's name) and `MANAGE_TENANTS` (creating
and deleting tenants, see [Organizations](#organizations)), always in that order. The key is always present, `[]` when
the caller holds none. The administrator holds the first two, and `MANAGE_TENANTS` unless
`wasichai.organizations.separate-provisioning` is on; a [service account](#service-accounts) never holds
`MANAGE_ORGANIZATION` or `MANAGE_TENANTS`, whatever its roles. A grant on one object does
not count, and does not show in that object's array either: the arrays list record and declared actions only. It is
the same check the endpoints enforce, so a client may show or hide its admin screens on it
([ADR-053](../adr/0053-the-caller-is-told-their-tenant-wide-capabilities.md)). wasichai-ui may read it; older clients
ignore it. A later read scope gets a key of its own.

The answer is for hiding actions a client would be refused, and it grants nothing: every write is
still checked by the service that performs it (ADR-020).

`GET/PUT /api/auth/me/preferences` stores per-user UI preferences ([ADR-034](../adr/0034-user-preferences-and-themes.md)).
A field left out of the `PUT` keeps its current value; sending `null` for `locale` clears it back to "the browser's".
`theme` must match `^[a-z0-9-]{1,40}$` and is never checked against a list of known themes, since themes belong to
each app, not to the server; `locale` must be a BCP 47 tag. Either failing that shape answers `400`. An unknown field
in the body is refused with `400` rather than silently ignored. A caller with no stored row yet reads the defaults
(`{ "theme": "system", "locale": null }`) without a row ever being written for them.

`GET /api/auth/me` carries `serviceAccount`, the account's name, when a [service account](#service-accounts) calls; it
is never set for a person: the key is left out, so a person's answer is unchanged.

## Service accounts

A server-to-server caller of one organization ([ADR-043](../adr/0043-service-accounts.md)). It holds roles like a
user and signs in with a client id and a secret instead of an email and a password.

```http
GET    /api/service-accounts                 the tenant's accounts
POST   /api/service-accounts                 { "name": "rentas", "roles": ["SISTEMA_ORIGEN"] }  →  201, with clientSecret
GET    /api/service-accounts/{id}
PUT    /api/service-accounts/{id}            { "enabled"?: false, "roles"?: [...] }   roles replace the whole set
POST   /api/service-accounts/{id}/secret     rotate  →  with the new clientSecret
DELETE /api/service-accounts/{id}            →  204
```

```json
{
  "id": "…", "clientId": "…", "name": "rentas", "enabled": true, "roles": ["SISTEMA_ORIGEN"],
  "createdAt": "2026-10-02T09:00:00Z", "secretRotatedAt": "2026-10-02T09:00:00Z", "clientSecret": "…"
}
```

Every route needs `MANAGE_ORGANIZATION`, and only reaches the caller's own tenant: another tenant's account is `404`,
as one that never existed. `clientSecret` is in the answer to create and rotate only, and is never returned again: the
server keeps a hash. `clientId` is the account's `id`. `name` is lower case, `^[a-z][a-z0-9_-]{1,48}$`, unique in the
tenant (`409` otherwise) and never changes. A role the tenant does not have is `400`, and so is `ADMIN`: a service
account never administers the tenant, and a service account's token is refused (`403`) on every
`MANAGE_ORGANIZATION` route, whatever its roles grant.

`POST /api/auth/token` is public. A known, enabled account with the right secret gets a JWT carrying its roles, its
organization and `service_account: "<name>"`, for `wasichai.security.jwt.service-account-ttl` (15 minutes by
default); the client asks again when it runs out. Anything else, an unknown or malformed id, a wrong secret, a
disabled or deleted account, is `401` with the same `detail`, `Invalid client credentials`. Disabling, rotating or
deleting stops new tokens at once; a token already issued lives until it expires, unless
`wasichai.security.jwt.revocation` is on, when it is refused too (so is one issued before new roles).

The account is backed by a user row with the same id, so its writes are recorded under that id like anyone's:
`created_by`, `updated_by` and the audit log. That user is not listed by `GET /api/users`, cannot sign in, and is
`404` to the user routes.

## Organizational units

A tree of units per organization (gerencia › subgerencia › área) and who sits in which
([ADR-045](../adr/0045-organizational-units.md)). A unit addresses people; it grants nothing and is not in the token.

```http
GET    /api/org-units                  the tenant's units, flat, by label  →  [{ code, label, parentCode, memberCount }]
POST   /api/org-units                  { "code": "SGFT", "label": "...", "parentCode"?: "GR" }  →  201
GET    /api/org-units/{code}           the unit and its members
PUT    /api/org-units/{code}           { "label"?: "...", "parentCode"?: "GR" | null }  rename or move
DELETE /api/org-units/{code}           →  204, only an empty leaf
PUT    /api/users/{id}/org-units       { "units": ["SGFT", "SGR"] }  replaces the user's whole set  →  the user
GET    /api/auth/me/org-units          the caller's own units, with their paths
```

```json
{ "code": "SGFT", "label": "Subgerencia de Fiscalización Tributaria", "parentCode": "GR", "memberCount": 3 }
```

`GET /api/org-units/{code}` answers `members` instead of `memberCount`, sorted by email:

```json
{
  "code": "SGFT", "label": "Subgerencia de Fiscalización Tributaria", "parentCode": "GR",
  "members": [{ "id": "…", "email": "ana@muni.pe", "displayName": "Ana" }]
}
```

Units are addressed by `code`, never by id: it is what apps bind to (`model/org_units.json`, a notification's
audience), and it never changes. A `code` is trimmed and upper-cased, then must match `^[A-Z][A-Z0-9_]{1,48}$`; it is
unique in the tenant (`409 Organizational unit 'SGFT' already exists`). The path `{code}` is normalised the same way,
so `/api/org-units/sgft` is the same unit. `label` is trimmed text of 1 to 120 characters and may change. A unit with
no `parentCode`, or a blank one, is a root. The list is flat, sorted by label then code; the client builds the tree
from `parentCode`.

`PUT /api/org-units/{code}` takes a map, as `PUT /api/auth/me/preferences` does: `label` renames; `parentCode` moves
the unit under another one, and `null` (or blank) moves it to the root; a key left out keeps its value. Any other key,
`code` included, is `400` on that key (`is not a unit property`), and nothing changes. A move under the unit itself or
one of its own sub-units is `400 A unit cannot move under itself`. Units nest at most 10 levels deep (a root is level
1), counting the subtree a move carries along: a create or a move past that is `400 Too deep`. Every write takes a lock
on the tenant's tree for its transaction, so two concurrent moves cannot build a cycle between them.

`DELETE` refuses a unit with sub-units (`409 Organizational unit 'GR' has sub-units`) or members
(`409 … has members`): move or empty it first. With wasichai-notifications installed, deleting a unit also drops the
notification targets that named it, so a client warns before deleting one.

`PUT /api/users/{id}/org-units` replaces the user's units with the ones listed; an empty list, or a body without
`units`, takes the user out of every unit. Membership is many to many: a person may sit in several units. A code the
tenant does not have is `400` naming `units[i]` (`unknown unit '…'`), and nothing changes. An unknown user, another
tenant's, or a service account's backing user is `404`, as on the other user routes. The answer is the user as every
`/api/users` route answers it, now with `orgUnits`, the codes of the user's units sorted, `[]` for a user in none:

```json
{
  "id": "…", "email": "ana@muni.pe", "displayName": "Ana", "enabled": true, "roles": ["FISCALIZADOR"],
  "orgUnits": ["SGFT"], "createdAt": "2026-10-06T09:00:00Z"
}
```

`GET /api/users`, `POST /api/users`, `PUT /api/users/{id}` and `PUT /api/users/{id}/roles` carry the same `orgUnits`.
`GET /api/auth/me` does not change.

Every route except the last needs `MANAGE_ORGANIZATION` and reaches the caller's own tenant only: another tenant's
unit is `404`, as one that never existed, and naming it as a parent or a user's unit is `400`. A service account's
token is refused (`403`) whatever its roles grant, as on every `MANAGE_ORGANIZATION` route
([service accounts](#service-accounts)). A malformed body answers `400 Invalid organizational unit` with the offending
field in `errors[]` (`code`, `label`, a `parentCode` that is not text, an unknown key); a parent the tenant does not
have is `400 Unknown organizational unit '…'` on `parentCode`.

`GET /api/auth/me/org-units` needs only a token. It answers the caller's direct units, sorted by label then code, each
with `path`, the codes from the root down to the unit itself; no id, since apps bind to the code. A service account sits
in no unit and reads `[]`.

```json
[
  { "code": "SGFT", "label": "Fiscalización Tributaria", "path": ["GR", "SGFT"] },
  { "code": "TUPA", "label": "Mesa de partes", "path": ["TUPA"] }
]
```

## Objects (metadata)

```http
GET    /api/objects                              list objects in the tenant
POST   /api/objects                              create object, fields and physical table
GET    /api/objects/{object}                     definition (object + fields)
PUT    /api/objects/{object}                     relabel / describe / enable or disable / write rules
DELETE /api/objects/{object}                     drop object, its table and its metadata

GET    /api/metadata/objects/{object}            same definition, spec-shaped path
GET    /api/metadata/objects/{object}/fields
POST   /api/metadata/objects/{object}/fields     add a field (ALTER TABLE)
GET    /api/metadata/objects/{object}/actions    declared actions (see below)
GET    /api/metadata/objects/{object}/views
GET    /api/metadata/objects/{object}/pages
```

Create request:

```json
{
  "name": "predio",
  "label": "Predio",
  "pluralLabel": "Predios",
  "fields": [
    { "name": "codigo", "type": "TEXT", "required": true, "unique": true },
    { "name": "area", "type": "DECIMAL" },
    { "name": "uso", "type": "ENUM", "enumOptions": ["RESIDENCIAL", "COMERCIAL"] },
    { "name": "contribuyente", "type": "RELATION", "relationTarget": "contribuyente" },
    { "name": "lote", "type": "GEOMETRY", "geometryType": "POLYGON", "srid": 32718 },
    { "name": "acceso", "type": "GEOMETRY", "geometryType": "POINT", "srid": 32718 }
  ]
}
```

### Indexes

```json
{
  "name": "cuota",
  "label": "Cuota",
  "fields": [
    { "name": "anio", "type": "INTEGER" },
    { "name": "mes", "type": "INTEGER" },
    { "name": "codigo", "type": "TEXT", "indexed": true }
  ],
  "indexes": [["anio", "mes"]]
}
```

`indexed: true` on a field gives its column an index of its own. `indexes` lists composite ones, field names in index
order. Both are built on the organization's table along with the object, and every `RELATION` column gets an index
without being asked, because PostgreSQL does not index a foreign key. `indexed` comes back on a field only when it is
`true`, and `indexes` on an object or a definition only when there are some, so a model that declares none reads
as before ([ADR-036](../adr/0036-declared-indexes-optional-count-and-keyset-reads.md)).

`PUT /api/objects/{object}` with `indexes` replaces the list: an index no longer listed is dropped and a new one is
built. Without `indexes`, the list is left as it is, and `[]` drops every composite index. `PUT …/fields/{field}`
with `indexed` adds or drops the field's own index. Sending what is already declared changes nothing. A `400` names
`indexes` (or `indexed`) for an empty entry, more than 32 fields, an unknown field, a field named twice, or a field
that cannot be indexed: `LONG_TEXT`, or a type that cannot be filtered on, such as a geometry. Deleting a field that a
composite index names is a `409`, and so is deleting the relationship that owns such a field: remove it from
`indexes` first.

### Unique constraints

```json
{
  "name": "orden_de_cobro",
  "label": "Orden de cobro",
  "fields": [
    { "name": "sistema_origen", "type": "TEXT" },
    { "name": "referencia_externa", "type": "TEXT" }
  ],
  "uniqueConstraints": [["sistema_origen", "referencia_externa"]]
}
```

`uniqueConstraints` lists uniqueness over two or more fields, in the same shape as `indexes` and checked the same way:
two to 32 fields per entry. Each entry is a real `UNIQUE (a, b, …)` on the organization's own table, so two records
of one organization cannot share the combination and another organization's records never count. A one-field entry
is a `400` naming `uniqueConstraints`: a single field takes `unique: true`. As with `unique`, `NULL`s are
distinct: a record with an empty field in a set never collides, so `["caja", "cajero", "fecha"]` only stops repeats
among records that fill all three. Make the fields `required` when that matters.
`uniqueConstraints` comes back on an object or a definition only when there are some
([ADR-037](../adr/0037-composite-unique-constraints-and-409-on-repeats.md)).

`PUT /api/objects/{object}` with `uniqueConstraints` replaces the list, without it the list is left as it is, and `[]`
drops them all. A `400` names `uniqueConstraints` for the same mistakes as `indexes`.
Adding one the existing records already repeat is a `409` naming `uniqueConstraints`, and nothing changes, the
object's other properties included. So is making a field `unique` over repeated values (`409` naming `unique`).
Deleting a field that a unique constraint names, or the relationship that owns it, is a `409`.

A record write that repeats a unique value, of a `unique` field or of a `uniqueConstraints` entry, answers `409` with
one `errors[]` entry per field of the constraint. The values themselves are never echoed back:

```json
{
  "type": "https://wasichai.dev/problems/409",
  "title": "Conflict",
  "status": 409,
  "detail": "Another record already has this sistema_origen, referencia_externa",
  "errors": [
    { "field": "sistema_origen", "message": "must be unique together with referencia_externa" },
    { "field": "referencia_externa", "message": "must be unique together with sistema_origen" }
  ]
}
```

A geometry is a field like any other (ADR-019), so an object has as many as it needs and gains one
after the fact through `POST …/fields`. `geometryType` is required on one, `srid` defaults to 4326
and `dimension` to 2; `unique` and `defaultValue` are refused on one. The object's `geometry` in the
response is its **first** geometry field, kept for callers that only ask whether it is spatial.

Update request — everything except the technical name:

```json
{ "label": "Predio catastral", "pluralLabel": "Predios", "description": "Unidad de suelo", "enabled": true }
```

`name` is immutable. It backs the physical table and the API path, so sending a different one is
answered with `400` naming the field rather than being ignored. Create a new object instead.

`enabled: false` retires an object without losing anything: its records stay readable, and
`POST`, `PUT` and `DELETE` on them answer `409`. It is the reversible alternative to deleting.

`DELETE` is not. It drops the physical table with its records, and the views, forms, pages,
permissions, workflow and automations that hang off the object. It is refused with `409` instead when:

- another object has a `RELATION` field pointing here — the response names them as `object.field`;
- the object is `appendOnly`, or shares a `MANY_TO_MANY` join table with an `appendOnly` object (see "Write rules").

Nothing else stops it. Relationships owned by the object go with it, join tables included, and a published
GeoServer layer is unpublished first so no layer is left pointing at a table that no longer exists.

### Write rules

Three flags, all `false` unless set, all in the create request, in `PUT` and in every object response (`GET
/api/objects`, `GET /api/objects/{object}`, `GET /api/metadata/objects/{object}`)
([ADR-040](../adr/0040-append-only-objects-and-a-pre-write-guard.md)):

```json
{ "name": "recibo", "label": "Recibo", "appendOnly": true, "apiOnly": true, "fields": [] }
```

- `appendOnly: true` — records are created, never changed or deleted, by anyone: `PUT`, `PATCH` and `DELETE` on a record, a
  workflow transition, and a link or unlink touching one of its records answer `409`, for `ADMIN` too, even a link
  that already exists or an unlink of one that does not (elsewhere those are a no-op `204`). So do deleting the
  object, one of its fields, a relationship that holds its values, or an object it shares a join table with: switch
  `appendOnly` off first. Deleting another object's record that an append-only record points at, through a
  `RELATION` field or a `MANY_TO_MANY` link, answers `409` naming the append-only object, for `ADMIN` too: the
  database would otherwise null the field or drop the link behind the append-only record's back. The check and the
  delete run in one transaction behind a lock on the record, so an append-only record created at the same moment
  either is seen (`409`) or fails its own insert with `409`; it is never nulled
  ([ADR-044](../adr/0044-append-only-delete-check-under-a-row-lock.md)).
- `apiOnly: true` — the generic record API (`POST`, `PUT`, `PATCH`, `DELETE` under `/records`, and link or unlink when either
  end is api-only) answers `403` on writes, for `ADMIN` too. Only the app's own code writes it, in-process. Reads
  are unchanged.
- `requiresReason: true` — every write of its records must say why, in the `X-Change-Reason` header (see "Change
  reason" under Records): without one, `POST`, `PUT`, `PATCH`, `DELETE`, a link or unlink touching one of its records and a
  workflow transition answer `400` on `reason`, and nothing is stored
  ([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)).

On `PUT`, leaving any of the three flags out keeps it as it is (unlike `enabled`, which defaults to `true`): a client
that does not know a flag never switches it off by saving a label.

## Declared actions

```http
GET    /api/metadata/objects/{object}/actions             what may be granted beyond CRUD (READ on the object)
POST   /api/metadata/objects/{object}/actions             declare one (MANAGE_METADATA)
DELETE /api/metadata/objects/{object}/actions/{action}    remove it and every grant of it (MANAGE_METADATA)
```

An object declares the verbs it has beyond `READ`/`CREATE`/`UPDATE`/`DELETE`, so a role can be granted them and the
app can check them ([ADR-042](../adr/0042-app-declared-actions.md)):

```json
{ "name": "ANULAR_AJENO", "label": "Anular recibo ajeno" }
```

`name` is upper snake, `^[A-Z][A-Z0-9_]{1,48}$`, sent in any case and stored upper; one of the built-in actions
(`READ`, `CREATE`, `UPDATE`, `DELETE`, `MANAGE_METADATA`, `MANAGE_ORGANIZATION`, `MANAGE_TENANTS`) or a bad shape is
`400`, a name the
object already declares is `409`. `label` defaults to the name. The response, and each entry of the list, is
`{ name, label }`. Deleting an action the object does not declare is `404`.

A declared action is granted with `PUT /api/roles/{name}/permissions` like any other, always with the `objectName` that
declares it:

```json
{ "permissions": [ { "objectName": "recibo", "action": "READ" }, { "objectName": "recibo", "action": "ANULAR_AJENO" } ] }
```

The same entry without `objectName`, or naming an object that does not declare the action, is
`400 Unknown action 'ANULAR_AJENO'`, as is any name that is neither built in nor declared. The app checks it with
`CurrentUser.requirePermission(user, "ANULAR_AJENO", objectId)`; `ADMIN` holds every declared action. The caller sees
the ones they hold in `GET /api/auth/me/permissions`. Removing the declaration removes its grants with it.

The permissions `PUT` replaces the role's whole set, so a client must send the declared grants back with the rest. A
client that drops actions it does not know deletes them on save; wasichai-ui's roles page does this today, until its
`Action` type is widened and it keeps declared rows (ADR-042).

## Organizations

```http
GET    /api/organizations/current      the caller's tenant
PUT    /api/organizations/current      rename
POST   /api/organizations              provision a tenant plus its first administrator
DELETE /api/organizations/current      drop the tenant and every table it owns
```

There is deliberately no list-all endpoint: a tenant must not be able to enumerate the others.
Provisioning needs `MANAGE_TENANTS` and creates the organization, an `ADMIN` role with every
permission except `MANAGE_TENANTS`, and the administrator account:

```json
{ "name": "Municipalidad", "slug": "muni", "adminEmail": "admin@muni.pe", "adminPassword": "…" }
```

Renaming needs `MANAGE_ORGANIZATION`; provisioning and deleting need `MANAGE_TENANTS`
([ADR-055](../adr/0055-tenant-provisioning-apart-from-tenant-administration.md)). Who holds it depends on
`wasichai.organizations.separate-provisioning`:

| Switch | `MANAGE_TENANTS` is held by |
|---|---|
| `false` (default) | whoever holds `MANAGE_ORGANIZATION`, the administrator included, as before |
| `true` | only a role granted `MANAGE_TENANTS` with no object; `ADMIN` and `MANAGE_ORGANIZATION` alone get `403` |

A service account is refused (`403`) either way. The action is granted like any object-less one:

```json
{ "permissions": [ { "objectName": null, "action": "MANAGE_TENANTS" } ] }
```

with two rules of its own in `PUT /api/roles/{name}/permissions`: an entry naming an object is `400` naming
`objectName`, and a request that adds, removes or changes a role's `MANAGE_TENANTS` entry is
`403 Missing permission MANAGE_TENANTS` unless the caller's own roles hold the grant (being `ADMIN` is not enough),
whatever the switch. A request that leaves the entry as it is needs `MANAGE_ORGANIZATION` only. The first holder is
granted out of band: see [Build your app](../guides/build-your-app.md#operator-and-customer-tenants).

## Fields

```http
GET    /api/metadata/objects/{object}/fields
POST   /api/metadata/objects/{object}/fields          add a field (ALTER TABLE ADD COLUMN)
PUT    /api/metadata/objects/{object}/fields/{field}  label, required, unique, enum options, default, visibility
DELETE /api/metadata/objects/{object}/fields/{field}  drop the field and its column
```

Updating applies DDL alongside the metadata, in one transaction: `required` toggles `NOT NULL`,
`unique` adds or drops the constraint, `indexed` adds or drops the field's index, and new `enumOptions` replace the
`CHECK`.

`defaultValue` is text, parsed as the field's type parses a value sent for it: `"5"` for an `INTEGER`, `"true"` for a
`BOOLEAN`, `"2026-01-31"` for a `DATE`, one of the options for an `ENUM`, a record id for a `RELATION`. One the type
cannot parse is a `400` naming `defaultValue`, on `POST` and on `PUT`; a blank one is none, and on `PUT` clears it.
New `enumOptions` that leave the current default out are a `400` naming `enumOptions`. A `PUT` without either
property never checks the stored default. A `GEOMETRY` field takes no default. The default fills a record's field on
create only (see "Defaults" under Records); records that exist keep what they hold. The column has no SQL `DEFAULT`:
an insert that bypasses the API gets `NULL` (ADR-031 D40).

`name` and `type` are immutable: views, forms and automation rules refer to a field by name, and a
type change may lose data. Sending either is answered with `400` naming the field — add a new field
instead.

Deleting drops the column and its data. It is refused with `409` when the field is not the caller's
to drop:

- it belongs to a relationship — delete the relationship instead, which removes both sides;
- the object's `indexes` or `uniqueConstraints` name it — remove it from them first (see "Indexes" and "Unique
  constraints");
- an automation reads it, writes it, or fills it when creating a record — the response names the
  rules, because a rule that lost its field only fails the next time it fires.

### System fields

```http
GET    /api/metadata/system-fields    the names the platform keeps for itself
```

Static and the same for every object and organization, so the field editor can list them instead of
letting the administrator find out through a `400`. Each entry is `{ name, type, scope }`, where
`type` is the `FieldType` vocabulary and `scope` says where the column lives:

| scope | meaning | names |
|---|---|---|
| `ALWAYS` | on every record table | `id`, `organization_id`, `created_at`, `updated_at`, `created_by`, `updated_by` |
| `WORKFLOW` | only once a workflow is attached (ADR-013) | `workflow_state` |
| `RESERVED` | refused, but no column exists — `type` is `null` | `version`, `count`, `after` |

Every published name is refused as a field name with `400 … is reserved by the platform`, whatever
the case it is sent in. SQL keywords are refused too but are not published: they are not names anyone
reaches for by accident.

## Relationships

```http
GET    /api/relationships
POST   /api/relationships
PUT    /api/relationships/{name}              the labels, and only the labels
DELETE /api/relationships/{name}
GET    /api/objects/{object}/relationships    the relationships this object takes part in, either side
```

```json
{
  "name": "predio_titular",
  "label": "Titular",
  "inverseLabel": "Predios",
  "type": "MANY_TO_ONE",
  "source": "predio",
  "target": "contribuyente",
  "fieldName": "titular"
}
```

`PUT` takes `{ "label": …, "inverseLabel": … }`; a `null` field is left as it is, and a blank
`inverseLabel` is stored as none, which makes the other side fall back to that object's plural label.
`label` may not be left blank.

`type`, `source` and `target` are accepted only to be refused with `400`: the column or join table
behind the relationship would have to move between tables, and the links stored in it cannot follow.
Delete the relationship and create it again — knowing that deleting drops the column, and its data,
with it.

Creating a relationship builds what it needs: a `RELATION` field with a real foreign key
(`MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY`) or a join table (`MANY_TO_MANY`). Deleting it removes
them again.

`GET /api/objects/{object}/relationships` answers one entry per side the object stands on, ordered by the relationship's
`label`:

```json
[
  { "relationship": "unidad_jefe", "label": "Jefe", "type": "MANY_TO_ONE",
    "objectName": "persona", "objectLabel": "Personas", "many": false },
  { "relationship": "unidad_padre", "label": "Unidad padre", "type": "MANY_TO_ONE",
    "objectName": "unidad", "objectLabel": "Unidades", "many": false, "direction": "forward" },
  { "relationship": "unidad_padre", "label": "Subunidades", "type": "MANY_TO_ONE",
    "objectName": "unidad", "objectLabel": "Unidades", "many": true, "direction": "inverse" }
]
```

A relationship whose `source` and `target` are the same object (a parent unit, a previous version, a duplicate-of) is
listed **twice**, forward first, each entry describing what that direction reads (see the table under Related records):
`label` when the read stands on the source end, `inverseLabel` (or the object's plural label when there is none) when
it stands on the target end, and `many` accordingly. Any
other relationship is listed once, and its entry has no `direction` key at all. Pass `direction` to the related read
below to walk the side an entry describes. A client that keys entries by `relationship` alone should key them by
`relationship` and `direction`, or keep the first (forward) entry of each name, which is the one it saw before
(ADR-031 D42).

### Related records

```http
GET    /api/objects/{object}/records/{id}/related/{relationship}
POST   /api/objects/{object}/records/{id}/related/{relationship}            { "otherId": "…" }
DELETE /api/objects/{object}/records/{id}/related/{relationship}/{otherId}
```

The read works from **either** end: from a plot it returns its owner, from the owner it returns their
plots. Link and unlink apply to `MANY_TO_MANY` only — for the others, set the field on the record.

The read takes the record list's paging and filters (`page`, `size`, `sort`, `dir`, `q`, field filters, `count=false`,
`after=`; see Records), plus `direction`. On a relationship from an object to itself the object is both ends, so
`direction` says which end the record stands on:

```http
GET /api/objects/unidad/records/{child}/related/unidad_padre                      its parent (forward, the default)
GET /api/objects/unidad/records/{parent}/related/unidad_padre?direction=inverse   its children
```

| Type | `forward` (the default) | `inverse` |
|---|---|---|
| `MANY_TO_ONE`, `ONE_TO_ONE` | the record its field points at (from the source end) | the records whose field points at it |
| `ONE_TO_MANY` | the record its field points at (from the target end) | the records whose field points at it |
| `MANY_TO_MANY` | the targets it was linked to (from the source end) | the sources linked to it |

`forward` is the walk this read always made, so no default read changes: for a `ONE_TO_MANY` self-relationship it is
the target end, which holds the key, and its forward entry in the listing says `inverseLabel` and `many: false`. On a
relationship between two objects, `forward` or no `direction` reads from the object's own end, as always. `inverse` on a relationship between two
different objects is a `400` naming `direction` (read it from the other object instead), and so is any value other than
`forward` or `inverse` (case and surrounding spaces do not matter). On this route `direction` is that parameter, never
a filter on a field of that name. Permissions, field permissions, own-records-only and the app's read scope apply to
an inverse read exactly as to a forward one. Link and unlink take no `direction`: the record in the path is the
source of a `MANY_TO_MANY` self-relationship and `otherId` its target.

A link or unlink writes both records: `409` when either end is `appendOnly`, `403` when either end is `apiOnly`, `400`
on `reason` when either end is `requiresReason` and no `X-Change-Reason` came. A reason sent is stored on both
records' history.

Either record missing, of another organization, or not the caller's under own-records-only is a `404` "Record … does
not exist", `otherId` included: a link addresses both records as `PUT` addresses one. A `RELATION` value, by contrast,
is a field of the record written, so naming no record there is a `400` on the field (see Records).

## Pages

Module: wasichai-pages.

```http
GET    /api/pages                              every page in the tenant
GET    /api/pages/{name}
POST   /api/pages
PUT    /api/pages/{name}
DELETE /api/pages/{name}                       reset: the object falls back to the generated default
GET    /api/objects/{object}/pages/record-detail   the page to render — stored, or derived from metadata
GET    /api/metadata/objects/{object}/pages        stored pages for the object
GET    /api/metadata/page-templates                the template catalogue — static, tenant-free
```

The resolve endpoint always answers. When no page is stored it derives one from the object's
metadata and marks it `generated: true`, so the client has a single rendering path (ADR-011).

A page's `definition` holds one root node, `page` — always a `PAGE`, always holding one `REGION`
child per region its `template` declares, no more and no fewer, in the template's order (ADR-022).
Inside a region the tree is exactly the free tree ADR-021 describes: `SECTION`, `TABS` and `TAB` hold
children to any depth, each laying out its own with its own `layout`.

`template` travels as the **whole catalogue entry**, not just its name — `PageRenderer` needs the
column spans to lay the page out, and a bare name would cost a second request and a loading state on
a component that has none today.

```json
{
  "id": "cf04993e-6642-33b8-a741-6f116f092c50",
  "name": "predio-record-detail",
  "label": "Predio",
  "objectName": "predio",
  "kind": "RECORD_DETAIL",
  "template": {
    "name": "one-region",
    "columns": 12,
    "rows": [
      { "regions": [{ "name": "MAIN", "span": 12 }] }
    ]
  },
  "generated": true,
  "definition": {
    "page": {
      "type": "PAGE",
      "children": [
        {
          "type": "REGION",
          "region": "MAIN",
          "children": [
            {
              "type": "TABS",
              "column": 1,
              "layout": "single-column",
              "children": [
                { "type": "TAB", "title": "DETAILS", "key": "DETAILS", "children": [{ "type": "FORM" }] },
                { "type": "TAB", "title": "MAP", "key": "MAP", "children": [{ "type": "MAP", "title": "Predio" }] },
                {
                  "type": "TAB",
                  "title": "RELATED",
                  "key": "RELATED",
                  "children": [{ "type": "RELATED_LIST", "title": "Titular", "relationship": "predio_titular" }]
                },
                { "type": "TAB", "title": "HISTORY", "key": "HISTORY", "children": [{ "type": "HISTORY" }] }
              ]
            }
          ]
        }
      ]
    }
  }
}
```

A generated page carries a deterministic id derived from the object and kind, so a client can key on
it even though no row exists yet. It always uses `one-region`, the only template whose region is
guaranteed non-empty for any object: the form and the workflow panel go to a `DETAILS` tab, the map
to `MAP` when the object has geometry, each related list to `RELATED`, and the trail to `HISTORY`, all
inside the single `MAIN` region. Each of those tabs carries its title as its `key`.

### Templates

`GET /api/metadata/page-templates` returns the catalogue, in this shape:

```json
[
  { "name": "one-region", "columns": 12, "rows": [{ "regions": [{ "name": "MAIN", "span": 12 }] }] },
  {
    "name": "main-and-right-sidebar",
    "columns": 12,
    "rows": [{ "regions": [{ "name": "MAIN", "span": 8 }, { "name": "RIGHT", "span": 4 }] }]
  }
]
```

Nine entries, fixed in code — no create, update or delete, and no per-tenant customisation. `columns`
is always 12; `span` is out of that, so `grid-column: span N` and `N/12` both fall out of the same
number. Region names are a single global vocabulary (`HEADER`, `MAIN`, `LEFT`, `CENTER`, `RIGHT`),
shared across templates on purpose, and `MAIN` appears in every one of them: it never needs a new
region when the template changes, only `HEADER` and the sidebars ever can.

`POST /api/pages` and `PUT /api/pages/{name}` take `template` as a bare name (default `one-region`
on create; unchanged on update when omitted) — the client picks one, it does not describe one. The
response always carries the full object back, per the shape above.

### Component fields

Every node in the tree, container or leaf, has the same shape; only the fields its type reads are
non-null.

| Field | Type | Meaning |
|---|---|---|
| `type` | string | one of the types below |
| `column` | int, default 1 | which column of the **parent** container holds this node |
| `title` | string? | label; a `TAB`'s title is what shows on the strip |
| `layout` | `single-column` \| `two-column`, default `single-column` | how this node lays out **its own** children; ignored by a leaf |
| `children` | node[] | only `PAGE`, `REGION`, `TABS`, `TAB` and `SECTION` may have any |
| `region` | string? | `REGION`: which of the template's regions this is, e.g. `MAIN` |
| `relationship` | string? | `RELATED_LIST`: which relationship to follow |
| `fields` | string[]? | `FORM`: a subset of the object's fields, in order; `null` means all |
| `form` | string? | `FORM`: a stored form to render instead of `fields`; mutually exclusive with it |
| `geometry` | string? | `MAP`: one geometry field to draw; `null` draws every one the object has |
| `content` | string? | `TEXT`: the note to show |
| `action` | `TRANSITION` \| `NAVIGATE` | `ACTION`: what the button does |
| `transition` | string? | `ACTION`/`TRANSITION`: the workflow transition to apply |
| `target` | string? | `ACTION`/`NAVIGATE`: an object name; navigates to its record list |
| `url` | string? | `ACTION`/`NAVIGATE`: an external `http(s)://` url |
| `style` | `PRIMARY` \| `SECONDARY`, default `SECONDARY` | `ACTION`: button emphasis |
| `key` | string? | `TAB` only: a stable name a link opens the tab by (`?tab=KEY`); see below |

A `TAB` may carry a `key` ([ADR-046](../adr/0046-notifications-module.md), ADR-031 D33). It is trimmed and
upper-cased on write, blank counts as none, and it must then match `^[A-Z][A-Z0-9_]{0,39}$`. It is unique in the whole
page, tabs of nested strips included, so a key never names two tabs. A component without a key is stored and sent
without the property, never as `null`, so a page that uses no keys reads exactly as before. A `PUT` without a
`definition` keeps the stored keys.

A generated page keys its tabs with their titles: `DETAILS`, `RELATED`, `HISTORY`, and each module tab its own
(`MAP` from wasichai-gis). The built-in keys come first, `RELATED` even on an object without a related tab: a module tab
whose title repeats a key already taken keeps its tab but gets no key. Opening a tab from `?tab=` and editing keys in
the builder belong to wasichai-ui; there a key the page lacks falls back to the first tab (ADR-046). The server never
checks a link's tab against pages.

### Component types

| Type | Kind | Renders |
|---|---|---|
| `PAGE` | container, the root, `REGION` children only | scaffolding; draws nothing itself |
| `REGION` | container, only inside `PAGE` | scaffolding; one of the template's named slots |
| `TABS` | container, `TAB` children only | a tab strip |
| `TAB` | container, only inside a `TABS` | one panel of the strip, labelled by `title` |
| `SECTION` | container, any children | a titled group, no strip |
| `FORM` | leaf | the dynamic form; the first `FORM` in the tree, in document order, owns saving — every other one renders read-only |
| `MAP` | leaf | the record's geometries, or the one `geometry` names |
| `RELATED_LIST` | leaf | related records, either direction |
| `TEXT` | leaf | a note, plain text |
| `HISTORY` | leaf | the record's audit trail |
| `WORKFLOW` | leaf | the record's state and the transitions open to the caller |
| `ACTION` | leaf | a button that fires a transition or navigates |

`TABS` accepts only `TAB` children, and a `TAB` exists only inside a `TABS` — a rule about what the
types mean, not a limit on how deep or wide the tree may otherwise go. Every other container accepts
anything, including another container.

`PAGE` and `REGION` are scaffolding, not something an administrator places (ADR-022). `PAGE` is
always the root, holding exactly the regions its `template` declares, in the template's order — no
more, no fewer, none renamed or reordered. Nothing below `PAGE`'s children is scaffolded: a `REGION`
holds a fully free tree, exactly as `SECTION` does today.

A `TAB`'s `title` is its label on the strip. The **generated** page uses the keys `DETAILS`, `MAP`,
`RELATED` and `HISTORY`, which the client translates — the server has no language. Anything an
administrator types is shown exactly as typed. Its `key`, when it has one, is what a link names, and
is never shown.

### Bounds

| Bound | Value | Refusal |
|---|---|---|
| Depth | 12 | `400`, "nesting must be at most 12 deep" |
| Total nodes | 200 | `400`, "a page holds at most 200 components" |

Depth rose from 10 to 12 when the `PAGE`/`REGION` scaffold was added (ADR-022): the scaffold itself
spends two levels, so the free tree inside a region keeps exactly the ten ADR-021 gave it. Neither
bound is reachable by dragging in the editor; both exist for a `definition` typed or generated by
hand.

### Refusals

Definitions are validated on write. A `400` names the offending component and the reason:

- `type` naming anything other than a real component type — *"Unknown component '…'"* / `"must be
  one of PAGE, REGION, TABS, TAB, SECTION, FORM, MAP, RELATED_LIST, TEXT, HISTORY, WORKFLOW,
  ACTION"`;
- `template` naming anything other than a catalogue entry — *"Unknown template '…'"*
  / `"must be one of one-region, two-regions, …"`;
- `layout` — a container's own, or the page's — naming anything other than `single-column` or
  `two-column` — *"Unknown layout '…'"* / `"must be one of single-column, two-column"`;
- a leaf carrying `children`;
- a `TABS` with a child that is not `TAB`, or a `TAB` outside a `TABS`;
- a definition with no `page` — *"A definition with no page"* / *"definition must hold one page
  node"* — or a root that is not `PAGE` — *"The root is a TYPE"* / *"the root must be a PAGE"*;
- a `PAGE` found anywhere but the root — *"A PAGE inside the tree"* / *"PAGE is the root and nothing
  else"*;
- a child of `PAGE` that is not `REGION` — *"The page holds TYPE"* / *"PAGE accepts only REGION
  children"*;
- a `REGION` anywhere but under `PAGE` — *"A REGION sits outside the page"* / *"REGION must be a
  child of PAGE"*;
- the page's regions not matching the template's, exactly — repeated, missing, unknown to the
  template, or merely out of order — *"template '…' declares regions HEADER, MAIN, RIGHT"*;
- `region` naming anything other than `HEADER`, `MAIN`, `LEFT`, `CENTER` or `RIGHT` — *"Unknown
  region '…'"*;
- `column` outside `1..parentLayout.columns` — checked against the **parent's** layout, not the
  page's;
- `RELATED_LIST` with a missing or blank `relationship` — *"RELATED_LIST needs a relationship"* /
  `"relationship is required"` — or one naming anything other than a relationship of this object;
- `FORM` naming both `form` and `fields`, or naming a field or form that does not exist;
- `TEXT.content` blank;
- `MAP` on an object with no geometry, or `MAP.geometry` naming an unknown geometry field;
- `ACTION` with no `action` kind — *"ACTION needs a kind"* / `"action must be TRANSITION or
  NAVIGATE"`;
- `ACTION.action` naming anything other than `TRANSITION` or `NAVIGATE` — *"Unknown action '…'"* /
  `"action must be one of TRANSITION, NAVIGATE"`;
- `ACTION.style` naming anything other than `PRIMARY` or `SECONDARY` — *"Unknown style '…'"* /
  `"style must be one of PRIMARY, SECONDARY"`;
- `ACTION`/`TRANSITION` with a missing or blank `transition` — *"ACTION/TRANSITION needs a
  transition"* / `"transition is required"` — or one naming a transition the object's workflow does
  not have, or any transition at all on an object with no workflow;
- `ACTION`/`NAVIGATE` naming both `target` and `url`, or naming neither;
- `ACTION`/`NAVIGATE.target` naming anything other than an object of this organization — a
  relationship of the same name does not count; navigating to a related record is what
  `RELATED_LIST` is for;
- `ACTION`/`NAVIGATE.url` not starting with `http://` or `https://`;
- `key` on anything but a `TAB` — *"key is only for TAB"* / `"SECTION cannot carry a key"`;
- a `TAB.key` that, trimmed and upper-cased, does not match `^[A-Z][A-Z0-9_]{0,39}$` — *"Invalid tab
  key '…'"* / `"key must match ^[A-Z][A-Z0-9_]{0,39}$"`;
- two tabs with the same key anywhere in the page, nested strips included — *"repeated tab key '…'"*
  / `"a tab key names one tab in the page"`.

## Views

Module: wasichai-views.

A view is a saved list configuration for an object: columns and their order, exact-match filters, a
sort and a page size. An object may have several; one may be the default the list opens with.

```http
GET    /api/objects/{object}/views            stored views, or a single generated default
POST   /api/objects/{object}/views
GET    /api/objects/{object}/views/{name}     the name "default" resolves the default
PUT    /api/objects/{object}/views/{name}
DELETE /api/objects/{object}/views/{name}     reset: the object falls back to the generated default
GET    /api/metadata/objects/{object}/views   stored views only
```

```json
{
  "id": "…", "name": "comerciales", "label": "Predios comerciales", "objectName": "predio",
  "isDefault": false, "generated": false,
  "definition": {
    "columns": ["codigo", "direccion", "area"],
    "filters": { "uso": "COMERCIAL" },
    "sort": { "field": "codigo", "direction": "ASC" },
    "pageSize": 25
  }
}
```

The generated default takes the object's visible fields in order, capped at eight, with no filters.
Marking a view default clears the flag on the object's other views in the same transaction.
Validation rejects a column, filter key or sort field that is not a field of the object (or one of
`id`, `created_at`, `updated_at` for sorting), and a page size outside 1–200.

## Forms

Module: wasichai-forms.

A form arranges an object's fields into titled sections. A page's `FORM` component may name one
instead of listing fields.

```http
GET    /api/objects/{object}/forms            stored forms, or a single generated default
POST   /api/objects/{object}/forms
GET    /api/objects/{object}/forms/{name}     "default" resolves the default
PUT    /api/objects/{object}/forms/{name}
DELETE /api/objects/{object}/forms/{name}
GET    /api/metadata/objects/{object}/forms   stored forms only
```

```json
{
  "id": "…", "name": "alta", "label": "Alta de predio", "objectName": "predio", "generated": false,
  "definition": {
    "sections": [
      { "title": "Identificación", "fields": ["codigo", "direccion"] },
      { "title": "Valoración", "fields": ["area", "uso"] }
    ]
  }
}
```

A field may appear in only one section, must exist, and must be editable. The generated default is a
single untitled section with every editable field.

## Documents

Module: wasichai-documents.

A **document type** is a template an admin writes once; a record **issues** it, by hand or when a
workflow state is reached. Each issue takes a correlative — `SGTM-2026-001` — and freezes a copy.

### Types

```http
GET    /api/objects/{object}/document-types
POST   /api/objects/{object}/document-types
GET    /api/objects/{object}/document-types/{name}
PUT    /api/objects/{object}/document-types/{name}
DELETE /api/objects/{object}/document-types/{name}
```

```json
{
  "id": "…", "name": "oficio", "label": "Oficio", "prefix": "SGTM", "objectName": "predio",
  "template": {
    "type": "doc",
    "content": [
      { "type": "paragraph", "content": [
        { "type": "text", "text": "Berlín, " },
        { "type": "platformValue", "attrs": { "value": "today" } }
      ] },
      { "type": "relatedTable", "attrs": { "relationship": "predio_titular" } }
    ]
  }
}
```

`template` is the editor's node tree, **not HTML**: the server never renders it, and the client
walks it to elements. Three node types mean something to the server, and it checks what they name:

- `objectField` — `attrs.field` must be a field of the object.
- `relatedTable` — `attrs.relationship` must be one of its relationships.
- `platformValue` — `attrs.value` must be one of `today`, `now`, `user`, `id`, `documentName`,
  `documentPrefix`, `documentSerial`, `documentNumber`.

The document's own identity comes in pieces, so a heading can read
`[documentName] [documentPrefix]-[documentSerial]` and print as "Oficio SGTM-2026-001".

Refusals: `name` must match `^[a-z][a-z0-9_-]{0,48}$`; `prefix` must match `^[A-Z][A-Z0-9]{0,11}$`;
a prefix already used by another type in the organization is a 409 — the counter runs per type, so
two types sharing a sigla would each issue their own `SGTM-2026-001`. A type that has issued any
document cannot be deleted.

### Issuing

```http
GET    /api/objects/{object}/records/{id}/documents          every document this record issued, newest first
POST   /api/objects/{object}/records/{id}/documents/{type}   issue one
GET    /api/documents/{id}
```

```json
{
  "id": "…", "number": "SGTM-2026-001", "year": 2026, "sequence": 1, "status": "VALID",
  "recordId": "…", "objectName": "predio", "issuedAt": "2026-09-20T09:12:00Z",
  "snapshot": {
    "template": { "…": "the template as it was" },
    "values": { "codigo": "P-001" },
    "platform": { "today": "2026-09-20", "documentNumber": "SGTM-2026-001" },
    "related": { "predio_titular": { "label": "Titular", "columns": [], "rows": [] } },
    "objectName": "predio", "objectLabel": "Predio", "number": "SGTM-2026-001", "issuedAt": "…"
  }
}
```

The snapshot is frozen at issue time and carries **the template as well as the values**, so editing
a type never rewrites what it already issued. Platform values are resolved when the document is
issued, never when it is read — an archived document does not print today's date.

Issuing again archives the previous document of that type on that record: exactly one is `VALID`,
enforced by a partial unique index rather than by a service. Two issues of one type on one record
are serialised, so a double-click issues twice rather than failing.

**A document is not filtered by field permissions.** Whoever may see it sees all of it; the control
is on seeing the document at all. This is deliberate and differs from every other endpoint here —
see ADR-023.

## GIS layers

Module: wasichai-gis.

```http
GET    /api/gis/layers                          every geometry of every object, and whether GeoServer publishes it
POST   /api/gis/layers/{object}/{geometry}      publish (idempotent)
DELETE /api/gis/layers/{object}/{geometry}      unpublish
GET    /api/gis/services                        GeoServer base URLs and whether it is configured
```

**A layer is one geometry of one object**, so an object with two owns two. The forms without
`{geometry}` mean the first one, so links saved before this still work.

Publishing creates the workspace and the PostGIS datastore when missing, then registers a feature
type named `<physical table>__<column>` — two tenants with an object called `predio` do not collide,
and neither do two geometries of the same one. The feature type is a JDBC virtual table that selects
`id`, the plain columns and that one geometry, so GeoServer publishes the column we mean rather than
choosing between them, and the object's other geometries stay out of the layer's attributes.

Nothing on a record or metadata request path calls GeoServer: when it is down or disabled,
`GET /api/gis/layers` still lists the layers as unpublished and only the publishing endpoints fail.

Layers published before ADR-019 are named after the table alone. One is reported as the object's
**first** geometry being published, so it shows on the layers screen instead of sitting there
invisible, and both publishing and unpublishing retire it — republishing is how you normalise.

## Records (dynamic)

```http
GET    /api/objects/{object}/records?page=0&size=25&sort=codigo&dir=asc&q=text&<field>=<value>
POST   /api/objects/{object}/records
GET    /api/objects/{object}/records/{id}
PUT    /api/objects/{object}/records/{id}       full replace: a field left out is cleared
PATCH  /api/objects/{object}/records/{id}       partial: only the attributes sent are written
DELETE /api/objects/{object}/records/{id}
```

`GET`, `POST`, `PUT` and `PATCH` of one record answer its `ETag`; `PUT`, `PATCH` and `DELETE` take `If-Match` (see
"Concurrent edits" below). `POST` takes `Idempotency-Key` (see "Retrying a create" below).

A record:

```json
{
  "id": "…",
  "createdAt": "2026-09-17T20:17:29Z",
  "updatedAt": "2026-09-17T20:17:29Z",
  "attributes": { "codigo": "P-001", "area": 850.5, "uso": "COMERCIAL" },
  "geometries": {
    "lote": { "type": "Polygon", "coordinates": [[[-77.05, -12.05], …]] },
    "acceso": null
  }
}
```

`geometries` is keyed by geometry field name and sits beside `attributes`, never inside. Writing one
that is left out of the map leaves it alone; sending it as `null` clears it. Reading lists every
geometry the object declares, `null` included, so a missing key never means two things.

A page: `{ content, page, size, totalElements, totalPages, nextCursor }`. `nextCursor` is present only when
another row follows the page.

Query parameters: `page`, `size` (max 200), `sort` (field name or `created_at`/`updated_at`/`id`),
`dir` (`asc`/`desc`), `q` (case-insensitive search across text-like fields), `count`, `after`, `bbox`, `geometry`,
and any field name for an equality filter. Unknown field names are rejected, and so is sorting or
filtering by a geometry — `bbox` is how you filter one.

Rows that tie on the sort key come back ordered by `id`, in the same direction, so paging through them never repeats or
skips a row.

`count=false` runs no `COUNT(*)`, and the page answers `totalElements: null` and `totalPages: null`. Use it when
walking a large set that does not need a total. Any other value than `true` or `false` is a `400`.

`after=<nextCursor>` is a keyset read: the page starts strictly after the row the cursor was taken from, by its sort
value and `id`, so a large set costs one pass instead of a growing `OFFSET`, and every row comes back once, tied rows
included. Keep the same `sort`, `dir` and filters, and follow `nextCursor` until a page comes without one:

```http
GET /api/objects/cuota/records?anio=2026&size=200&count=false
GET /api/objects/cuota/records?anio=2026&size=200&count=false&after=MQpjcmVhdGVkX2F0CmEK…
```

The cursor is opaque. `after` together with a `page` above 0, a cursor issued for another `sort` or `dir`, or a value
that is not a cursor, or whose value does not fit the sort key's type, is a `400` naming `after`. `totalElements`,
when counted, is the whole match, not what is left after the cursor. Related-record lists take `count` and `after` the
same way.

`geometry` names the geometry field a `bbox` applies to; without it, the object's first. A `bbox` on
an object with no geometry, or naming one it does not have, is a `400`.

Writes answer `409` on an `appendOnly` object (`PUT`, `PATCH`, `DELETE`) and `403` on an `apiOnly` one (`POST`,
`PUT`, `PATCH`, `DELETE`), whatever the caller's roles (see "Write rules" under Objects). An app's `RecordWriteGuard` may refuse any
write with its own status, `400` or `409` as a rule.

A `RELATION` value must name a record of the caller's organization that the caller can read: `READ` on the target
object and, for a caller whose roles all set `ownRecordsOnly`, a record they created. `ADMIN` reads every record of the
organization, and so do the platform and automations, in process. One that names no record, a record of another
organization or one the caller cannot read is a `400` on the field, the same answer every way, and nothing is stored.
It is checked after the write rules above (`appendOnly` answers its `409`, `requiresReason` its `400` on `reason`,
first) and before any `RecordWriteGuard`, only for values sent, not `null` and, on `PUT`, different from the stored
one: an update may keep a value the caller can no longer read. A value that is no UUID is still the usual `400` "is
not a UUID":

```json
{
  "type": "https://wasichai.dev/problems/400",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid value for 'customer'",
  "errors": [{ "field": "customer", "message": "no record with this id" }]
}
```

The id is not echoed back. A record deleted between that check and the write still fails the database's foreign key:
that is a `409`, "A record this one points at does not exist any more" (ADR-031 D28, D29, D30).

### Defaults

A create fills every attribute the body leaves out with the field's `defaultValue`, if it has one (see Fields). A key
that is sent wins, `null` included: `null` stores `NULL` on purpose, and is a `400` on a `required` field. The same
holds for every create: `POST`, an app's `RecordService.create`, `asPlatform` and an automation's `CREATE_RECORD`.

```json
{ "attributes": { "codigo": "E-1" } }
```

stores `{ "codigo": "E-1", "estado": "NUEVO", "cantidad": 5 }` when `estado` defaults to `NUEVO` and `cantidad` to
`5`, and answers it so. The default is the field's value, not the caller's input: it is stored where the caller's
field permissions, or `editable: false`, forbid writing the field, so a `required` field with a default never stops a
create on its permissions. Sending such a field is still the usual `400`. A `RELATION` default is checked like a value
sent: a record the caller cannot read is a `400` on the field. A stored default that no longer parses (saved before
defaults were checked) is a `400` on the field until it is fixed with `PUT …/fields/{field}`. The audit row's `after`,
guards and listeners see the defaults as stored. `PUT` and `PATCH` never apply a default: `PUT` still clears what it
leaves out (ADR-031 D40).

### Read scope

An app may keep a person or a service account to some records of an object, such as those of their projects, with a
`RecordReadScope` bean ([ADR-048](../adr/0048-a-read-scope-narrows-what-a-caller-reads.md)). No route or parameter
changes; what the caller reads does. A list and its `totalElements` hold only the records in scope, and with an empty
scope every total is `0`. A record out of scope answers exactly as a missing one: `404` on `GET`, `PUT`, `PATCH` and
`DELETE`, with or without `If-Match`,
on its related records, its history and its transitions, on a link or unlink naming it, and on its GIS feature; `400`
on a `RELATION` field naming it. Never `403`. `ADMIN` is not narrowed. Without such a bean every answer above is
unchanged.

### Change reason

```http
PUT /api/objects/recibo/records/{id}
X-Change-Reason: UTF-8''correcci%C3%B3n%20del%20monto%20por%20error%20de%20digitaci%C3%B3n
```

Every record write takes an optional `X-Change-Reason` header: `POST`, `PUT`, `PATCH` and `DELETE` here, link and unlink
(Related records) and a workflow transition ([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)). It is stored
on that write's audit entry and comes back as `reason` from the audit API.

- Trimmed; an empty or blank value is no reason. At most **500 characters**; longer is a `400` on `reason`.
- **Send anything beyond ASCII in the RFC 8187 form**: `UTF-8''` followed by the percent-encoded UTF-8 text (in
  JavaScript: `"UTF-8''" + encodeURIComponent(reason)`), as in the example above. A plain value is read as
  ISO-8859-1, as for any header: a browser sends `ñ` and accents that way, but a client that writes raw UTF-8 bytes
  (curl, most HTTP libraries) gets mojibake stored (`correcciÃ³n`). A plain value is never percent-decoded, so
  `10% de descuento` means what it says. A malformed `UTF-8''` value (anything but `%` and two hex digits, or bytes
  that are not UTF-8) is a `400` on `reason`.
- Control characters are a `400` on `reason`, except tab and line breaks (`%09`, `%0A`, `%0D`), which are text: an
  observation may span lines. In-process the same rule applies to `RecordService`'s `reason`.
- On a `requiresReason` object a write without a reason is refused before anything is stored:

```json
{ "status": 400, "detail": "Object 'recibo' requires a reason for every change",
  "errors": [{ "field": "reason", "message": "send the X-Change-Reason header" }] }
```

The missing reason is judged right before the write: after permissions, field rules, an unknown record (`404`) and
`appendOnly` (`409`), before the app's `RecordWriteGuard`s. A reason over the cap is refused first of all.

### Concurrent edits: ETag and If-Match

Two people editing the same record no longer overwrite each other when their client sends back the version it read
([ADR-051](../adr/0051-optimistic-locking-and-partial-update-of-records.md)):

```http
GET /api/objects/caso/records/{id}
→ 200, ETag: "2026-10-07T10:15:30.123456Z"

PUT /api/objects/caso/records/{id}
If-Match: "2026-10-07T10:15:30.123456Z"
→ 200, ETag: "2026-10-07T10:16:02.481920Z"     or 412 when someone wrote it in between
```

- The `ETag` is the record's `updatedAt` in quotes, exactly as the JSON writes it, on `GET`, `POST`, `PUT` and `PATCH`
  of one record and on a workflow transition. A list item has no header: `"` + its `updatedAt` + `"` is its ETag.
  Treat it as opaque; it changes on every write, two writes in one transaction included.
- `If-Match` on `PUT`, `PATCH`, `DELETE` and `POST …/transitions/{name}`: the write lands only while the record still
  has that version. The compare is part of the write's own statement, so of several writers holding the same version
  exactly one wins. A list of tags is fine (any may match); a weak tag `W/"…"` never matches.
- Stale: `412 Precondition Failed`, and nothing is stored or audited. Re-read and decide again:

```json
{ "type": "https://wasichai.dev/problems/412", "title": "Precondition Failed", "status": 412,
  "detail": "Record 6f1c… changed since you read it",
  "errors": [{ "field": "If-Match", "message": "does not match the record's current ETag; read it again" }] }
```

- A record that is missing, of another organization, not the caller's under own-records-only or out of their read
  scope is a `404` as without the header, never a `412`. Every other refusal also comes first and keeps its status:
  permissions, field rules, `appendOnly` (`409`), a missing reason (`400`), a `RecordWriteGuard`.
- No `If-Match`, or `If-Match: *`, is the write it always was: last writer wins. A malformed value (unquoted, empty,
  `*` mixed with tags) is a `400` on `If-Match`.
- Link and unlink take no `If-Match`: they change no record row, so no record's version.
- In-process: `RecordService.update(…, reason, expectedUpdatedAt)`, `delete(…, reason, expectedUpdatedAt)` and
  `patch(…, expectedUpdatedAt = …)` take the `updatedAt` you read and throw `PreconditionFailedException` (412) when
  stale, inside your transaction too.

### Retrying a create: Idempotency-Key

A client that timed out or got a `5xx` cannot tell whether its create landed. Sending a key makes the retry safe
([ADR-058](../adr/0058-idempotency-key-on-record-creation.md)):

```http
POST /api/objects/caso/records
Idempotency-Key: 0b8e6a1c-5f3e-4c1e-9d1a-2f4e8c7b6a50

{ "attributes": { "codigo": "C-1" } }
→ 201, ETag: "2026-10-07T10:15:30.123456Z"                 the record, as without the key

(the same request again, same key)
→ 201, Idempotent-Replayed: true, ETag: "2026-10-07T10:15:30.123456Z"   the same body, nothing created
```

- Only on `POST /api/objects/{object}/records`. The key is 1 to 128 printable ASCII characters (a UUID is a good
  one); anything else is a `400` on `Idempotency-Key`. Without the header nothing changes.
- A key belongs to the caller (a person or a service account) in their organization: another caller's same key is
  another key, and nobody learns that someone else used it.
- First request: processed as usual, and its status and body are stored with the key in the record's own transaction.
  The answer is those stored bytes.
- Same key, same method, path and body (key order and spacing do not count): the stored `201` and body, byte for byte,
  with `Idempotent-Replayed: true` and the `ETag` the record had then. Nothing is written, audited or told to
  automations, and no permission is checked again. `X-Correlation-Id` is the retry's own.
- Same key, another body or another object: `422`, nothing written:

```json
{ "type": "https://wasichai.dev/problems/422", "title": "Unprocessable Content", "status": 422,
  "detail": "This Idempotency-Key was already used for another request",
  "errors": [{ "field": "Idempotency-Key", "message": "was sent before with another method, path or body; use a new key" }] }
```

- Same key while the first request is still running: `409` with `Retry-After: 1` and `errors[]` naming
  `Idempotency-Key`. Send it again; it then gets the replay, or creates when the first one failed.
- A request that fails (`4xx` or `5xx`) stores nothing: fix the body and retry with the same key. With a key the create
  is one transaction: an automation or listener that fails after the write leaves no record either.
- A key lives `wasichai.idempotency.ttl` (24 hours by default). After that it is gone and the same key creates again.
- `X-Change-Reason` is not part of what makes two requests the same.
- CORS exposes `Idempotent-Replayed` and `Retry-After` to browser clients.
- In-process: `RecordService.create(objectName, request, reason, idempotencyKey)` joins your transaction (or opens
  one), returns the stored record on a replay (values as its JSON holds them), and throws
  `UnprocessableContentException` (422) or `RetryLaterException` (409).

### Partial update (PATCH)

```http
PATCH /api/objects/caso/records/{id}
If-Match: "2026-10-07T10:15:30.123456Z"

{ "attributes": { "estado": "EN_CURSO", "nota": null } }
```

JSON merge on `attributes`: only the keys sent are written, `null` clears one, and every other field keeps its stored
value, even against a concurrent write of another field. Sections such as `geometries` merge as on `PUT`. The answer
is the whole record, with its new `ETag`. Everything else is `PUT`'s: `UPDATE` permission, field permissions (a field
your roles may not write is a `400` on it), `appendOnly`, `apiOnly`, `requiresReason` and `X-Change-Reason`, the
`RELATION` check (for the values sent), every `RecordWriteGuard` (which sees the stored record with the sent keys over
it), the audit entry (`changes` names only what moved) and automations. Two answers are `PATCH`'s own, where `PUT`
silently ignores the key: a key that is no attribute of the object is a `400` on that key, and a field whose metadata
says `editable: false` is a `403` "Field '…' is read-only". `Content-Type` may be `application/json` or
`application/merge-patch+json`.

## GIS

Module: wasichai-gis.

```http
GET /api/gis/objects/{object}/features?bbox=minX,minY,maxX,maxY&geometry=lote&limit=1000
GET /api/gis/objects/{object}/features/{id}?geometry=lote
```

Returns GeoJSON in **EPSG:4326**, whatever the field's storage CRS. A GeoJSON `Feature` holds one
geometry, so a request serves one, named by `geometry` or the object's first. Feature ids are
`<record>:<geometry>`, and the record's own id is repeated in `properties.__id`.

## Files

Module: wasichai-files ([files.md](../modules/files.md), ADR-061). A `FILE` or `IMAGE` field reads, in a record's
`attributes` and in its audit `before`/`after`, as a descriptor, never the bytes:

```json
"acta": { "id": "7c0e…", "name": "acta.pdf", "contentType": "application/pdf", "size": 48213, "sha256": "9f2c…" }
```

```http
POST /api/objects/{object}/records/{id}/files/{field}    multipart/form-data, part "file"
GET  /api/objects/{object}/records/{id}/files/{field}
POST /api/objects/{object}/files/{field}                 multipart/form-data, part "file"
```

- **Replace** (`POST …/records/{id}/files/{field}`): stores the upload and writes it to the field as a `PATCH` of that
  one field, so it answers like one: `200` with the record and its `ETag`. Everything a record write checks applies:
  `UPDATE` (`403`), the record as the caller sees it (owner, read scope: `404`), field write access (`400` on the
  field), `apiOnly` (`403`), `appendOnly` (`409`), `requiresReason` (`X-Change-Reason`, `400` on `reason`),
  `If-Match` (`412`), write guards. A refused upload leaves nothing stored.
- **Download** (`GET`): `READ` on the record and the field, as for reading it (`403`/`404`). `IMAGE` is served
  `Content-Disposition: inline`, everything else `attachment`; always `X-Content-Type-Options: nosniff`, the stored
  content type and length. A field the caller cannot read, or that holds no file, is `404`.
- **Staged upload** (`POST /api/objects/{object}/files/{field}`, `CREATE`): `201` with the descriptor. Its `id`, sent
  as the field's value in `POST /api/objects/{object}/records`, attaches it: the way to give a new record (or a record
  of an `appendOnly` object) its file. Unattached, the cleanup removes it.
- **Validation**: over the field's `maxBytes`, empty, or of a content type its `contentTypes` refuses: `400` on the
  field. The type is sniffed from the bytes (magic numbers), never trusted from the header.
- In a record body, a file field takes only `null` (clears), the id it already holds (a `PUT` sends back what it read),
  or the id of the caller's own upload for that object and field within half of `wasichai.files.cleanup.delay`;
  anything else is `400` on the field. It cannot be filtered or sorted on.

A field declares its settings next to `type` in `POST /api/metadata/objects/{object}/fields`: `maxBytes` (1 to
`wasichai.files.max-bytes`, the default) and `contentTypes` (`["application/pdf", "image/*"]`; `IMAGE` defaults to
`image/png`, `image/jpeg`, `image/webp`). The field's JSON carries `"file": { "maxBytes", "contentTypes" }`.

## Audit and history

```http
GET /api/audit?objectName=&recordId=&operation=&correlationId=&source=&from=&to=&userId=&serviceAccount=&limit=&after=
GET /api/objects/{object}/records/{id}/history?from=&to=&userId=&limit=&after=
```

The first is every recorded change in the tenant, the second one record's trail; both newest first, by `occurredAt`
then `id`.

```json
{
  "id": "…", "userEmail": "ana@wasichai.local", "objectName": "predio", "recordId": "…",
  "operation": "UPDATE", "occurredAt": "2026-09-18T09:00:00Z",
  "changes": [ { "field": "area", "before": 850.5, "after": 1200 } ],
  "reason": "corrección del monto",
  "correlationId": "5d1e0c4a-8f3b-4a62-9a57-0b9e1f2c7d10", "source": "api"
}
```

An entry made by a [service account](#service-accounts) also carries `serviceAccount`, its name; `userEmail` is then the
address of its backing user, `<id>@service-accounts.invalid`. Neither survives deleting the account: disable it
instead to keep the trail readable.

`changes` holds only the fields that actually differ; `CREATE` and `DELETE` carry an empty list.

`reason` is what the write's `X-Change-Reason` said (see "Change reason" under Records), `null` when it said nothing.
An automation's writes carry `automation '<name>'`. It is free text about the change, not a field value, so it is
shown to whoever may read the entry ([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)).

`correlationId` is the [correlation id](#correlation-id) of the request that wrote the entry, and `source` what wrote
it, set by the server, never by a client ([ADR-050](../adr/0050-correlation-id-and-change-source-on-audit-rows.md)):

| `source` | Written by |
|---|---|
| `api` | a request: the record API, related records, admin routes, transitions, documents, an app's own endpoint |
| `platform` | the app's background work through `RecordService.asPlatform` |
| an app's label | the same, labelled by the app: `job:retention`, `import:42` |
| `automation:<rule>` | a rule's actions; they keep the `correlationId` of the request whose change queued them |
| `app` | an in-process write nothing labelled |

Both are left out on entries written before they existed; `correlationId` is also left out when nothing gave one (the
platform, an app's job). `correlationId=` and `source=` are exact-match filters, blank meaning none. They only narrow:
`READ`, the admin entries' `MANAGE_ORGANIZATION`, the read scope and field permissions apply as before.

A fourth operation, `ISSUE`, records a document being issued, and it is the only entry that points
somewhere: it carries `documentId`, which `GET /api/documents/{id}` resolves. The other three have
nothing to point at, so the field is absent on them.
Values that differ only in scale (`10` and `10.0` as they come back from `jsonb`) are not reported as
changes.

The history of a record is a read **of its object**, so a role granted `READ` on one object can read
that object's history and no other's. Field permissions apply here too: a field the caller may not
read never appears as a change or as a value, in either endpoint. Without that, the audit log would
be a way around the field permissions.

When the app declares a read scope (see "Read scope" under Records), the history of a record outside the caller's
scope is a `404`, and `/api/audit` leaves out the entries of records outside it. An entry whose record no longer
exists is shown only to a caller with no scope on that object. The filter runs after `limit`, so a scoped caller may
get fewer entries than asked for, but the next page's cursor still starts where the read stopped.

### Period, user and pages

`from` and `to` are ISO-8601 instants, `Z` or an offset (`2026-10-01T00:00:00Z`, `2026-10-01T00:00:00-05:00`; encode
the `+` of a positive offset as `%2B`, though a bare `+` is accepted): an entry is kept when
`occurredAt >= from` and `occurredAt < to`, so consecutive periods never share one. `userId` keeps the entries of one
user or service account by id, `serviceAccount` those of one service account by name. There is no email filter: an
email is not a stable key. All four are blank meaning none, compose with every other filter, and only narrow, like
`correlationId` and `source` ([ADR-052](../adr/0052-audit-pages-by-cursor-period-and-user.md)). The history takes
`from`, `to` and `userId`.

`limit` is a page: 100 by default, 500 at most. The body stays a JSON array; when another entry follows the page, the
response carries the next page's cursor in `X-Next-Cursor`, and `after=<cursor>` reads that page. Keep every filter as it was, and
follow the header until a response comes without it:

```http
GET /api/audit?userId=…&from=2026-09-01T00:00:00Z&to=2026-10-01T00:00:00Z&limit=500
→ 200, [ …500 entries… ], X-Next-Cursor: MQpWdkZ0…
GET /api/audit?userId=…&from=2026-09-01T00:00:00Z&to=2026-10-01T00:00:00Z&limit=500&after=MQpWdkZ0…
→ 200, [ …the rest… ], no X-Next-Cursor
```

Every entry comes back once, tied `occurredAt`s included. The cursor is opaque and bound to its filters: a cursor of
another filter set (another object, operation, period, user…) or of the other route is a `400` naming `after`, as is a
value that is not a cursor; `limit` may change between pages. A malformed `from`, `to` or `userId` is a `400` naming
it. Core's CORS default exposes `X-Next-Cursor` to browsers on another origin.

With a read scope, the entries outside it are dropped after the page is read, so a page may come short, even empty,
with `X-Next-Cursor` still there: stop on a missing header, never on a short page.

### Administration entries

Changes to the tenant's administration and model are in the same log, under reserved `objectName`s that no object can
have ([ADR-049](../adr/0049-admin-changes-in-the-audit-log.md)). `recordId` is the entity's id, `operation` is `CREATE`,
`UPDATE` or `DELETE`, and `userEmail` (or `serviceAccount`) is who did it:

| `objectName` | `recordId` | Written by |
|---|---|---|
| `admin:user` | user | create, update, `PUT .../roles`, delete under `/api/users`; `PUT /api/users/{id}/org-units` |
| `admin:role` | role | create, update, delete under `/api/roles` |
| `admin:permission` | role | `PUT /api/roles/{name}/permissions` and `/field-permissions` |
| `admin:service-account` | account | create, update, secret rotation, delete under `/api/service-accounts` |
| `admin:org-unit` | unit | create, update, delete under `/api/org-units` |
| `admin:object` | object | create, update (flags, indexes, uniques), delete; declaring or removing an action |
| `admin:field` | field | add, update, delete under `/api/metadata/objects/{object}/fields` |
| `admin:relationship` | relationship | create, update, delete under `/api/relationships`, its column included |
| `admin:organization` | organization | provisioning (in the provisioner's tenant), rename, delete |

```http
GET /api/audit?objectName=admin:permission&recordId={roleId}
```

```json
[{
  "id": "…", "userEmail": "admin@wasichai.local", "objectName": "admin:permission", "recordId": "…",
  "operation": "UPDATE", "occurredAt": "2026-10-07T09:00:00Z", "reason": null,
  "changes": [
    { "field": "predio.DELETE", "before": true, "after": null },
    { "field": "*.CREATE", "before": null, "after": true }
  ]
}]
```

A replaced permission set is stored whole on both sides, one key per grant (`<object>.<ACTION>`, `*.<ACTION>` for every
object, `<object>.<field>` for a field rule), so `changes` names only the grants that changed. An object's entries carry
its fields and declared actions. No entry holds a password, a hash or a client secret: a password change is
`passwordChanged: true`, a rotation `secretRotated: true`. Unlike a record's, an admin `CREATE` or `DELETE` lists every
key in `changes`.

Only `MANAGE_ORGANIZATION` reads them, with or without `READ`. Anyone else asking for `objectName=admin:…` gets `[]`,
never a `403`; asking for no object, they get the record entries only (the admin ones are left out before `limit`).
Field permissions and a read scope do not apply to them: they are shown whole. A failed or rolled-back call leaves no
entry, and a deleted tenant's entries stay in the database.

## Workflows

Module: wasichai-workflow.

```http
GET    /api/objects/{object}/workflow                          the object's workflow, 404 when it has none
PUT    /api/objects/{object}/workflow                          create or replace
DELETE /api/objects/{object}/workflow                          remove the definition; record states are kept
GET    /api/objects/{object}/records/{id}/transitions          what this record can do next, and what it cannot
POST   /api/objects/{object}/records/{id}/transitions/{name}   move the record
```

```json
{
  "id": "…", "objectName": "predio", "name": "predio-aprobacion", "label": "Aprobación", "enabled": true,
  "definition": {
    "states": [
      { "name": "draft", "label": "Borrador", "type": "INITIAL", "x": 40, "y": 40 },
      { "name": "approved", "label": "Aprobado", "type": "FINAL", "x": 300, "y": 40 }
    ],
    "transitions": [
      { "name": "approve", "label": "Aprobar", "from": "draft", "to": "approved", "roles": ["SUPERVISOR"] }
    ]
  }
}
```

Records carry their state in `RecordResponse.state`, and the transition listing reports every
transition leaving the current state — including the ones the caller may not take, with a reason —
so the UI can explain a disabled button instead of hiding it:

```json
[ { "name": "approve", "label": "Aprobar", "to": "approved", "toLabel": "Aprobado",
    "allowed": false, "reason": "requires role SUPERVISOR" } ]
```

`x` and `y` are where the visual editor put the state (ADR-018). They are optional and nothing
validates them: a state saved without them comes back with both null and the client lays it out.
Sending only one of the two stores neither — half a position is not a position.

A transition is the only way to move a record (ADR-013): the state is not a Custom Field, so no
record payload can set it. Applying one from the wrong state answers 409 naming the current state.
Every transition is written to the audit log, so workflow movement shows up in the history screen
beside field changes. The transition takes an `X-Change-Reason` header like any record write, stored on its audit
entry; on a `requiresReason` object it is required (`400` on `reason` without one). It answers the record's new
`ETag` and takes `If-Match` like `PUT` (see "Concurrent edits" under Records): stale is a `412`, and a transition from
the wrong state keeps its `409`, whatever `If-Match` says.

## Automations

Module: wasichai-automation.

```http
GET    /api/objects/{object}/automations
POST   /api/objects/{object}/automations
GET    /api/objects/{object}/automations/{name}
PUT    /api/objects/{object}/automations/{name}
DELETE /api/objects/{object}/automations/{name}
GET    /api/objects/{object}/automations/{name}/runs?limit=50
GET    /api/automation-runs?limit=50
```

A rule is a trigger, some conditions and some actions:

```json
{
  "name": "marca-grandes",
  "label": "Marca predios grandes",
  "enabled": true,
  "definition": {
    "trigger": { "type": "RECORD_CREATED" },
    "conditions": [{ "field": "area", "operator": "GREATER_THAN", "value": "1000" }],
    "actions": [{ "type": "UPDATE_FIELD", "field": "revisado", "value": "{{uso}} de {{area}} m2 el {{today}}" }]
  }
}
```

Triggers are `RECORD_CREATED`, `RECORD_UPDATED`, `RECORD_DELETED`, `TRANSITION_APPLIED` (optionally
naming a transition) and `STATE_ENTERED` (naming a state). Operators are `EQUALS`, `NOT_EQUALS`,
`GREATER_THAN`, `LESS_THAN`, `CONTAINS`, `IS_EMPTY`, `IS_NOT_EMPTY` and `CHANGED`; numbers compare as
numbers. `field` may be a Custom Field or `state`. Actions are `UPDATE_FIELD`, `CREATE_RECORD`
(`targetObject` plus `values`), `WEBHOOK` (`url`), `GENERATE_DOCUMENT` (`documentType`) and `NOTIFY`
(`to`, `title`, `body`, `kind`). Every text value accepts `{{field}}` out of the record, plus `{{id}}`,
`{{state}}`, `{{user}}`, `{{today}}` and `{{now}}`.

`NOTIFY` tells people, through the notifications module ([ADR-060](../adr/0060-delivery-channels-and-automation-notify.md)):

```json
{ "type": "NOTIFY", "to": "{{responsable}}, role:SUPERVISOR", "kind": "WARNING",
  "title": "Tramite {{codigo}} aprobado", "body": "Pase a {{state}}" }
```

`to` is a comma-separated list, rendered per run: a user id (or `user:<id>`), an email, `role:<NAME>` or
`unit:<CODE>`. `kind` is `INFO` (the default) or `WARNING`; an `ACTION` is refused, since nothing would resolve it.
`to` and `title` are required. Without the notifications module the action is `400 NOTIFY needs the notifications
module` when saved. A run creates one notification per record and action, of source `automation:<name>`, keyed
`<recordId>.<n>` and linking to the record: entering the state again reopens or updates that one, never a second.
An entry that names nobody (an empty field, an unknown person) is dropped; with nobody left the step says
`notified nobody` and the run still succeeds. Its `{{field}}` placeholders are not field usages: deleting such a
field is allowed and prints empty.

`GENERATE_DOCUMENT` is how a workflow state issues a document: pair it with a `STATE_ENTERED`
trigger and the record issues that type on arriving. The type must belong to the automation's
object, checked when the rule is saved rather than when it runs. Nobody is named as the issuer —
a queued run has no user behind it (ADR-016) — so the document says the platform issued it.

Writing a rule needs `MANAGE_METADATA` on its object; reading its runs needs only `READ`, so the
person whose record changed can find out why. A webhook URL must be public http(s) — a host that
resolves to a private address is refused when the rule is saved.

The actions run off the request (ADR-016), so every rule that matched leaves a run:

```json
[ { "id": "…", "automation": "marca-grandes", "objectName": "predio", "recordId": "…",
    "trigger": "RECORD_CREATED", "status": "SUCCEEDED", "depth": 0,
    "steps": [{ "action": "UPDATE_FIELD", "detail": "predio.revisado = 'comercial de 1500 m2' on …" }],
    "error": null, "attempts": 1 } ]
```

`SKIPPED` carries the reason in `error` — the condition that did not hold, or the depth limit that
stopped a chain of rules feeding each other.

## Notifications

Module: wasichai-notifications ([notifications.md](../modules/notifications.md)). What people must know or do, for
everyone, a user, a role or an [organizational unit](#organizational-units), within a window
([ADR-046](../adr/0046-notifications-module.md)). These routes manage the manual ones: every route needs
`MANAGE_ORGANIZATION`, so a service account is refused (`403`), and reaches the caller's own tenant only (another
tenant's notification is `404`).

```http
GET    /api/notifications?source&kind&unit&status&page&size   the admin view, newest created first
POST   /api/notifications            { kind, title, body?, link?, audience, publishAt?, expiresAt?, dueAt? }  →  201
GET    /api/notifications/{id}
PUT    /api/notifications/{id}       the same body, a full replace
DELETE /api/notifications/{id}       →  204
```

```json
{
  "kind": "INFO",
  "title": "Ordenanza 006-2026",
  "body": "Nuevo TUPA desde el lunes.",
  "link": { "type": "URL", "url": "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf" },
  "audience": [{ "type": "ROLE", "value": "CAJERO" }, { "type": "UNIT", "value": "SGFT" }],
  "publishAt": "2026-10-07T13:00:00Z",
  "expiresAt": "2026-10-31T23:59:59Z"
}
```

- `kind` is `INFO`, `WARNING` or `ACTION`. `title` is trimmed, 1 to 200 characters; `body` at most 4000, plain text.
  Over REST nothing is cut: too long is `400`.
- `link` is one of three shapes. A `tab` is trimmed, upper-cased and must have the [TAB key](#component-fields) format,
  `^[A-Z][A-Z0-9_]{0,39}$`; it is not checked against the object's pages.

  ```json
  { "type": "RECORD", "object": "tasa", "recordId": "7c1…", "tab": "VIGENCIA" }
  { "type": "ROUTE", "route": "caja:pagos-sin-entregar", "params": { "fecha": "2026-10-06" }, "tab": null }
  { "type": "URL", "url": "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf" }
  ```

  A `RECORD` names an object of the tenant (`400` on `link.object` otherwise). A `ROUTE` names a route key the UI
  knows, `^[a-z][a-z0-9-]*:[A-Za-z0-9_.-]+$`, with at most 10 `params` (keys `^[A-Za-z][A-Za-z0-9_]{0,39}$`, values at
  most 200 characters); they fill the route's path parameters first and the rest go to the query string. A `URL` is an
  absolute `http` or `https` address with a host, of at most 2000 characters.
- `audience` is not empty: `{"type": "ALL"}`, `{"type": "USER", "value": "<uuid>"}`, `{"type": "EMAIL", "value":
  "a@b.pe"}`, `{"type": "ROLE", "value": "CAJERO"}` or `{"type": "UNIT", "value": "SGFT"}`. A role name and a unit code
  are trimmed and upper-cased and must match `^[A-Z][A-Z0-9_]{1,48}$`, an email trimmed and lower-cased; an `EMAIL` is
  stored as the `USER` it names. A user, email, role or unit the tenant does not have is `400` naming `audience[i]`
  (`unknown role 'CAJERO'`); a service account is never found by email.
- `publishAt` defaults to now; before it, nobody sees the notification. `expiresAt` must be after `publishAt` (in the
  future, without one); `dueAt` is when an `ACTION` becomes overdue.
- A malformed body is one `400 Invalid notification`, with every offending field in `errors[]` (`kind`, `title`,
  `link.type`, `link.url`, `audience`, `audience[2]`, `expiresAt`…).

The answer, and every admin view:

```json
{
  "id": "…", "kind": "INFO", "title": "Ordenanza 006-2026", "body": "Nuevo TUPA desde el lunes.",
  "link": { "type": "URL", "url": "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf" },
  "audience": [{ "type": "ROLE", "value": "CAJERO" }, { "type": "USER", "value": "…", "email": "ana@muni.pe" }],
  "publishAt": "2026-10-07T13:00:00Z", "expiresAt": "2026-10-31T23:59:59Z", "dueAt": null,
  "source": "manual", "key": null, "resolvedAt": null,
  "createdAt": "2026-10-06T15:00:00Z", "updatedAt": "2026-10-06T15:00:00Z", "readCount": 0
}
```

`audience` shows a unit by its code and a user with its email. `readCount` is how many people read it. A `POST` stores
`source: "manual"` and the caller as its author.

`PUT` replaces every field. Without `publishAt` it keeps the stored one, and a publication still ahead is what
`expiresAt` must follow. Who read it keeps having read it, unless `kind` changed: then everyone sees it again. The same
content writes nothing. A notification of any other source (an app's, or a rule's `rule:<name>`) is shown here but
never changed: `PUT` and `DELETE` answer `409 Notification is owned by its source`; the app resolves it.

The list filters, each optional and case-insensitive: `source` (exact), `kind`, `unit` (a code: those addressed to that
unit, so a client can warn before deleting it) and `status`: `open` (not resolved, in its window), `scheduled` (not
resolved, `publishAt` ahead) or `ended` (resolved or expired). A blank one is no filter, as in the inbox. An unknown
`kind`, `status` or `unit` is `400` on that parameter. The newest created come first (`createdAt`, not `publishAt`).
Paging is core's `PageResponse`.

## My notifications

Module: wasichai-notifications. What the caller sees and does with it. Any signed-in person; a service account gets
`403 A service account has no notifications`.

```http
GET  /api/auth/me/notifications?kind&state&page&size   the inbox, a PageResponse
GET  /api/auth/me/notifications/summary                counts per kind and the newest one
GET  /api/auth/me/notifications/stream                 the summary, live (text/event-stream)
POST /api/auth/me/notifications/{id}/read              →  204
POST /api/auth/me/notifications/{id}/dismiss           →  204
POST /api/auth/me/notifications/{id}/snooze            { "until": "2026-10-07T08:00:00Z" }  →  204
POST /api/auth/me/notifications/read-all               { "kind"?: "INFO" }  →  204
GET  /api/auth/me/notification-preferences             { "email": ["INFO", "WARNING", "ACTION"] }
PUT  /api/auth/me/notification-preferences             { "email"?: ["ACTION"] }  →  the stored preferences
```

A person sees a notification of their tenant that is not resolved, inside its window (`publishAt` passed, `expiresAt`
not yet), and addressed to everyone, to them, to one of their token's roles, or to one of their units or a unit above
one (a unit reaches its whole subtree). Anything else, another tenant's id included, is `404` on every route here, as
one that never existed.

```json
{
  "id": "…", "kind": "ACTION", "title": "Firmar el acta 12-2026", "body": "Vence hoy.",
  "link": { "type": "RECORD", "object": "acta", "recordId": "…", "tab": "RESOLUCIONES" },
  "publishAt": "2026-10-06T09:00:00Z", "expiresAt": null, "dueAt": "2026-10-07T05:00:00Z", "overdue": false,
  "source": "rule:acta_sin_ris", "read": false, "snoozedUntil": null, "dismissible": false
}
```

- `state`: `active` (the default: not dismissed, not snoozed past now), `unread` (active and never read) or `snoozed`
  (snoozed past now, not dismissed). `kind` keeps one kind. An unknown value is `400` on that parameter.
- Order: newest `publishAt` first; with `kind=ACTION`, a to-do list: earliest `dueAt` first, the undated last.
- `overdue` is `dueAt` passed. `dismissible` is `false` only for an `ACTION` of a source or a rule.
- A `RECORD` link is `null` for a reader without `READ` on its object, and for everyone once the object is deleted.
  `ROUTE` and `URL` links are kept: the UI guards its routes. Record-level scope (`own_records_only`) is not checked:
  the record route still answers `404`.

`read` marks the item read and keeps the first time. `dismiss` hides it for good; an `ACTION` of a source or a rule is
`409 This notification leaves when its work is done`. A dismissed item is still the caller's, so reading it again is
`204`. `snooze` hides it until `until`, which must be after now and at most `wasichai.notifications.snooze-max` (30
days) ahead, else `400` on `until`; a later snooze replaces it. `read-all` marks every active unread item, of one
`kind` or, without a body, of every kind; snoozed and dismissed ones are left as they are.

The summary counts the caller's active items per kind, and names the newest active one (`latest`, by `publishAt`, or
`null`), its link filtered as above. Every kind is present, zeros included:

```json
{
  "kinds": {
    "INFO": { "active": 2, "unread": 1, "overdue": 0 },
    "WARNING": { "active": 1, "unread": 1, "overdue": 0 },
    "ACTION": { "active": 3, "unread": 2, "overdue": 1 }
  },
  "latest": {
    "id": "…", "kind": "ACTION", "title": "Firmar el acta 12-2026", "publishAt": "2026-10-06T09:00:00Z",
    "link": { "type": "RECORD", "object": "acta", "recordId": "…", "tab": "RESOLUCIONES" }
  }
}
```

### Preferences

`/api/auth/me/notification-preferences` says which kinds reach the caller on each delivery channel of the app
([ADR-060](../adr/0060-delivery-channels-and-automation-notify.md)), the way `/api/auth/me/preferences` does
(ADR-034): `GET` answers every channel the app has, with its kinds in enum order (every kind until the person chooses);
`PUT` takes a map, a channel left out keeps what it had, `[]` stops the channel, kind names are case-insensitive, and
the answer is the whole map. A key that is not a channel of the app (`in-app` included: the inbox is always on) or a
value that is not a list of `INFO`, `WARNING`, `ACTION` is `400` naming that key. An app without channels answers `{}`
and refuses every key. A service account gets `403`.

A delivery leaves news only (a notification created, reopened, or whose kind changed), never an edit of the text, so
an email is not resent for a new count in a title. Who gets it is decided when the news is written; the email goes out
within `delivery-interval`, or when a scheduled notification is published.

### The stream

`GET /api/auth/me/notifications/stream` (`Accept: text/event-stream`) sends the summary, live
([ADR-047](../adr/0047-server-push-over-sse-and-listen-notify.md)):

```
event:summary
data:{"kinds":{"INFO":{"active":2,"unread":1,"overdue":0},"WARNING":{…},"ACTION":{…}},"latest":{…}}

:ping

event:summary
data:{"kinds":{…},"latest":{…}}

```

- The first `summary` comes at once. Another comes only when the summary changed: after a write that concerns the
  caller, on any replica (PostgreSQL `LISTEN/NOTIFY`), and at the latest every `stream-refresh` (60 s), which also
  catches a window that opens or ends.
- `:ping` is a comment line, every `stream-heartbeat` (25 s), so proxies do not close an idle connection. The answer
  carries `X-Accel-Buffering: no`.
- The token travels in the `Authorization` header like any route; `?access_token=` is not read (`401`). Without a
  token it is `401`, for a service account `403`, before any event.
- The stream completes at the token's `exp`. The client reconnects with the token it holds then; a `401` means sign
  in again.

## Notification rules

Module: wasichai-notifications. A date rule watches a `DATE` or `DATETIME` field of an object and gives one
notification per record in its window, linking to the record and a tab. Rules need `MANAGE_METADATA` on their object.

```http
GET    /api/notification-rules                              every rule of the tenant, by object, then name
GET    /api/objects/{object}/notification-rules             the object's rules, by name
POST   /api/objects/{object}/notification-rules             →  201, and runs the rule at once when enabled
GET    /api/objects/{object}/notification-rules/{name}
PUT    /api/objects/{object}/notification-rules/{name}      replace, and run (or resolve, when disabled)
DELETE /api/objects/{object}/notification-rules/{name}      →  204, and resolves its notifications
POST   /api/objects/{object}/notification-rules/{name}/run  →  { created, updated, reopened, resolved }
```

```json
{
  "name": "licencia_por_vencer", "label": "Licencias por vencer", "enabled": true,
  "field": "vigencia_hasta",
  "stages": [{ "fromDays": -15, "kind": "WARNING" }, { "fromDays": 0, "kind": "ACTION" }],
  "untilDays": 3,
  "conditions": [{ "field": "estado", "op": "EQ", "value": "VIGENTE" }, { "field": "baja", "op": "EMPTY" }],
  "audience": [{ "type": "ROLE", "value": "TESORERIA" }],
  "title": "La licencia {{numero}} vence el {{date}}",
  "body": "Quedan {{days}} días.",
  "tab": "VIGENCIA"
}
```

Every answer is the rule as stored, normalised (stages by `fromDays`, role names and unit codes upper-cased), with the
name of its object first: `{object, name, label, enabled, field, stages, untilDays, conditions, audience, title, body,
tab}`. `GET /api/notification-rules` needs `MANAGE_METADATA`; the object routes need it on the object, and an unknown
object or rule is `404`.

- **Window and kind.** The offset is today minus the field's date, in days, in the rule zone (a `DATETIME` counts its
  date in that zone). The zone is `wasichai.notifications.zone`; unset, the app's `Clock` bean's zone when it has
  exactly one; else the system's, with a WARN at start. A record is in the window while
  `min(stages.fromDays) ≤ offset ≤ untilDays`; its kind is the stage with the largest `fromDays` not after the offset.
  Here a licence warns from 15 days before its date, asks for action from that day on, and leaves once 3 days have
  passed.
- **Due.** A `DATE` is due at the start of the next day in the zone (the date itself still counts), a `DATETIME` at its
  value; past that the notification is `overdue`.
- **Conditions** must all hold, at most 10: `EQ` (with a `value` the field's type accepts, one per field), `EMPTY` or
  `NOT_EMPTY` (without a `value`). On a text field (`TEXT`, `LONG_TEXT`, `ENUM`, `EMAIL`, `URL`) blank counts as empty.
- **Templates.** `{{<field>}}` prints a field of the record, `{{days}}` the date minus today (negative once passed),
  `{{date}}` the date and `{{object}}` the object's label; these three win over a field of the same name. A `DATE`
  prints with `wasichai.notifications.date-pattern` (`dd/MM/yyyy`), a `DATETIME` as that pattern plus ` HH:mm` in the
  rule zone, a null as nothing. An unknown placeholder is `400` on save. A title that renders blank becomes the rule's
  `label`; the rendered title is cut at 200 characters and the body at 4000, with "…"; a body that renders blank is
  none. Values print without field permissions: the author is a metadata administrator.
- **On save** the field must exist and be `DATE` or `DATETIME`; condition and placeholder fields must exist and have
  a core type (not a module's, such as a geometry); the audience is checked as for [notifications](#notifications) (an
  unknown recipient is `400`); the `tab` has the TAB key format. `name` is `^[a-z][a-z0-9_]{1,48}$`, unique in the
  tenant (`409 Notification rule '<name>' already exists`); `label` is trimmed, 1 to 120 characters; the `title`
  template 1 to 200, the `body` template at most 4000; `stages` holds 1 to 5 entries with distinct `fromDays`, each at
  most `untilDays`; `fromDays` and `untilDays` are within ±365. A bad rule is one `400 Invalid notification rule` naming
  every field (`stages[1].fromDays`, `conditions[0].value`, `audience[2]`, `tab`…).
- **`PUT`** replaces the whole rule; a body `name` other than the path's is `400` (a rule is not renamed). Enabled, it
  runs at once; disabled, its notifications are resolved. Either way the save and what follows are one transaction:
  a run that fails (`500`) leaves the rule as it was, and so does a `POST` (no rule is left behind). **`run`** on a
  disabled rule is `409 Notification rule '<name>' is disabled`: enabling it is the way to run it.
- **What it gives.** Each record in the window is one notification of source `rule:<name>`, keyed by the record id,
  with the rule's audience and a `RECORD` link to the record and `tab`. A run takes at most
  `wasichai.notifications.rule-max-notifications` records (100), earliest dates first; a record that left the window
  has its notification resolved. The rules also run every `wasichai.notifications.rule-interval` (15 minutes). A
  record write re-evaluates that record at once, but only touches a notification the rule has **open**: it updates it
  in place, or resolves it when the record left the window or a condition stopped holding; deleting the record
  resolves it. A record that enters the window, or comes back to it, appears on the next run (`POST`, `PUT`, `run`,
  or the loop): only a run sees the cap, so a record past it never comes and goes with each write. A run reads
  records as the module, with no permission or record-level scope. A rule whose field is no longer a `DATE` or
  `DATETIME` field is skipped with a WARN.
- **Lifecycle.** Disabling or deleting a rule resolves its notifications (a delete in one transaction with them).
  Deleting the object resolves what its rules published, then deletes the rules. A field a rule reads, disabled rules
  included, cannot be deleted: `409 Field '…' is used by notification rule '<name>'`. Its notifications are not
  edited or deleted by hand (`409`), and its `ACTION`s are not dismissed.

## AI assistant

Module: wasichai-agent.

```http
GET  /api/agent/status    { "enabled": true, "model": "claude-haiku-4-5" }
POST /api/agent/ask       { "question": "…" }  ->  { answer, steps, truncated, usage? }
```

```json
{
  "answer": "Hay 3 predios comerciales de más de 1.000 m².",
  "steps": [
    { "tool": "list_objects", "input": {}, "summary": "3 objects" },
    { "tool": "query_records", "input": { "object": "predio", "uso": "COMERCIAL" }, "summary": "3 records" }
  ],
  "truncated": false,
  "usage": { "model": "claude-haiku-4-5", "inputTokens": 5120, "outputTokens": 214 }
}
```

`usage` is what the question cost, summed over the run's model calls; it is left out when the provider reported none.

The assistant has no database access: it calls the same services a person's requests go through, as
the person asking, so permissions, tenancy and field visibility apply unchanged (ADR-014). Its tools
are read-only — there is no tool that creates a record or moves a workflow. `truncated` means it hit
its step limit and stopped, so the answer may be incomplete.

Without `ANTHROPIC_API_KEY` the status reports `enabled: false` and asking answers `503` with a
problem+json explaining what is missing. Nothing else in the platform depends on it.

An app can switch the assistant off per caller (`AgentAccessPolicy`): the status then reports `enabled: false` for
that caller, and asking answers `403` with the app's reason before anything is sent. An app's result filters
(`AgentResultFilter`) shape what the model and `steps[].summary` see; a filter that fails makes asking answer `500`
("nothing was sent") unless it raised an error of its own. See [agent.md](../modules/agent.md#extension-points) and
ADR-056.

## Errors

RFC 7807 `application/problem+json`:

```json
{
  "type": "https://wasichai.dev/problems/400",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid option",
  "instance": "/api/objects/predio/records",
  "errors": [{ "field": "uso", "message": "must be one of RESIDENCIAL, COMERCIAL" }]
}
```

| Status | When |
|---|---|
| 400 | validation: bad value, unknown field, wrong geometry type, invalid technical name; no change reason on a `requiresReason` object |
| 401 | missing or invalid token |
| 403 | authenticated but lacking the object/action permission; a record write through the generic API on an `apiOnly` object |
| 404 | unknown object or record |
| 409 | duplicate name; a repeated unique value (`errors[]` names its fields); changing or deleting an `appendOnly` record; a reference deleted meanwhile |
| 409 | an `Idempotency-Key` whose first request is still running, with `Retry-After` (`errors[]` names `Idempotency-Key`) |
| 412 | `If-Match` no longer matches the record: someone wrote it since it was read (`errors[]` names `If-Match`) |
| 422 | an `Idempotency-Key` sent before with another body or object (`errors[]` names `Idempotency-Key`) |
