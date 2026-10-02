# ADR-036: Declared indexes, an optional count and keyset reads

**Status**: accepted · 2026-10-02

## Context

An app built on wasichai writes large tables into one organization's schema: srtm-backend's arbitrios add about 540 000
to 900 000 rows a year to `cuota_arbitrio`, and almost every read filters on `{predio, anio}` or `{contribuyente,
anio}` ([wasichai/wasichai#21](https://github.com/wasichai/wasichai/issues/21)). Three things stood in the way:

- **Indexes.** `ObjectSchemaManager` indexed `organization_id`, the record state, a join table's `target_id`, unique
  constraints and whatever a field type handler asked for. A `RELATION` column was a bare `REFERENCES`, which
  PostgreSQL does not index, and an app could declare nothing else. In a spike, a filter on `anio + predio` read 60 rows
  out of 505 000 in 62 ms without an index and 3.3 ms with one. srtm-backend worked around it by building the indexes
  itself, from table and column names wasichai does not promise.
- **Count.** Every list ran `SELECT COUNT(*) … WHERE` before its page, so a client walking a big set counted the whole
  match again on every page.
- **Offset.** `LIMIT/OFFSET` makes reading a set page by page quadratic. #20 already made the order unique: every
  `ORDER BY` ends with `id` ([ADR-031](0031-deliberate-deviations-from-sapgis.md) D21).

## Decision

**A field can be `indexed`, and an object lists composite `indexes`.** `indexed: true` on `FieldRequest`,
`UpdateFieldRequest` and `FieldResponse` gives the field's column an index of its own. `indexes: [["anio", "predio"]]`
on `CreateObjectRequest`, `UpdateObjectRequest`, `ObjectResponse` and `ObjectDefinitionResponse` lists composite ones,
field names in index order. They live in metadata (core migration `V3__declared_indexes.sql`: `custom_fields.indexed
boolean NOT NULL DEFAULT false` and `custom_objects.indexes jsonb NOT NULL DEFAULT '[]'`), and the DDL goes through
`ObjectSchemaManager` alone.

- **Every `RELATION` column is indexed**, declared or not, because PostgreSQL does not index a foreign key and a filter
  on a relation is the common case. This covers `MANY_TO_ONE` and the `ONE_TO_MANY` column on the other side. A column
  that is `unique` gets no second index, since the unique constraint already gives it one. The same goes for a
  `ONE_TO_ONE` relation.
- **Names are derived, not stored.** An index is `<physical table>_ix_<first 10 hex of SHA-256 of the column list>`
  (`SqlIdentifier.fieldSetName`), so the same list always has the same name, order counts, `["a_b"]` never collides
  with `["a", "b"]`, and the name fits 63 characters for every physical table. `CREATE INDEX IF NOT EXISTS` and
  `DROP INDEX IF EXISTS` make applying the same metadata twice a no-op.
- **The DDL follows the metadata.** `ObjectSchemaManager.declaredIndexes(definition)` is the list of column lists a
  table should carry. Creating an object or adding a field builds its indexes. Updating `indexed`, `unique` or an
  object's `indexes` compares the list before and after the change and drops or creates only the difference, in the
  same transaction as the metadata. An `UpdateObjectRequest` without `indexes` leaves them alone, and `[]` drops them.
- **Validation is one path, `FieldSets`**, so that a sibling list (unique constraints, #14) is checked and stored the
  same way. Names are trimmed and lower-cased, and order is kept. A repeated set counts once. An empty set, more than
  32 fields (PostgreSQL's limit), an unknown field, a field named twice, a `LONG_TEXT` field (a long value can outgrow
  a btree entry and fail a later write) or a type whose handler refuses filter and sort is a `400` naming the property.
  A field that a composite index names cannot be deleted (`409`): PostgreSQL would drop the index with the column,
  and the metadata would still list it.
- **Every organization, including one provisioned later.** Metadata is per organization, and a new tenant starts with
  no objects, so provisioning itself has nothing to build. The indexes are built when the app applies its model to
  that tenant, exactly as its tables are. In addition, `DeclaredIndexReconciler` runs on `ApplicationReadyEvent`. It
  reads the catalog once, then creates what the metadata of every organization declares and the catalog lacks. It
  never drops anything. Failures are logged per object rather than thrown: two instances that start together race on
  the same `CREATE INDEX`, and a missing index means slower reads, which is no reason to refuse to start. It is
  switched off with `wasichai.metadata.reconcile-indexes=false`.
- **Existing `RELATION` columns get their index from that reconciliation, not from the migration.** A Flyway migration
  would have to walk every organization's tables and generate DDL outside `ObjectSchemaManager` (rule 6), with the
  naming logic duplicated in SQL. The reconciler reuses the one code path. The first start after an upgrade builds the
  missing indexes. On a large table that holds a write lock on that table while the index builds.

**`count=false` skips the count.** `RecordQuery.count` (default `true`) and `?count=false` over REST run no
`COUNT(*)`. The page's `totalElements` and `totalPages` are then `null`. Any other value of `count` is a `400`. By
default nothing changes.

**Keyset reads resume after `(sort value, id)`.** Every page carries `nextCursor` when another row follows it. The
store reads one row past the page to know this, and counts nothing. `RecordQuery.after` (`?after=<nextCursor>`)
starts the next page strictly after that row:

- The cursor is opaque: base64url of a version, the sort key, the direction, the last row's id and its sort value as
  PostgreSQL's own text of the column (null kept apart). The condition casts it back to the column's type, so any
  sortable type round-trips exactly.
- It is the same pair the `ORDER BY` ends on, so rows tied on the sort value are split by `id` and every row is read
  exactly once. A `NOT NULL` key (`created_at`, `updated_at`, a required field) is a row comparison,
  `(key, id) > (:value, :id)`, which an index on the key serves. A nullable key steps around its nulls the way
  PostgreSQL orders them: last when ascending, first when descending. Sorting by `id` compares `id` alone.
- `after` with `page > 0`, a cursor issued for another `sort` or `dir`, or a string that is not a cursor is a `400`
  on `after`. A cursor whose value was tampered with is not detected and fails like any bad SQL value.
- `totalElements`, when counted, is still the total of the match, not what is left after the cursor.
- `RecordService.list` and related-record lists take the same `RecordQuery`, so in-process callers and
  `/related/{relationship}` get both options too.

**The wire stays as it was by default.** `indexed` is written only when `true`, `indexes` only when not empty, and
`nextCursor` only when not null. An object, field or page that uses none of this serializes byte for byte as before,
which `GeometryWireParityTest` checks. `count` and `after` become reserved query parameters, so they can no longer
filter a field that happens to be named `count` or `after`.

## Consequences

- An app declares its indexes in its model and stops reaching into wasichai's table and column names.
  srtm-backend's `IndicesArbitrios` can go.
- A filter on a relation or a declared field uses an index on every organization's table. `DeclaredIndexApiTest`
  shows the index in a second organization and an `EXPLAIN` that uses it (with `enable_seqscan` off inside the
  check's own transaction, so the test does not depend on how the planner weighs a few thousand rows).
- A big set can be read in one pass with `count=false&after=…`. `RecordKeysetApiTest` reads 23 rows, all tied on
  `created_at`, with ties and nulls in the sort key, in every direction, and sees each row once.
- The metadata schema gains two columns the original does not have. On a fresh database, `custom_fields.indexed`
  comes before the `wasichai-gis` attribute columns, so those sit one position later than in the original.
  `SchemaParityTest` lists these lines as known deviations, citing this ADR. They are additions, not a different
  behaviour, so they get no ADR-031 entry.
- `PageResponse.totalElements` and `totalPages` become nullable in Kotlin. A module that reads them has to handle
  `null` once it passes `count = false`. The ones that do not pass it always get numbers.
- An index is not built `CONCURRENTLY`: the metadata change and its DDL share one transaction, and `CONCURRENTLY`
  cannot run inside one. Declaring an index on a large existing table blocks writes to it while the index builds.
