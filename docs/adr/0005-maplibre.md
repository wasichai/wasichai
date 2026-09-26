# ADR-005: MapLibre GL JS for the geoviewer

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

The platform needs an embeddable map: render features, select them, inspect attributes, draw
geometry, and later consume WMS/WFS/WMTS and vector tiles.

## Decision

MapLibre GL JS, wrapped in a `MapView` component that knows nothing about the business model — it
takes a FeatureCollection and an optional draw mode. Drawing uses Terra Draw through its MapLibre
adapter.

## Consequences

- Open source, no API key, vector and raster sources, and a straight path to vector tiles.
- MapLibre 6 ships its worker as a separate file and resolves it from `import.meta.url`, which
  bundling breaks; `setWorkerUrl` must point at it or GeoJSON sources silently never load.
- The map is reusable for the record detail, the map page and any future page component.
