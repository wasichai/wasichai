# ADR-072: Attribute styles computed on the server, kept in map views and published as SLD

**Status**: accepted · 2026-10-25 · builds on [ADR-019](0019-a-geometry-is-a-field.md) and
[ADR-031](0031-deliberate-deviations-from-sapgis.md) (D51)

## Context

The map of `@wasichai/gis` painted every geometry in one colour, and a GeoServer layer was published with
GeoServer's default style. Apps that colour by an attribute (fares per street segment, gaps per plot) ended up
writing their own MapLibre map and their own SLD ([wasichai#134](https://github.com/wasichai/wasichai/issues/134),
[wasichai-ui#86](https://github.com/wasichai/wasichai-ui/issues/86)). The "Map views" menu entry was a placeholder
with nothing behind it.

A colouring by quantiles depends on all of the tenant's rows. Computed in the browser it would see only the features
it loaded (a bbox, a filter) and disagree with the WMS layer; computed twice it would disagree whenever either side
changed its arithmetic.

## Decision

- One model, `AttributeStyle { by, type: categories | quantiles, classes?, palette? }`, travels in the REST API, is
  stored in a map view and drives the SLD.
- The server computes the classes: `POST /api/gis/objects/{object}/style-classes`. An `ENUM` is classed by its options
  (no query), a `BOOLEAN` by `true`/`false`, a `TEXT` by its distinct values (at most 50), an `INTEGER` or `DECIMAL`
  by `percentile_cont` over the tenant's rows. The UI paints a MapLibre `match`/`step` expression and a legend from
  that answer; the SLD is built from the same answer. The field must be readable by the caller.
- A publish may carry the style. The layer's default style becomes a workspace style named after the layer; a
  publish without one sets GeoServer's built-in style back and drops it, and so do unpublishing and the object's
  removal. Republishing is how a style changes, as it is how a layer is normalised.
- A map view is `{ name, label, object, geometry, style? }` in `${metadata}.map_views`, wasichai-gis's own table,
  read with `READ` on the object and written with `MANAGE_METADATA`, like a list view.

## Consequences

- The map, its legend and the WMS layer show the same classes, at the price of one request per style change.
- An SLD is a snapshot: the breaks are those of its publish. Data that changes needs a republish.
- A style naming a field that was later removed or retyped answers `400` when it is used, not when the field goes.
- The original app had none of this: it is ADR-031 D51, routes and table included.
