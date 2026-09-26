# ADR-019: A geometry is a field, so an object can have more than one

**Status**: accepted · 2026-09-18 · amends ADR-004, ADR-006 and ADR-007

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

Geometry was a property of the object: `geometryType`, `srid` and `dimension` on
`wasichai.custom_objects`, materialised as one column named literally `geom`. The decision was
explicit, and so was its refusal:

> `// MetadataService.buildField`
> `if (type == FieldType.GEOMETRY) throw ValidationException("Geometry is declared on the object", …)`

That holds while a thing has one shape. It stops the moment one has two: a parcel with a plot
polygon and an access point, a network with a route and its nodes. Modelling the second one meant a
second object and a relationship, which is a join to answer "where is it".

The database had been ready the whole time. `V1__core.sql:108-111` already admitted `'GEOMETRY'` in
the `custom_fields.type` check, and `FieldType.GEOMETRY("geometry")` already existed. Two Kotlin
refusals were the only thing in the way.

## Decision

**A geometry is a Custom Field of type `GEOMETRY`**, carrying its own `geometry_type`, `srid` and
`dimension` on the field row — the shape `enum_options` already had for ENUM and
`relation_target_object_id` for RELATION. `custom_objects` loses its three geometry columns; there is
one place to read what a record carries, not two.

The alternative was a `wasichai.object_geometries` table, keeping geometry a first-class concept
separate from fields. It would have been a parallel universe — its own table, endpoints, DTOs and
editor — and geometry would still have been unable to sit inside a form section.

**Being a field is the point.** A geometry now inherits the field editor, `visible`/`editable`/
`required`, field-level permissions, deletion, and its **position** — so it is drawn where the form's
author put it, instead of always trailing the form outside its sections.

**On the wire, `geometries` is a map beside `attributes`, not inside it.** A geometry is already
special underneath: `ST_AsGeoJSON` to read, `ST_GeomFromGeoJSON` to write, never a bound value. Inside
`attributes` it would also put coordinates into every audit diff. A geometry left out of the map is
left alone; one sent as `null` is cleared. Every declared geometry is listed on the way back, `null`
included, so a missing key never means two things.

**A spatial query names its geometry.** `ST_Intersects` needs a column and a GeoJSON `Feature` holds
one geometry, so `?geometry=<field>` does both jobs and defaults to the first. A `bbox` on an object
with no geometry is now a `400`; it used to be dropped in silence and answered `200`.

**A layer is a pair.** One geometry of one object, named `<physical table>__<column>`. No table
stores this: both halves are already metadata, the name derives from them, and published-ness stays a
live question to GeoServer, as ADR-006 decided.

**The GeoServer payload names the column.** It used to send only `nativeName` and let GeoServer find
the geometry column — fine with one, a silent coin toss with two. The feature type is now a JDBC
virtual table selecting `id`, the plain columns and that one geometry, with `keyColumn` and the
geometry's type and SRID declared. ADR-004 anticipated this: *"layer needs a generated view anyway"*.

**`geom` stops being a reserved name.** It was a system column because the platform owned it; it is a
user field now, and `SystemColumnScope.GEOMETRY` goes with it.

## Consequences

- **`RecordRequest.geometry` becomes `geometries`, with no alias.** A map cannot be derived from a
  single value without picking one arbitrarily. It is the only break in the record contract.
  `ObjectResponse.geometry` survives, derived from the first geometry field, so callers that only ask
  "is this object spatial" keep working.
- **`GeometryType.NO_GEOMETRY` is gone.** "No geometry" is an empty list, not a sentinel inside the
  enum — which means the ~15 places that asked `obj.geometryType.hasGeometry` now ask the definition.
- **The migration moves metadata, not data.** The column was already called `geom`, so V9 inserts a
  field row pointing at it and runs no DDL. It writes `dimension = 2` whatever the object claimed,
  because 2 is what the column is: `ObjectSchemaManager` had never emitted `PointZ`. Dimension is
  honoured from here on, so objects created before this keep 2D columns and say so.
- **A geometry can now be added to an object that already exists**, which was impossible before:
  it goes through `addField` → `ALTER TABLE ADD COLUMN` plus its own GIST index. Changing or dropping
  one is still refused, like any other field's type.
- **Index names are truncated on purpose.** `<table>_<column>_gix` can pass Postgres's 63 characters,
  and a silent truncation collides with its neighbour; `SqlIdentifier.indexName` cuts and ends on a
  hash of what it cut.
- **Layers published before this are named after the table alone.** Unpublishing still finds them,
  but they should be republished to get the new name.
- **Field-level permissions now cover geometry**, because it is a field: a role that may not write one
  is refused, and one it may not read does not come back. It had no field security at all before.
- **Views, sorting, audit diffs and automations still do not see geometry.** Sorting or filtering by
  one is refused outright — `bbox` is how you filter a geometry. Nothing got worse; it simply stopped
  being impossible to propose.
