package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

// whole bodies, compared with the original's (derived from its dtos, see the plan's Task 10).
// a TEXT field, a 2d polygon and a 3d point, all in 4326 so coordinates come back as sent.
class GeometryWireParityTest : FullAppIntegrationTest() {
    private lateinit var token: String
    private lateinit var name: String

    @BeforeEach
    fun createObject() {
        token = bearer()
        name = uniqueName("wire")
        val created = send("POST", "/api/objects", OBJECT_REQUEST.plus("name" to name), 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(DEFINITION))
    }

    @Test
    fun `the object definition is the original's`() {
        assertThat(WireJson.normalize(send("GET", "/api/objects/$name", null, 200), name)).isEqualTo(WireJson.parse(DEFINITION))
    }

    @Test
    fun `the object list entry is the original's`() {
        val list = WireJson.normalize(send("GET", "/api/objects", null, 200), name)
        val entry = list.firstOrNull { it.get("name").asString() == "<name>" }
        assertThat(entry).isEqualTo(WireJson.parse(LIST_ENTRY))
    }

    @Test
    fun `a record with both geometries is the original's, on create and on read`() {
        val created = send("POST", "/api/objects/$name/records", RECORD_REQUEST, 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(RECORD))
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        assertThat(WireJson.normalize(send("GET", "/api/objects/$name/records/$id", null, 200), name)).isEqualTo(WireJson.parse(RECORD))
    }

    @Test
    fun `a record without geometries lists each one as null`() {
        val created = send("POST", "/api/objects/$name/records", mapOf("attributes" to mapOf("codigo" to "P-2")), 201)
        assertThat(WireJson.normalize(created, name)).isEqualTo(WireJson.parse(RECORD_EMPTY))
    }

    @Test
    fun `features are the original's geojson`() {
        val created = send("POST", "/api/objects/$name/records", RECORD_REQUEST, 201)
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features", null, 200), name)).isEqualTo(WireJson.parse(FEATURES))
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features?geometry=punto", null, 200), name))
            .isEqualTo(WireJson.parse(FEATURES_PUNTO))
        assertThat(WireJson.normalize(send("GET", "/api/gis/objects/$name/features/$id", null, 200), name)).isEqualTo(WireJson.parse(FEATURE))
    }

    private fun send(
        method: String,
        uri: String,
        body: Any?,
        status: Int
    ): String {
        val spec =
            when (method) {
                "POST" ->
                    client
                        .post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .bodyValue(body!!)
                else -> client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, token)
            }
        return spec
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
    }

    companion object {
        private val RING =
            listOf(listOf(-77.03, -12.05), listOf(-77.02, -12.05), listOf(-77.02, -12.04), listOf(-77.03, -12.04), listOf(-77.03, -12.05))

        private val OBJECT_REQUEST: Map<String, Any> =
            mapOf(
                "label" to "Predio",
                "pluralLabel" to "Predios",
                "description" to "Lote catastral",
                "fields" to
                    listOf(
                        mapOf("name" to "codigo", "label" to "Codigo", "type" to "TEXT", "required" to true),
                        mapOf("name" to "lote", "label" to "Lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 4326),
                        mapOf("name" to "punto", "label" to "Punto", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 4326, "dimension" to 3)
                    )
            )

        private val RECORD_REQUEST: Map<String, Any> =
            mapOf(
                "attributes" to mapOf("codigo" to "P-1"),
                "geometries" to
                    mapOf(
                        "lote" to mapOf("type" to "Polygon", "coordinates" to listOf(RING)),
                        "punto" to mapOf("type" to "Point", "coordinates" to listOf(-77.025, -12.045, 150.5))
                    )
            )

        private const val POLYGON = """{"type":"Polygon","coordinates":[[[-77.03,-12.05],[-77.02,-12.05],[-77.02,-12.04],[-77.03,-12.04],[-77.03,-12.05]]]}"""
        private const val POINT = """{"type":"Point","coordinates":[-77.025,-12.045,150.5]}"""

        // appendOnly and apiOnly are the one addition to the original's object json: ADR-031 D24
        private const val DEFINITION = """
            {"id":"<uuid>","name":"<name>","label":"Predio","pluralLabel":"Predios","description":"Lote catastral","enabled":true,
             "appendOnly":false,"apiOnly":false,
             "geometry":{"type":"POLYGON","srid":4326,"dimension":2},
             "fields":[
              {"id":"<uuid>","name":"codigo","label":"Codigo","type":"TEXT","required":true,"unique":false,"defaultValue":null,"description":null,
               "position":0,"enumOptions":null,"relationTarget":null,"geometry":null,"visible":true,"editable":true},
              {"id":"<uuid>","name":"lote","label":"Lote","type":"GEOMETRY","required":false,"unique":false,"defaultValue":null,"description":null,
               "position":1,"enumOptions":null,"relationTarget":null,"geometry":{"type":"POLYGON","srid":4326,"dimension":2},"visible":true,"editable":true},
              {"id":"<uuid>","name":"punto","label":"Punto","type":"GEOMETRY","required":false,"unique":false,"defaultValue":null,"description":null,
               "position":2,"enumOptions":null,"relationTarget":null,"geometry":{"type":"POINT","srid":4326,"dimension":3},"visible":true,"editable":true}
             ]}
        """

        private const val LIST_ENTRY = """
            {"id":"<uuid>","name":"<name>","label":"Predio","pluralLabel":"Predios","description":"Lote catastral","enabled":true,
             "appendOnly":false,"apiOnly":false,
             "geometry":{"type":"POLYGON","srid":4326,"dimension":2},"createdAt":"<ts>","updatedAt":"<ts>"}
        """

        private const val RECORD = """
            {"id":"<uuid>","createdAt":"<ts>","updatedAt":"<ts>","attributes":{"codigo":"P-1"},
             "geometries":{"lote":$POLYGON,"punto":$POINT},"state":null}
        """

        private const val RECORD_EMPTY = """
            {"id":"<uuid>","createdAt":"<ts>","updatedAt":"<ts>","attributes":{"codigo":"P-2"},
             "geometries":{"lote":null,"punto":null},"state":null}
        """

        private const val FEATURES = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"<uuid>:lote","geometry":$POLYGON,"properties":{"codigo":"P-1","__label":"P-1","__id":"<uuid>"}}
            ]}
        """

        private const val FEATURES_PUNTO = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"<uuid>:punto","geometry":$POINT,"properties":{"codigo":"P-1","__label":"P-1","__id":"<uuid>"}}
            ]}
        """

        private const val FEATURE = """
            {"type":"Feature","id":"<uuid>:lote","geometry":$POLYGON,"properties":{"codigo":"P-1"}}
        """
    }
}
