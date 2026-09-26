# ADR-027: GIS is optional, and the API is unchanged when it is present

**Status**: accepted · 2026-09-25 · amends ADR-002, ADR-007, ADR-019

> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

In sapgis, geometry lived in the core. `metadata` had `FieldType.GEOMETRY`, `GeometryType` and
`CustomField.geometryType/srid/dimension`. `data` emitted the geometry DDL and GIST index, selected
`ST_AsGeoJSON(ST_Transform(…, 4326))`, bound `ST_GeomFromGeoJSON`, filtered by `bbox`, and carried
`RecordRow.geometries`. The REST contract exposes all of it: `"geometry": {type, srid, dimension}` on
every field and object (null when flat), `"geometries": {…}` on every record, `?bbox=&geometry=` on
queries. Core must run on plain PostgreSQL, and with wasichai-gis present every one of those bytes must
come back unchanged.

## Decision

**A field type is a Strategy, looked up in a Registry.** `FieldType` is an open value
(`data class FieldType(val name: String)`). Core registers the 12 scalar types. wasichai-gis registers
one `FieldTypeHandler` for `GEOMETRY`, and that handler contributes everything geometry needs:

| Concern | Hook |
|---|---|
| column type `geometry(PolygonZ, 32718)` | `columnType(field)` |
| GIST index | `indexes(obj, table, field)` |
| read `ST_AsGeoJSON(ST_Transform(col, 4326)) AS "col__geojson"` | `select(field, column)` + `readName(field)` + `fromDatabase` |
| write `ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:p AS text)), 4326), srid)` | `bindExpression(field, parameter)` + `toDatabase` + `javaType` |
| shape check (`must be a Polygon`) | `toDatabase` |
| `geometryType`/`srid`/`dimension` on the request; no unique, no default | `attributesOf(fieldName, request)` via `FieldRequest.extensions`, `checkUpdate` |
| stored in `custom_fields.geometry_type/srid/dimension` | `attributeColumns` → `CustomField.attributes` |
| `"geometry": {…}` or `null` on every field and object | `fieldProperties`, `objectProperties` |
| `"geometries": {…}` beside `"attributes"` | `section = "geometries"`, `unknownSectionKey` |
| no sort/filter on a geometry | `rejectFilterOrSort` |
| `?bbox=&geometry=` | a `RecordQueryContributor` returning a `RecordCriterion` |
| drop its layer when the object goes | `ObjectRemovalListener` |

JSON extension properties are flattened with `@JsonAnyGetter` and captured with `@JsonAnySetter`.
With gis present, keys and values are identical to sapgis. Only key order changes (flattened keys
come last), and JSON object order carries no meaning. Without gis, `geometry` and `geometries` are not
`null` — they are absent keys, since nothing contributes them — `GEOMETRY` is an unknown type (400),
and `bbox` is an unknown field (400).

**SRID is validated twice, not once.** sapgis's `validSrid` ran only where the DDL was built; a handler
that checked it only in `attributesOf`, at field-creation time, would let anything that reaches
`columnType` directly — a migration path, a second call site — emit `geometry(Point, 999999)` straight
into Postgres. The handler checks it in both `attributesOf` and `columnType`, so the DDL-time check
stays true to sapgis even if the create-time one is ever bypassed.

**Schema identity over core purity.** The three attribute columns stay on `custom_fields` (sapgis's
final schema), but core neither creates nor names them: gis's migration adds them, and core reads and
writes whatever columns installed handlers declare. The `custom_fields_type_valid` CHECK is re-added by
gis with `GEOMETRY` appended, under the same name (ADR-026).

A stored field whose type is no longer installed still lists in metadata (its type is just a name);
record reads and writes on its object answer 409 `Field type 'GEOMETRY' is not installed`.

`MeasureFieldTypeApiTest` proves the contract on plain PostgreSQL with a made-up `MEASURE` type that
uses every hook above (own column, section, cast-based select/bind, rules, index, query parameter,
field permissions through both doors).

### Illustrative sketch of wasichai-gis (P2, not built here)

> Addendum (2026-09-25, P7): wasichai-gis was built in P2 along these lines. The module as it is, with its
> properties and routes, is described in [docs/modules/gis.md](../modules/gis.md). This sketch stays as the
> reasoning at the time.

```kotlin
val GEOMETRY = FieldType("GEOMETRY")

class GeometryFieldType(private val json: ObjectMapper) : FieldTypeHandler {
    override val type = GEOMETRY
    override val section = "geometries"
    override val attributeColumns =
        mapOf("geometry_type" to String::class.java, "srid" to Integer::class.java, "dimension" to Integer::class.java)

    override fun attributesOf(fieldName: String, request: FieldRequest): Map<String, Any?> {
        if (request.unique) {
            throw ValidationException("Geometry field '$fieldName' cannot be unique", "unique", "has no meaning on a geometry")
        }
        if (request.defaultValue != null) {
            throw ValidationException("Geometry field '$fieldName' cannot have a default", "defaultValue", "has no meaning on a geometry")
        }
        val dimension = (request.extensions["dimension"] as Number?)?.toInt() ?: 2
        val srid = (request.extensions["srid"] as Number?)?.toInt() ?: 4326
        validSrid(srid)
        // same checks and messages as sapgis MetadataService.geometryOf
        val geometryType = GeometryType.parse(request.extensions["geometryType"] as String?).name
        return mapOf("geometry_type" to geometryType, "srid" to srid, "dimension" to dimension)
    }

    override fun columnType(field: CustomField): String {
        validSrid(srid(field))
        return "geometry(${shape(field).columnType(dimension(field))}, ${srid(field)})"
    }
    override fun indexes(obj: CustomObject, table: String, field: CustomField) =
        listOf(
            "CREATE INDEX ${SqlIdentifier.quote(SqlIdentifier.indexName(obj.physicalTable, field.columnName, "gix"))} " +
                "ON $table USING GIST (${SqlIdentifier.quote(field.columnName)})",
        )
    override fun select(field: CustomField, column: String) = "ST_AsGeoJSON(ST_Transform($column, 4326)) AS ${SqlIdentifier.quote(readName(field))}"
    override fun readName(field: CustomField) = "${field.columnName}__geojson"
    override fun bindExpression(field: CustomField, parameter: String) =
        "ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(CAST(:$parameter AS text)), 4326), ${srid(field)})"
    override fun toDatabase(field: CustomField, value: Any?) = json.writeValueAsString(checkShape(field, value))
    override fun javaType(field: CustomField) = String::class.java
    override fun fromDatabase(field: CustomField, value: Any?) = (value as String?)?.let { json.readValue(it, Map::class.java) }
    override fun fieldProperties(field: CustomField) = mapOf("geometry" to field.geometryResponse())
    override fun objectProperties(definition: ObjectDefinition) =
        mapOf("geometry" to definition.fields.firstOrNull { it.type == GEOMETRY }?.geometryResponse())
    override fun unknownSectionKey(key: String, definition: ObjectDefinition) =
        ValidationException("Unknown geometry '$key'", key, "is not a geometry of '${definition.obj.name}'")
    override fun rejectFilterOrSort(field: CustomField) =
        ValidationException("Cannot filter or sort by geometry '${field.name}'", field.name, "use bbox instead")
}

class BboxQuery : RecordQueryContributor {
    override val parameters = setOf("bbox", "geometry")
    override fun parse(params: Map<String, String>): RecordCriterion? {
        val box = params["bbox"]?.let(::parseBbox) ?: return null   // "Invalid bbox" as in sapgis
        val named = params["geometry"]?.trim()?.ifBlank { null }
        return RecordCriterion { definition, bind ->
            val field = geometryOrFail(definition, named)           // sapgis messages
            val envelope = "ST_MakeEnvelope(${bind(box.minX)}, ${bind(box.minY)}, ${bind(box.maxX)}, ${bind(box.maxY)}, 4326)"
            "ST_Intersects(${SqlIdentifier.quote(field.columnName)}, ST_Transform($envelope, ${srid(field)}))"
        }
    }
}
```

gis's migration (`db/wasichai/gis/V1__gis.sql`, order ≥ 100): `CREATE EXTENSION IF NOT EXISTS postgis`,
`ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN geometry_type text, ADD COLUMN srid integer,
ADD COLUMN dimension integer`, sapgis V9's three CHECKs, and `custom_fields_type_valid` re-added with
`'GEOMETRY'` appended. Its integration tests set `systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6")`.

## Consequences

- Core's record path costs one registry lookup per field. It never branches on a type name, except
  the ENUM check and the RELATION foreign key, which are core's own.
- The only visible difference with gis present is key order.
- Anything else that wants a new column type (money with currency, say) takes the same road.
