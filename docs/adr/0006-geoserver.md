# ADR-006: GeoServer as publisher, not as the core

**Status**: accepted · 2026-09-17

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

OGC services (WMS, WFS, WMTS) are expected, and desktop clients such as QGIS and QField will consume
them. Building them into the application would be a large amount of undifferentiated work.

## Decision

GeoServer publishes services from the same PostGIS database. Wasichai owns metadata, business rules and
the application API; GeoServer owns rendering and OGC protocols. The application never calls
GeoServer on a request path, and works with it stopped.

## Consequences

- Layers publish without data movement, because every object is already a real table with a
  registered geometry column.
- Automating layer creation when an object is created is a later phase, behind a port in `gis`.
- Authorization across the two systems will need attention when layers become non-public.
