# ADR-004: One physical table per Custom Object

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

Dynamic records can be stored as a generic table with a `jsonb` column, as EAV rows, or as a real
table created at runtime. GeoServer, QGIS and WFS all want a table or view per layer with a typed
geometry column, and spatial and attribute queries need real indexes.

## Alternatives

- **Generic table + `jsonb`**: no runtime DDL, but no native constraints, weaker indexing, and every
  layer needs a generated view anyway — which is still DDL.
- **EAV**: worst query shape of the three, and hostile to spatial work.

## Decision

`ObjectSchemaManager` issues DDL and creates `app_data.<name>__<org8>` with typed columns, enum
`CHECK` constraints, relation foreign keys, a `geometry(<type>, <srid>)` column and a GIST index.
All record access goes through the `RecordStore` port, so another strategy can be swapped in without
touching callers.

## Consequences

- Native constraints, real spatial indexes, and a table GeoServer can publish as it stands.
- Runtime DDL is a real risk, contained by validating technical names, quoting identifiers in one
  place, binding every value, and allowing DDL from `ObjectSchemaManager` only.
- Objects per tenant now cost tables. Acceptable at the scale this targets; the port is the escape
  hatch if that changes.
