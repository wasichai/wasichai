# REST API

Base path `/api`. Everything except `/api/auth/login`, `/api/auth/token` and `/api/health` needs
`Authorization: Bearer <token>`.

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
| gis | `/api/gis/layers/**`, `/api/gis/services`, `/api/gis/objects/{object}/features/**` | [gis.md](../modules/gis.md) |
| agent | `/api/agent/status`, `/api/agent/ask` | [agent.md](../modules/agent.md) |

A route of a module that is not installed answers `404` to an authenticated caller and `401` without a token, never
`403`. The frontend relies on that `404` to tell "not installed" from "not allowed" (ADR-031 D1).

## Auth

```http
POST /api/auth/login        { "email": "...", "password": "..." }  →  { token, expiresAt, user }
POST /api/auth/token        { "clientId": "...", "clientSecret": "..." }  →  { token, expiresAt, serviceAccount }
GET  /api/auth/me
GET  /api/auth/me/permissions   what the caller may do with each object they can read
GET  /api/auth/me/preferences   → { "theme": "system", "locale": null }
PUT  /api/auth/me/preferences   { "theme"?: "dark", "locale"?: "en" | null }  →  the stored preferences
```

```json
{ "admin": false, "objects": { "predio": ["READ", "CREATE", "UPDATE"] } }
```

Only objects the caller may `READ` are listed, as in `GET /api/objects`, each with the record actions
the caller holds on it: `READ`, `CREATE`, `UPDATE`, `DELETE`, followed by the [declared actions](#declared-actions)
they hold, by name (`["READ", "CREATE", "ANULAR_AJENO"]`). The administrator gets all four on every
object, plus every action the object declares. Field access is not repeated here: the definition endpoints already leave out the fields the
caller cannot read and mark the ones they cannot write `editable: false`.

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
deleting stops new tokens at once; a token already issued lives until it expires.

The account is backed by a user row with the same id, so its writes are recorded under that id like anyone's:
`created_by`, `updated_by` and the audit log. That user is not listed by `GET /api/users`, cannot sign in, and is
`404` to the user routes.

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

- `appendOnly: true` — records are created, never changed or deleted, by anyone: `PUT` and `DELETE` on a record, a
  workflow transition, and a link or unlink touching one of its records answer `409`, for `ADMIN` too, even a link
  that already exists or an unlink of one that does not (elsewhere those are a no-op `204`). So do deleting the
  object, one of its fields, a relationship that holds its values, or an object it shares a join table with: switch
  `appendOnly` off first. Deleting another object's record that an append-only record points at, through a
  `RELATION` field or a `MANY_TO_MANY` link, answers `409` naming the append-only object, for `ADMIN` too: the
  database would otherwise null the field or drop the link behind the append-only record's back. The check and the
  delete run in one transaction behind a lock on the record, so an append-only record created at the same moment
  either is seen (`409`) or fails its own insert with `409`; it is never nulled
  ([ADR-044](../adr/0044-append-only-delete-check-under-a-row-lock.md)).
- `apiOnly: true` — the generic record API (`POST`, `PUT`, `DELETE` under `/records`, and link or unlink when either
  end is api-only) answers `403` on writes, for `ADMIN` too. Only the app's own code writes it, in-process. Reads
  are unchanged.
- `requiresReason: true` — every write of its records must say why, in the `X-Change-Reason` header (see "Change
  reason" under Records): without one, `POST`, `PUT`, `DELETE`, a link or unlink touching one of its records and a
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
(`READ`, `CREATE`, `UPDATE`, `DELETE`, `MANAGE_METADATA`, `MANAGE_ORGANIZATION`) or a bad shape is `400`, a name the
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
Provisioning needs `MANAGE_ORGANIZATION` and creates the organization, an `ADMIN` role with every
permission, and the administrator account:

```json
{ "name": "Municipalidad", "slug": "muni", "adminEmail": "admin@muni.pe", "adminPassword": "…" }
```

## Fields

```http
GET    /api/metadata/objects/{object}/fields
POST   /api/metadata/objects/{object}/fields          add a field (ALTER TABLE ADD COLUMN)
PUT    /api/metadata/objects/{object}/fields/{field}  label, required, unique, enum options, visibility
DELETE /api/metadata/objects/{object}/fields/{field}  drop the field and its column
```

Updating applies DDL alongside the metadata, in one transaction: `required` toggles `NOT NULL`,
`unique` adds or drops the constraint, `indexed` adds or drops the field's index, and new `enumOptions` replace the
`CHECK`.

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

### Related records

```http
GET    /api/objects/{object}/records/{id}/related/{relationship}
POST   /api/objects/{object}/records/{id}/related/{relationship}            { "otherId": "…" }
DELETE /api/objects/{object}/records/{id}/related/{relationship}/{otherId}
```

The read works from **either** end: from a plot it returns its owner, from the owner it returns their
plots. Link and unlink apply to `MANY_TO_MANY` only — for the others, set the field on the record.
A link or unlink writes both records: `409` when either end is `appendOnly`, `403` when either end is `apiOnly`, `400`
on `reason` when either end is `requiresReason` and no `X-Change-Reason` came. A reason sent is stored on both
records' history.

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
                { "type": "TAB", "title": "DETAILS", "children": [{ "type": "FORM" }] },
                { "type": "TAB", "title": "MAP", "children": [{ "type": "MAP", "title": "Predio" }] },
                {
                  "type": "TAB",
                  "title": "RELATED",
                  "children": [{ "type": "RELATED_LIST", "title": "Titular", "relationship": "predio_titular" }]
                },
                { "type": "TAB", "title": "HISTORY", "children": [{ "type": "HISTORY" }] }
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
inside the single `MAIN` region.

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
administrator types is shown exactly as typed.

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
- `ACTION`/`NAVIGATE.url` not starting with `http://` or `https://`.

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
PUT    /api/objects/{object}/records/{id}
DELETE /api/objects/{object}/records/{id}
```

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

Writes answer `409` on an `appendOnly` object (`PUT`, `DELETE`) and `403` on an `apiOnly` one (`POST`, `PUT`,
`DELETE`), whatever the caller's roles (see "Write rules" under Objects). An app's `RecordWriteGuard` may refuse any
write with its own status, `400` or `409` as a rule.

### Change reason

```http
PUT /api/objects/recibo/records/{id}
X-Change-Reason: UTF-8''correcci%C3%B3n%20del%20monto%20por%20error%20de%20digitaci%C3%B3n
```

Every record write takes an optional `X-Change-Reason` header: `POST`, `PUT` and `DELETE` here, link and unlink
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

## GIS

Module: wasichai-gis.

```http
GET /api/gis/objects/{object}/features?bbox=minX,minY,maxX,maxY&geometry=lote&limit=1000
GET /api/gis/objects/{object}/features/{id}?geometry=lote
```

Returns GeoJSON in **EPSG:4326**, whatever the field's storage CRS. A GeoJSON `Feature` holds one
geometry, so a request serves one, named by `geometry` or the object's first. Feature ids are
`<record>:<geometry>`, and the record's own id is repeated in `properties.__id`.

## Audit and history

```http
GET /api/audit?objectName=&recordId=&operation=&limit=       every recorded change in the tenant
GET /api/objects/{object}/records/{id}/history?limit=        one record's trail, newest first
```

```json
{
  "id": "…", "userEmail": "ana@wasichai.local", "objectName": "predio", "recordId": "…",
  "operation": "UPDATE", "occurredAt": "2026-09-18T09:00:00Z",
  "changes": [ { "field": "area", "before": 850.5, "after": 1200 } ],
  "reason": "corrección del monto"
}
```

An entry made by a [service account](#service-accounts) also carries `serviceAccount`, its name; `userEmail` is then the
address of its backing user, `<id>@service-accounts.invalid`. Neither survives deleting the account: disable it
instead to keep the trail readable.

`changes` holds only the fields that actually differ; `CREATE` and `DELETE` carry an empty list.

`reason` is what the write's `X-Change-Reason` said (see "Change reason" under Records), `null` when it said nothing.
An automation's writes carry `automation '<name>'`. It is free text about the change, not a field value, so it is
shown to whoever may read the entry ([ADR-041](../adr/0041-a-change-reason-on-record-writes.md)).

A fourth operation, `ISSUE`, records a document being issued, and it is the only entry that points
somewhere: it carries `documentId`, which `GET /api/documents/{id}` resolves. The other three have
nothing to point at, so the field is absent on them.
Values that differ only in scale (`10` and `10.0` as they come back from `jsonb`) are not reported as
changes.

The history of a record is a read **of its object**, so a role granted `READ` on one object can read
that object's history and no other's. Field permissions apply here too: a field the caller may not
read never appears as a change or as a value, in either endpoint. Without that, the audit log would
be a way around the field permissions.

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
entry; on a `requiresReason` object it is required (`400` on `reason` without one).

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
(`targetObject` plus `values`), `WEBHOOK` (`url`) and `GENERATE_DOCUMENT` (`documentType`). Every
text value accepts `{{field}}` out of the record, plus `{{id}}`, `{{state}}`, `{{user}}`,
`{{today}}` and `{{now}}`.

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

## AI assistant

Module: wasichai-agent.

```http
GET  /api/agent/status    { "enabled": true, "model": "claude-haiku-4-5" }
POST /api/agent/ask       { "question": "…" }  ->  { answer, steps, truncated }
```

```json
{
  "answer": "Hay 3 predios comerciales de más de 1.000 m².",
  "steps": [
    { "tool": "list_objects", "input": {}, "summary": "3 objects" },
    { "tool": "query_records", "input": { "object": "predio", "uso": "COMERCIAL" }, "summary": "3 records" }
  ],
  "truncated": false
}
```

The assistant has no database access: it calls the same services a person's requests go through, as
the person asking, so permissions, tenancy and field visibility apply unchanged (ADR-014). Its tools
are read-only — there is no tool that creates a record or moves a workflow. `truncated` means it hit
its step limit and stopped, so the answer may be incomplete.

Without `ANTHROPIC_API_KEY` the status reports `enabled: false` and asking answers `503` with a
problem+json explaining what is missing. Nothing else in the platform depends on it.

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
