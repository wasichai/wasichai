# GIS and geometry

Everything on this page needs the gis module: `wasichai-spring-boot-starter-gis` on the backend,
`gisModule()` from `@wasichai/gis` on the frontend, and a PostgreSQL with PostGIS. Without it, wasichai
runs on plain PostgreSQL and none of these routes, types or parameters exist
([gis module](../modules/gis.md), ADR-027).

## A geometry is a field

An object has as many geometries as it needs, each a Custom Field of type `GEOMETRY` carrying its own
shape, CRS and dimension (ADR-019). A parcel can hold its plot and its access point at once:

```json
{ "name": "lote",   "type": "GEOMETRY", "geometryType": "POLYGON", "srid": 32718 }
{ "name": "acceso", "type": "GEOMETRY", "geometryType": "POINT",   "srid": 32718 }
```

An object with no geometry field is a flat object. There is no "no geometry" value to set.

## Storage

Each one is a real typed PostGIS column in the CRS its field declares, with its own GIST index:

```sql
lote   geometry(Polygon, 32718)
acceso geometry(Point, 32718)
CREATE INDEX predio__00000000_lote_gix   ON app_data.predio__00000000 USING GIST (lote);
CREATE INDEX predio__00000000_acceso_gix ON app_data.predio__00000000 USING GIST (acceso);
```

Never a JSON string. A real typed column gives spatial indexes, spatial predicates, correct area and
length in projected units, and a table GeoServer and QGIS consume directly.

Index names are built by `SqlIdentifier.indexName`, which truncates past Postgres's 63 characters and
ends on a hash of what it cut, so two long names cannot collapse into one.

A field declaring `dimension: 3` gets `geometry(PointZ, …)`. Objects created before ADR-019 kept the
2D column they had, and their metadata says 2.

A field's own `geometry_type`, `srid` and `dimension` live on `custom_fields`, but gis's migration
adds those three columns, not core's: core's `custom_fields` has no geometry columns at all until
wasichai-gis is installed (ADR-026).

## Over the wire

The API always speaks **GeoJSON in EPSG:4326**; the database keeps each field's declared CRS. R2DBC
has no PostGIS codec, so the conversion happens in SQL, once per geometry column:

```sql
-- read
ST_AsGeoJSON(ST_Transform("lote", 4326)) AS "lote__geojson"

-- write
ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:geom_lote AS text)), 4326), 32718)
```

A polygon drawn in the browser around Lima therefore lands as UTM 18S metres, and `ST_Area` returns
square metres rather than square degrees.

A record carries them in a map beside its attributes, never inside:

```json
{
  "attributes": { "codigo": "P-001" },
  "geometries": {
    "lote":   { "type": "Polygon", "coordinates": [] },
    "acceso": null
  }
}
```

On the way in, a geometry left out of the map is left alone and one sent as `null` is cleared. On the
way back every declared geometry is listed, `null` included, so a missing key never means two things.

## Spatial query

`?bbox=minX,minY,maxX,maxY` (EPSG:4326) filters one column, named by `?geometry=`; without it, the
first geometry the object declares:

```sql
ST_Intersects("acceso", ST_Transform(ST_MakeEnvelope(:minX, :minY, :maxX, :maxY, 4326), 32718))
```

The envelope is transformed to the field's CRS so its GIST index is used. A `bbox` on an object with
no geometry, or naming one the object does not have, is a `400`.

## Features

A GeoJSON `Feature` holds one geometry, so `/api/gis/objects/{object}/features` serves one at a time,
picked by the same `?geometry=`. Feature ids are `<record>:<geometry>`, because one record can appear
twice on a map and MapLibre keys feature state on the id.

## Publishing

A layer is one geometry of one object, named `<physical table>__<column>`. The GeoServer feature type
is a JDBC virtual table that selects `id`, the plain columns and that one geometry — naming it,
rather than leaving GeoServer to pick between two, and keeping the object's other geometries out of
the published attributes.

A layer published before ADR-019 is named after the table alone. Nothing is called that under the
current scheme, so one is taken as the object's first geometry: it shows as published rather than
hiding, and publishing or unpublishing that geometry retires it.

A GeoServer used from a local dev server needs CORS on (`CORS_ENABLED` in its container's environment), because
the layers screen previews a published layer by fetching WMS tiles straight from it, across origins. A deployment
fronts both behind one host and does not need it.

## Validation

The declared type is enforced twice: the API rejects a GeoJSON whose `type` does not match the field
(the error names the field), and the typed column rejects anything that slips past.

## Known limitations

- A `MULTI*` geometry field cannot be drawn in the UI: the draw modes are single-part, as in the
  original app. The API accepts `MULTI*` GeoJSON.
