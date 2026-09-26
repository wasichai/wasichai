# ADR-007: Geometry over R2DBC as GeoJSON in SQL

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

WebFlux forces a reactive driver. R2DBC has no PostGIS codec comparable to hibernate-spatial, so
there is no `Geometry` type to bind or read.

## Alternatives

- **WKB/EWKB as `bytea` plus JTS in the JVM**: works, but adds a dependency and a conversion layer to
  produce the GeoJSON the frontend wants anyway.
- **A custom codec**: real work, and a maintenance burden tied to driver internals.

## Decision

Convert in SQL, in the format the client already speaks:

```sql
ST_AsGeoJSON(ST_Transform(geom, 4326)) AS geom_geojson
ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:geometry AS text)), 4326), <srid>)
```

The API always exchanges EPSG:4326; the database keeps the object's declared CRS.

## Consequences

- No JTS, no codec, no driver coupling; PostGIS does the projection, which is what it is for.
- Geometry crosses as text, which costs some bytes. Irrelevant next to a network round trip, and
  vector tiles are the answer if a layer ever outgrows it.
- Clients get consistent coordinates regardless of how each object is stored.
