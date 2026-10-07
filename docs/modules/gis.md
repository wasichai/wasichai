# GIS module

An app that installs this module lets an object carry one or more geometry fields, drawn and edited on a map,
queried by bounding box, served as GeoJSON features, and published as WMS/WFS layers in GeoServer.

## Install

```kotlin
implementation("wasichai:wasichai-spring-boot-starter-gis")
```

Needs PostgreSQL 18 with PostGIS 3.6 (the `postgis` extension; the image `postgis/postgis:18-3.6` has it). GeoServer is optional.

```bash
yarn add @wasichai/gis
yarn add maplibre-gl@6.10.0
```

`terra-draw` and `terra-draw-maplibre-gl-adapter` are regular dependencies of `@wasichai/gis`, not peers to add
yourself — see the package README for the exact versions and why `maplibre-gl` is pinned in the app too.

```tsx
<WasichaiApp modules={[gisModule({ workerUrl })]} />
```

`workerUrl` points at MapLibre's worker script as your bundler serves it; see the package README, "MapLibre
worker", for the recipe (Vite, webpack/rspack, or anything else).

See [../guides/build-your-app.md](../guides/build-your-app.md).

## What it adds

The `GEOMETRY` field type (registered under the record payload section `geometries`, not `attributes`), the
`bbox` and `geometry` record query parameters, feature endpoints that serve an object's records as GeoJSON, GeoServer
layer publishing, and the `MAP` page component (`@Order(100)`, only when wasichai-pages is present).

REST routes:

| Method | Path |
|---|---|
| GET | `/api/gis/layers` |
| POST | `/api/gis/layers/{object}` |
| DELETE | `/api/gis/layers/{object}` |
| POST | `/api/gis/layers/{object}/{geometry}` |
| DELETE | `/api/gis/layers/{object}/{geometry}` |
| GET | `/api/gis/services` |
| GET | `/api/gis/objects/{object}/features` |
| GET | `/api/gis/objects/{object}/features/{id}` |

A `/layers/{object}` call without a `{geometry}` segment means the object's first geometry field, so links saved
before an object could carry more than one geometry still work.

The feature endpoints read through `RecordService`, so they hold what the record list holds: the caller's permissions,
`own_records_only` and the app's read scope; a record out of scope is a `404`
([ADR-048](../adr/0048-a-read-scope-narrows-what-a-caller-reads.md)). A GeoServer layer does not: GeoServer reads the
table itself.

Frontend routes and slots:

| Slot | Contribution |
|---|---|
| route `gis:map` | `/gis/map` (`?object=&geometry=`): pick an object, draw and browse its features |
| route `gis:layers` | `/gis/layers`: GeoServer publishing status, publish/unpublish, WMS/WFS links |
| nav | group "GIS" (order 20): Maps, Layers, Map views (placeholder, disabled) |
| `fieldRenderers.GEOMETRY` | input, settings (`geometryType`, `srid`) for a geometry field, section `geometries` |
| `pageComponents.MAP` | draws the record's shapes, optionally one targeted geometry field |
| `recordListActions` | "Map" button on spatial objects' record lists |
| `dashboardCards`, `objectTileDetails`, `objectColumns` | spatial object count, `TYPE · EPSG:n` lines, an objects-table column |
| `auditValueFormatters`, `auditFieldLabels` | a shape change reads "geometry updated", never coordinates |
| `recordQueryKeys` | `['features', object]` goes stale on every write to that object's records |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `wasichai.gis.enabled` | `true` | `false` removes the `GEOMETRY` type, `bbox`/`geometry` query parameters, gis routes and the postgis migration |
| `wasichai.gis.geoserver.enabled` | `true` | `false` refuses layer publish/unpublish with a message; features and the `GEOMETRY` type still work |
| `wasichai.gis.geoserver.url` | `http://localhost:8081/geoserver` | base URL of the GeoServer REST API |
| `wasichai.gis.geoserver.username` | `admin` | GeoServer REST credentials |
| `wasichai.gis.geoserver.password` | `geoserver` | GeoServer REST credentials |
| `wasichai.gis.geoserver.workspace` | `wasichai` | GeoServer workspace layers publish into |
| `wasichai.gis.geoserver.datastore.name` | `wasichai-postgis` | name GeoServer gives the JDBC data store |
| `wasichai.gis.geoserver.datastore.host` | `localhost` | how GeoServer reaches Postgres; on a container network, the database's host there (e.g. `postgres`) |
| `wasichai.gis.geoserver.datastore.port` | `5432` | how GeoServer reaches Postgres |
| `wasichai.gis.geoserver.datastore.database` | `wasichai` | how GeoServer reaches Postgres |
| `wasichai.gis.geoserver.datastore.schema` | none | schema GeoServer's data store reads; unset follows `wasichai.database.data-schema` |
| `wasichai.gis.geoserver.datastore.username` | `wasichai` | how GeoServer reaches Postgres |
| `wasichai.gis.geoserver.datastore.password` | `wasichai` | how GeoServer reaches Postgres |

Env form once below the table: `WASICHAI_GIS_ENABLED`.

The datastore settings are how GeoServer, not wasichai, reaches the database: when GeoServer runs on a container
network, set them to the database as seen from there ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D17).

The GeoServer datastore schema follows `wasichai.database.data-schema` unless `wasichai.gis.geoserver.datastore.schema`
is set: an app that only configures the former still gets a working layer.

## Extension points

**Implements:** `wasichai.core.metadata.FieldTypeHandler` (`GeometryFieldType`, the `GEOMETRY` type),
`wasichai.core.data.RecordQueryContributor` (`BboxQuery`, the `bbox`/`geometry` parameters),
`wasichai.core.metadata.ObjectRemovalListener` (`LayerCleanup`, unpublishing every layer of an object when the
object itself is deleted), and pages' `wasichai.pages.PageComponentProvider` (`MapPageComponent`, the `MAP` type,
only when wasichai-pages is on the classpath).

**Overridable beans:** `geometryFieldType`, `bboxQuery`, `geoServerClient`, `layerCleanup`, `layerService`,
`layerController`, `featureController`, `mapPageComponent` — all `@ConditionalOnMissingBean`, so an app can
replace any of them.

## Database

Migration location `classpath:db/wasichai/gis`, history table `flyway_history_gis`. `V1__gis.sql`: `CREATE EXTENSION
IF NOT EXISTS postgis WITH SCHEMA public`, three new columns on core's `custom_fields` (`geometry_type`, `srid`,
`dimension`), and `custom_fields_type_valid` dropped and redefined with `GEOMETRY` appended to core's twelve types
([ADR-026 addendum](../adr/0026-per-module-migrations.md#addendum-2026-09-25-p7-a-check-has-one-extending-owner)).
wasichai-gis also adds its own CHECKs on those three columns (a `GEOMETRY` field must carry all three; the shape and
dimension are each one of a fixed set).

## Frontend package

`@wasichai/gis`: `gisModule(options)`, with `options.basePath` (default `'gis'`) and `options.workerUrl` (MapLibre's
worker script URL). Main exports from `index.ts`: `gisModule`, `gisMessages`, `MapView`, `GeometryField`,
`useFeatures`, `featureIdOf`, `geometryFields`, `wmsTileUrl`, and the `Feature`/`FeatureCollection`/
`GeoJsonGeometry`/`GeometryMeta`/`GeometryType` types.

`GeometryField` is the geometry editor a form places where the field's author put it; the registered field
renderer (`GeometryInput`) wraps it, reading the field's declared `geometryType` and `srid`. `MapView` is
`React.lazy`-loaded from a thin wrapper (`LazyMapView`), so MapLibre and terra-draw are pulled in only once a map
is actually drawn, never in the app's first chunk.

i18n namespace `gis`, exported as `gisMessages` (checked in `index.ts`).

Saving a geometry field's settings sends `{ geometryType, srid }`, defaulting to `POLYGON`/`4326`; `dimension` is
never sent — it is the server's call.

See [`packages/gis` in wasichai-ui](https://github.com/wasichai/wasichai-ui/tree/main/packages/gis) for the full API, including the
MapLibre worker recipe.

## Without this module

Plain PostgreSQL works: core never requires PostGIS. `GEOMETRY` is an unknown field type (`400` on create),
`bbox`/`geometry` are unknown query parameters (`400`), and the gis REST routes answer `404`
([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1). The map and layers screens and their nav entries
are absent. A page whose generated or edited layout still names a `MAP` component draws a muted placeholder
instead of the map ([ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D4).

## Behaviour differences

[ADR-031](../adr/0031-deliberate-deviations-from-sapgis.md) D1: a gis route with the module absent answers `404`
to an authenticated caller, never `403`. D2: any edit of a `GEOMETRY` field with the module absent answers `409`,
revalidated against the handler on every edit. D4: a `MAP` component with the module absent draws a placeholder
instead of failing to load the page.

## Known limitations

- A `MULTI*` geometry field cannot be drawn in the UI: the draw modes are single-part, as in the original app. The
  API accepts `MULTI*` GeoJSON. See [../gis/geometry.md](../gis/geometry.md) for storage and wire format.
- A published layer is served by GeoServer from the table, with GeoServer's own credentials: no wasichai record rule
  reaches it, neither `own_records_only` nor an app's read scope (ADR-048). Do not publish an object whose reads are
  scoped, or secure the layer in GeoServer.
