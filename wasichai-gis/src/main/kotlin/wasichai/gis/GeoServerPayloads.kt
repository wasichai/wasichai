package wasichai.gis

import wasichai.core.platform.SqlIdentifier

// pure. no http here, so every payload and url is unit-testable.
object GeoServerPayloads {
    fun workspace(name: String): Map<String, Any> = mapOf("workspace" to mapOf("name" to name))

    // geoserver wants connection parameters as a list of {"@key","$"} entries, not as a plain object.
    // schema is resolved by the caller (datastore.schema, or wasichai.database.data-schema when unset).
    fun dataStore(
        datastore: GeoServerDataStoreProperties,
        schema: String
    ): Map<String, Any> =
        mapOf(
            "dataStore" to
                mapOf(
                    "name" to datastore.name,
                    "type" to "PostGIS",
                    "enabled" to true,
                    "connectionParameters" to
                        mapOf(
                            "entry" to
                                listOf(
                                    entry("dbtype", "postgis"),
                                    entry("host", datastore.host),
                                    entry("port", datastore.port.toString()),
                                    entry("database", datastore.database),
                                    entry("schema", schema),
                                    entry("user", datastore.username),
                                    entry("passwd", datastore.password),
                                    // without this the id column never reaches wfs clients
                                    entry("Expose primary keys", "true")
                                )
                        )
                )
        )

    // a table with two geometry columns would let geoserver pick one in silence, and it might not
    // be the one we mean. a virtual table names it, so the layer says what it publishes.
    fun featureType(
        schema: String,
        table: String,
        layerName: String,
        geometryColumn: String,
        geometryType: String,
        srid: Int,
        title: String,
        attributeColumns: List<String>
    ): Map<String, Any> =
        mapOf(
            "featureType" to
                mapOf(
                    "name" to layerName,
                    "nativeName" to layerName,
                    "title" to title,
                    "srs" to "EPSG:$srid",
                    "enabled" to true,
                    "metadata" to
                        mapOf(
                            "entry" to
                                listOf(
                                    mapOf(
                                        "@key" to "JDBC_VIRTUAL_TABLE",
                                        "virtualTable" to
                                            mapOf(
                                                "name" to layerName,
                                                "sql" to virtualTableSql(schema, table, geometryColumn, attributeColumns),
                                                "escapeSql" to false,
                                                "keyColumn" to "id",
                                                "geometry" to
                                                    mapOf(
                                                        "name" to geometryColumn,
                                                        "type" to geometryType,
                                                        "srid" to srid
                                                    )
                                            )
                                    )
                                )
                        )
                )
        )

    // named columns rather than SELECT *: the object's other geometries must not travel along as
    // extra attributes, and a virtual table with two geometry columns is the problem all over again.
    // schema is config, not bound data, and it lands raw in the sql string: validate it the same
    // way wasichai-core validates every schema name, then quote it.
    fun virtualTableSql(
        schema: String,
        table: String,
        geometryColumn: String,
        attributeColumns: List<String>
    ): String {
        val columns = (listOf("id", "created_at", "updated_at") + attributeColumns + geometryColumn).joinToString(", ") { "\"$it\"" }
        return "SELECT $columns FROM ${SqlIdentifier.quote(schema)}.\"$table\""
    }

    private fun entry(
        key: String,
        value: String
    ): Map<String, String> = mapOf("@key" to key, "$" to value)
}

// how a layer is named and recognised. a layer is one geometry of one object.
object GeoServerLayers {
    // the table plus the column, so two tenants owning a 'predio' do not collide and neither do
    // two geometries of the same one
    fun name(
        table: String,
        geometryColumn: String
    ): String = "${table}__$geometryColumn"

    // a layer published before ADR-019 is named after the table alone. it is still live and still
    // serving, and under the new scheme nothing else could be called that, so it counts as the
    // first geometry's -- or it would sit there invisible to the screen meant to retire it.
    fun published(
        table: String,
        geometryColumn: String,
        firstGeometry: Boolean,
        published: Set<String>
    ): Boolean = name(table, geometryColumn) in published || (firstGeometry && table in published)
}

// every url geoserver answers on. built from config, never hardcoded.
object GeoServerUrls {
    fun workspaces(base: String): String = "$base/rest/workspaces"

    fun workspace(
        base: String,
        workspace: String
    ): String = "$base/rest/workspaces/$workspace"

    fun dataStores(
        base: String,
        workspace: String
    ): String = "$base/rest/workspaces/$workspace/datastores"

    fun dataStore(
        base: String,
        workspace: String,
        datastore: String
    ): String = "$base/rest/workspaces/$workspace/datastores/$datastore"

    fun featureTypes(
        base: String,
        workspace: String,
        datastore: String
    ): String = "$base/rest/workspaces/$workspace/datastores/$datastore/featuretypes"

    fun featureType(
        base: String,
        workspace: String,
        datastore: String,
        layerName: String
    ): String = "${featureTypes(base, workspace, datastore)}/$layerName"

    fun wms(
        base: String,
        workspace: String
    ): String = "$base/$workspace/wms"

    fun wfs(
        base: String,
        workspace: String
    ): String = "$base/$workspace/wfs"

    // gwc is global, it does not hang off the workspace
    fun wmts(base: String): String = "$base/gwc/service/wmts"

    fun wmsLayer(
        base: String,
        workspace: String,
        layerName: String
    ): String = "${wms(base, workspace)}?service=WMS&version=1.3.0&request=GetMap&layers=$workspace:$layerName"

    fun wfsLayer(
        base: String,
        workspace: String,
        layerName: String
    ): String = "${wfs(base, workspace)}?service=WFS&version=2.0.0&request=GetFeature&typeNames=$workspace:$layerName"
}
