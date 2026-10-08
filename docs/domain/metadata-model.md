# Domain model

```
Organization
├── User ── Role ── Permission
└── CustomObject
    ├── CustomField
    │   └── (GEOMETRY fields come from wasichai-gis)
    ├── Relationship
    ├── View / Page / Form  (JSON definitions the UI interprets)
    └── records             (app_data.<physical_table>)
```

## CustomObject

| Column | Notes |
|---|---|
| `name` | stable technical name, `^[a-z][a-z0-9_]{0,38}$`, unique per organization |
| `label`, `plural_label` | what people see |
| `physical_table` | `<name>__<first 8 of organization id>`, generated once and persisted |

An object has no geometry of its own. A geometry is a field (ADR-019): a Custom Field of type
`GEOMETRY` with its own shape, SRID and dimension, so an object can carry as many as it needs. The
`GEOMETRY` type exists only when `wasichai-gis` is installed ([gis module](../modules/gis.md)). Without
it, core runs on plain PostgreSQL and the type is unknown: creating such a field is refused, and
editing one that already exists answers `409` (ADR-031 D2).

## CustomField

Types today: `TEXT`, `LONG_TEXT`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME`, `ENUM`,
`EMAIL`, `URL`, `UUID`, `RELATION`, registered by core (`ScalarFieldTypes`), plus `GEOMETRY` when
wasichai-gis is installed, and `FILE` and `IMAGE` when wasichai-files is ([files module](../modules/files.md)).
Planned: `CURRENCY`, `PHONE`, `FORMULA`, `ROLLUP`, `JSON`.
Adding one means a `FieldTypeHandler` bean (ADR-025), which owns the column type, validation and SQL.
A module ships it, and core does not change.

| Field type | Column type | Enforced by |
|---|---|---|
| TEXT / LONG_TEXT / EMAIL / URL | `text` | codec (format), `NOT NULL` when required |
| INTEGER | `bigint` | codec |
| DECIMAL | `numeric` | codec |
| BOOLEAN | `boolean` | codec |
| DATE / DATETIME | `date` / `timestamptz` | codec (ISO parsing) |
| ENUM | `text` | `CHECK (col IN (...))` plus codec |
| UUID | `uuid` | codec |
| RELATION | `uuid` | `REFERENCES <target> (id) ON DELETE SET NULL` |
| GEOMETRY | `geometry(<type>, <srid>)` | wasichai-gis handler, PostGIS |

`unique` becomes a real `UNIQUE` constraint; `required` becomes `NOT NULL`. Validation lives in the
database as well as in the application, so bad data cannot arrive by another route.

`default_value` is text, parsed by the field type's own handler when the field is created or its default or enum
options change, so a default the type cannot read is refused then (`400` on `defaultValue`), not on every later
insert. Blank means none. On create, and only on create, a record takes the default of every attribute field its
request leaves out; a key sent, `null` included, wins. The default is the field's value, so it is written even where
the caller may not write the field. Updates never apply it, and records that exist keep what they hold. It is applied
by the platform, not by the column: there is no SQL `DEFAULT`. A field type in a payload section (`GEOMETRY`) takes no
default (ADR-031 D40).

## Relationships

| Type | Where the foreign key lives | Read from source | Read from target |
|---|---|---|---|
| `MANY_TO_ONE` | `RELATION` field on the source | one target | many sources |
| `ONE_TO_ONE` | `RELATION` field on the source, `UNIQUE` | one target | one source |
| `ONE_TO_MANY` | `RELATION` field on the target | many targets | one source |
| `MANY_TO_MANY` | join table `rel_<name>__<org8>` | many | many |

A relationship is metadata plus the physical structure that implements it. `label` names it from the
source, `inverseLabel` from the target, which is what the related lists show on each detail page.
Foreign keys are real (`ON DELETE SET NULL` for fields, `ON DELETE CASCADE` inside join tables), so
the database keeps the graph honest.

A relationship may have the same object as source and target (a parent unit, a previous version). Its object then
stands on both ends: the related read takes `direction=forward` (the default, the walk it always made: the "Read from
source" column, but "Read from target" for `ONE_TO_MANY`) or `direction=inverse` (the other column), and the
object's relationship list shows it once per direction (ADR-031 D42, [rest.md](../api/rest.md) "Related records").

## Pages

Provided by wasichai-pages ([pages module](../modules/pages.md)).

A page is a layout plus an ordered list of components, each declaring the column it lives in:

```json
{
  "layout": "two-column",
  "components": [
    { "type": "FORM", "column": 1 },
    { "type": "MAP", "column": 2, "title": "Ubicación" },
    { "type": "RELATED_LIST", "column": 1, "relationship": "predio_titular", "title": "Titular" },
    { "type": "TEXT", "column": 1, "content": "Verifica el área contra el plano catastral." }
  ]
}
```

One page per object and kind (`RECORD_DETAIL` today), scoped to the organization. When none is
stored, the server derives one from the object's own metadata, so a screen exists from the moment an
object does (ADR-011).

## Tenancy

One database, `organization_id` on every row, one physical table per (organization, object). A tenant
never appears in a request body: it comes from the JWT.

## Audit

`audit_log` records user, organization, object, record, operation and the before/after attribute maps
for every record create, update and delete.
