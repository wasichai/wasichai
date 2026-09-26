# ADR-002: PostgreSQL 18 + PostGIS (+ pgvector)

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

The platform is spatial first: storage, indexing, reprojection and spatial predicates are core, not
an add-on. A future phase adds AI agents that need embeddings.

## Decision

PostgreSQL 18 with PostGIS as the only datastore, plus pgvector for later semantic search. No
official image ships PostGIS and pgvector together, so `infra/docker/postgres` builds on
`postgis/postgis:18-3.6` and installs `postgresql-18-pgvector` from PGDG.

## Consequences

- Real geometry columns, GIST indexes, `ST_Transform`, and a database GeoServer and QGIS speak
  natively.
- One image to maintain. The migration creates the `vector` extension only when the server offers it,
  so a plain PostGIS server still works until the AI phase needs it.
