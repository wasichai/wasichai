# ADR-037: Composite unique constraints, and a repeated unique value as a 409

**Status**: accepted · 2026-10-02

## Context

caja-backend needs uniqueness over more than one field: `orden_de_cobro (sistema_origen, referencia_externa)`,
`turno (caja, cajero, fecha)`, `tasa (codigo, vigencia_desde)` ([wasichai/wasichai#14](https://github.com/wasichai/wasichai/issues/14)).
wasichai could declare `unique: true` on one field only, a real `UNIQUE` on that column of the organization's table.
caja-backend worked around it with a derived TEXT field (`clave_origen = sistema|referencia`) computed in Kotlin,
which a write through the generic record API could leave inconsistent.

A repeated value was also an untyped `500` with no `errors[]`: nothing mapped the driver's unique violation, so the
catch-all answered "Unexpected error" and a client could not tell which field to fix.

## Decision

**An object lists composite `uniqueConstraints`, in the shape of `indexes`.** `uniqueConstraints:
[["sistema_origen", "referencia_externa"]]` on `CreateObjectRequest`, `UpdateObjectRequest`, `ObjectResponse` and
`ObjectDefinitionResponse`, field names in constraint order. It reuses what [ADR-036](0036-declared-indexes-optional-count-and-keyset-reads.md)
built for `indexes` rather than a second shape:

- **Validation is `FieldSets.normalize`**, with the property name `uniqueConstraints`: names trimmed and lower-cased,
  order kept, a repeated set counted once, and a `400` naming the property for an empty entry, an unknown field, a
  field named twice, or a field that cannot be indexed (`LONG_TEXT`, or a type its handler keeps out of filters, such
  as a geometry). A unique constraint is a btree index, so what cannot be indexed cannot be unique. An entry takes at
  most 31 fields, one fewer than an index, because the constraint also covers `organization_id`.
- **Storage is a jsonb list on `custom_objects`**: core migration `V4__unique_constraints.sql` adds
  `custom_objects.unique_constraints jsonb NOT NULL DEFAULT '[]'`, next to V3's `indexes`.
- **The DDL goes through `ObjectSchemaManager` alone.** Each entry is `ALTER TABLE … ADD CONSTRAINT
  "<physical table>_uq_<hash>" UNIQUE (organization_id, …)`, named by `SqlIdentifier.fieldSetName` like a declared
  index, so it is dropped by name with no catalog read. `organization_id` leads: the table is already per
  organization, so this changes nothing today, but uniqueness stays per tenant if a table ever holds more than one,
  and every record query filters on `organization_id` first, so the index still serves lookups on the set.
  Creating an object adds its constraints. Updating `uniqueConstraints` compares the lists before and after, drops
  what went first and adds what came, in the transaction that writes the metadata. Without `uniqueConstraints` the
  list is left alone, and `[]` drops every entry.
- **Adding one the data already breaks is a `409`, and nothing changes.** PostgreSQL refuses the `ADD CONSTRAINT`,
  `MetadataService` turns that into a `409` naming `uniqueConstraints`, and the transaction rolls back the metadata
  written before it, label and other properties included. Making a single field `unique` over repeated values gets
  the same treatment, naming `unique`.
- **A field or relationship that a set names cannot be deleted** (`409`), extending ADR-036's guard: PostgreSQL would
  drop the constraint with the column while the metadata still listed it. `FieldSets.blocking` answers for both lists.
- **Every organization, including one provisioned later.** As with indexes, a new tenant starts with no objects, and
  the constraints are built when the app applies its model to it. There is no startup reconciliation for them: the
  metadata and its constraint are written in one transaction, so they cannot drift apart through wasichai, and adding a
  constraint at startup could fail on existing data where a missing index only meant slower reads.
- A caller who cannot read one of the fields of a set does not see that set in the definition, as with `indexes`.

**A unique violation on a write is a `409` naming the constraint's fields.** `GlobalExceptionHandler` maps Spring's
`DuplicateKeyException` (SQLSTATE `23505`) to a `409` problem+json in the shape of every other error, with one
`errors[]` entry per field: `{ "field": "sistema_origen", "message": "must be unique together with
referencia_externa" }`, or `"must be unique"` for a single field. The detail names the fields, never the values.

- **The fields come from the constraint name, not the message.** The PostgreSQL driver reports the schema, table and
  constraint of the violation (`PostgresqlException.errorDetails`). `ObjectSchemaManager.uniqueFields` reads that
  constraint's columns from `pg_constraint`, in constraint order, and leaves `organization_id` out. The message text
  is never parsed: it changes with `lc_messages` and carries the values. A column is its field's name in core
  (`MetadataService` sets `columnName = name`), so the columns are the field names.
- **The catalog read happens in the handler**, after the failed write's transaction has ended. Inside it, an aborted
  transaction would refuse any further statement.
- **`common` sits below `metadata`** in core's package order (`CoreArchitectureTest`), so the handler takes the
  resolution as a function, wired in `WasichaiPlatformAutoConfiguration` to `ObjectSchemaManager`. If it is missing,
  or the violation is not a unique on a data table (a primary key, a metadata table), or the lookup fails, the answer
  is still a `409`, without `errors[]`.
- **The driver is a compile-only dependency of core.** The starter brings it at runtime, and the handler checks it is
  on the classpath before looking for its exception type.
- In-process callers of `RecordService` still get the `DuplicateKeyException`. Only the response changes.

## Consequences

- caja-backend declares its composite keys in its model and drops the derived `clave_origen` fields. A write through
  the generic record API can no longer break them.
- A client shows a repeated value next to the field, from `errors[].field`, as it does for a `400`.
- The metadata schema gains a column the original does not have. `SchemaParityTest` lists it as a known deviation,
  citing [ADR-031](0031-deliberate-deviations-from-sapgis.md) D23, which records it with the `500` → `409` change.
- `FieldApiTest`, core's and the ported parity one, asserted `5xx` for a repeated unique value and now asserts the
  `409` with `errors[0].field`. `CompositeUniqueApiTest` covers a second organization, create and update, the `409`
  on existing repeats with metadata unchanged, the delete guards, and dropping the list.
- Adding a constraint on a large table holds a write lock while PostgreSQL builds its index, as an index does
  (ADR-036).
- A `409`'s `errors[]` can name a field the caller cannot read, when a constraint covers one. It never carries the
  value.
