package wasichai.it.full

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource

// geoserver stays out of the test suite. a real publish is verified by hand against a running instance.
@TestPropertySource(
    properties = [
        "wasichai.gis.geoserver.enabled=false",
        "wasichai.gis.geoserver.url=http://geoserver.invalid:8081/geoserver",
        "wasichai.gis.geoserver.workspace=wasichai"
    ]
)
class LayerApiTest : FullAppIntegrationTest() {
    private fun createObject(
        token: String,
        name: String,
        geometryType: String?,
        srid: Int? = null
    ) {
        val fields = mutableListOf<Map<String, Any>>(mapOf("name" to "codigo", "type" to "TEXT"))
        geometryType?.let {
            fields +=
                buildMap {
                    put("name", "geom")
                    put("type", "GEOMETRY")
                    put("geometryType", it)
                    srid?.let { code -> put("srid", code) }
                }
        }
        val body =
            buildMap<String, Any> {
                put("name", name)
                put("label", name.replaceFirstChar { it.uppercase() })
                put("fields", fields)
            }
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated
    }

    @Test
    fun `lists an object with geometry as an unpublished layer`() {
        val token = bearer()
        val name = uniqueName("predio")
        createObject(token, name, "POLYGON", 32718)

        client
            .get()
            .uri("/api/gis/layers")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.objectName == '$name')].published")
            .isEqualTo(false)
            .jsonPath("$[?(@.objectName == '$name')].geometryType")
            .isEqualTo("POLYGON")
            .jsonPath("$[?(@.objectName == '$name')].srid")
            .isEqualTo(32718)
            .jsonPath("$[?(@.objectName == '$name')].geometryName")
            .isEqualTo("geom")
            // the layer name carries the column, so two geometries of one table do not collide
            .jsonPath("$[?(@.objectName == '$name')].layerName")
            .isEqualTo("${name}__00000000__geom")
            .jsonPath("$[?(@.objectName == '$name')].wms")
            .isEqualTo(
                "http://geoserver.invalid:8081/geoserver/wasichai/wms" +
                    "?service=WMS&version=1.3.0&request=GetMap&layers=wasichai:${name}__00000000__geom"
            ).jsonPath("$[?(@.objectName == '$name')].wfs")
            .isEqualTo(
                "http://geoserver.invalid:8081/geoserver/wasichai/wfs" +
                    "?service=WFS&version=2.0.0&request=GetFeature&typeNames=wasichai:${name}__00000000__geom"
            )
    }

    // an object with two geometries owns two layers, each addressable on its own
    @Test
    fun `each geometry of an object is its own layer`() {
        val token = bearer()
        val name = uniqueName("predio")

        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Predio",
                    "fields" to
                        listOf(
                            mapOf("name" to "lote", "type" to "GEOMETRY", "geometryType" to "POLYGON", "srid" to 32718),
                            mapOf("name" to "acceso", "type" to "GEOMETRY", "geometryType" to "POINT", "srid" to 32718)
                        )
                )
            ).exchange()
            .expectStatus()
            .isCreated

        client
            .get()
            .uri("/api/gis/layers")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.objectName == '$name')].geometryName")
            .value<List<String>> { names -> assert(names.toSet() == setOf("lote", "acceso")) { "expected both geometries, got: $names" } }
            .jsonPath("$[?(@.geometryName == 'acceso' && @.objectName == '$name')].geometryType")
            .isEqualTo("POINT")
    }

    @Test
    fun `publishing a geometry the object does not have is refused`() {
        val token = bearer()
        val name = uniqueName("predio")
        createObject(token, name, "POLYGON", 32718)

        client
            .post()
            .uri("/api/gis/layers/$name/fachada")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `publishing fails with a clear error while geoserver is disabled`() {
        val token = bearer()
        val name = uniqueName("predio")
        createObject(token, name, "POINT")

        client
            .post()
            .uri("/api/gis/layers/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(502)
            .expectHeader()
            .contentTypeCompatibleWith("application/problem+json")
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { detail -> assert(detail.contains("disabled")) { "expected a clear message, got: $detail" } }
    }

    @Test
    fun `a flat object owns no layer and cannot be published`() {
        val token = bearer()
        val name = uniqueName("flat")
        createObject(token, name, null)

        client
            .get()
            .uri("/api/gis/layers")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[?(@.objectName == '$name')]")
            .doesNotExist()

        client
            .post()
            .uri("/api/gis/layers/$name")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("object")
    }

    @Test
    fun `unknown objects are not found`() {
        client
            .post()
            .uri("/api/gis/layers/doesnotexist")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `reports the service base urls`() {
        client
            .get()
            .uri("/api/gis/services")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)
            .jsonPath("$.url")
            .isEqualTo("http://geoserver.invalid:8081/geoserver")
            .jsonPath("$.workspace")
            .isEqualTo("wasichai")
            .jsonPath("$.wms")
            .isEqualTo("http://geoserver.invalid:8081/geoserver/wasichai/wms")
            .jsonPath("$.wfs")
            .isEqualTo("http://geoserver.invalid:8081/geoserver/wasichai/wfs")
            .jsonPath("$.wmts")
            .isEqualTo("http://geoserver.invalid:8081/geoserver/gwc/service/wmts")
    }

    @Test
    fun `layers need authentication`() {
        client
            .get()
            .uri("/api/gis/layers")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
